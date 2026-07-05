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
import java.util.UUID;
import java.util.concurrent.Callable;

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
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
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
import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.config.OvnNicConfig;
import com.cloud.network.ovn.config.OvnNicTunables;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.api.command.admin.AddOvnControllerCmd;
import com.cloud.network.ovn.api.command.admin.DeleteOvnControllerCmd;
import com.cloud.network.ovn.api.command.admin.ImportOvnVpcCmd;
import com.cloud.network.ovn.api.command.admin.ListOvnControllersCmd;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionDaoImpl;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.utils.component.PluggableService;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
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
    private VpcOfferingDao vpcOfferingDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    @Inject
    private NetworkDetailsDao networkDetailsDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private OvnPublicNetworkManager publicNetworkManager;
    @Inject
    private OvnBgpRedistributeManager bgpRedistributeManager;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

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
        OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.PUBLIC_LRP, network.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — see OvnPublicNetworkManager for rationale.
            final OvnNbClient probe = pluginManager.nbClient(network.getDataCenterId());
            if (!probe.rowExistsByUuid("Logical_Router_Port", existing.getOvnUuid())) {
                LOGGER.warn("OvnNetworkElement.ensureTierBound: PUBLIC_LRP mapping net={} -> {} stale; recreating",
                        network.getId(), existing.getOvnUuid());
                logicalIdMapDao.remove(existing.getId());
                existing = null;
            }
        }
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
                final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
                nb.updateLogicalRouterPortNetworks(existing.getOvnUuid(), networks, gwMac);
                LOGGER.debug("OvnNetworkElement.ensureTierBound: LRP {} reconciled (tier id={}, networks={})",
                        existing.getOvnUuid(), network.getId(), networks);
                // Self-heal: verify the router-type peer LSP exists on the tier LS.
                // The mapping stores only the LRP UUID, so a missing LSP (caused by
                // a prior cleanup bug, partial LS rebuild, or manual ovn-nbctl edit)
                // is invisible to the stale-mapping guard above. Without the LSP
                // OVN cannot resolve the LRP.peer column and inter-tier L3 routing
                // through the VPC LR is broken. ensureRouterPeerLsp is idempotent:
                // if the LSP already exists the call is a no-op (reuses the row
                // and re-attaches it to the LS if detached).
                final String lspName = "rsp-" + tierName;
                final String lrpName = "lrp-" + tierName;
                final String lspUuid = nb.ensureRouterPeerLsp(tierLsUuid, lspName, lrpName);
                LOGGER.info("OvnNetworkElement.ensureTierBound: peer LSP {} ensured (tier id={}, lrpName={})",
                        lspUuid, network.getId(), lrpName);
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
        OvnLogicalIdMapVO already = logicalIdMapDao.findByCsId(Kind.NIC, nic.getId(), controller.getId());
        final List<String> addresses = buildAddresses(nic);
        if (already != null) {
            // Stale-mapping guard — recreate when LSP was deleted out-of-band
            // (manual ovn-nbctl lsp-del, parent LS rebuild, etc).
            final OvnNbClient probe = pluginManager.nbClient(network.getDataCenterId());
            if (!probe.rowExistsByUuid("Logical_Switch_Port", already.getOvnUuid())) {
                LOGGER.warn("OvnNetworkElement.prepare: NIC mapping nic={} -> {} stale; recreating",
                        nic.getId(), already.getOvnUuid());
                logicalIdMapDao.remove(already.getId());
                already = null;
            }
        }
        if (already == null) {
            final String lsUuid = ensureLogicalSwitch(network);
            final String lspName = buildLspName(nic);
            try {
                final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
                final String lspUuid = nb.addLogicalSwitchPort(lsUuid, lspName, addresses, null, null);
                // port_security mirrors addresses → spoof guard.
                nb.lspSetPortSecurity(lspUuid, addresses);
                // Feature: requested-chassis — pin LSP to specific OVN chassis.
                // Feature: arp_proxy — suppress ARP flooding on the logical segment.
                // Feature: ha-chassis-priority — combined with requested-chassis when set.
                applyLspOptions(nb, lspUuid, nic, vm);
                // Feature: BFD — liveness probing toward the tier gateway.
                applyLspBfd(nb, lspName, nic, vm);
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
        // P5 mixed-mode dispatch: program egress PER-TIER when the VPC hosts
        // any routed tier (NAT tier => snat scoped to its OWN cidr; routed
        // tier => no snat, announce handled below). A pure-NAT VPC and a
        // uniformly-ROUTED VPC both keep their P2 path unchanged (the legacy
        // VPC-wide snat writer, whose isRoutedVpc gate already no-ops routed).
        ensureTierEgressSourceNat(network);
        // Phase II — public-side LRP + default route attachment. Same
        // motivation as ensureVpcSourceNatFromTier: the VPC LR may have
        // implemented before the source-NAT IP existed, so retry on prepare.
        ensureVpcPublicAttachedFromTier(network);
        // P3 — ROUTED tier: advertise the tier subnet (/24) to the RRs. Fired
        // here (after public-attach) so the VPC public LRP next-hop exists and
        // the announce installs the datapath route + truly originates. No-op
        // for NATTED VPCs and when the routed-tier toggle is off. Idempotent.
        ensureRoutedTierAnnounce(network);
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
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), network.getDataCenterId(), Kind.NIC,
                mapping.getOvnUuid(), nic.getId());
        try {
            final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
            nb.deleteLogicalSwitchPort(mapping.getOvnUuid());
            // Remove any BFD session that was installed for this LSP name.
            // Best-effort: a failure here does not block the LSP deletion path.
            try {
                nb.removeBfdSession(mapping.getOvnName());
            } catch (OvnException bfdEx) {
                LOGGER.warn("OvnNetworkElement.release: BFD cleanup for LSP {} failed (non-fatal): {}",
                        mapping.getOvnName(), bfdEx.getMessage());
            }
            logicalIdMapDao.remove(mapping.getId());
            if (pendingDeletionDao.isPendingByOvnUuid(mapping.getOvnUuid(), Kind.NIC.name())) {
                pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.NIC.name());
            }
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.release: LSP {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
        }
        return true;
    }

    /**
     * Roll back a partially-implemented network. If the LS mapping row exists
     * but no active VMs remain on the switch (CloudStack has already cleaned
     * up any LSPs via {@code release()}), delete the Logical_Switch now so the
     * NB DB does not accumulate ghost switches for networks stuck in
     * {@code Shutdown} state.
     *
     * <p>Returns {@code true} in all cases: CloudStack lifecycle continues
     * regardless of OVN cleanup success. Failed cleanups are queued for retry.
     */
    @Override
    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return true;
        }
        // Drop per-tier DHCP_Options + DNS rows before the LS itself, same
        // ordering as destroy(). Must run even when the tier LS mapping is
        // already gone (removeTierDhcp/removeTierDns handle a null LS
        // mapping internally) — otherwise a network shut down here and
        // destroyed later leaks a DNS row that neither path ever revisits.
        try {
            dhcpService.removeTierDhcp(network);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNetworkElement.shutdown: removeTierDhcp failed (network id={}): {}",
                    network.getId(), e.getMessage());
        }
        try {
            dnsService.removeTierDns(network);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNetworkElement.shutdown: removeTierDns failed (network id={}): {}",
                    network.getId(), e.getMessage());
        }
        final OvnLogicalIdMapVO lsMapping = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        if (lsMapping == null) {
            return true;
        }
        LOGGER.info("OvnNetworkElement.shutdown: rolling back LS {} for network id={}", lsMapping.getOvnUuid(), network.getId());
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), network.getDataCenterId(), Kind.NETWORK,
                lsMapping.getOvnUuid(), network.getId());
        try {
            guru.deleteLogicalSwitchFor(network);
            // guru already marks succeeded inside deleteLogicalSwitchFor.
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.shutdown: LS {} delete failed; queued for retry: {}",
                    lsMapping.getOvnUuid(), e.getMessage());
        }
        return true;
    }

    /**
     * Each cleanup step runs in its own try/catch so that a failure in step N
     * does not skip steps N+1..5. Failed steps are queued to
     * {@code ovn_pending_deletion} for background retry instead of leaking.
     *
     * <p>Returns {@code true} regardless: CloudStack removes the network from
     * its DB; the pending-deletion processor handles eventual OVN cleanup.
     */
    @Override
    public boolean destroy(final Network network, final ReservationContext context) {
        final List<String> failures = new ArrayList<>();
        //  1. Drop per-tier DHCP_Options + DNS rows.
        runDestroyStep("removeTierDhcp", () -> { dhcpService.removeTierDhcp(network); return null; },
                failures, network, Kind.DHCP_OPTIONS);
        runDestroyStep("removeTierDns", () -> { dnsService.removeTierDns(network); return null; },
                failures, network, Kind.DNS_RECORDS);
        //  2. Drop the SOURCE_NAT row for this tier.
        runDestroyStep("removeSnatForTier",
                () -> { sourceNatService.removeSnatForTier(network.getDataCenterId(), network.getId()); return null; },
                failures, network, Kind.SOURCE_NAT);
        //  3. Drop tier-LRP from VPC LR.
        runDestroyStep("detachTierFromVpcLr", () -> { detachTierFromVpcLr(network); return null; },
                failures, network, Kind.PUBLIC_LRP);
        //  3b. Withdraw the ROUTED tier subnet announce (FRR + kernel route + row).
        runDestroyStep("withdrawRoutedTierAnnounce",
                () -> { withdrawRoutedTierAnnounce(network); return null; },
                failures, network, Kind.BGP_SUBNET_ANNOUNCE);
        //  4. Drop the Logical_Switch (cascade kills any leftover LSPs).
        runDestroyStep("deleteLogicalSwitchFor", () -> { guru.deleteLogicalSwitchFor(network); return null; },
                failures, network, Kind.NETWORK);
        if (!failures.isEmpty()) {
            LOGGER.warn("OvnNetworkElement.destroy: network id={} had {} step failure(s) queued for retry: {}",
                    network.getId(), failures.size(), failures);
        }
        return true;
    }

    /**
     * Execute one destroy step; on exception log + record failure + enqueue
     * pending deletion so the background processor retries it.
     */
    private void runDestroyStep(final String stepName, final Callable<Void> step,
                                final List<String> failures, final Network network, final Kind kindForRetry) {
        try {
            step.call();
        } catch (Exception e) {
            LOGGER.warn("OvnNetworkElement.destroy: step={} network id={} failed: {}",
                    stepName, network.getId(), e.getMessage(), e);
            failures.add(stepName);
            enqueueMappingsForRetry(network, kindForRetry);
        }
    }

    /**
     * Enqueue every surviving mapping row of {@code kind} for {@code network}
     * into the pending-deletion queue. Safe to call when the mapping was
     * already wiped — the kind lookup returns null in that case and no row
     * is added.
     */
    private void enqueueMappingsForRetry(final Network network, final Kind kind) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(kind, network.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        enqueueIfAbsent(controller.getId(), network.getDataCenterId(), kind,
                mapping.getOvnUuid(), network.getId());
    }

    /**
     * Enqueue a pending deletion if no live row for the same UUID+kind exists.
     * Uses controller_id=0 sentinel when controllerId matches the sentinel
     * constant (e.g. called from shutdownVpc with no controller resolved).
     */
    private void enqueueIfAbsent(final long controllerId, final long zoneId, final Kind kind,
                                  final String ovnUuid, final Long csId) {
        if (ovnUuid == null || ovnUuid.isEmpty()) {
            return;
        }
        if (pendingDeletionDao.isPendingByOvnUuid(ovnUuid, kind.name())) {
            return;
        }
        final OvnPendingDeletionVO entry = new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), controllerId, zoneId, kind, ovnUuid, csId);
        pendingDeletionDao.persist(entry);
        LOGGER.info("OvnNetworkElement: queued pending deletion kind={} ovn_uuid={} cs_id={}", kind, ovnUuid, csId);
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
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), network.getDataCenterId(), Kind.PUBLIC_LRP,
                mapping.getOvnUuid(), network.getId());
        try {
            pluginManager.nbClient(network.getDataCenterId()).deleteLogicalRouterPort(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.PUBLIC_LRP.name());
            LOGGER.info("OvnNetworkElement.detachTier: LRP {} dropped (tier id={})",
                    mapping.getOvnUuid(), network.getId());
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.detachTier: LRP {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
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
                    final IPAddressVO removed = ipAddressDao.findById(rule.getSourceIpAddressId());
                    final String removedAddr = removed == null || removed.getAddress() == null
                            ? null : removed.getAddress().addr();
                    if (StringUtils.isNotBlank(removedAddr)) {
                        bgpRedistributeManager.withdraw(removedAddr, rule.getSourceIpAddressId(), vpcId,
                                config.getDataCenterId());
                    }
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
                bgpRedistributeManager.announce(externalIpStr, rule.getSourceIpAddressId(), vpcId,
                        config.getDataCenterId());
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

    /**
     * Resolve and apply per-LSP {@code options} entries that require mgmt-side
     * NB programming:
     * <ul>
     *   <li>{@code requested-chassis} — pin the port to the VM's scheduled
     *       hypervisor so OVN does not float the port binding to a different
     *       chassis on a transient claim. Resolved from VM detail &gt; global
     *       ConfigKey. Empty string = omit (any chassis, OVN default).</li>
     *   <li>{@code arp_proxy} — suppress ARP flooding: OVN answers ARP queries
     *       for the NIC's IPv4 (and IPv6 if present) on behalf of the port.
     *       Resolved from VM detail &gt; global ConfigKey; only emitted when the
     *       NIC has at least one IP assigned.</li>
     * </ul>
     *
     * <p>Both features are backward-compatible: when neither knob produces a
     * non-empty value, this method issues no OVSDB call and the LSP row is
     * identical to what existed before this feature was added.
     */
    private void applyLspOptions(final OvnNbClient nb, final String lspUuid,
                                 final NicProfile nic, final VirtualMachineProfile vm) {
        final Map<String, String> opts = new HashMap<>();

        // Feature 2: requested-chassis (+ optional HA chassis priority).
        final Map<String, String> vmDetails = vm == null ? null
                : (vm.getVirtualMachine() == null ? null : vm.getVirtualMachine().getDetails());
        final String chassis = OvnNicTunables.resolve(OvnNicTunables.OVN_REQUESTED_CHASSIS,
                vmDetails, null, null,
                OvnNicConfig.RequestedChassis.value(), String.class);
        if (StringUtils.isNotBlank(chassis)) {
            final Integer haPriority = OvnNicTunables.resolve(OvnNicTunables.OVN_HA_CHASSIS_PRIORITY,
                    vmDetails, null, null,
                    OvnNicConfig.HaChassisPriority.value(), Integer.class);
            if (haPriority != null && haPriority != 0) {
                // OVN NB accepts "chassis=priority" for ha-chassis-group binding.
                opts.put("requested-chassis", chassis + "=" + haPriority);
            } else {
                opts.put("requested-chassis", chassis);
            }
        }

        // Feature 3: arp_proxy.
        final Boolean arpProxy = OvnNicTunables.resolve(OvnNicTunables.OVN_LSP_ARP_PROXY,
                vmDetails, null, null,
                OvnNicConfig.LspArpProxy.value(), Boolean.class);
        if (Boolean.TRUE.equals(arpProxy)) {
            final StringBuilder ips = new StringBuilder();
            if (StringUtils.isNotBlank(nic.getIPv4Address())) {
                ips.append(nic.getIPv4Address());
            }
            if (StringUtils.isNotBlank(nic.getIPv6Address())) {
                if (ips.length() > 0) {
                    ips.append(',');
                }
                ips.append(nic.getIPv6Address());
            }
            if (ips.length() > 0) {
                opts.put("arp_proxy", ips.toString());
            }
        }

        if (!opts.isEmpty()) {
            nb.lspSetOptions(lspUuid, opts);
            LOGGER.debug("OvnNetworkElement.applyLspOptions: LSP {} options={}", lspUuid, opts);
        }
    }

    /**
     * Installs (or replaces) a BFD session for the named LSP when the
     * {@code ovn.bfd.enable} knob resolves to {@code true} at any scope.
     * The BFD peer IP is the tier gateway ({@link NicProfile#getGateway()});
     * when the gateway is unavailable, the method logs a warning and skips
     * BFD installation rather than failing the prepare path.
     *
     * <p>Backward-compat: when {@code bfd.enable} is false or absent, no
     * OVSDB call is issued and any previously installed BFD row is left
     * as-is (idempotent on the no-op path).
     *
     * @param nb      Northbound client to use for the BFD insert
     * @param lspName LSP name string (NOT UUID) — BFD schema key
     * @param nic     NicProfile carrying the tier gateway IP
     * @param vm      VirtualMachineProfile for VM-scope detail resolution
     */
    private void applyLspBfd(final OvnNbClient nb, final String lspName,
                             final NicProfile nic, final VirtualMachineProfile vm) {
        final Map<String, String> vmDetails = vm == null ? null
                : (vm.getVirtualMachine() == null ? null : vm.getVirtualMachine().getDetails());
        final Boolean bfdEnable = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_ENABLE,
                vmDetails, null, null,
                OvnNicConfig.BfdEnable.value(), Boolean.class);
        if (!Boolean.TRUE.equals(bfdEnable)) {
            return;
        }
        final String dstIp = nic.getIPv4Gateway();
        if (StringUtils.isBlank(dstIp)) {
            LOGGER.warn("OvnNetworkElement.applyLspBfd: LSP {} BFD enabled but NIC has no gateway IP; skipping", lspName);
            return;
        }
        final int minRx = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_MIN_RX,
                vmDetails, null, null,
                OvnNicConfig.BfdMinRx.value(), Integer.class);
        final int minTx = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_MIN_TX,
                vmDetails, null, null,
                OvnNicConfig.BfdMinTx.value(), Integer.class);
        final int mult = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_MULTIPLIER,
                vmDetails, null, null,
                OvnNicConfig.BfdMultiplier.value(), Integer.class);
        nb.addBfdSession(lspName, dstIp, minRx, minTx, mult);
        LOGGER.debug("OvnNetworkElement.applyLspBfd: LSP {} BFD session -> {} rx={} tx={} mult={}",
                lspName, dstIp, minRx, minTx, mult);
    }

    /**
     * Pushes conntrack inactive-timeout options onto the Logical_Router backing
     * a VPC. The four OVN NB option keys written are:
     * <ul>
     *   <li>{@code ct_tcp_idle_timeout}</li>
     *   <li>{@code ct_udp_idle_timeout}</li>
     *   <li>{@code ct_snat_idle_timeout}</li>
     *   <li>{@code ct_icmp_idle_timeout}</li>
     * </ul>
     *
     * <p>All four are read from the global ConfigKey defaults (no per-VM /
     * per-network scope for LR options — the LR is VPC-scoped). When all
     * four values match the OVN built-in defaults the method is a no-op to
     * avoid unnecessary OVSDB churn.
     *
     * <p>OVN supports these options since OVN 21.x. On older builds the
     * update is silently ignored by the NB DB (unknown columns are rejected;
     * the OVSDB error is swallowed by {@code OvnNbClient.lrSetOptions}).
     *
     * @param nb     Northbound client to use for the update
     * @param lrUuid Logical_Router row UUID to update
     */
    private void applyLrCtTimeouts(final OvnNbClient nb, final String lrUuid) {
        final int tcpTimeout = OvnNicConfig.CtTcpInactiveTimeout.value();
        final int udpTimeout = OvnNicConfig.CtUdpInactiveTimeout.value();
        final int snatTimeout = OvnNicConfig.CtSnatInactiveTimeout.value();
        final int icmpTimeout = OvnNicConfig.CtIcmpInactiveTimeout.value();

        // OVN built-in defaults; skip write if all values are at default.
        final boolean allDefault = (tcpTimeout == 86400) && (udpTimeout == 60)
                && (snatTimeout == 7440) && (icmpTimeout == 30);
        if (allDefault) {
            return;
        }
        final Map<String, String> opts = new HashMap<>();
        opts.put("ct_tcp_idle_timeout", String.valueOf(tcpTimeout));
        opts.put("ct_udp_idle_timeout", String.valueOf(udpTimeout));
        opts.put("ct_snat_idle_timeout", String.valueOf(snatTimeout));
        opts.put("ct_icmp_idle_timeout", String.valueOf(icmpTimeout));
        nb.lrSetOptions(lrUuid, opts);
        LOGGER.debug("OvnNetworkElement.applyLrCtTimeouts: LR {} options={}", lrUuid, opts);
    }

    // ------------------------------------------------------------------
    // VpcProvider — delegate to OvnVpcElement.
    // ------------------------------------------------------------------

    @Override
    public boolean implementVpc(final Vpc vpc, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        try {
            final String lrUuid = vpcElement.createLogicalRouterFor(vpc);
            // Feature: CT inactive timeouts — push per-LR conntrack timeout options
            // when any of the four knobs is set to a non-default value.
            applyLrCtTimeouts(pluginManager.nbClient(vpc.getZoneId()), lrUuid);
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
            // Stale-mapping guard: when the VPC_PUBLIC_LRP row was deleted
            // out-of-band (manual ovn-nbctl lrp-del, partial cleanup), the
            // mapping points to a dead UUID. Drop the stale row + paired
            // STATIC_ROUTE row and fall through to recreate via
            // publicNetworkManager.ensureVpcBoundToPublic. Without this
            // guard the helper short-circuits without recreating.
            final OvnNbClient probe = pluginManager.nbClient(vpc.getZoneId());
            if (probe.rowExistsByUuid("Logical_Router_Port", existing.getOvnUuid())) {
                return;
            }
            LOGGER.warn("OvnNetworkElement.ensureVpcPublicAttached: VPC_PUBLIC_LRP mapping vpc={} -> {} stale; recreating",
                    vpc.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
            // Paired STATIC_ROUTE mapping likely orphan too (LR.static_routes
            // strong-ref cascaded when LRP was dropped via lrp-del). Drop
            // unconditionally so bindVpcToPublic can recreate cleanly.
            final OvnLogicalIdMapVO staleRoute = logicalIdMapDao.findByCsId(Kind.STATIC_ROUTE, vpc.getId(), controller.getId());
            if (staleRoute != null) {
                logicalIdMapDao.remove(staleRoute.getId());
            }
        }
        final List<IPAddressVO> sourceNatIps = ipAddressDao.listByAssociatedVpc(vpc.getId(), Boolean.TRUE);
        final IPAddressVO ip;
        if (sourceNatIps != null && !sourceNatIps.isEmpty()) {
            ip = sourceNatIps.get(0);
        } else if (vpcHasRoutedTier(vpc.getId())) {
            // Mode-4 (routed-public): a VPC hosting only routed tiers never gets
            // a source-NAT IP marked (nothing drives VPC-level SourceNat), yet
            // its LR still needs a public-segment foot + default route so the
            // routed tiers can egress. Anchor the public LRP on the first
            // operator-associated public IP instead of the (absent) source-NAT
            // IP. Gated on vpcHasRoutedTier so pure-NAT / FIP VPCs (which always
            // have a source-NAT IP) never reach this branch.
            final List<IPAddressVO> associated = ipAddressDao.listByAssociatedVpc(vpc.getId(), Boolean.FALSE);
            if (associated == null || associated.isEmpty()) {
                LOGGER.debug("OvnNetworkElement.ensureVpcPublicAttached: routed VPC id={} has no source-NAT and no associated public IP to anchor the public LRP — deferred (associate a public IP)",
                        vpc.getId());
                return;
            }
            ip = associated.get(0);
        } else {
            LOGGER.debug("OvnNetworkElement.ensureVpcPublicAttached: VPC id={} no source-NAT IP yet — deferred",
                    vpc.getId());
            return;
        }
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

    // ------------------------------------------------------------------
    // P5 — per-tier mode (mixed NAT + routed under one VPC LR).
    //
    // CloudStack networkmode is a per-VPC property, but a tier's OWN network
    // offering may omit SourceNat and carry Gateway instead
    // (VpcManagerImpl.validateNtwkOffForVpc accepts Isolated + SourceNat OR
    // Gateway; validateNtwkOffForNtwkInVpc only needs the tier's
    // service/provider pairs to be a subset of the VPC offering's). So NAT
    // tiers (SourceNat/Ovn) and routed tiers (Gateway/Ovn, no SourceNat)
    // coexist under one NATTED OVN VPC offering. The per-tier mode is read
    // from the tier's OWN offering, never from the VPC networkmode.
    //
    // Behaviour matrix (preserves P2/P3 for uniform VPCs):
    //   - uniform NATTED VPC  (no routed tier)  -> legacy VPC-wide snat path.
    //   - uniform ROUTED VPC  (isRoutedVpc)      -> P2: no snat; P3: announce.
    //   - MIXED VPC (NATTED offering + >=1 routed tier) -> per-tier: NAT tier
    //     snat scoped to its own cidr, routed tier no snat + announce.
    // ------------------------------------------------------------------

    /** ROUTED tier iff its OWN offering does NOT provide {@link Service#SourceNat}
     *  (it carries Gateway/Connectivity only). NAT tiers (offering has
     *  SourceNat) => false. */
    private boolean isRoutedTier(final Network network) {
        final long offId = network.getNetworkOfferingId();
        // Fail-safe to NATTED (mirrors isRoutedVpc): a missing/corrupt offering
        // has NO service-map rows, so areServicesSupportedByNetworkOffering(...
        // SourceNat) returns false and the raw !SourceNat signal would wrongly
        // read "routed" — dropping the VPC-wide SNAT and killing NAT egress.
        // Require Connectivity (every valid OVN tier declares it) as proof the
        // service map is populated before trusting the SourceNat-absent signal.
        if (!networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offId, Service.Connectivity)) {
            return false;
        }
        return !networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offId, Service.SourceNat);
    }

    /** True when at least one guest tier of the VPC is a routed tier. */
    private boolean vpcHasRoutedTier(final long vpcId) {
        final List<NetworkVO> tiers = networkDao.listByVpc(vpcId);
        if (tiers == null) {
            return false;
        }
        for (final NetworkVO tier : tiers) {
            if (isRoutedTier(tier)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Per-tier egress SNAT dispatcher (P5). Idempotent; called on every NIC
     * prepare. Preserves the P2 legacy VPC-wide path for uniform VPCs and
     * only switches to per-tier SNAT when a NATTED VPC actually hosts a
     * routed tier (a mixed VPC). The announce side is handled separately by
     * {@link #ensureRoutedTierAnnounce} (after public-attach, per P3 ordering).
     */
    private void ensureTierEgressSourceNat(final Network network) {
        if (network.getVpcId() == null) {
            return;
        }
        final Vpc vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return;
        }
        // Uniform ROUTED VPC (P2): delegate to the VPC-wide writer whose
        // isRoutedVpc gate short-circuits (no snat, no /32 announce). Unchanged.
        if (isRoutedVpc(vpc)) {
            ensureVpcSourceNatFromTier(network);
            return;
        }
        // NATTED VPC offering with only NAT tiers -> pure-NAT VPC: keep the
        // legacy VPC-wide snat on the parent CIDR. No behavioural change.
        if (!vpcHasRoutedTier(vpc.getId())) {
            ensureVpcSourceNatFromTier(network);
            return;
        }
        // MIXED VPC: a VPC-wide parent-CIDR snat would wrongly SNAT the routed
        // tiers living inside the VPC cidr. Drop it (no-op when never written)
        // and program egress per-tier. A routed tier gets NO snat; a NAT tier
        // gets a snat scoped to its OWN cidr.
        sourceNatService.removeVpcSourceNat(network.getDataCenterId(), vpc.getId());
        // Re-assert per-tier SNAT for EVERY NAT tier of the VPC, not just the
        // tier being prepared: the VPC-wide row we just removed may have been the
        // sole egress for NAT tiers prepared while the VPC was still pure-NAT, so
        // deleting it on a routed-tier prepare would strand them until they
        // re-prepare. ensureTierSourceNat is idempotent (one Kind.SOURCE_NAT row
        // per tier), so re-asserting all NAT tiers is safe.
        final List<NetworkVO> vpcTiers = networkDao.listByVpc(vpc.getId());
        if (vpcTiers != null) {
            for (final NetworkVO tier : vpcTiers) {
                if (!isRoutedTier(tier)) {
                    ensureTierSourceNat(tier);
                }
            }
        }
    }

    /**
     * NAT tier inside a mixed VPC: emit {@code snat <vpcSourceNatIp> <tierCidr>}
     * on the VPC LR (one {@link Kind#SOURCE_NAT} row per tier, matching
     * {@link #applyIps} and destroy's {@code removeSnatForTier}), and keep the
     * shared source-nat /32 steered to the gateway chassis for return traffic.
     */
    private void ensureTierSourceNat(final Network network) {
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
        final String tierCidr = network.getCidr();
        if (StringUtils.isBlank(tierCidr)) {
            return;
        }
        final List<IPAddressVO> sourceNatIps = ipAddressDao.listByAssociatedVpc(vpc.getId(), Boolean.TRUE);
        if (sourceNatIps == null || sourceNatIps.isEmpty()) {
            return;
        }
        final IPAddressVO snatIp = sourceNatIps.get(0);
        final String externalIp = snatIp.getAddress() == null ? null : snatIp.getAddress().addr();
        if (StringUtils.isBlank(externalIp)) {
            return;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.SOURCE_NAT, network.getId(), controller.getId());
        if (existing == null) {
            try {
                sourceNatService.addSnat(network.getDataCenterId(), network.getId(), lrMapping.getOvnUuid(),
                        externalIp, tierCidr);
            } catch (RuntimeException e) {
                LOGGER.warn("OvnNetworkElement.ensureTierSourceNat: tier id={} snat add failed: {}",
                        network.getId(), e.getMessage());
            }
        }
        // /32 return-traffic steering (self-gates on the FIP redistribute toggle).
        bgpRedistributeManager.announce(externalIp, snatIp.getId(), vpc.getId(), vpc.getZoneId());
    }

    /**
     * Per-tier advertise override for a routed tier. An explicit
     * {@link OvnNetworkConfig#NETWORK_DETAIL_TIER_ADVERTISE} network detail
     * wins ({@code true/1/yes/on} => announce, anything else => suppress);
     * absent/blank => {@code true}, deferring to
     * {@link OvnBgpRedistributeManager#announceSubnet}'s own global
     * routed-tiers gate (so P3 behaviour is unchanged). Nothing hardcoded —
     * the operator toggles a single tier via {@code cmk update network
     * details[0].key=ovn.tier.advertise}.
     */
    private boolean isTierAdvertiseEnabled(final Network network) {
        final NetworkDetailVO detail = networkDetailsDao.findDetail(network.getId(),
                OvnNetworkConfig.NETWORK_DETAIL_TIER_ADVERTISE);
        if (detail == null || StringUtils.isBlank(detail.getValue())) {
            return true;
        }
        final String v = detail.getValue().trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v)
                || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
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
        // P2: ROUTED VPCs have no SourceNat service. Their tiers egress with
        // their real (RFC1918 or public) source IP and are reachable via
        // BGP-advertised connected routes, not NAT. Skip BOTH the VPC-wide
        // 'snat <ip> <cidr>' row and the source-nat-IP /32 announce here (both
        // belong to NATTED mode only). The per-IP applyIps path is already
        // gated on Service.SourceNat, so this closes the one remaining leak
        // (implementVpc + ensureVpcSourceNatFromTier both funnel through here).
        if (isRoutedVpc(vpc)) {
            LOGGER.info("OvnNetworkElement.ensureVpcSourceNat: VPC id={} is ROUTED — skipping VPC-wide SNAT + source-nat /32 announce",
                    vpc.getId());
            return;
        }
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
            // BGP /32 redistribute is opt-in — the manager skips inside when
            // the VPC / global toggle is off, so the call is unconditional.
            bgpRedistributeManager.announce(externalIp, ip.getId(), vpc.getId(), vpc.getZoneId());
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNetworkElement.ensureVpcSourceNat: VPC id={} SNAT add failed: {}",
                    vpc.getId(), e.getMessage());
        }
    }

    /**
     * True when the VPC's offering is in ROUTED network mode. ROUTED VPCs get
     * no OVN SNAT and no source-nat /32 announce (P2); reachability is via
     * BGP-advertised tier subnets (P3/P4). Fails safe to {@code false} (NATTED
     * behaviour) when the offering row is missing, so an orphaned offering
     * never silently disables NAT on a genuinely NATTED VPC.
     */
    private boolean isRoutedVpc(final Vpc vpc) {
        if (vpc == null) {
            return false;
        }
        final VpcOffering offering = vpcOfferingDao.findById(vpc.getVpcOfferingId());
        if (offering == null) {
            LOGGER.warn("OvnNetworkElement.isRoutedVpc: VPC id={} offering id={} not found; assuming NATTED",
                    vpc.getId(), vpc.getVpcOfferingId());
            return false;
        }
        return NetworkOffering.NetworkMode.ROUTED == offering.getNetworkMode();
    }

    /**
     * Announce this tier's subnet to the route reflectors. Fires when the VPC
     * is uniformly ROUTED (P3) OR when THIS specific tier is a routed tier
     * inside an otherwise-NATTED (mixed) VPC (P5). A NAT tier never announces
     * its subnet. Best-effort: a missing VPC / blank CIDR / advertise-disabled
     * detail is a soft no-op. The redistribute manager applies the global
     * routed-tier toggle on top of the per-tier gate here.
     */
    private void ensureRoutedTierAnnounce(final Network network) {
        if (network.getVpcId() == null) {
            return;
        }
        final Vpc vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return;
        }
        // Uniform ROUTED VPC (P3) announces every tier; a mixed VPC announces
        // only its routed tiers (per-tier signal = tier offering has no SourceNat).
        if (!isRoutedVpc(vpc) && !isRoutedTier(network)) {
            return;
        }
        // Per-tier advertise override (nothing hardcoded — see isTierAdvertiseEnabled).
        if (!isTierAdvertiseEnabled(network)) {
            return;
        }
        final String cidr = network.getCidr();
        if (StringUtils.isBlank(cidr)) {
            return;
        }
        bgpRedistributeManager.announceSubnet(cidr, network.getId(), vpc.getId(), vpc.getZoneId());
    }

    /**
     * Withdraw this tier's routed-subnet announce on tier teardown. Not gated
     * on {@link #isRoutedVpc} — the offering may already be gone during
     * destroy, and withdrawSubnet is a no-op when no announce row exists.
     */
    private void withdrawRoutedTierAnnounce(final Network network) {
        if (network.getVpcId() == null) {
            return;
        }
        final Vpc vpc = vpcDao.findById(network.getVpcId());
        final long vpcId = vpc != null ? vpc.getId() : network.getVpcId();
        final long zoneId = vpc != null ? vpc.getZoneId() : network.getDataCenterId();
        final String cidr = network.getCidr();
        if (StringUtils.isBlank(cidr)) {
            return;
        }
        bgpRedistributeManager.withdrawSubnet(cidr, network.getId(), vpcId, zoneId);
    }

    /**
     * Tear down the OVN LR and all public-side attachments for a VPC.
     *
     * <p>Each step is independent: a failure in public-unbind does not skip
     * the LR delete, and vice versa. When the LR delete fails the mapping
     * UUID is enqueued into {@code ovn_pending_deletion} BEFORE returning so
     * the retry queue holds the OVN UUID even if the mapping row is
     * subsequently wiped by other cleanup paths.
     *
     * <p>Returns {@code true} in all cases so CloudStack removes the VPC
     * from its DB; the pending-deletion processor handles eventual LR cleanup.
     */
    @Override
    public boolean shutdownVpc(final Vpc vpc, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        // Step 1: withdraw BGP /32 announces (best-effort; surplus reaped by reconciler).
        withdrawBgpForVpc(vpc);
        // Step 2: unbind VPC LR from public LS.
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller != null) {
            unbindVpcPublicStep(vpc, controller);
        }
        // Step 3: delete VPC LR. Enqueue BEFORE calling deleteLogicalRouterFor
        // so the OVN UUID is in the queue even if the call wipes the mapping row.
        final OvnLogicalIdMapVO lrMapping = controller == null ? null
                : logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (lrMapping != null) {
            try {
                vpcElement.deleteLogicalRouterFor(vpc);
            } catch (OvnException e) {
                LOGGER.warn("OvnNetworkElement.shutdownVpc: VPC id={} LR delete failed; queuing for retry: {}",
                        vpc.getId(), e.getMessage());
                final long effControllerId = controller != null
                        ? controller.getId() : OvnPendingDeletionDaoImpl.CONTROLLER_SENTINEL;
                enqueueIfAbsent(effControllerId, vpc.getZoneId(), Kind.VPC,
                        lrMapping.getOvnUuid(), vpc.getId());
            }
        } else {
            // No mapping — either already cleaned or controller absent.
            // Call deleteLogicalRouterFor anyway; it is a no-op when the mapping is missing.
            try {
                vpcElement.deleteLogicalRouterFor(vpc);
            } catch (OvnException e) {
                LOGGER.warn("OvnNetworkElement.shutdownVpc: VPC id={} LR delete (no-mapping path) failed: {}",
                        vpc.getId(), e.getMessage());
            }
        }
        return true;
    }

    private void withdrawBgpForVpc(final Vpc vpc) {
        final List<IPAddressVO> publicIps = ipAddressDao.listByAssociatedVpc(vpc.getId(), null);
        if (publicIps == null) {
            return;
        }
        for (final IPAddressVO ip : publicIps) {
            final String addr = ip.getAddress() == null ? null : ip.getAddress().addr();
            if (StringUtils.isNotBlank(addr)) {
                bgpRedistributeManager.withdraw(addr, ip.getId(), vpc.getId(), vpc.getZoneId());
            }
        }
    }

    private void unbindVpcPublicStep(final Vpc vpc, final OvnControllerVO controller) {
        final OvnLogicalIdMapVO lrMapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (lrMapping == null) {
            return;
        }
        try {
            publicNetworkManager.unbindVpcFromPublic(vpc.getZoneId(), vpc.getId(), lrMapping.getOvnUuid());
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNetworkElement.shutdownVpc: VPC id={} public unbind failed (non-fatal): {}",
                    vpc.getId(), e.getMessage());
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
            // Capture the old source-NAT IP (if any) before we rewrite, so
            // we can withdraw the /32 announce for it on the gateway-chassis.
            final List<IPAddressVO> previousSourceNat = ipAddressDao.listByAssociatedVpc(vpc.getId(), Boolean.TRUE);
            sourceNatService.ensureVpcSourceNat(vpc.getZoneId(), vpc.getId(), lrMapping.getOvnUuid(), newExt, vpcCidr);
            for (final IPAddressVO previous : previousSourceNat) {
                final String prevAddr = previous.getAddress() == null ? null : previous.getAddress().addr();
                if (StringUtils.isNotBlank(prevAddr) && !prevAddr.equals(newExt)) {
                    bgpRedistributeManager.withdraw(prevAddr, previous.getId(), vpc.getId(), vpc.getZoneId());
                }
            }
            if (address != null) {
                bgpRedistributeManager.announce(newExt, address.getId(), vpc.getId(), vpc.getZoneId());
            }
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
        cmds.add(com.cloud.network.ovn.api.command.admin.RunOvnReconcilerCmd.class);
        return cmds;
    }

    private static Map<Service, Map<Capability, String>> buildCapabilities() {
        final Map<Service, Map<Capability, String>> caps = new HashMap<>();
        // Every Service entry must have a (possibly empty) map; CloudStack
        // capability look-ups assume non-null values. Service.Firewall is
        // intentionally left out (its upstream definition enforces
        // TrafficStatistics, which OVN does not surface natively).
        // Service.Gateway IS declared: a Routed (dynamic-routing / BGP) VPC
        // needs a Gateway provider, and the OVN distributed LR is that gateway
        // (HA via ha_chassis_group). Without it a ROUTED OVN VPC tier fails
        // "Service/provider combination Gateway/Ovn is not supported by VPC".
        caps.put(Service.Connectivity, new HashMap<>());
        caps.put(Service.Gateway, gatewayCaps());
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

    private static Map<Capability, String> gatewayCaps() {
        final Map<Capability, String> m = new HashMap<>();
        // OVN's LR gateway is distributed + HA via ha_chassis_group; expose the
        // RedundantRouter capability (consistent with sourceNatCaps) so the
        // Gateway service validates on Routed VPC offerings.
        m.put(Capability.RedundantRouter, "true");
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
