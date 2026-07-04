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
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnPublicNetworkManager;

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
        final String anchorCidr = resolveAnchorCidr(zoneId, vpcId);
        if (sendCommand(hostId, publicIp, gatewayIp, anchorCidr, OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
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
     */
    public void withdraw(final String publicIp, final long ipAddrId, final long vpcId, final long zoneId) {
        if (StringUtils.isBlank(publicIp)) {
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
            sendCommand(hostId, publicIp, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
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
     * Reconcile the live gateway-chassis assignment against the persisted
     * {@link Kind#BGP_ANNOUNCE} rows. When the gateway-chassis migrated,
     * announce on the new host and withdraw from the old. Designed to be
     * invoked periodically (see {@link OvnNetworkConfig#BgpReconcileIntervalSeconds}).
     */
    public void reconcileZone(final long zoneId) {
        if (!Boolean.TRUE.equals(OvnNetworkConfig.BgpRedistributePublicIps.value())) {
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
            final String anchorCidr = resolveAnchorForIpAddr(zoneId, row.getCsId());
            if (sendCommand(currentGw, publicIp, gatewayIp, anchorCidr, OvnBgpAnnounceCommand.OP_ANNOUNCE)) {
                if (lastHost != null) {
                    sendCommand(lastHost, publicIp, null, null, OvnBgpAnnounceCommand.OP_WITHDRAW);
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

    private boolean sendCommand(final long hostId, final String publicIp, final String gatewayIp,
                                final String anchorCidr, final String operation) {
        final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                publicIp,
                operation,
                OvnNetworkConfig.BgpFrrVtyshPath.value(),
                OvnNetworkConfig.BgpFrrAsn.value(),
                gatewayIp,
                anchorCidr);
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
