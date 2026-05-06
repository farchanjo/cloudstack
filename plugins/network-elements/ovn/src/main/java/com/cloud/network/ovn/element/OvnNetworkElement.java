// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ovn.element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.dc.Vlan;
import com.cloud.dc.dao.VlanDao;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.utils.net.NetUtils;
import com.cloud.network.element.ConnectivityProvider;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.DnsServiceProvider;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkACLServiceProvider;
import com.cloud.network.element.PortForwardingServiceProvider;
import com.cloud.network.element.SourceNatServiceProvider;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.VpcProvider;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.api.command.admin.AddOvnControllerCmd;
import com.cloud.network.ovn.api.command.admin.DeleteOvnControllerCmd;
import com.cloud.network.ovn.api.command.admin.ImportOvnVpcCmd;
import com.cloud.network.ovn.api.command.admin.ListOvnControllersCmd;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.utils.component.PluggableService;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Tier-level OVN network element. Owns the LS / LSP lifecycle and federates
 * every other CloudStack network service to its OVN-backed helper bean:
 *
 * <ul>
 *   <li>Connectivity     ─ this class (LS + LSP)
 *   <li>NetworkACL       ─ {@link OvnFirewallService}
 *   <li>SourceNat        ─ {@link OvnSourceNatService}
 *   <li>StaticNat        ─ {@link OvnStaticNatService}
 *   <li>PortForwarding   ─ {@link OvnPortForwardingService}
 *   <li>Lb               ─ {@link OvnLoadBalancerService}
 *   <li>Dhcp             ─ {@link OvnDhcpService}
 *   <li>Dns              ─ {@link OvnDnsService}
 *   <li>Gateway          ─ default route via {@link OvnPublicNetworkManager}
 * </ul>
 *
 * <p>Federation is a CloudStack invariant — each {@code Network.Provider}
 * registers a single {@code NetworkElement}; the per-service provider
 * interfaces all hang off this one element, dispatching to the helpers via
 * @Inject so the helpers stay testable in isolation.
 *
 * <p>The element also implements {@link IpDeployer} so the orchestrator's
 * IP allocator delegates public-IP application here. {@code applyIps} is a
 * no-op for OVN: NAT rules carry the public IP semantics directly via
 * {@link OvnSourceNatService} and {@link OvnStaticNatService}.
 */
