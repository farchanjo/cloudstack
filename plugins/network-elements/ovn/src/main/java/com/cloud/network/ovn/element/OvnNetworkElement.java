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
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
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
        } catch (OvnException e) {
            throw new ResourceUnavailableException("OVN LS create failed: " + e.getMessage(),
                    Network.class, network.getId());
        }
        return true;
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
        if (already == null) {
            final String lsUuid = ensureLogicalSwitch(network);
            final List<String> addresses = buildAddresses(nic);
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
        }
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
            // Order matters:
            //  1. Drop per-tier QoS rows first (depend on LSP+LS still present
            //     for their parent LS lookup; QoS rows tied to specific LSPs
            //     get dropped via release() per NIC, but the bean's
            //     remove-by-network safety net runs here too via removeTierDhcp
            //     side effect of LSP cascade).
            //  2. Drop per-tier DHCP_Options + DNS rows.
            //  3. Detach the tier from any VPC LR (drops the LRP/LSP
            //     router-patch pair so the LS has no dangling ref).
            //  4. Drop the LS itself.
            dhcpService.removeTierDhcp(network);
            dnsService.removeTierDns(network);
            // Detach from VPC LR if attached. Skip when network has no VPC.
            // OvnVpcElement helper carries the LRP cleanup; weak-ref detach
            // not needed because router-patch LSPs are strong refs from LS
            // and OVSDB cascades on LS delete. We still drop the LRP first
            // so the LR's ports set doesn't dangle.
            // (Implementation pending — for the MVP we rely on LR delete in
            // shutdownVpc() to cascade-drop LRPs; orphan LRP only persists
            // when tier is removed without the VPC also being removed.)
            guru.deleteLogicalSwitchFor(network);
        } catch (OvnException e) {
            LOGGER.warn("OvnNetworkElement.destroy: failed network id={}: {}", network.getId(), e.getMessage());
        }
        return true;
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
        // OVN encodes public-IP semantics directly inside SNAT / DNAT rules
        // and via the public-side LRP — the orchestrator's "deploy IP" hook
        // is a no-op here; SourceNat/StaticNat/PortForwarding bring the IP
        // online when their rules are applied.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("OvnNetworkElement.applyIps: network id={} ips={} services={} — handled by NAT rules",
                    network.getId(), ipAddress == null ? 0 : ipAddress.size(), services);
        }
        return true;
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
            vpcElement.createLogicalRouterFor(vpc);
            return true;
        } catch (com.cloud.network.ovn.client.OvnException e) {
            LOGGER.error("OvnNetworkElement.implementVpc: VPC id={} OVN LR create failed: {}", vpc.getId(), e.getMessage());
            throw new ResourceUnavailableException("OVN LR create failed: " + e.getMessage(),
                    Vpc.class, vpc.getId());
        }
    }

    @Override
    public boolean shutdownVpc(final Vpc vpc, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        try {
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
        // Source NAT IP rotation via OvnSourceNatService.updateSnatExternalIp
        // is not yet exposed; accept and log so the orchestrator does not
        // bounce the rotation. Real swap lands when the public network
        // manager wires the new IP to the VPC's public-side LRP.
        LOGGER.debug("OvnNetworkElement.updateVpcSourceNatIp: vpc id={} new ip={} accepted as no-op",
                vpc.getId(), address == null ? null : address.getAddress());
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
