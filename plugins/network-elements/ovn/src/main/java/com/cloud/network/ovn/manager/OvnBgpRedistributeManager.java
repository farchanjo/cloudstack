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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.OvnBgpAnnounceCommand;
import com.cloud.network.IpAddress;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
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

/**
 * Announces / withdraws a {@code /32} host route per allocated public IP via
 * the host-side FRR daemon on the OVN gateway-chassis. Pure opt-in,
 * controlled by the global ConfigKey {@code ovn.bgp.redistribute.public_ips}
 * or its per-VPC detail override (see {@link OvnPublicNetworkManager#isBgpRedistributeEnabled}).
 *
 * <p>Why this exists: when the public network's parent prefix is announced
 * by every data node via ECMP (the canonical CloudStack DC layout), inbound
 * traffic to a public IP can land on any node — but the conntrack /
 * stateful-NAT state lives only on the OVN gateway-chassis hosting the
 * VPC's distributed-gateway LRP. A {@code /32} announce from the
 * gateway-chassis pulls the /32 prefix toward the right node, fixing
 * inbound DNAT silently dropping on the wrong node.
 *
 * <p>The plugin does NOT replace FRR. It only writes
 * {@code router bgp <asn> ; network <ip>/32} into the host's already-running
 * FRR via {@code vtysh}, leaving FRR's own iBGP / EVPN / route-reflector
 * pipeline untouched.
 *
 * <p>Tracking: each successful announce persists a row of
 * {@link Kind#BGP_ANNOUNCE} in {@code ovn_logical_id_map}. The cs_id is the
 * {@code IPAddressVO.id}; the {@code ovn_uuid} column is reused to carry the
 * agent-side host id (encoded as a string) so the periodic reconciler can
 * detect gateway-chassis migration and re-announce on the new host.
 */
@Component
public class OvnBgpRedistributeManager {

    private static final Logger LOGGER = LogManager.getLogger(OvnBgpRedistributeManager.class);

    /** Per-zone HA_Chassis_Group name programmed by
     *  {@link OvnPublicNetworkManager#ensureHaChassisGroupForZone(long)}. */
    private static final String HAG_NAME_PREFIX = "hag-public-z";

    /** In-memory cache of the last-announced (publicIp, hostId) pair so the
     *  reconciler can short-circuit unchanged entries between ticks. The
     *  authoritative state lives in the DAO; this cache only avoids redundant
     *  agent calls when nothing moved. */
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