@Component
public class OvnNetworkElement extends AdapterBase
        implements ConnectivityProvider,
                   DhcpServiceProvider,
                   DnsServiceProvider,
                   SourceNatServiceProvider,
                   StaticNatServiceProvider,
                   PortForwardingServiceProvider,
                   LoadBalancingServiceProvider,
                   NetworkACLServiceProvider,
                   IpDeployer,
                   VpcProvider,
                   PluggableService {

    private static final Logger LOGGER = LogManager.getLogger(OvnNetworkElement.class);

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = buildCapabilities();

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnGuestNetworkGuru guru;
    @Inject
    private OvnFirewallService firewallService;
    @Inject
    private OvnSourceNatService sourceNatService;
    @Inject
    private OvnStaticNatService staticNatService;
    @Inject
    private OvnPortForwardingService portForwardingService;
    @Inject
    private OvnLoadBalancerService loadBalancerService;
    @Inject
    private OvnDhcpService dhcpService;
    @Inject
    private OvnDnsService dnsService;
    @Inject
    private OvnQosService qosService;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private OvnVpcElement vpcElement;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private OvnPublicNetworkManager publicNetworkManager;

    /** Default OVS bridge mapping name; must match {@code ovn-bridge-mappings}
     *  on every chassis. Aragog deployment uses {@code physnet1:br-bond}. */
    private static final String DEFAULT_PUBLIC_PHYSNET = "physnet1";

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public Provider getProvider() {
        return OvnNetworkProvider.OVN_PROVIDER;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        return super.configure(name, params);
    }

    // ------------------------------------------------------------------
    // NetworkElement core lifecycle (LS + LSP).
    // ------------------------------------------------------------------

    @Override
    public boolean implement(final Network network, final NetworkOffering offering, final DeployDestination dest,
                             final ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        try {
            final String lsUuid = guru.createLogicalSwitchFor(network);
            LOGGER.info("OvnNetworkElement.implement: LS {} ready (network id={})", lsUuid, network.getId());
            // When the tier is part of a VPC, bind it to the VPC LR via a
            // router-patch pair so east-west routing across tiers works
            // without an external VR. Idempotent: bindTierToVpc returns the
            // existing LRP/LSP UUIDs when the patch already exists.
            ensureTierBoundToVpcLr(network, lsUuid);
        } catch (OvnException e) {
            throw new ResourceUnavailableException("OVN LS create failed: " + e.getMessage(),
                    Network.class, network.getId());
        }
        return true;
    }

    /**
     * If the network belongs to a VPC, ensure the LRP/LSP router-patch pair
     * connecting this tier's Logical_Switch to the VPC's Logical_Router is
     * present. The LRP carries the tier gateway IP/MAC; OVN auto-installs
     * the L3 forwarding entries on every chassis once the patch exists.
     *
     * <p>Best-effort: a missing VPC mapping or a transient OVN failure logs
     * and returns without aborting the tier implement (caller already
     * succeeded creating the LS — the LRP can be reconciled later).
     */
    private void ensureTierBoundToVpcLr(final Network network, final String tierLsUuid) {
        if (network.getVpcId() == null) {
            return;
        }
        final Vpc vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        // We persist the tier-LRP mapping under Kind.PUBLIC_LRP keyed by
        // network id (re-use of that bucket avoids introducing a new Kind
        // enum value just for tier bindings — semantically the LRP attaches
        // a tier LS to an LR, mirroring the public-side LRP role).
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.PUBLIC_LRP, network.getId(), controller.getId());
        try {
            final String tierName = network.getUuid();
            final String gateway = network.getGateway();
            final String cidr = network.getCidr();
            if (gateway == null || cidr == null) {
                LOGGER.warn("OvnNetworkElement.ensureTierBound: network id={} missing gateway/cidr; skipping LRP bind",
                        network.getId());
                return;
            }
            final int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
            final List<String> networks = List.of(gateway + "/" + prefix);
            final String gwMac = deriveGatewayMac(gateway);
            if (existing != null) {
                // Already bound — reconcile gateway IP / CIDR. Cheap idempotent
                // path: rewrite LRP.networks (and MAC) so a tier reconfigure
                // (CloudStack updateNetwork on gateway/CIDR) propagates without
                // tearing the patch pair down.
                pluginManager.nbClient(network.getDataCenterId())
                        .updateLogicalRouterPortNetworks(existing.getOvnUuid(), networks, gwMac);
                LOGGER.debug("OvnNetworkElement.ensureTierBound: LRP {} reconciled (tier id={}, networks={})",
                        existing.getOvnUuid(), network.getId(), networks);
                return;
            }
            final OvnNbClient.BindResult bind = vpcElement.bindTierToVpc(vpc, tierLsUuid, tierName, gwMac, networks);
            logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.PUBLIC_LRP, network.getId(), controller.getId(),
                    bind.lrpUuid, "lrp-" + tierName));
            LOGGER.info("OvnNetworkElement.ensureTierBound: LRP {} + LSP {} created (tier id={}, vpc id={})",
                    bind.lrpUuid, bind.lspUuid, network.getId(), vpc.getId());
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.ensureTierBound: tier id={} bind to VPC LR failed: {}",
                    network.getId(), e.getMessage());
        }
    }

    /**
     * Build a deterministic MAC for the tier gateway LRP. Encodes the last
     * three octets of the gateway IP into the locally-administered range
     * {@code 02:01:01:**:**:**}; matches the convention used by
     * OvnDhcpService.deriveServerMac so the LRP and DHCP responder share
     * an identity prefix per tier.
     */
    private static String deriveGatewayMac(final String gatewayIp) {
        if (gatewayIp == null || !gatewayIp.contains(".")) {
            return "02:01:01:00:00:01";
        }
        final String[] octets = gatewayIp.trim().split("\\.");
        if (octets.length != 4) {
            return "02:01:01:00:00:01";
        }
        try {
            final int o1 = Integer.parseInt(octets[1]) & 0xff;
            final int o2 = Integer.parseInt(octets[2]) & 0xff;
            final int o3 = Integer.parseInt(octets[3]) & 0xff;
            return String.format("02:01:01:%02x:%02x:%02x", o1, o2, o3);
        } catch (NumberFormatException e) {
            return "02:01:01:00:00:01";
        }
    }

    @Override
    public boolean prepare(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                           final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (nic == null || StringUtils.isBlank(nic.getMacAddress())) {
            return true;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return true;
        }
        final OvnLogicalIdMapVO already = logicalIdMapDao.findByCsId(Kind.NIC, nic.getId(), controller.getId());
        final List<String> addresses = buildAddresses(nic);
        if (already == null) {
            final String lsUuid = ensureLogicalSwitch(network);
            final String lspName = buildLspName(nic);
            try {
                final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
                final String lspUuid = nb.addLogicalSwitchPort(lsUuid, lspName, addresses, null, null);
                // port_security mirrors addresses → spoof guard.
                nb.lspSetPortSecurity(lspUuid, addresses);
                logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.NIC, nic.getId(), controller.getId(), lspUuid, lspName));
                LOGGER.info("OvnNetworkElement.prepare: LSP {} (name={}, addrs={}) for nic id={}",
                        lspUuid, lspName, addresses, nic.getId());
            } catch (OvnException e) {
                throw new ResourceUnavailableException("OVN LSP create failed: " + e.getMessage(),
                        Network.class, network.getId());
            }
        } else {
            // LSP already exists — reconcile addresses + port_security so an
            // updateVmNicIp / NIC IP change without VM stop+start propagates
            // to OVN. Idempotent: writing the same set is a no-op cost-wise
            // but lets the spoof guard track the new IP.
            try {
                pluginManager.nbClient(network.getDataCenterId())
                        .updateLogicalSwitchPortAddresses(already.getOvnUuid(), addresses);
                LOGGER.debug("OvnNetworkElement.prepare: LSP {} reconciled (nic id={}, addrs={})",
                        already.getOvnUuid(), nic.getId(), addresses);
            } catch (OvnException e) {
                LOGGER.warn("OvnNetworkElement.prepare: LSP {} reconcile failed: {}",
                        already.getOvnUuid(), e.getMessage());
            }
        }
        // VPC-level SourceNAT reconciler — CloudStack allocates the source-NAT
        // public IP lazily (typically on the first VM deploy that triggers VR
        // boot, which in OVN's case is bypassed). The implementVpc hook fires
        // before any IP exists; updateVpcSourceNatIp only fires on explicit
        // rotation. Catching the case here, on every NIC prepare, ensures
        // the SNAT row exists as soon as both the VPC LR and the source-NAT
        // IP coexist. Idempotent — re-runs return the existing UUID.
        ensureVpcSourceNatFromTier(network);
        // Phase II — public-side LRP + default route attachment. Same
        // motivation as ensureVpcSourceNatFromTier: the VPC LR may have
        // implemented before the source-NAT IP existed, so retry on prepare.
        ensureVpcPublicAttachedFromTier(network);
        // DHCP pin (idempotent — OvnDhcpService handles the per-tier row).
        dhcpService.ensureDhcpForNic(network, nic);
        // DNS record (best-effort — DnsServiceProvider path runs separately
        // when CloudStack invokes addDnsEntry, but we also fire here to
        // catch the case where Dns is enabled at the tier level).
        dnsService.addDnsEntry(network, nic, vm);
        // QoS shaping when offering carries a network rate.
        if (nic.getNetworkRate() != null && nic.getNetworkRate() > 0) {
            qosService.applyQosForNic(network, nic, nic.getNetworkRate(), null);
        }
        return true;
    }

    @Override
    public boolean release(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                           final ReservationContext context) {
        if (nic == null) {
            return true;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return true;
        }
        // QoS / DHCP / DNS first so they don't dangle on a deleted LSP.
        qosService.removeQosForNic(network, nic);
        dhcpService.clearDhcpForNic(network, nic);
        dnsService.removeDnsEntry(network, nic, vm);
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.NIC, nic.getId(), controller.getId());
        if (mapping == null) {
            return true;
        }
        try {
            pluginManager.nbClient(network.getDataCenterId()).deleteLogicalSwitchPort(mapping.getOvnUuid());
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.release: LSP {} delete failed: {}", mapping.getOvnUuid(), e.getMessage());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
        return true;
    }

    @Override
    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) {
        return true;
    }

    @Override
    public boolean destroy(final Network network, final ReservationContext context) {
        try {
            //  1. Drop per-tier DHCP_Options + DNS rows.
            //  2. Drop the SOURCE_NAT row for this tier (mapped under
            //     Kind.SOURCE_NAT keyed by network id; safely no-op when
            //     applyIps never ran for this tier).
            //  3. Drop tier-LRP from VPC LR (OvnNbClient detach handles
            //     ports set; LRP-side router-patch LSP gets cascade-dropped
            //     when the parent LS goes away in step 4).
            //  4. Drop the Logical_Switch (cascade kills any leftover LSPs).
            dhcpService.removeTierDhcp(network);
            dnsService.removeTierDns(network);
            sourceNatService.removeSnatForTier(network.getDataCenterId(), network.getId());
            detachTierFromVpcLr(network);
            guru.deleteLogicalSwitchFor(network);
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.destroy: failed network id={}: {}", network.getId(), e.getMessage());
        }
        return true;
    }

    /**
     * Drop the tier-LRP attached to the VPC LR. Matches the
     * {@link Kind#PUBLIC_LRP} mapping persisted by
     * {@link #ensureTierBoundToVpcLr}; mapping row stays gone so a future
     * re-create of the same tier triggers a fresh bind.
     */
    private void detachTierFromVpcLr(final Network network) {
        if (network.getVpcId() == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.PUBLIC_LRP, network.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        try {
            pluginManager.nbClient(network.getDataCenterId()).deleteLogicalRouterPort(mapping.getOvnUuid());
            LOGGER.info("OvnNetworkElement.detachTier: LRP {} dropped (tier id={})",
                    mapping.getOvnUuid(), network.getId());
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.detachTier: LRP {} delete failed: {}",
                    mapping.getOvnUuid(), e.getMessage());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
    }

    @Override
    public boolean isReady(final PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(final PhysicalNetworkServiceProvider provider, final ReservationContext context) {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return true;
    }

    @Override
    public boolean verifyServicesCombination(final Set<Service> services) {
        return services.contains(Service.Connectivity);
    }

    // ------------------------------------------------------------------
    // DhcpServiceProvider.
    // ------------------------------------------------------------------

    @Override
    public boolean addDhcpEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                                final DeployDestination dest, final ReservationContext context) {
        return dhcpService.ensureDhcpForNic(network, nic);
    }

    @Override
    public boolean configDhcpSupportForSubnet(final Network network, final NicProfile nic,
                                              final VirtualMachineProfile vm, final DeployDestination dest,
                                              final ReservationContext context) {
        return true;
    }

    @Override
    public boolean removeDhcpSupportForSubnet(final Network network) {
        dhcpService.removeTierDhcp(network);
        return true;
    }

    @Override
    public boolean setExtraDhcpOptions(final Network network, final long nicId, final Map<Integer, String> dhcpOptions) {
        // Extra DHCP options (option 121, etc.) propagate via the per-tier
        // DHCP_Options row's options map. CloudStack's option-code → string
        // translation is layered on top in a future phase; for the MVP we
        // log and accept so the UI flow doesn't fail.
        LOGGER.info("OvnNetworkElement.setExtraDhcpOptions: nic id={} opts={} (network id={}) — accepted; per-tier extension TBD",
                nicId, dhcpOptions, network.getId());
        return true;
    }

    @Override
    public boolean removeDhcpEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vmProfile) {
        dhcpService.clearDhcpForNic(network, nic);
        return true;
    }

    // ------------------------------------------------------------------
    // DnsServiceProvider.
    // ------------------------------------------------------------------

    @Override
    public boolean addDnsEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                               final DeployDestination dest, final ReservationContext context) {
        return dnsService.addDnsEntry(network, nic, vm);
    }

    @Override
    public boolean configDnsSupportForSubnet(final Network network, final NicProfile nic,
                                             final VirtualMachineProfile vm, final DeployDestination dest,
                                             final ReservationContext context) {
        return true;
    }

    @Override
    public boolean removeDnsSupportForSubnet(final Network network) {
        dnsService.removeTierDns(network);
        return true;
    }

    // ------------------------------------------------------------------
    // SourceNat / StaticNat / PortForwarding / NetworkACL providers.
    // ------------------------------------------------------------------

    @Override
    public IpDeployer getIpDeployer(final Network network) {
        return this;
    }

    @Override
    public boolean applyIps(final Network network, final List<? extends PublicIpAddress> ipAddress,
                            final Set<Service> services) {
        // Emit an OVN snat row for the VPC's source-NAT public IP scoped to
        // the tier CIDR. Public LRP attachment + default route to upstream
        // gateway are deferred (Phase II / OvnPublicNetworkManager) — this
        // hook only programs the rule shape so re-applies / cleanup remain
        // observable in the OVN NB DB.
        if (ipAddress == null || ipAddress.isEmpty()) {
            return true;
        }
        if (services == null || !services.contains(Service.SourceNat)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("OvnNetworkElement.applyIps: network id={} services={} no SourceNat — skip",
                        network.getId(), services);
            }
            return true;
        }
        final Long vpcId = network.getVpcId();
        if (vpcId == null) {
            return true;
        }
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return false;
        }
        final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpcId, controller.getId());
        if (lrMapping == null) {
            LOGGER.warn("OvnNetworkElement.applyIps: VPC LR not found for vpc id={}; skipping SNAT apply", vpcId);
            return false;
        }
        final String tierCidr = network.getCidr();
        if (StringUtils.isBlank(tierCidr)) {
            return true;
        }
        boolean overall = true;
        for (final PublicIpAddress pip : ipAddress) {
            if (pip == null || !pip.isSourceNat()) {
                continue;
            }
            final String externalIp = pip.getAddress() == null ? null : pip.getAddress().addr();
            if (StringUtils.isBlank(externalIp)) {
                continue;
            }
            try {
                final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.SOURCE_NAT,
                        network.getId(), controller.getId());
                if (existing == null) {
                    sourceNatService.addSnat(zoneId, network.getId(), lrMapping.getOvnUuid(),
                            externalIp, tierCidr);
                } else if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("OvnNetworkElement.applyIps: SNAT for tier id={} already mapped (uuid={})",
                            network.getId(), existing.getOvnUuid());
                }
            } catch (RuntimeException e) {
                LOGGER.error("OvnNetworkElement.applyIps: SNAT add for tier id={} cidr={} ext={} failed: {}",
                        network.getId(), tierCidr, externalIp, e.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    @Override
    public boolean applyStaticNats(final Network config, final List<? extends StaticNat> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final Long vpcId = config.getVpcId();
        if (vpcId == null) {
            return true;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(config.getDataCenterId());
        if (controller == null) {
            return false;
        }
        final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpcId, controller.getId());
        if (lrMapping == null) {
            return false;
        }
        boolean overall = true;
        for (final StaticNat rule : rules) {
            try {
                if (rule.isForRevoke()) {
                    staticNatService.removeStaticNat(config.getDataCenterId(), rule.getSourceIpAddressId());
                    continue;
                }
                final IPAddressVO publicIp = ipAddressDao.findById(rule.getSourceIpAddressId());
                final String externalIpStr = publicIp == null || publicIp.getAddress() == null
                        ? null : publicIp.getAddress().addr();
                if (StringUtils.isBlank(externalIpStr) || StringUtils.isBlank(rule.getDestIpAddress())) {
                    LOGGER.warn("OvnNetworkElement.applyStaticNats: rule src id={} could not resolve external IP (public={} priv={}); skipping",
                            rule.getSourceIpAddressId(), externalIpStr, rule.getDestIpAddress());
                    overall = false;
                    continue;
                }
                staticNatService.addStaticNat(config.getDataCenterId(), rule.getSourceIpAddressId(),
                        lrMapping.getOvnUuid(), externalIpStr, rule.getDestIpAddress(), null);
            } catch (RuntimeException e) {
                LOGGER.error("OvnNetworkElement.applyStaticNats: rule revoke={} src={} dst={} failed: {}",
                        rule.isForRevoke(), rule.getSourceIpAddressId(), rule.getDestIpAddress(), e.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    @Override
    public boolean applyPFRules(final Network network, final List<PortForwardingRule> rules) {
        return portForwardingService.applyPFRules(network, rules);
    }

    @Override
    public boolean applyLBRules(final Network network, final List<LoadBalancingRule> rules)
            throws ResourceUnavailableException {
        return loadBalancerService.applyLBRules(network, rules);
    }

    @Override
    public boolean validateLBRule(final Network network, final LoadBalancingRule rule) {
        return true;
    }

    @Override
    public List<LoadBalancerTO> updateHealthChecks(final Network network, final List<LoadBalancingRule> lbrules) {
        return new ArrayList<>();
    }

    @Override
    public boolean handlesOnlyRulesInTransitionState() {
        // OVN driver re-applies the desired state — no transition gating needed.
        return false;
    }

    @Override
    public boolean applyNetworkACLs(final Network config, final List<? extends NetworkACLItem> rules)
            throws ResourceUnavailableException {
        return firewallService.applyNetworkACLs(config, rules);
    }

    @Override
    public boolean reorderAclRules(final Vpc vpc, final List<? extends Network> networks,
                                   final List<? extends NetworkACLItem> networkACLItems) {
        return firewallService.reorderAclRules(vpc, networks, networkACLItems);
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private String ensureLogicalSwitch(final Network network) {
        final String existing = guru.findLogicalSwitchUuidFor(network);
        if (existing != null) {
            return existing;
        }
        return guru.createLogicalSwitchFor(network);
    }

    private List<String> buildAddresses(final NicProfile nic) {
        final StringBuilder s = new StringBuilder();
        s.append(nic.getMacAddress());
        if (StringUtils.isNotBlank(nic.getIPv4Address())) {
            s.append(' ').append(nic.getIPv4Address());
        }
        if (StringUtils.isNotBlank(nic.getIPv6Address())) {
            s.append(' ').append(nic.getIPv6Address());
        }
        final List<String> out = new ArrayList<>(1);
        out.add(s.toString());
        return out;
    }

    private String buildLspName(final NicProfile nic) {
        final String uuid = nic.getUuid();
        if (StringUtils.isNotBlank(uuid)) {
            return "lsp-" + uuid;
        }
        return "lsp-nic-" + nic.getId();
    }

    // ------------------------------------------------------------------
    // VpcProvider — delegate to OvnVpcElement.
    // ------------------------------------------------------------------

    @Override
    public boolean implementVpc(final Vpc vpc, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        try {
            final String lrUuid = vpcElement.createLogicalRouterFor(vpc);
            // Idempotent: if VPC already has a source-NAT public IP allocated,
            // program the VPC-level SNAT row up front (parent CIDR -> ext IP)
            // instead of waiting for the per-tier applyIps lifecycle (which
            // CloudStack only fires when a public IP is explicitly associated
            // to a tier — typically never for the source-NAT IP).
            ensureVpcSourceNat(vpc, lrUuid);
            // Phase II: attach the LR to the per-zone public Logical_Switch
            // via a router-patch pair pinned to the HA chassis group, plus a
            // default route 0.0.0.0/0 nexthop=upstream. The localnet LSP on
            // the public LS exits via ovn-bridge-mappings to br-bond.
            ensureVpcPublicAttached(vpc, lrUuid);
            return true;
        } catch (com.cloud.network.ovn.client.OvnException e) {
            LOGGER.error("OvnNetworkElement.implementVpc: VPC id={} OVN LR create failed: {}", vpc.getId(), e.getMessage());
            throw new ResourceUnavailableException("OVN LR create failed: " + e.getMessage(),
                    Vpc.class, vpc.getId());
        }
    }

    /**
     * Phase II — public-side attachment of the VPC LR. Idempotent: when the
     * VPC already has a {@code VPC_PUBLIC_LRP} mapping, returns immediately.
     * Soft no-op when the source-NAT public IP / Vlan metadata is missing
     * (the orchestrator may not have allocated one yet — caller retries via
     * {@link #ensureVpcPublicAttachedFromTier} on the first NIC prepare).
     *
     * <p>Derives:
     * <ul>
     *   <li>publicNetworks — {@code <sourceNAT-ip>/<prefix>} from the Vlan
     *       netmask (one CIDR per LRP).</li>
     *   <li>nexthop — the Vlan's gateway IP, pushed as the LR's default
     *       static route by {@link OvnPublicNetworkManager#bindVpcToPublic}.</li>
     *   <li>publicMac — deterministic {@code 02:02:02:%02x:%02x:%02x} from
     *       the source-NAT IPv4 last three octets. Distinct from the tier
     *       gateway MAC ({@code 02:01:01:...}).</li>
     *   <li>vlanTag — parsed from {@code Vlan.getVlanTag()} (CloudStack
     *       stores the bare numeric tag for tagged Vlans).</li>
     * </ul>
     */
    private void ensureVpcPublicAttached(final Vpc vpc, final String lrUuid) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_LRP, vpc.getId(), controller.getId());
        if (existing != null) {
            return;
        }
        final List<IPAddressVO> sourceNatIps = ipAddressDao.listByAssociatedVpc(vpc.getId(), Boolean.TRUE);
        if (sourceNatIps == null || sourceNatIps.isEmpty()) {
            LOGGER.debug("OvnNetworkElement.ensureVpcPublicAttached: VPC id={} no source-NAT IP yet — deferred",
                    vpc.getId());
            return;
        }
        final IPAddressVO ip = sourceNatIps.get(0);
        final String externalIp = ip.getAddress() == null ? null : ip.getAddress().addr();
        if (StringUtils.isBlank(externalIp)) {
            return;
        }
        final Vlan vlan = vlanDao.findById(ip.getVlanId());
        if (vlan == null) {
            LOGGER.warn("OvnNetworkElement.ensureVpcPublicAttached: VPC id={} no Vlan for IP {}",
                    vpc.getId(), externalIp);
            return;
        }
        final String netmask = vlan.getVlanNetmask();
        if (StringUtils.isBlank(netmask)) {
            return;
        }
        final long prefix = NetUtils.getCidrSize(netmask);
        final List<String> publicNetworks = List.of(externalIp + "/" + prefix);
        final String publicMac = derivePublicMac(externalIp);
        final Integer vlanTag = parseVlanTag(vlan.getVlanTag());
        try {
            publicNetworkManager.ensureVpcBoundToPublic(vpc.getZoneId(), vpc.getId(), lrUuid,
                    publicMac, publicNetworks, vlanTag, DEFAULT_PUBLIC_PHYSNET);
            LOGGER.info("OvnNetworkElement.ensureVpcPublicAttached: VPC id={} bound to public (lrp networks={}, mac={}, vlan={})",
                    vpc.getId(), publicNetworks, publicMac, vlanTag);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNetworkElement.ensureVpcPublicAttached: VPC id={} bind failed: {}",
                    vpc.getId(), e.getMessage());
        }
    }

    /**
     * Tier-driven retry path. Same intent as
     * {@link #ensureVpcPublicAttached(Vpc, String)} but invoked from
     * {@link #prepare} after the SNAT reconciler — when CloudStack allocated
     * the source-NAT IP only after the VPC was implemented (lazy
     * allocation), this catches it on the first VM bring-up.
     */
    private void ensureVpcPublicAttachedFromTier(final Network network) {
        if (network.getVpcId() == null) {
            return;
        }
        final Vpc vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (lrMapping == null) {
            return;
        }
        ensureVpcPublicAttached(vpc, lrMapping.getOvnUuid());
    }

    /** {@code 02:02:02:XX:XX:XX} derived from the IPv4 last three octets.
     *  Stable across plugin restarts; distinct from tier gateway MACs
     *  ({@code 02:01:01:...}) so a packet capture can tell them apart. */
    private static String derivePublicMac(final String ipv4) {
        if (ipv4 == null || !ipv4.contains(".")) {
            return "02:02:02:00:00:01";
        }
        final String[] octets = ipv4.trim().split("\\.");
        if (octets.length != 4) {
            return "02:02:02:00:00:01";
        }
        try {
            final int o1 = Integer.parseInt(octets[1]) & 0xff;
            final int o2 = Integer.parseInt(octets[2]) & 0xff;
            final int o3 = Integer.parseInt(octets[3]) & 0xff;
            return String.format("02:02:02:%02x:%02x:%02x", o1, o2, o3);
        } catch (NumberFormatException e) {
            return "02:02:02:00:00:01";
        }
    }

    /** CloudStack stores Vlan.vlan_tag as the bare numeric tag for tagged
     *  Vlans (e.g. {@code "2988"}); untagged is {@code "untagged"}. Returns
     *  {@code null} for the untagged case (localnet without {@code tag}). */
    private static Integer parseVlanTag(final String raw) {
        if (raw == null || raw.isBlank() || "untagged".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Same intent as {@link #ensureVpcSourceNat(Vpc, String)} but driven from
     * a tier {@link Network} (no Vpc reference handy). Loads the parent Vpc
     * + LR mapping + source-NAT IP from the DAO chain. Used from {@link
     * #prepare} so the SNAT row appears as soon as the first VM in the VPC
     * boots, even if {@code implementVpc} ran before the source-NAT IP got
     * allocated.
     */
    private void ensureVpcSourceNatFromTier(final Network network) {
        if (network.getVpcId() == null) {
            return;
        }
        final Vpc vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (lrMapping == null) {
            return;
        }
        ensureVpcSourceNat(vpc, lrMapping.getOvnUuid());
    }

    /**
     * Programs the VPC-level SNAT row when a source-NAT public IP exists.
     * Best-effort: missing IP / missing CIDR is a soft no-op (the VPC just
     * has no public-side egress configured yet — the row will be added when
     * {@link #updateVpcSourceNatIp} fires later).
     */
    private void ensureVpcSourceNat(final Vpc vpc, final String lrUuid) {
        final List<IPAddressVO> sourceNatIps = ipAddressDao.listByAssociatedVpc(vpc.getId(), Boolean.TRUE);
        if (sourceNatIps == null || sourceNatIps.isEmpty()) {
            LOGGER.debug("OvnNetworkElement.ensureVpcSourceNat: VPC id={} no source-NAT IP yet", vpc.getId());
            return;
        }
        final IPAddressVO ip = sourceNatIps.get(0);
        final String externalIp = ip.getAddress() == null ? null : ip.getAddress().addr();
        final String vpcCidr = vpc.getCidr();
        if (StringUtils.isBlank(externalIp) || StringUtils.isBlank(vpcCidr)) {
            LOGGER.warn("OvnNetworkElement.ensureVpcSourceNat: VPC id={} missing ext={} or cidr={}; skipping",
                    vpc.getId(), externalIp, vpcCidr);
            return;
        }
        try {
            sourceNatService.ensureVpcSourceNat(vpc.getZoneId(), vpc.getId(), lrUuid, externalIp, vpcCidr);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNetworkElement.ensureVpcSourceNat: VPC id={} SNAT add failed: {}",
                    vpc.getId(), e.getMessage());
        }
    }

    @Override
    public boolean shutdownVpc(final Vpc vpc, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        try {
            // Phase II — drop the public-side LRP + default route + STATIC_ROUTE
            // mapping row before the LR cascade. The cascade-on-LR-delete
            // would also tear them down (strong refs), but explicit unbind
            // keeps the CS-side mapping rows clean and avoids relying on
            // OVSDB's cascade for our bookkeeping.
            final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
            if (controller != null) {
                final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
                if (lrMapping != null) {
                    publicNetworkManager.unbindVpcFromPublic(vpc.getZoneId(), vpc.getId(), lrMapping.getOvnUuid());
                }
            }
            vpcElement.deleteLogicalRouterFor(vpc);
            return true;
        } catch (com.cloud.network.ovn.client.OvnException e) {
            LOGGER.warn("OvnNetworkElement.shutdownVpc: VPC id={} OVN LR delete failed: {}", vpc.getId(), e.getMessage());
            // Best-effort: OVN cleanup failure should not block CloudStack VPC delete.
            return true;
        }
    }

    @Override
    public boolean createPrivateGateway(final PrivateGateway gateway) {
        // OVN private gateways translate to a tier-style LRP attached to the
        // VPC LR. MVP: VR fallback retains private GW; OVN-native private GW
        // is a Phase F.x extension. Returning true accepts the request as a
        // best-effort no-op so the orchestrator does not abort.
        LOGGER.debug("OvnNetworkElement.createPrivateGateway: gateway id={} accepted as no-op", gateway.getId());
        return true;
    }

    @Override
    public boolean deletePrivateGateway(final PrivateGateway privateGateway) {
        return true;
    }

    @Override
    public boolean applyStaticRoutes(final Vpc vpc, final List<StaticRouteProfile> routes) {
        // Static routes via Logical_Router_Static_Route — already supported in
        // OvnNbClient.addLogicalRouterStaticRoute. Wiring routes from the
        // CloudStack StaticRoute table to OVN is layered when the Phase F.x
        // public network manager goes live; for now accept the call so the
        // VPC orchestration does not fail.
        if (routes == null || routes.isEmpty()) {
            return true;
        }
        LOGGER.debug("OvnNetworkElement.applyStaticRoutes: vpc id={} {} route(s) accepted as no-op",
                vpc.getId(), routes.size());
        return true;
    }

    @Override
    public boolean applyACLItemsToPrivateGw(final PrivateGateway gateway, final List<? extends NetworkACLItem> rules) {
        return true;
    }

    @Override
    public boolean updateVpcSourceNatIp(final Vpc vpc, final com.cloud.network.IpAddress address) {
        // Rewrite the VPC-level SNAT row's external_ip in place. The NAT
        // row UUID stays, the LR.nat strong-ref is untouched, only the
        // external_ip column changes. Caller (CloudStack) handles the
        // upstream public-side announcement (BGP / next-hop), which is
        // out of band for OVN NB DB.
        final String newExt = address == null || address.getAddress() == null
                ? null : address.getAddress().addr();
        if (StringUtils.isBlank(newExt)) {
            LOGGER.warn("OvnNetworkElement.updateVpcSourceNatIp: vpc id={} new IP missing; skipping", vpc.getId());
            return true;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            LOGGER.warn("OvnNetworkElement.updateVpcSourceNatIp: vpc id={} no controller for zone", vpc.getId());
            return true;
        }
        final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (lrMapping == null) {
            LOGGER.warn("OvnNetworkElement.updateVpcSourceNatIp: vpc id={} no LR mapping; skipping", vpc.getId());
            return true;
        }
        final String vpcCidr = vpc.getCidr();
        if (StringUtils.isBlank(vpcCidr)) {
            return true;
        }
        try {
            sourceNatService.ensureVpcSourceNat(vpc.getZoneId(), vpc.getId(), lrMapping.getOvnUuid(), newExt, vpcCidr);
            LOGGER.info("OvnNetworkElement.updateVpcSourceNatIp: vpc id={} SNAT ext_ip rewritten to {}",
                    vpc.getId(), newExt);
        } catch (RuntimeException e) {
            LOGGER.error("OvnNetworkElement.updateVpcSourceNatIp: vpc id={} SNAT update failed: {}",
                    vpc.getId(), e.getMessage());
            return false;
        }
        return true;
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmds = new ArrayList<>();
        cmds.add(AddOvnControllerCmd.class);
        cmds.add(DeleteOvnControllerCmd.class);
        cmds.add(ListOvnControllersCmd.class);
        cmds.add(ImportOvnVpcCmd.class);
        return cmds;
    }

    private static Map<Service, Map<Capability, String>> buildCapabilities() {
        final Map<Service, Map<Capability, String>> caps = new HashMap<>();
        // Every Service entry must have a (possibly empty) map; CloudStack
        // capability look-ups assume non-null values. Service.Firewall and
        // Service.Gateway intentionally left out: their upstream definitions
        // enforce sub-capability lists OVN does not surface natively
        // (Firewall: TrafficStatistics, Gateway: RedundantRouter).
        caps.put(Service.Connectivity, new HashMap<>());
        caps.put(Service.Dhcp, dhcpCaps());
        caps.put(Service.Dns, dnsCaps());
        caps.put(Service.SourceNat, sourceNatCaps());
        caps.put(Service.StaticNat, staticNatCaps());
        caps.put(Service.PortForwarding, portForwardingCaps());
        caps.put(Service.Lb, lbCaps());
        caps.put(Service.NetworkACL, aclCaps());
        return caps;
    }

    private static Map<Capability, String> dnsCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.AllowDnsSuffixModification, "true");
        return m;
    }

    private static Map<Capability, String> portForwardingCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.SupportedProtocols, "tcp,udp");
        return m;
    }

    private static Map<Capability, String> dhcpCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.DhcpAccrossMultipleSubnets, "true");
        return m;
    }

    private static Map<Capability, String> sourceNatCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.SupportedSourceNatTypes, "peraccount");
        m.put(Capability.RedundantRouter, "true");
        return m;
    }

    private static Map<Capability, String> staticNatCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.ElasticIp, "true");
        return m;
    }

    private static Map<Capability, String> lbCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.SupportedLBAlgorithms, "roundrobin,leastconn,source");
        m.put(Capability.SupportedProtocols, "tcp,udp");
        m.put(Capability.SupportedLBIsolation, "dedicated,shared");
        m.put(Capability.LbSchemes, "Public,Internal");
        return m;
    }

    private static Map<Capability, String> aclCaps() {
        final Map<Capability, String> m = new HashMap<>();
        m.put(Capability.SupportedProtocols, "tcp,udp,icmp,all");
        return m;
    }
}
