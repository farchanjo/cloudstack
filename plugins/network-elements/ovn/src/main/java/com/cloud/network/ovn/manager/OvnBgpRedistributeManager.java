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
package com.cloud.network.ovn.manager;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.OvnBgpAnnounceCommand;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.network.IpAddress;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnPublicNetworkManager;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Announces / withdraws a {@code /32} host route per allocated public IP via
 * the host-side FRR daemon on OVN data-plane chassis. Pure opt-in,
 * controlled by the global ConfigKey {@code ovn.bgp.redistribute.public_ips}
 * or its per-VPC detail override (see {@link OvnPublicNetworkManager#isBgpRedistributeEnabled}).
 *
 * <p>Why this exists: when the public network's parent prefix is announced
 * by every data node via ECMP (the canonical CloudStack DC layout), inbound
 * traffic to a public IP can land on any node — but the conntrack /
 * stateful-NAT state for SourceNAT / StaticNAT / PortForward lives only on
 * the OVN gateway-chassis hosting the VPC's distributed-gateway LRP. A
 * {@code /32} announce from the gateway-chassis pulls those IPs toward the
 * right node.
 *
 * <p><b>Option B (LB anycast)</b>: when a public IPv4 is used <em>only</em> by
 * LoadBalancing (no SNAT / 1:1 NAT / PF), the {@code /32} is announced on
 * every hypervisor that currently hosts a non-revoked Running LB backend VM.
 * BGP ECMP then spreads ingress across those data nodes; OVN LB still
 * round-robins guest backends. K8s control-plane VMs are not special-cased —
 * they join only if assigned to the LB pool. Baremetal CS management / RR
 * hosts never announce (they are not KVM hosts of backends and have no
 * chassis). New workers auto-join on the next invent tick via
 * {@link #ensurePublicIpv4AnnouncesForZone}.
 *
 * <p>The plugin does NOT replace FRR. It only writes
 * {@code router bgp <asn> ; network <ip>/32} into the host's already-running
 * FRR via {@code vtysh}, leaving FRR's own iBGP / EVPN / route-reflector
 * pipeline untouched.
 *
 * <p>Tracking: each successful announce persists a row of
 * {@link Kind#BGP_ANNOUNCE} in {@code ovn_logical_id_map}. The cs_id is the
 * {@code IPAddressVO.id}; the {@code ovn_uuid} column carries one host id or a
 * comma-separated sorted list of host ids (LB multi-chassis).
 */
@Component
public class OvnBgpRedistributeManager {

    private static final Logger LOGGER = LogManager.getLogger(OvnBgpRedistributeManager.class);

    /** Per-zone HA_Chassis_Group name programmed by
     *  {@link OvnPublicNetworkManager#ensureHaChassisGroupForZone(long)}. */
    private static final String HAG_NAME_PREFIX = "hag-public-z";

    /** In-memory cache of the last-announced (publicIp → primary host id) so
     *  tests and light diagnostics can see the last successful path. The
     *  authoritative multi-host set lives in the DAO {@code ovn_uuid} CSV. */
    private final Map<String, Long> lastHostByIp = new ConcurrentHashMap<>();

    @Inject
    private AgentManager agentManager;
    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnChassisMapDao chassisMapDao;
    @Inject
    private OvnPublicNetworkManager publicNetworkManager;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private FirewallRulesDao firewallRulesDao;
    @Inject
    private LoadBalancerDao loadBalancerDao;
    @Inject
    private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private UserPublicIpv6AddressDao userPublicIpv6AddressDao;
    @Inject
    private HostDao hostDao;

    /** Damping for the agent-not-Up skip WARN: one line per host per window. */
    private static final long AGENT_SKIP_LOG_INTERVAL_MS = 300_000L;
    private final Map<Long, Long> lastAgentSkipLogByHost = new ConcurrentHashMap<>();

    /**
     * Announce the supplied public IP as a {@code /32} on the correct chassis
     * set. SNAT / StaticNAT / PortForward pin to the zone gateway-chassis;
     * LB-only IPs anycast on every hypervisor that hosts a Running backend
     * (Option B). No-op when the redistributor is not enabled for the VPC.
     *
     * @param publicIp public IPv4 (no prefix length)
     * @param ipAddrId CloudStack {@code public_ip_address.id} (used as the
     *                 stable cs_id for the {@link Kind#BGP_ANNOUNCE} row)
     * @param vpcId    owning VPC id
     * @param zoneId   zone id (selects the controller / NB client)
     */
    public void announce(final String publicIp, final long ipAddrId, final long vpcId, final long zoneId) {
        if (!isEnabled(vpcId)) {
            return;
        }
        if (StringUtils.isBlank(publicIp)) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.debug("OvnBgpRedistribute.announce: no OVN controller for zone {}", zoneId);
            return;
        }
        final List<Long> desiredHosts = resolveAnnounceHostIds(ipAddrId, zoneId, controller.getId());
        if (desiredHosts.isEmpty()) {
            LOGGER.warn("OvnBgpRedistribute.announce: no announce hosts for zone={} ip={}/32; skipping",
                    zoneId, publicIp);
            return;
        }
        final String gatewayIp = publicNetworkManager.getVpcPublicGatewayIp(zoneId, vpcId);
        if (gatewayIp == null) {
            LOGGER.warn("OvnBgpRedistribute.announce: no public LRP IP for vpc={}; advertise-only "
                    + "(datapath /32 route skipped) for {}/32", vpcId, publicIp);
        }
        final String gatewayMac = publicNetworkManager.getVpcPublicLrpMac(zoneId, vpcId);
        final String anchorCidr = resolveAnchorCidr(zoneId, vpcId);
        final String vlan = resolveVlan(zoneId, vpcId);
        final String networkGatewayIp = resolveNetworkGateway(zoneId, vpcId);

        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_ANNOUNCE, ipAddrId, controller.getId());
        final Set<Long> previousHosts = new LinkedHashSet<>(parseHostIds(existing == null ? null : existing.getOvnUuid()));
        final Set<Long> desiredSet = new LinkedHashSet<>(desiredHosts);

        final List<Long> announced = new ArrayList<>();
        for (final Long hostId : desiredHosts) {
            if (sendCommand(hostId, publicIp, gatewayIp, gatewayMac, anchorCidr, vlan, networkGatewayIp,
                    OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
                announced.add(hostId);
            }
        }
        if (announced.isEmpty()) {
            // Keep the previous announces AND the previous mapping intact:
            // withdrawing the stale hosts before at least one new announce
            // landed would blackhole the VIP entirely (e.g. the new host's
            // agent is Connecting during a fabric incident). Next reconcile
            // tick retries the whole set.
            LOGGER.warn("OvnBgpRedistribute.announce: {}/32 failed on all hosts {} (ip_id={}, vpc={}); "
                    + "previous announce set retained", publicIp, desiredHosts, ipAddrId, vpcId);
            return;
        }
        // Withdraw hosts that no longer host a backend (or left the gateway
        // pin) — only now that the new announce landed somewhere. A stale host
        // whose withdraw fails (agent down) stays in the persisted set so the
        // next announce pass retries the withdraw instead of orphaning it.
        final List<Long> persisted = new ArrayList<>(announced);
        for (final Long stale : previousHosts) {
            if (!desiredSet.contains(stale)
                    && !sendCommand(stale, publicIp, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
                persisted.add(stale);
            }
        }
        persistAnnounceHosts(controller.getId(), ipAddrId, persisted, publicIp);
        lastHostByIp.put(publicIp, announced.get(0));
        LOGGER.info("OvnBgpRedistribute.announce: {}/32 announced on host(s) {} (ip_id={}, vpc={}, lbOnly={})",
                publicIp, announced, ipAddrId, vpcId, isLbOnlyPublicIp(ipAddrId));
    }

    /**
     * Withdraw the supplied public IP from FRR on every host recorded in the
     * bookkeeping row. Removes the row regardless of agent success —
     * best-effort cleanup. Safe to call multiple times.
     *
     * <p><b>Refcount safety</b>: if any remaining SourceNAT / StaticNAT /
     * LoadBalancer / PortForward still uses {@code ipAddrId}, the withdraw is
     * skipped so a sibling rule does not black-hole inbound traffic. The
     * periodic invent-missing pass re-announces if a race still drops the row.
     */
    public void withdraw(final String publicIp, final long ipAddrId, final long vpcId, final long zoneId) {
        if (StringUtils.isBlank(publicIp)) {
            return;
        }
        if (publicIpHasActiveUsers(ipAddrId)) {
            LOGGER.info("OvnBgpRedistribute.withdraw: {}/32 retained (ip_id={} still used by SNAT/StaticNat/LB/PF)",
                    publicIp, ipAddrId);
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.BGP_ANNOUNCE, ipAddrId, controller.getId());
        if (mapping == null) {
            // Either never announced, or already withdrawn.
            lastHostByIp.remove(publicIp);
            return;
        }
        final List<Long> hostIds = parseHostIds(mapping.getOvnUuid());
        final List<Long> failed = new ArrayList<>();
        for (final Long hostId : hostIds) {
            // Withdraw needs no next-hop and no anchor: the wrapper deletes the
            // /32 route by prefix and writes `no network <ip>/32`. The chassis
            // anchor is shared across FIPs and is NOT torn down per withdraw.
            if (!sendCommand(hostId, publicIp, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
                failed.add(hostId);
            }
        }
        if (!failed.isEmpty()) {
            // Do NOT drop the bookkeeping row while a host still holds the
            // /32 (agent down/Connecting): there is no invent-missing pass for
            // withdraws, so the FRR route would be orphaned forever and hijack
            // the prefix if the IP is later reallocated. Keep only the failed
            // hosts; reconcileZone retries them once the IP has no users.
            mapping.setOvnUuid(encodeHostIds(failed));
            logicalIdMapDao.update(mapping.getId(), mapping);
            lastHostByIp.remove(publicIp);
            LOGGER.warn("OvnBgpRedistribute.withdraw: {}/32 withdraw pending on host(s) {} (ip_id={}, vpc={}); "
                    + "row retained for retry", publicIp, failed, ipAddrId, vpcId);
            return;
        }
        try {
            logicalIdMapDao.remove(mapping.getId());
        } finally {
            lastHostByIp.remove(publicIp);
        }
        LOGGER.info("OvnBgpRedistribute.withdraw: {}/32 withdrawn on host(s) {} (ip_id={}, vpc={})",
                publicIp, hostIds, ipAddrId, vpcId);
    }

    /**
     * Invent-missing + self-heal: for every allocated public IPv4 in
     * {@code zoneId} that is still used by SNAT / StaticNat / LB / PF on a VPC
     * with redistribute enabled, ensure a BGP {@code /32} is present in FRR.
     *
     * <p>Always re-sends {@code OP_ANNOUNCE} (idempotent on the agent) even when
     * a {@link Kind#BGP_ANNOUNCE} mapping already exists. Bookkeeping alone is
     * not enough: FRR restarts / Puppet rewrites drop {@code network x/32}
     * while the map row remains, so invent must re-assert FRR. Gateway-chassis
     * migration still uses {@link #reconcileZone}.
     *
     * @return number of IPs for which {@link #announce} was attempted
     */
    public int ensurePublicIpv4AnnouncesForZone(final long zoneId) {
        if (!isPublicRedistributeEnabled()) {
            return 0;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null || ipAddressDao == null) {
            return 0;
        }
        final List<IPAddressVO> ips = ipAddressDao.listByDcId(zoneId);
        if (ips == null || ips.isEmpty()) {
            return 0;
        }
        int attempted = 0;
        int firstTime = 0;
        for (final IPAddressVO ip : ips) {
            if (ip == null || ip.getVpcId() == null) {
                continue;
            }
            if (ip.getState() != IpAddress.State.Allocated) {
                continue;
            }
            final String publicIp = ip.getAddress() == null ? null : ip.getAddress().addr();
            if (StringUtils.isBlank(publicIp)) {
                continue;
            }
            if (!publicIpHasActiveUsers(ip.getId())) {
                continue;
            }
            if (!isEnabled(ip.getVpcId())) {
                continue;
            }
            final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                    Kind.BGP_ANNOUNCE, ip.getId(), controller.getId());
            if (existing == null) {
                firstTime++;
            }
            // Re-assert FRR every tick (same pattern as IPv6 host announces).
            announce(publicIp, ip.getId(), ip.getVpcId(), zoneId);
            attempted++;
        }
        if (attempted > 0) {
            LOGGER.info("OvnBgpRedistribute.ensurePublicIpv4AnnouncesForZone: zone={} reasserted {} /32 "
                            + "announce(s) ({} without prior mapping)",
                    zoneId, attempted, firstTime);
        }
        return attempted;
    }

    /**
     * True when the public IP still needs a BGP /32: SourceNAT flag, StaticNAT
     * (one-to-one) flag, or any non-revoked LoadBalancing / PortForwarding /
     * StaticNat firewall purpose rule.
     *
     * <p>Package-visible for unit tests.
     */
    boolean publicIpHasActiveUsers(final long ipAddrId) {
        if (ipAddressDao != null) {
            final IPAddressVO ip = ipAddressDao.findById(ipAddrId);
            if (ip != null) {
                if (ip.isSourceNat() || ip.isOneToOneNat()) {
                    return true;
                }
            }
        }
        if (firewallRulesDao == null) {
            return false;
        }
        if (hasNonRevokedPurpose(ipAddrId, FirewallRule.Purpose.LoadBalancing)) {
            return true;
        }
        if (hasNonRevokedPurpose(ipAddrId, FirewallRule.Purpose.PortForwarding)) {
            return true;
        }
        return hasNonRevokedPurpose(ipAddrId, FirewallRule.Purpose.StaticNat);
    }

    private boolean hasNonRevokedPurpose(final long ipAddrId, final FirewallRule.Purpose purpose) {
        final List<FirewallRuleVO> rules =
                firewallRulesDao.listByIpAndPurposeAndNotRevoked(ipAddrId, purpose);
        return rules != null && !rules.isEmpty();
    }

    /**
     * Announce a ROUTED-tier subnet (tier CIDR, e.g. {@code 10.90.6.0/24}) as a
     * BGP {@code network <cidr>} on the gateway-chassis FRR, and install the
     * matching kernel route via the VPC public LRP so fabric return traffic
     * enters OVN and zebra originates the prefix. No-op when the routed-tier
     * toggle is off or no gateway-chassis exists yet.
     *
     * @param cidr      tier CIDR with prefix (e.g. {@code 10.90.6.0/24})
     * @param networkId CloudStack tier {@code NetworkVO.id} (cs_id for the
     *                  {@link Kind#BGP_SUBNET_ANNOUNCE} row)
     * @param vpcId     owning VPC id
     * @param zoneId    zone id (selects controller / gateway-chassis)
     */
    public void announceSubnet(final String cidr, final long networkId, final long vpcId, final long zoneId) {
        if (!isRoutedAnnounceEnabled()) {
            return;
        }
        if (StringUtils.isBlank(cidr) || !cidr.contains("/")) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.debug("OvnBgpRedistribute.announceSubnet: no OVN controller for zone {}", zoneId);
            return;
        }
        final Long hostId = findGatewayChassisHostId(zoneId, controller.getId());
        if (hostId == null) {
            LOGGER.warn("OvnBgpRedistribute.announceSubnet: no gateway-chassis for zone={}; skipping {}",
                    zoneId, cidr);
            return;
        }
        // Datapath next-hop + anchor are identical to the /32 FIP path: the tier
        // return traffic enters OVN via the VPC public LRP, and installing the
        // kernel route seeds zebra's RIB so `network <cidr>` truly originates
        // (advertise-only would be inert). null gatewayIp => advertise-only.
        final String gatewayIp = publicNetworkManager.getVpcPublicGatewayIp(zoneId, vpcId);
        final String gatewayMac = publicNetworkManager.getVpcPublicLrpMac(zoneId, vpcId);
        final String anchorCidr = resolveAnchorCidr(zoneId, vpcId);
        final String vlan = resolveVlan(zoneId, vpcId);
        final String networkGatewayIp = resolveNetworkGateway(zoneId, vpcId);
        if (sendSubnetCommand(hostId, cidr, gatewayIp, gatewayMac, anchorCidr, vlan, networkGatewayIp,
                OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
            persistSubnetAnnounce(controller.getId(), networkId, hostId, cidr);
            lastHostByIp.put(cidr, hostId);
            LOGGER.info("OvnBgpRedistribute.announceSubnet: {} announced on host {} (net={}, vpc={})",
                    cidr, hostId, networkId, vpcId);
        }
    }

    /**
     * Withdraw a previously-announced ROUTED-tier subnet. Best-effort and
     * idempotent: safe to call when never announced or already withdrawn.
     */
    public void withdrawSubnet(final String cidr, final long networkId, final long vpcId, final long zoneId) {
        if (StringUtils.isBlank(cidr)) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping =
                logicalIdMapDao.findByCsId(Kind.BGP_SUBNET_ANNOUNCE, networkId, controller.getId());
        if (mapping == null) {
            lastHostByIp.remove(cidr);
            return;
        }
        final Long hostId = parseHostId(mapping.getOvnUuid());
        if (hostId != null
                && !sendSubnetCommand(hostId, cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
            // Host still holds the announce (agent down) — keep the row so a
            // later withdrawSubnet call / operator pass can retry instead of
            // orphaning the FRR route.
            lastHostByIp.remove(cidr);
            LOGGER.warn("OvnBgpRedistribute.withdrawSubnet: {} withdraw pending on host {} (net={}, vpc={}); "
                    + "row retained for retry", cidr, hostId, networkId, vpcId);
            return;
        }
        try {
            logicalIdMapDao.remove(mapping.getId());
        } finally {
            lastHostByIp.remove(cidr);
        }
        LOGGER.info("OvnBgpRedistribute.withdrawSubnet: {} withdrawn on host {} (net={}, vpc={})",
                cidr, hostId, networkId, vpcId);
    }

    /**
     * PARSEL-V6 — announce a ROUTED-tier IPv6 subnet ({@code getIp6Cidr}, e.g.
     * {@code 2a13:8740:0:a::/64}) into the fabric's IPv6 unicast address-family
     * on the gateway-chassis FRR, and install the matching v6 kernel route via
     * the VPC public LRP's v6 GUA so fabric return traffic enters OVN. Native
     * routing only — NO v6 NAT. No-op when the v6 tier toggle is off, the CIDR
     * is not a v6 prefix, or no gateway-chassis exists yet. Independent of the
     * tier's v4 network mode (fires for NAT-mode CKS tiers too — their v6 is
     * natively routed).
     *
     * @param ip6Cidr   tier IPv6 CIDR with prefix (e.g. {@code 2a13:8740:0:a::/64})
     * @param networkId CloudStack tier {@code NetworkVO.id} (cs_id for the
     *                  {@link Kind#BGP_SUBNET_ANNOUNCE_V6} row)
     * @param vpcId     owning VPC id
     * @param zoneId    zone id (selects controller / gateway-chassis)
     */
    public void announceSubnet6(final String ip6Cidr, final long networkId, final long vpcId, final long zoneId) {
        if (!isRoutedAnnounceIpv6Enabled()) {
            return;
        }
        if (StringUtils.isBlank(ip6Cidr) || !ip6Cidr.contains("/") || !ip6Cidr.contains(":")) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.debug("OvnBgpRedistribute.announceSubnet6: no OVN controller for zone {}", zoneId);
            return;
        }
        final Long hostId = findGatewayChassisHostId(zoneId, controller.getId());
        if (hostId == null) {
            LOGGER.warn("OvnBgpRedistribute.announceSubnet6: no gateway-chassis for zone={}; skipping {}",
                    zoneId, ip6Cidr);
            return;
        }
        // v6 datapath next-hop = the VPC public LRP's v6 GUA (its MAC answers NDP
        // on the localnet); the chassis anchor holds the v6 fabric gateway on
        // pub-anchor so both the tier kernel route and the ::/0 return resolve.
        // null gua => advertise-only (v6 public foot not applied yet). vlan=null:
        // the routed public v6 /64 is untagged, exactly like the v4 217.179.89.0/24.
        final String v6GatewayIp = publicNetworkManager.getVpcPublicIpv6GatewayIp(zoneId, vpcId);
        // Same LRP MAC as the v4 public port — answers NDP for the v6 GUA on localnet.
        final String gatewayMac = publicNetworkManager.getVpcPublicLrpMac(zoneId, vpcId);
        final String v6AnchorCidr = publicNetworkManager.getPublicIpv6AnchorCidr();
        final String v6NetworkGateway = publicNetworkManager.getPublicIpv6Gateway();
        if (sendSubnetCommand(hostId, ip6Cidr, v6GatewayIp, gatewayMac, v6AnchorCidr, null, v6NetworkGateway,
                OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
            persistSubnetAnnounceV6(controller.getId(), networkId, hostId, ip6Cidr);
            lastHostByIp.put(ip6Cidr, hostId);
            LOGGER.info("OvnBgpRedistribute.announceSubnet6: {} announced on host {} (net={}, vpc={})",
                    ip6Cidr, hostId, networkId, vpcId);
        }
    }

    /**
     * Announce a public IPv6 LB VIP as a BGP {@code /128} host route (IPv6
     * unicast AF) with a matching kernel route via the VPC public LRP GUA.
     * Option B: anycast on every hypervisor hosting a Running IPv6 LB backend
     * for this VIP; falls back to the zone gateway-chassis when backends are
     * unknown. VIPs sit outside {@code user_ip_address}, so bookkeeping uses
     * {@link Kind#BGP_HOST_ANNOUNCE_V6} with a stable positive hash of the VIP.
     *
     * @param vip   bare IPv6 VIP (no prefix length)
     * @param vpcId owning VPC id (public LRP GUA / MAC resolution)
     * @param zoneId zone id (controller / chassis set)
     */
    public void announceHost6(final String vip, final long vpcId, final long zoneId) {
        if (StringUtils.isBlank(vip) || !vip.contains(":") || !NetUtils.isValidIp6(vip)) {
            return;
        }
        final String canonicalVip = canonicalizeHost6Vip(vip);
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.debug("OvnBgpRedistribute.announceHost6: no OVN controller for zone {}", zoneId);
            return;
        }
        final long csId = host6CsId(canonicalVip);
        final List<Long> desiredHosts = resolveHost6AnnounceHostIds(canonicalVip, zoneId, controller.getId());
        if (desiredHosts.isEmpty()) {
            LOGGER.warn("OvnBgpRedistribute.announceHost6: no announce hosts for zone={} vip={}/128; skipping",
                    zoneId, canonicalVip);
            return;
        }
        final String cidr = canonicalVip + "/128";
        final String v6GatewayIp = publicNetworkManager.getVpcPublicIpv6GatewayIp(zoneId, vpcId);
        final String gatewayMac = publicNetworkManager.getVpcPublicLrpMac(zoneId, vpcId);
        final String v6AnchorCidr = publicNetworkManager.getPublicIpv6AnchorCidr();
        final String v6NetworkGateway = publicNetworkManager.getPublicIpv6Gateway();

        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_HOST_ANNOUNCE_V6, csId, controller.getId());
        final Set<Long> previousHosts = new LinkedHashSet<>(parseHostIds(existing == null ? null : existing.getOvnUuid()));
        final Set<Long> desiredSet = new LinkedHashSet<>(desiredHosts);

        final List<Long> announced = new ArrayList<>();
        for (final Long hostId : desiredHosts) {
            if (sendSubnetCommand(hostId, cidr, v6GatewayIp, gatewayMac, v6AnchorCidr, null, v6NetworkGateway,
                    OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
                announced.add(hostId);
            }
        }
        if (announced.isEmpty()) {
            // Same ordering rule as announce(): never withdraw the previous
            // set before a new announce landed — that blackholes the VIP.
            LOGGER.warn("OvnBgpRedistribute.announceHost6: {}/128 failed on all hosts {} (vpc={}); "
                    + "previous announce set retained", canonicalVip, desiredHosts, vpcId);
            return;
        }
        final List<Long> persisted = new ArrayList<>(announced);
        for (final Long stale : previousHosts) {
            if (!desiredSet.contains(stale)
                    && !sendSubnetCommand(stale, cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
                persisted.add(stale);
            }
        }
        persistHostAnnounceV6Hosts(controller.getId(), csId, persisted, canonicalVip);
        lastHostByIp.put(cidr, announced.get(0));
        LOGGER.info("OvnBgpRedistribute.announceHost6: {}/128 announced on host(s) {} (vpc={})",
                canonicalVip, announced, vpcId);
    }

    /**
     * Withdraw a previously-announced public IPv6 LB VIP {@code /128} from
     * every host recorded in bookkeeping. Best-effort and idempotent.
     */
    public void withdrawHost6(final String vip, final long vpcId, final long zoneId) {
        if (StringUtils.isBlank(vip)) {
            return;
        }
        final String canonicalVip = canonicalizeHost6Vip(vip);
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final long csId = host6CsId(canonicalVip);
        final OvnLogicalIdMapVO mapping =
                logicalIdMapDao.findByCsId(Kind.BGP_HOST_ANNOUNCE_V6, csId, controller.getId());
        final String cidr = canonicalVip + "/128";
        if (mapping == null) {
            lastHostByIp.remove(cidr);
            return;
        }
        final List<Long> hostIds = parseHostIds(mapping.getOvnUuid());
        final List<Long> failed = new ArrayList<>();
        for (final Long hostId : hostIds) {
            if (!sendSubnetCommand(hostId, cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
                failed.add(hostId);
            }
        }
        if (!failed.isEmpty()) {
            mapping.setOvnUuid(encodeHostIds(failed));
            logicalIdMapDao.update(mapping.getId(), mapping);
            lastHostByIp.remove(cidr);
            LOGGER.warn("OvnBgpRedistribute.withdrawHost6: {}/128 withdraw pending on host(s) {} (vpc={}); "
                    + "row retained for retry", canonicalVip, failed, vpcId);
            return;
        }
        try {
            logicalIdMapDao.remove(mapping.getId());
        } finally {
            lastHostByIp.remove(cidr);
        }
        LOGGER.info("OvnBgpRedistribute.withdrawHost6: {}/128 withdrawn on host(s) {} (vpc={})",
                canonicalVip, hostIds, vpcId);
    }

    /**
     * Stable positive cs_id for a public IPv6 VIP under
     * {@link Kind#BGP_HOST_ANNOUNCE_V6}.
     *
     * <p>Canonicalizes the VIP via {@link NetUtils#standardizeIp6Address} so
     * compressed/expanded forms of the same address share one id, then mixes
     * the 16 address bytes (not {@link String#hashCode}) and clears the sign
     * bit so the result is always in {@code [1, Integer.MAX_VALUE]}.
     *
     * <p><b>Must never be negative</b>: {@code ovn_logical_id_map.cs_id} is
     * {@code bigint unsigned}; a negative value is rejected with
     * {@code MysqlDataTruncation: Out of range value for column 'cs_id'}
     * (live fail: {@code BGP_HOST_ANNOUNCE_V6 cs_id=-357727063}). Kind already
     * isolates this namespace from {@link Kind#BGP_ANNOUNCE}
     * ({@code public_ip_address.id}), so positivity alone is enough.
     */
    static long host6CsId(final String vip) {
        final String canonical = canonicalizeHost6Vip(vip);
        final byte[] addrBytes = host6AddressBytes(canonical);
        // Arrays.hashCode is a stable 32-bit mix of the address bytes. Mask to
        // clear the sign bit so the id always fits bigint unsigned / signed
        // positive Java long. Zero is reserved (ambiguous / empty sentinel).
        final long id = Arrays.hashCode(addrBytes) & 0x7fff_ffffL;
        return id == 0L ? 1L : id;
    }

    /** Prefer standardized IPv6 form; fall back to the raw string. */
    static String canonicalizeHost6Vip(final String vip) {
        if (StringUtils.isBlank(vip)) {
            return vip;
        }
        if (!NetUtils.isValidIp6(vip)) {
            return vip;
        }
        try {
            return NetUtils.standardizeIp6Address(vip);
        } catch (RuntimeException re) {
            return vip;
        }
    }

    private static byte[] host6AddressBytes(final String vip) {
        if (StringUtils.isBlank(vip)) {
            return new byte[0];
        }
        try {
            return InetAddress.getByName(vip).getAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return vip.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void persistHostAnnounceV6Hosts(final long controllerId, final long csId,
                                            final List<Long> hostIds, final String vip) {
        final String encoded = encodeHostIds(hostIds);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_HOST_ANNOUNCE_V6, csId, controllerId);
        if (existing != null) {
            existing.setOvnUuid(encoded);
            existing.setOvnName(vip);
            logicalIdMapDao.update(existing.getId(), existing);
            return;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(
                Kind.BGP_HOST_ANNOUNCE_V6, csId, controllerId, encoded, vip));
    }

    /**
     * PARSEL-V6 — withdraw a previously-announced ROUTED-tier IPv6 subnet.
     * Best-effort and idempotent: safe when never announced or already withdrawn.
     */
    public void withdrawSubnet6(final String ip6Cidr, final long networkId, final long vpcId, final long zoneId) {
        if (StringUtils.isBlank(ip6Cidr)) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping =
                logicalIdMapDao.findByCsId(Kind.BGP_SUBNET_ANNOUNCE_V6, networkId, controller.getId());
        if (mapping == null) {
            lastHostByIp.remove(ip6Cidr);
            return;
        }
        final Long hostId = parseHostId(mapping.getOvnUuid());
        if (hostId != null
                && !sendSubnetCommand(hostId, ip6Cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
            lastHostByIp.remove(ip6Cidr);
            LOGGER.warn("OvnBgpRedistribute.withdrawSubnet6: {} withdraw pending on host {} (net={}, vpc={}); "
                    + "row retained for retry", ip6Cidr, hostId, networkId, vpcId);
            return;
        }
        try {
            logicalIdMapDao.remove(mapping.getId());
        } finally {
            lastHostByIp.remove(ip6Cidr);
        }
        LOGGER.info("OvnBgpRedistribute.withdrawSubnet6: {} withdrawn on host {} (net={}, vpc={})",
                ip6Cidr, hostId, networkId, vpcId);
    }

    /** PARSEL-V6 toggle — {@code ovn.bgp.redistribute.tier.ipv6}. Parsed
     *  defensively for the same String-default-vs-Boolean reason as
     *  {@link #isRoutedAnnounceEnabled()}. Package-visible so unit tests can spy
     *  the gate without wiring the static {@code ConfigDepot}. */
    boolean isRoutedAnnounceIpv6Enabled() {
        return Boolean.parseBoolean(String.valueOf(OvnNetworkConfig.BgpRedistributeTierIpv6.value()));
    }

    /** Upsert the {@link Kind#BGP_SUBNET_ANNOUNCE_V6} bookkeeping row (cs_id = tier network id). */
    private void persistSubnetAnnounceV6(final long controllerId, final long networkId, final long hostId,
                                         final String cidr) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_SUBNET_ANNOUNCE_V6, networkId, controllerId);
        if (existing != null) {
            existing.setOvnUuid(String.valueOf(hostId));
            existing.setOvnName(cidr);
            logicalIdMapDao.update(existing.getId(), existing);
            return;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(
                Kind.BGP_SUBNET_ANNOUNCE_V6, networkId, controllerId, String.valueOf(hostId), cidr));
    }

    /**
     * Global public-IP /32 toggle. Package-visible so unit tests can spy the
     * gate without wiring the static {@code ConfigDepot}.
     */
    boolean isPublicRedistributeEnabled() {
        return Boolean.TRUE.equals(OvnNetworkConfig.BgpRedistributePublicIps.value());
    }

    /**
     * Reconcile persisted {@link Kind#BGP_ANNOUNCE} rows against live chassis
     * membership. LB-only IPs re-run {@link #announce} (Option B multi-host
     * set). SNAT/StaticNAT/PF rows pin-migrate with the gateway-chassis.
     */
    public void reconcileZone(final long zoneId) {
        if (!isPublicRedistributeEnabled()) {
            // Global toggle off — even VPC-level overrides only matter if
            // the operator explicitly turns the global on. This avoids
            // surprise announces when the global default is left at false.
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final Long currentGw = findGatewayChassisHostId(zoneId, controller.getId());
        if (currentGw == null) {
            LOGGER.debug("OvnBgpRedistribute.reconcileZone: no gateway-chassis (zone={})", zoneId);
            return;
        }
        final List<OvnLogicalIdMapVO> rows = logicalIdMapDao.listByKind(Kind.BGP_ANNOUNCE, controller.getId());
        for (final OvnLogicalIdMapVO row : rows) {
            final String publicIp = row.getOvnName();
            if (publicIp == null || publicIp.isEmpty()) {
                continue;
            }
            final long ipAddrId = row.getCsId();
            // A row whose IP no longer has active users is a deferred
            // withdraw (kept because a host agent was down at release time).
            // Retry the withdraw instead of re-announcing a released IP; drop
            // the row only when every recorded host acked.
            if (!publicIpHasActiveUsers(ipAddrId)) {
                if (withdrawStaleAnnounceRow(row)) {
                    logicalIdMapDao.remove(row.getId());
                    lastHostByIp.remove(publicIp);
                    LOGGER.info("OvnBgpRedistribute.reconcileZone: {}/32 deferred withdraw completed; "
                            + "row dropped (ip_id={})", publicIp, ipAddrId);
                }
                continue;
            }
            // Option B: LB-only membership is derived from backends every tick.
            if (isLbOnlyPublicIp(ipAddrId)) {
                final IpAddress ip = ipAddressDao == null ? null : ipAddressDao.findById(ipAddrId);
                if (ip != null && ip.getVpcId() != null) {
                    announce(publicIp, ipAddrId, ip.getVpcId(), zoneId);
                }
                continue;
            }
            final List<Long> lastHosts = parseHostIds(row.getOvnUuid());
            if (lastHosts.size() == 1 && currentGw.equals(lastHosts.get(0))) {
                continue;
            }
            // Gateway-pinned VIP: announce on current GW first, then withdraw stale.
            final String gatewayIp = resolveGatewayIpForIpAddr(zoneId, ipAddrId);
            final String gatewayMac = resolveGatewayMacForIpAddr(zoneId, ipAddrId);
            final String anchorCidr = resolveAnchorForIpAddr(zoneId, ipAddrId);
            final String vlan = resolveVlanForIpAddr(zoneId, ipAddrId);
            final String networkGatewayIp = resolveNetworkGatewayForIpAddr(zoneId, ipAddrId);
            if (sendCommand(currentGw, publicIp, gatewayIp, gatewayMac, anchorCidr, vlan, networkGatewayIp,
                    OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
                for (final Long lastHost : lastHosts) {
                    if (!currentGw.equals(lastHost)) {
                        sendCommand(lastHost, publicIp, null, null, null, null, null,
                                OvnBgpAnnounceCommand.OP_WITHDRAW);
                    }
                }
                row.setOvnUuid(String.valueOf(currentGw));
                logicalIdMapDao.update(row.getId(), row);
                lastHostByIp.put(publicIp, currentGw);
                LOGGER.info("OvnBgpRedistribute.reconcileZone: {}/32 migrated to gateway host {} from {} (zone={})",
                        publicIp, currentGw, lastHosts, zoneId);
            }
        }
    }

    /* ---------- internal helpers ---------- */

    /**
     * Best-effort FRR cleanup for a stale/deferred {@link Kind#BGP_ANNOUNCE}
     * row: withdraw the recorded /32 on every host in the row's host list.
     * Returns {@code true} only when every send was acked — callers must keep
     * the row (for a later retry) otherwise. Package-visible so
     * {@code OvnReconcilerService}'s stale-row sweep can clean FRR before
     * dropping bookkeeping.
     */
    boolean withdrawStaleAnnounceRow(final OvnLogicalIdMapVO row) {
        final String publicIp = row.getOvnName();
        if (StringUtils.isBlank(publicIp)) {
            return true;
        }
        boolean allAcked = true;
        for (final Long hostId : parseHostIds(row.getOvnUuid())) {
            if (!sendCommand(hostId, publicIp, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW)) {
                allAcked = false;
            }
        }
        return allAcked;
    }

    private boolean isEnabled(final long vpcId) {
        return publicNetworkManager != null && publicNetworkManager.isBgpRedistributeEnabled(vpcId);
    }

    /**
     * Resolve the VPC public LRP IP for a persisted BGP_ANNOUNCE row whose
     * cs_id is the {@code public_ip_address.id}. Returns {@code null} (→
     * advertise-only) when the IP row / VPC / public binding is missing.
     */
    private String resolveGatewayIpForIpAddr(final long zoneId, final long ipAddrId) {
        final IpAddress ip = ipAddressDao.findById(ipAddrId);
        if (ip == null || ip.getVpcId() == null) {
            return null;
        }
        return publicNetworkManager.getVpcPublicGatewayIp(zoneId, ip.getVpcId());
    }

    private String resolveGatewayMacForIpAddr(final long zoneId, final long ipAddrId) {
        final IpAddress ip = ipAddressDao.findById(ipAddrId);
        if (ip == null || ip.getVpcId() == null) {
            return null;
        }
        return publicNetworkManager.getVpcPublicLrpMac(zoneId, ip.getVpcId());
    }

    /**
     * Resolve the DERIVED datapath anchor CIDR for a VPC's public segment,
     * gated by the global {@link OvnNetworkConfig#BgpPublicAnchorEnabled}
     * toggle. Returns {@code null} (→ wrapper skips the anchor, advertise-/
     * route-only) when the anchor feature is off, or the public segment / pool
     * cannot be resolved. The address itself is never configured here — see
     * {@link OvnPublicNetworkManager#getVpcPublicAnchorCidr}.
     */
    private String resolveAnchorCidr(final long zoneId, final long vpcId) {
        if (!Boolean.TRUE.equals(OvnNetworkConfig.BgpPublicAnchorEnabled.value())) {
            return null;
        }
        return publicNetworkManager.getVpcPublicAnchorCidr(zoneId, vpcId);
    }

    /** Anchor CIDR for a persisted BGP_ANNOUNCE row (cs_id = {@code public_ip_address.id}). */
    private String resolveAnchorForIpAddr(final long zoneId, final long ipAddrId) {
        final IpAddress ip = ipAddressDao.findById(ipAddrId);
        if (ip == null || ip.getVpcId() == null) {
            return null;
        }
        return resolveAnchorCidr(zoneId, ip.getVpcId());
    }

    /** Public-segment VLAN id for the VPC (gated by the anchor toggle); {@code null}
     *  when disabled or the segment is untagged (anchor stays untagged). */
    private String resolveVlan(final long zoneId, final long vpcId) {
        if (!Boolean.TRUE.equals(OvnNetworkConfig.BgpPublicAnchorEnabled.value())) {
            return null;
        }
        return publicNetworkManager.getVpcPublicVlanTag(zoneId, vpcId);
    }

    private String resolveVlanForIpAddr(final long zoneId, final long ipAddrId) {
        final IpAddress ip = ipAddressDao.findById(ipAddrId);
        if (ip == null || ip.getVpcId() == null) {
            return null;
        }
        return resolveVlan(zoneId, ip.getVpcId());
    }

    /** Public network gateway IP (the VPC LR's egress next-hop) for the VPC;
     *  gated + null-safe like the anchor. The gateway chassis holds it so VM
     *  egress lands on the host and is forwarded upstream. */
    private String resolveNetworkGateway(final long zoneId, final long vpcId) {
        if (!Boolean.TRUE.equals(OvnNetworkConfig.BgpPublicAnchorEnabled.value())) {
            return null;
        }
        return publicNetworkManager.getVpcPublicNetworkGateway(zoneId, vpcId);
    }

    private String resolveNetworkGatewayForIpAddr(final long zoneId, final long ipAddrId) {
        final IpAddress ip = ipAddressDao.findById(ipAddrId);
        if (ip == null || ip.getVpcId() == null) {
            return null;
        }
        return resolveNetworkGateway(zoneId, ip.getVpcId());
    }

    /**
     * Skip agent sends while the host agent is not {@code Up} (e.g. stuck in
     * {@code Connecting} after a fabric incident): {@code easySend} would only
     * time out per prefix and spam a WARN per attempt, while the reconcile
     * loop retries everything on the next tick anyway. The skip WARN is
     * damped to one line per host per {@link #AGENT_SKIP_LOG_INTERVAL_MS}.
     */
    private boolean agentUp(final long hostId) {
        final HostVO host = hostDao.findById(hostId);
        if (host != null && host.getStatus() == Status.Up) {
            return true;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastAgentSkipLogByHost.get(hostId);
        if (last == null || now - last >= AGENT_SKIP_LOG_INTERVAL_MS) {
            lastAgentSkipLogByHost.put(hostId, now);
            LOGGER.warn("OvnBgpRedistribute: host={} agent not Up (status={}); deferring BGP ops to next reconcile",
                    hostId, host == null ? "unknown" : host.getStatus());
        }
        return false;
    }

    private boolean sendCommand(final long hostId, final String publicIp, final String gatewayIp,
                                final String gatewayMac, final String anchorCidr, final String vlan,
                                final String networkGatewayIp, final String operation) {
        if (!agentUp(hostId)) {
            return false;
        }
        final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                publicIp,
                operation,
                OvnNetworkConfig.BgpFrrVtyshPath.value(),
                OvnNetworkConfig.BgpFrrAsn.value(),
                gatewayIp,
                anchorCidr,
                vlan,
                networkGatewayIp);
        cmd.setGatewayMac(gatewayMac);
        try {
            final Answer answer = agentManager.easySend(hostId, cmd);
            if (answer == null) {
                LOGGER.warn("OvnBgpRedistribute: {} {}/32 host={} no answer (agent offline or wrapper missing)",
                        operation, publicIp, hostId);
                return false;
            }
            if (!answer.getResult()) {
                LOGGER.warn("OvnBgpRedistribute: {} {}/32 host={} failed: {}",
                        operation, publicIp, hostId, answer.getDetails());
                return false;
            }
            return true;
        } catch (RuntimeException re) {
            LOGGER.warn("OvnBgpRedistribute: {} {}/32 host={} threw: {}",
                    operation, publicIp, hostId, re.getMessage());
            return false;
        }
    }

    /**
     * Resolve the CloudStack host id currently hosting the public-side
     * gateway LRP for the zone. Walks: per-zone HA_Chassis_Group (NB) ->
     * top-priority chassis_name -> {@link OvnChassisMapDao} -> hostId.
     */
    Long findGatewayChassisHostId(final long zoneId, final long controllerId) {
        final OvnLogicalIdMapVO hagMapping = logicalIdMapDao.findByCsId(
                Kind.HA_CHASSIS_GROUP, zoneId, controllerId);
        if (hagMapping == null) {
            return null;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final String chassisName = nb.findTopPriorityChassisName(hagMapping.getOvnUuid());
        if (chassisName == null || chassisName.isEmpty()) {
            return null;
        }
        final OvnChassisMapVO chassis = chassisMapDao.findByChassisUuid(chassisName);
        return chassis == null ? null : chassis.getHostId();
    }

    private boolean isRoutedAnnounceEnabled() {
        // ConfigKey.value() can return the raw String default ("true") rather than
        // a parsed Boolean when the key was hot-added (jar-uf patch) and ConfigDepot
        // has no persisted/parsed entry yet, so Boolean.TRUE.equals(value()) compares
        // a Boolean to a String and is always false. Parse defensively — correct for
        // both the String-default and the properly-registered Boolean cases.
        return Boolean.parseBoolean(String.valueOf(OvnNetworkConfig.BgpRedistributeRoutedTiers.value()));
    }

    /**
     * Send an announce/withdraw for a full CIDR. Splits {@code cidr} into the
     * network address (carried in the command's publicIp slot) and the prefix
     * length (carried in the new prefixLength field). Mirrors {@link
     * #sendCommand} but logs the CIDR verbatim instead of {@code /32}.
     */
    private boolean sendSubnetCommand(final long hostId, final String cidr, final String gatewayIp,
                                      final String gatewayMac, final String anchorCidr, final String vlan,
                                      final String networkGatewayIp, final String operation) {
        final int slash = cidr.indexOf('/');
        final String netAddr = cidr.substring(0, slash);
        final int prefixLen;
        try {
            prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException nfe) {
            LOGGER.warn("OvnBgpRedistribute: bad CIDR {} (host={}); skipping {}", cidr, hostId, operation);
            return false;
        }
        if (!agentUp(hostId)) {
            return false;
        }
        final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                netAddr,
                operation,
                OvnNetworkConfig.BgpFrrVtyshPath.value(),
                OvnNetworkConfig.BgpFrrAsn.value(),
                gatewayIp,
                anchorCidr,
                vlan,
                networkGatewayIp);
        cmd.setPrefixLength(prefixLen);
        cmd.setGatewayMac(gatewayMac);
        try {
            final Answer answer = agentManager.easySend(hostId, cmd);
            if (answer == null) {
                LOGGER.warn("OvnBgpRedistribute: {} {} host={} no answer (agent offline or wrapper missing)",
                        operation, cidr, hostId);
                return false;
            }
            if (!answer.getResult()) {
                LOGGER.warn("OvnBgpRedistribute: {} {} host={} failed: {}",
                        operation, cidr, hostId, answer.getDetails());
                return false;
            }
            return true;
        } catch (RuntimeException re) {
            LOGGER.warn("OvnBgpRedistribute: {} {} host={} threw: {}", operation, cidr, hostId, re.getMessage());
            return false;
        }
    }

    /** Upsert the {@link Kind#BGP_SUBNET_ANNOUNCE} bookkeeping row (cs_id = tier network id). */
    private void persistSubnetAnnounce(final long controllerId, final long networkId, final long hostId,
                                       final String cidr) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_SUBNET_ANNOUNCE, networkId, controllerId);
        if (existing != null) {
            existing.setOvnUuid(String.valueOf(hostId));
            existing.setOvnName(cidr);
            logicalIdMapDao.update(existing.getId(), existing);
            return;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(
                Kind.BGP_SUBNET_ANNOUNCE, networkId, controllerId, String.valueOf(hostId), cidr));
    }

    private void persistAnnounceHosts(final long controllerId, final long ipAddrId,
                                      final List<Long> hostIds, final String publicIp) {
        final String encoded = encodeHostIds(hostIds);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_ANNOUNCE, ipAddrId, controllerId);
        if (existing != null) {
            existing.setOvnUuid(encoded);
            existing.setOvnName(publicIp);
            logicalIdMapDao.update(existing.getId(), existing);
            return;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(
                Kind.BGP_ANNOUNCE, ipAddrId, controllerId, encoded, publicIp));
    }

    /**
     * Host set for a public IPv4 announce: LB-only → unique Running backend
     * hypervisors; otherwise the zone gateway-chassis (singleton).
     */
    List<Long> resolveAnnounceHostIds(final long ipAddrId, final long zoneId, final long controllerId) {
        if (isLbOnlyPublicIp(ipAddrId)) {
            final List<Long> backends = resolveLbBackendHostIds(ipAddrId);
            if (!backends.isEmpty()) {
                return backends;
            }
            LOGGER.info("OvnBgpRedistribute: LB-only ip_id={} has no Running backends; falling back to gateway",
                    ipAddrId);
        }
        final Long gw = findGatewayChassisHostId(zoneId, controllerId);
        return gw == null ? Collections.emptyList() : List.of(gw);
    }

    /**
     * True when the public IP is used by LoadBalancing and not by SourceNAT,
     * 1:1 StaticNAT, PortForward, or StaticNat firewall purpose — Option B gate.
     */
    boolean isLbOnlyPublicIp(final long ipAddrId) {
        if (ipAddressDao != null) {
            final IPAddressVO ip = ipAddressDao.findById(ipAddrId);
            if (ip != null && (ip.isSourceNat() || ip.isOneToOneNat())) {
                return false;
            }
        }
        if (!hasNonRevokedPurpose(ipAddrId, FirewallRule.Purpose.LoadBalancing)) {
            return false;
        }
        if (hasNonRevokedPurpose(ipAddrId, FirewallRule.Purpose.PortForwarding)) {
            return false;
        }
        return !hasNonRevokedPurpose(ipAddrId, FirewallRule.Purpose.StaticNat);
    }

    /**
     * Unique CloudStack host ids of Running (non-Migrating) VMs assigned to
     * any non-revoked LoadBalancing rule on {@code ipAddrId}. Sorted for stable
     * bookkeeping. Package-visible for unit tests.
     */
    List<Long> resolveLbBackendHostIds(final long ipAddrId) {
        final Set<Long> hosts = new LinkedHashSet<>();
        if (loadBalancerDao == null || loadBalancerVMMapDao == null || vmInstanceDao == null) {
            return Collections.emptyList();
        }
        final List<LoadBalancerVO> lbs = loadBalancerDao.listByIpAddress(ipAddrId);
        if (lbs == null || lbs.isEmpty()) {
            // Fallback: firewall rule id == load balancer id in classic CS schema.
            final List<FirewallRuleVO> rules = firewallRulesDao == null ? null
                    : firewallRulesDao.listByIpAndPurposeAndNotRevoked(ipAddrId, FirewallRule.Purpose.LoadBalancing);
            if (rules != null) {
                for (final FirewallRuleVO rule : rules) {
                    if (rule != null) {
                        addBackendHostsForLb(rule.getId(), hosts);
                    }
                }
            }
            return sortedHostList(hosts);
        }
        for (final LoadBalancerVO lb : lbs) {
            if (lb == null) {
                continue;
            }
            final FirewallRule.State st = lb.getState();
            if (st != null && st != FirewallRule.State.Active && st != FirewallRule.State.Add) {
                continue;
            }
            addBackendHostsForLb(lb.getId(), hosts);
        }
        return sortedHostList(hosts);
    }

    private void addBackendHostsForLb(final long loadBalancerId, final Set<Long> hosts) {
        final List<LoadBalancerVMMapVO> maps =
                loadBalancerVMMapDao.listByLoadBalancerId(loadBalancerId, false);
        if (maps == null) {
            return;
        }
        for (final LoadBalancerVMMapVO map : maps) {
            if (map == null || map.isRevoke()) {
                continue;
            }
            final VMInstanceVO vm = vmInstanceDao.findById(map.getInstanceId());
            if (vm == null || vm.getHostId() == null) {
                continue;
            }
            final VirtualMachine.State state = vm.getState();
            // Skip Migrating: avoid flapping /32 between source and dest mid-move.
            if (state != VirtualMachine.State.Running) {
                continue;
            }
            hosts.add(vm.getHostId());
        }
    }

    /**
     * Host set for a public IPv6 LB VIP: hypervisors of Running backends bound
     * via inventory LB rules; gateway fallback when none resolve.
     */
    List<Long> resolveHost6AnnounceHostIds(final String canonicalVip, final long zoneId,
                                           final long controllerId) {
        final List<Long> backends = resolveHost6BackendHostIds(canonicalVip, zoneId);
        if (!backends.isEmpty()) {
            return backends;
        }
        LOGGER.info("OvnBgpRedistribute: IPv6 LB VIP {} has no Running backends; falling back to gateway",
                canonicalVip);
        final Long gw = findGatewayChassisHostId(zoneId, controllerId);
        return gw == null ? Collections.emptyList() : List.of(gw);
    }

    /**
     * Zone-aware IPv6 backend host resolution for Option B.
     */
    List<Long> resolveHost6BackendHostIds(final String canonicalVip, final long zoneId) {
        final Set<Long> hosts = new LinkedHashSet<>();
        if (loadBalancerDao == null || loadBalancerVMMapDao == null || vmInstanceDao == null
                || userPublicIpv6AddressDao == null) {
            return Collections.emptyList();
        }
        final List<UserPublicIpv6AddressVO> addrs =
                userPublicIpv6AddressDao.listByZone(zoneId);
        if (addrs == null) {
            return Collections.emptyList();
        }
        for (final UserPublicIpv6AddressVO addr : addrs) {
            if (addr == null || StringUtils.isBlank(addr.getAddress())) {
                continue;
            }
            if (!canonicalVip.equals(canonicalizeHost6Vip(addr.getAddress()))) {
                continue;
            }
            final List<LoadBalancerVO> rules = loadBalancerDao.listByPublicIpv6AddressId(addr.getId());
            if (rules == null) {
                continue;
            }
            for (final LoadBalancerVO lb : rules) {
                if (lb == null) {
                    continue;
                }
                final FirewallRule.State st = lb.getState();
                if (st != null && st != FirewallRule.State.Active && st != FirewallRule.State.Add) {
                    continue;
                }
                // DSR_SOFTWARE backends do not drive CT_LB /128 host announce hosts.
                if (lb.getLbKind() != null && lb.getLbKind().isDsr()) {
                    continue;
                }
                addBackendHostsForLb(lb.getId(), hosts);
            }
        }
        return sortedHostList(hosts);
    }

    static String encodeHostIds(final List<Long> hostIds) {
        if (hostIds == null || hostIds.isEmpty()) {
            return "";
        }
        return hostIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    static List<Long> parseHostIds(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Long> out = new ArrayList<>();
        for (final String part : raw.split(",")) {
            final String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.valueOf(t));
            } catch (NumberFormatException nfe) {
                // skip garbage
            }
        }
        return out;
    }

    private static Long parseHostId(final String raw) {
        final List<Long> ids = parseHostIds(raw);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static List<Long> sortedHostList(final Set<Long> hosts) {
        final List<Long> out = new ArrayList<>(hosts);
        Collections.sort(out);
        return out;
    }

    /* ---------- accessor surface for tests ---------- */

    Map<String, Long> lastHostByIpSnapshot() {
        return new HashMap<>(lastHostByIp);
    }

    /** Build a stable HAG name for a zone — exposed so other plugin
     *  components can rebuild the same name without coupling to this class
     *  directly. */
    public static String hagNameForZone(final long zoneId) {
        return HAG_NAME_PREFIX + zoneId;
    }
}