    /**
     * Announce the supplied public IP as a {@code /32} via the gateway-chassis
     * host's FRR. No-op when the redistributor is not enabled for the VPC.
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
        final Long hostId = findGatewayChassisHostId(zoneId, controller.getId());
        if (hostId == null) {
            LOGGER.warn("OvnBgpRedistribute.announce: no gateway-chassis for zone={}; skipping {}/32",
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
        if (sendCommand(hostId, publicIp, gatewayIp, gatewayMac, anchorCidr, vlan, networkGatewayIp,
                OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
            persistAnnounce(controller.getId(), ipAddrId, hostId, publicIp);
            lastHostByIp.put(publicIp, hostId);
            LOGGER.info("OvnBgpRedistribute.announce: {}/32 announced on host {} (ip_id={}, vpc={})",
                    publicIp, hostId, ipAddrId, vpcId);
        }
    }

    /**
     * Withdraw the supplied public IP from FRR on whatever host last
     * announced it. Removes the bookkeeping row regardless of agent
     * success — best-effort cleanup. Safe to call multiple times.
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
        final Long hostId = parseHostId(mapping.getOvnUuid());
        if (hostId != null) {
            // Withdraw needs no next-hop and no anchor: the wrapper deletes the
            // /32 route by prefix and writes `no network <ip>/32`. The chassis
            // anchor is shared across FIPs and is NOT torn down per withdraw.
            sendCommand(hostId, publicIp, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
        }
        try {
            logicalIdMapDao.remove(mapping.getId());
        } finally {
            lastHostByIp.remove(publicIp);
        }
        LOGGER.info("OvnBgpRedistribute.withdraw: {}/32 withdrawn on host {} (ip_id={}, vpc={})",
                publicIp, hostId, ipAddrId, vpcId);
    }

    /**
     * Invent-missing: for every allocated public IPv4 in {@code zoneId} that
     * is still used by SNAT / StaticNat / LB / PF on a VPC with redistribute
     * enabled, ensure a BGP {@code /32} announce exists. Skips IPs that already
     * have a {@link Kind#BGP_ANNOUNCE} mapping (gateway migration is handled
     * by {@link #reconcileZone}).
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
            if (existing != null) {
                continue;
            }
            announce(publicIp, ip.getId(), ip.getVpcId(), zoneId);
            attempted++;
        }
        if (attempted > 0) {
            LOGGER.info("OvnBgpRedistribute.ensurePublicIpv4AnnouncesForZone: zone={} attempted {} missing /32 announce(s)",
                    zoneId, attempted);
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
        if (hostId != null) {
            sendSubnetCommand(hostId, cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
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
     * Announce a public IPv6 LB VIP as a BGP {@code /128} host route on the
     * gateway-chassis FRR (IPv6 unicast AF), with a matching kernel route via
     * the VPC public LRP GUA so inbound N-S enters OVN. Used by
     * {@code ovn.lr.public.ipv6.lb} — VIPs sit outside CloudStack
     * {@code user_ip_address}, so bookkeeping uses
     * {@link Kind#BGP_HOST_ANNOUNCE_V6} with a stable positive hash of the VIP
     * (kind-isolated from {@link Kind#BGP_ANNOUNCE}; must stay &gt; 0 because
     * {@code ovn_logical_id_map.cs_id} is {@code bigint unsigned}).
     *
     * @param vip   bare IPv6 VIP (no prefix length)
     * @param vpcId owning VPC id (public LRP GUA / MAC resolution)
     * @param zoneId zone id (controller / gateway-chassis)
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
        final Long hostId = findGatewayChassisHostId(zoneId, controller.getId());
        if (hostId == null) {
            LOGGER.warn("OvnBgpRedistribute.announceHost6: no gateway-chassis for zone={}; skipping {}/128",
                    zoneId, canonicalVip);
            return;
        }
        final String cidr = canonicalVip + "/128";
        final String v6GatewayIp = publicNetworkManager.getVpcPublicIpv6GatewayIp(zoneId, vpcId);
        final String gatewayMac = publicNetworkManager.getVpcPublicLrpMac(zoneId, vpcId);
        final String v6AnchorCidr = publicNetworkManager.getPublicIpv6AnchorCidr();
        final String v6NetworkGateway = publicNetworkManager.getPublicIpv6Gateway();
        if (sendSubnetCommand(hostId, cidr, v6GatewayIp, gatewayMac, v6AnchorCidr, null, v6NetworkGateway,
                OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
            final long csId = host6CsId(canonicalVip);
            persistHostAnnounceV6(controller.getId(), csId, hostId, canonicalVip);
            lastHostByIp.put(cidr, hostId);
            LOGGER.info("OvnBgpRedistribute.announceHost6: {}/128 announced on host {} (vpc={})",
                    canonicalVip, hostId, vpcId);
        }
    }

    /**
     * Withdraw a previously-announced public IPv6 LB VIP {@code /128}.
     * Best-effort and idempotent. Accepts any textual form of the VIP;
     * canonicalization inside {@link #host6CsId} keeps the bookkeeping key stable.
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
        final Long hostId = parseHostId(mapping.getOvnUuid());
        if (hostId != null) {
            sendSubnetCommand(hostId, cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
        }
        try {
            logicalIdMapDao.remove(mapping.getId());
        } finally {
            lastHostByIp.remove(cidr);
        }
        LOGGER.info("OvnBgpRedistribute.withdrawHost6: {}/128 withdrawn on host {} (vpc={})",
                canonicalVip, hostId, vpcId);
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

    private void persistHostAnnounceV6(final long controllerId, final long csId, final long hostId,
                                       final String vip) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_HOST_ANNOUNCE_V6, csId, controllerId);
        if (existing != null) {
            existing.setOvnUuid(String.valueOf(hostId));
            existing.setOvnName(vip);
            logicalIdMapDao.update(existing.getId(), existing);
            return;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(
                Kind.BGP_HOST_ANNOUNCE_V6, csId, controllerId, String.valueOf(hostId), vip));
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
        if (hostId != null) {
            sendSubnetCommand(hostId, ip6Cidr, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
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
     * Reconcile the live gateway-chassis assignment against the persisted
     * {@link Kind#BGP_ANNOUNCE} rows. When the gateway-chassis migrated,
     * announce on the new host and withdraw from the old. Designed to be
     * invoked periodically (see {@link OvnNetworkConfig#BgpReconcileIntervalSeconds}).
     */
    /**
     * Global public-IP /32 toggle. Package-visible so unit tests can spy the
     * gate without wiring the static {@code ConfigDepot}.
     */
    boolean isPublicRedistributeEnabled() {
        return Boolean.TRUE.equals(OvnNetworkConfig.BgpRedistributePublicIps.value());
    }

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
            final Long lastHost = parseHostId(row.getOvnUuid());
            final String publicIp = row.getOvnName();
            if (publicIp == null || publicIp.isEmpty()) {
                continue;
            }
            if (currentGw.equals(lastHost)) {
                continue;
            }
            // Gateway moved. Announce on the new host first (so the route
            // is in BGP before we tear it down on the old host), then
            // withdraw on the previous host. Resolve the VPC's public LRP IP
            // (row cs_id = public_ip_address.id -> vpcId) so the /32 datapath
            // route is re-installed on the NEW gateway chassis, not just
            // re-advertised.
            final String gatewayIp = resolveGatewayIpForIpAddr(zoneId, row.getCsId());
            final String gatewayMac = resolveGatewayMacForIpAddr(zoneId, row.getCsId());
            final String anchorCidr = resolveAnchorForIpAddr(zoneId, row.getCsId());
            final String vlan = resolveVlanForIpAddr(zoneId, row.getCsId());
            final String networkGatewayIp = resolveNetworkGatewayForIpAddr(zoneId, row.getCsId());
            if (sendCommand(currentGw, publicIp, gatewayIp, gatewayMac, anchorCidr, vlan, networkGatewayIp,
                    OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
                if (lastHost != null) {
                    sendCommand(lastHost, publicIp, null, null, null, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
                }
                row.setOvnUuid(String.valueOf(currentGw));
                logicalIdMapDao.update(row.getId(), row);
                lastHostByIp.put(publicIp, currentGw);
                LOGGER.info("OvnBgpRedistribute.reconcileZone: {}/32 migrated from host {} to host {} (zone={})",
                        publicIp, lastHost, currentGw, zoneId);
            }
        }
    }

    /* ---------- internal helpers ---------- */

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

    private boolean sendCommand(final long hostId, final String publicIp, final String gatewayIp,
                                final String gatewayMac, final String anchorCidr, final String vlan,
                                final String networkGatewayIp, final String operation) {
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

    private void persistAnnounce(final long controllerId, final long ipAddrId, final long hostId,
                                 final String publicIp) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(
                Kind.BGP_ANNOUNCE, ipAddrId, controllerId);
        if (existing != null) {
            existing.setOvnUuid(String.valueOf(hostId));
            existing.setOvnName(publicIp);
            logicalIdMapDao.update(existing.getId(), existing);
            return;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(
                Kind.BGP_ANNOUNCE, ipAddrId, controllerId, String.valueOf(hostId), publicIp));
    }

    private static Long parseHostId(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException nfe) {
            return null;
        }
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
