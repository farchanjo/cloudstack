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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.apache.cloudstack.resourcedetail.VpcDetailVO;
import org.apache.cloudstack.resourcedetail.dao.VpcDetailsDao;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.utils.net.NetUtils;

/**
 * Owns the per-zone {@code Logical_Switch} that bridges OVN's overlay onto
 * the public physnet (CloudStack public traffic VLAN). One LS per zone,
 * created once and reused by every VPC LR via a public-side LRP. North-south
 * gateway HA is provided by an {@code HA_Chassis_Group} with one ranked
 * {@code HA_Chassis} entry per data-node chassis.
 *
 * <p>The {@code localnet} LSP attached to the public LS is what physically
 * exits the OVN datapath into the host bridge ({@code br-bond}); OVN
 * auto-creates the patch ports between {@code br-int} and the host bridge
 * via the {@code ovn-bridge-mappings} controller option.
 *
 * <p>Helper bean — invoked from {@link OvnNetworkElement} / {@link OvnVpcElement}.
 */
@Component
public class OvnPublicNetworkManager {

    private static final Logger LOGGER = LogManager.getLogger(OvnPublicNetworkManager.class);

    /** Default localnet physnet name (must match {@code ovn-bridge-mappings}). */
    public static final String DEFAULT_PHYSNET = "physnet-public";

    /** Public-side LSP name on the per-zone public LS. */
    public static final String PUBLIC_LOCALNET_LSP = "lsp-public-localnet";

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnChassisMapDao chassisMapDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private VpcDetailsDao vpcDetailsDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Ensure the per-zone public LS + localnet LSP exist. Idempotent. Returns
     * the LS UUID. Optional VLAN tag carried as the localnet's
     * {@code tag} column when the public physnet is trunked.
     *
     * <p>VLAN tag resolution chain (highest wins):
     * <ol>
     *   <li>{@code publicVlanTag} argument (explicit caller override).</li>
     *   <li>{@code ovn.public.vlan.override} global ConfigKey when non-zero.</li>
     *   <li>Auto-detect from CloudStack Public network broadcastUri when
     *       {@code ovn.public.vlan.auto} is on.</li>
     *   <li>{@code null} (untagged localnet — operator handles VLAN externally).</li>
     * </ol>
     *
     * <p>On the existing-row path the method also fixes VLAN drift: if the
     * resolved tag differs from what is currently programmed on
     * {@link #PUBLIC_LOCALNET_LSP}, the tag is rewritten in place. This
     * keeps the public-side localnet aligned with operator config without
     * forcing a destroy / recreate cycle.
     */
    public String ensurePublicLogicalSwitch(final long zoneId, final Integer publicVlanTag, final String physnetName) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no OVN controller for zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final Integer resolvedVlan = resolvePublicLocalnetVlan(zoneId, publicVlanTag);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.PUBLIC_LS, zoneId, controller.getId());
        if (existing != null) {
            // Stale-mapping guard: the NB row may have been deleted
            // out-of-band (manual ovn-nbctl ls-del, partial cleanup,
            // earlier failed transaction). Verify before returning the
            // cached UUID so we don't hand the caller a dead reference.
            if (nb.rowExistsByUuid("Logical_Switch", existing.getOvnUuid())) {
                reconcilePublicLocalnetTag(nb, existing.getOvnUuid(), resolvedVlan);
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnPublicNetworkManager: PUBLIC_LS mapping {} -> {} stale (NB row gone); recreating",
                    zoneId, existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final String lsName = "ls-public-z" + zoneId;
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.PUBLIC_LS.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(zoneId));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(zoneId));
        final String lsUuid = nb.createLogicalSwitch(lsName, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.PUBLIC_LS, zoneId, controller.getId(), lsUuid, lsName));
        // Always attach the localnet bridge LSP — that's the contract that
        // forwards datapath frames to br-bond via ovn-bridge-mappings.
        final String physnet = StringUtils.isNotBlank(physnetName) ? physnetName : DEFAULT_PHYSNET;
        nb.addLocalnetPort(lsUuid, PUBLIC_LOCALNET_LSP, resolvedVlan, physnet);
        LOGGER.info("OvnPublicNetworkManager: created public LS {} (vlan={}, physnet={})",
                lsUuid, resolvedVlan, physnet);
        return lsUuid;
    }

    /**
     * Resolve the VLAN tag that should be programmed on the per-zone public
     * localnet LSP. Used by {@link #ensurePublicLogicalSwitch} on creation
     * and by {@link com.cloud.network.ovn.manager.OvnReconcilerService} on
     * drift detection.
     *
     * <p>Resolution order (highest wins):
     * <ol>
     *   <li>{@code explicitOverride} caller argument when non-null.</li>
     *   <li>{@code ovn.public.vlan.override} global ConfigKey when non-zero.</li>
     *   <li>Auto-detect from CloudStack Public network broadcastUri when
     *       {@code ovn.public.vlan.auto} is true.</li>
     *   <li>{@code null} (untagged localnet).</li>
     * </ol>
     */
    public Integer resolvePublicLocalnetVlan(final long zoneId, final Integer explicitOverride) {
        if (explicitOverride != null) {
            return explicitOverride;
        }
        final Integer cfgOverride = OvnNetworkConfig.PublicVlanOverride.value();
        if (cfgOverride != null && cfgOverride.intValue() > 0) {
            return cfgOverride;
        }
        if (!Boolean.TRUE.equals(OvnNetworkConfig.PublicVlanAuto.value())) {
            return null;
        }
        return autoDetectPublicVlan(zoneId);
    }

    /**
     * Auto-detect helper — walks every {@link TrafficType#Public} network in
     * the zone and resolves the first usable VLAN id. Two probes, in order:
     *
     * <ol>
     *   <li>{@link NetworkVO#getBroadcastUri() broadcastUri} on the network
     *       row itself ({@code vlan://<id>} encoding) — covers freshly
     *       provisioned zones whose Public traffic type carries the URI on
     *       the network record.</li>
     *   <li>The {@code vlan} table joined to the network — the canonical CS
     *       location for the public-IP VLAN when the network row leaves
     *       {@code broadcast_uri NULL}. Iterates rows via
     *       {@link VlanDao#listVlansByNetworkId(long)} and picks the first
     *       row whose {@code vlan_id} parses as a numeric VLAN tag (rows like
     *       {@code vlan://untagged} are skipped).</li>
     * </ol>
     *
     * <p>Returns {@code null} when no public network carries a numeric VLAN
     * id (fully untagged trunk deployment).
     */
    private Integer autoDetectPublicVlan(final long zoneId) {
        final List<NetworkVO> publics = networkDao.listByZoneAndTrafficType(zoneId, TrafficType.Public);
        if (publics == null || publics.isEmpty()) {
            return null;
        }
        for (final NetworkVO net : publics) {
            final Integer fromBroadcastUri = parseVlanFromBroadcastUri(net);
            if (fromBroadcastUri != null) {
                return fromBroadcastUri;
            }
            final Integer fromVlanTable = parseVlanFromVlanDao(net);
            if (fromVlanTable != null) {
                return fromVlanTable;
            }
        }
        return null;
    }

    /**
     * Probe #1 — network row's own {@code broadcastUri}. CloudStack encodes
     * the VLAN as {@code vlan://<tag>} when present. Returns {@code null}
     * when the URI is null or its scheme is not {@code vlan}.
     */
    private Integer parseVlanFromBroadcastUri(final NetworkVO net) {
        final URI broadcast = net.getBroadcastUri();
        if (broadcast == null) {
            return null;
        }
        if (!"vlan".equalsIgnoreCase(broadcast.getScheme())) {
            return null;
        }
        final String host = broadcast.getHost() != null ? broadcast.getHost() : broadcast.getSchemeSpecificPart();
        final String trimmed = host == null ? null : host.replace("/", "").trim();
        if (StringUtils.isBlank(trimmed)) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException nfe) {
            LOGGER.debug("OvnPublicNetworkManager: unparseable VLAN '{}' on Public network id={} broadcast={}",
                    trimmed, net.getId(), broadcast);
            return null;
        }
    }

    /**
     * Probe #2 — the {@code vlan} table. CS stores per-public-IP VLANs there
     * with {@code vlan_id} encoded as {@code vlan://<tag>} or
     * {@code vlan://untagged}. The first numeric tag wins.
     *
     * <p>Returns {@code null} when no row carries a numeric tag (every row is
     * untagged) or when the {@link VlanDao} bean is unavailable (test seam —
     * the legacy unit tests inject only the {@link NetworkDao}).
     */
    private Integer parseVlanFromVlanDao(final NetworkVO net) {
        if (vlanDao == null) {
            return null;
        }
        final List<VlanVO> rows;
        try {
            rows = vlanDao.listVlansByNetworkId(net.getId());
        } catch (RuntimeException re) {
            LOGGER.debug("OvnPublicNetworkManager: vlanDao.listVlansByNetworkId failed for network id={}: {}",
                    net.getId(), re.getMessage());
            return null;
        }
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (final VlanVO row : rows) {
            // Only VirtualNetwork (= Public) rows can carry the public VLAN
            // tag. DirectAttached / VirtualMachineRange are skipped as a
            // safety net — they never apply to the OVN public localnet.
            if (row.getVlanType() != null && row.getVlanType() != Vlan.VlanType.VirtualNetwork) {
                continue;
            }
            final String vlanId = row.getVlanTag();
            if (StringUtils.isBlank(vlanId)) {
                continue;
            }
            // vlan_id column carries values like "vlan://2988" or
            // "vlan://untagged"; strip the scheme then parse the integer.
            final String stripped = vlanId.startsWith("vlan://") ? vlanId.substring("vlan://".length()) : vlanId;
            final String trimmed = stripped.replace("/", "").trim();
            if (StringUtils.isBlank(trimmed) || "untagged".equalsIgnoreCase(trimmed)) {
                continue;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException nfe) {
                LOGGER.debug("OvnPublicNetworkManager: unparseable VLAN '{}' on vlan row id={} (network id={})",
                        trimmed, row.getId(), net.getId());
            }
        }
        return null;
    }

    /**
     * Drift-fix helper: read the current {@code tag} on the public localnet
     * LSP and rewrite it when it diverges from the resolved VLAN. No-op when
     * the LSP cannot be located (operator-managed mode) or when the tag is
     * already correct.
     */
    private void reconcilePublicLocalnetTag(final OvnNbClient nb, final String publicLsUuid,
                                            final Integer desiredVlan) {
        final String lspUuid = nb.findLogicalSwitchPortUuidByExactName(PUBLIC_LOCALNET_LSP);
        if (lspUuid == null) {
            LOGGER.debug("OvnPublicNetworkManager: no localnet LSP {} on public LS {}; skipping VLAN drift-fix",
                    PUBLIC_LOCALNET_LSP, publicLsUuid);
            return;
        }
        final Integer current = nb.getLogicalSwitchPortTag(lspUuid);
        if (java.util.Objects.equals(current, desiredVlan)) {
            return;
        }
        try {
            nb.setLogicalSwitchPortTag(lspUuid, desiredVlan);
            LOGGER.info("OvnPublicNetworkManager: public localnet vlan drift-fix {} -> {} (lsp={}, ls={})",
                    current, desiredVlan, lspUuid, publicLsUuid);
        } catch (OvnException e) {
            LOGGER.warn("OvnPublicNetworkManager: public localnet vlan drift-fix failed (lsp={}): {}",
                    lspUuid, e.getMessage());
        }
    }

    /** Convenience accessor for the per-zone public LSP name + UUID lookup,
     *  used by the reconciler so tests can stub the seam without depending
     *  on internal NB-client behaviour. */
    public String findPublicLocalnetLspUuid(final long zoneId) {
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        return nb.findLogicalSwitchPortUuidByExactName(PUBLIC_LOCALNET_LSP);
    }

    /**
     * Resolve whether BGP /32 redistribution is enabled for a given VPC. The
     * VPC detail row {@code ovn.bgp.redistribute} (free-form string) wins
     * over the global ConfigKey when present. Used by callers in the
     * Source/Static/PortForward services and by the periodic gateway-chassis
     * reconciler.
     */
    public boolean isBgpRedistributeEnabled(final long vpcId) {
        if (vpcDetailsDao != null) {
            final VpcDetailVO detail = vpcDetailsDao.findDetail(vpcId, OvnNetworkConfig.VPC_DETAIL_BGP_REDISTRIBUTE);
            if (detail != null && StringUtils.isNotBlank(detail.getValue())) {
                return parseBoolean(detail.getValue());
            }
        }
        return Boolean.TRUE.equals(OvnNetworkConfig.BgpRedistributePublicIps.value());
    }

    private static boolean parseBoolean(final String raw) {
        if (raw == null) {
            return false;
        }
        final String trimmed = raw.trim();
        return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)
                || "yes".equalsIgnoreCase(trimmed) || "on".equalsIgnoreCase(trimmed);
    }

    /**
     * Build (or return existing) HA_Chassis_Group covering all chassis under
     * the supplied controller. Used as the gateway-failover anchor for any
     * north-south LRP a VPC creates against the public LS.
     */
    public String ensureHaChassisGroupForZone(final long zoneId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no OVN controller for zone " + zoneId);
        }
        final OvnNbClient nbExist = pluginManager.nbClient(zoneId);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.HA_CHASSIS_GROUP, zoneId, controller.getId());
        if (existing != null) {
            if (nbExist.rowExistsByUuid("HA_Chassis_Group", existing.getOvnUuid())) {
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnPublicNetworkManager: HA_CHASSIS_GROUP mapping {} -> {} stale; recreating",
                    zoneId, existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final List<OvnChassisMapVO> chassis = chassisMapDao.listByController(controller.getId());
        if (chassis.isEmpty()) {
            throw new OvnException("OvnPublicNetworkManager: no OVN chassis registered for controller id="
                    + controller.getId());
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        // Priority decreases by host id order so failover deterministically
        // picks the lower-id chassis when multiple are healthy. We insert
        // every HA_Chassis row + the HA_Chassis_Group in a single OVSDB
        // transaction via createHaChassisGroupAtomic — separate transactions
        // would let ovsdb-server GC the orphan HA_Chassis rows before the
        // group references them, raising "referential integrity violation".
        final List<java.util.Map.Entry<String, Integer>> members = new ArrayList<>(chassis.size());
        int prio = 100;
        for (final OvnChassisMapVO row : chassis) {
            members.add(java.util.Map.entry(row.getChassisUuid(), prio--));
        }
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.HA_CHASSIS_GROUP.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(zoneId));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(zoneId));
        final String hagName = "hag-public-z" + zoneId;
        final String hagUuid = nb.createHaChassisGroupAtomic(hagName, members, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.HA_CHASSIS_GROUP, zoneId, controller.getId(), hagUuid, hagName));
        LOGGER.info("OvnPublicNetworkManager: created HA_Chassis_Group {} with {} chassis (zone={})",
                hagUuid, members.size(), zoneId);
        return hagUuid;
    }

    /**
     * Bind a VPC LR to the per-zone public LS via a router-patch pair, then
     * pin the LR-side LRP to the zone's HA_Chassis_Group so north-south
     * traffic fails over across data nodes.
     */
    public OvnNbClient.BindResult bindVpcToPublic(final long zoneId, final String lrUuid, final String publicMac,
                                                   final List<String> publicNetworks, final long vpcId) {
        return bindOwnerToPublic(zoneId, vpcId, lrUuid, publicMac, publicNetworks,
                "lrp-public-vpc" + vpcId, "rsp-public-vpc" + vpcId, null,
                Kind.VPC_PUBLIC_LRP, Kind.VPC_PUBLIC_RSP, Kind.STATIC_ROUTE, "default-vpc" + vpcId);
    }

    /**
     * Owner-agnostic public bind. A VPC caller passes the VPC public Kinds +
     * {@code lrp-public-vpc<id>} / {@code rsp-public-vpc<id>} names +
     * {@code nexthopOverride=null} (→ {@link #pickGatewayFromNetworks}), which
     * reproduces the historical VPC behaviour byte-for-byte. A standalone
     * Isolated network passes the ISOLATED_* Kinds + {@code *-net<id>} names +
     * an explicit next-hop (the real public VLAN gateway) so its default route
     * points at a reachable upstream instead of the naive {@code .1} heuristic.
     */
    private OvnNbClient.BindResult bindOwnerToPublic(final long zoneId, final long ownerId, final String lrUuid,
                                                     final String publicMac, final List<String> publicNetworks,
                                                     final String lrpName, final String lspName,
                                                     final String nexthopOverride,
                                                     final Kind lrpKind, final Kind rspKind,
                                                     final Kind routeKind, final String routeName) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no controller for zone " + zoneId);
        }
        final String publicLsUuid = ensurePublicLogicalSwitch(zoneId, null, null);
        final String hagUuid = ensureHaChassisGroupForZone(zoneId);
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnNbClient.BindRequest req = new OvnNbClient.BindRequest(
                lrUuid, publicLsUuid, lrpName, publicMac, publicNetworks, lspName);
        // Tag the peer LSP so OvnReconcilerService can classify it as a
        // *_PUBLIC_RSP row instead of an untaggable, invisible orphan.
        final OvnNbClient.BindResult result = nb.bindLrToLs(req, ownerRspExternalIds(zoneId, ownerId, rspKind));
        nb.lrpSetHaChassisGroup(result.lrpUuid, hagUuid);
        persistOwnerPublicBind(controller, zoneId, ownerId, lrUuid, req, result,
                nexthopOverride, lrpKind, rspKind, routeKind, routeName);
        LOGGER.info("OvnPublicNetworkManager: bound LR {} to public LS {} (lrp={} hag={})",
                lrUuid, publicLsUuid, result.lrpUuid, hagUuid);
        return result;
    }

    /** {@code external_ids} tag for the public peer LSP, keyed by owner + rsp Kind. */
    private Map<String, String> ownerRspExternalIds(final long zoneId, final long ownerId, final Kind rspKind) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, rspKind.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(ownerId));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(zoneId));
        return ext;
    }

    /**
     * Persists the default route plus the VPC_PUBLIC_LRP / VPC_PUBLIC_RSP
     * mapping rows created by {@link #bindVpcToPublic} so
     * {@link #unbindVpcFromPublic} can resolve and drop all three cleanly.
     */
    private void persistOwnerPublicBind(final OvnControllerVO controller, final long zoneId, final long ownerId,
                                        final String lrUuid, final OvnNbClient.BindRequest req,
                                        final OvnNbClient.BindResult result, final String nexthopOverride,
                                        final Kind lrpKind, final Kind rspKind,
                                        final Kind routeKind, final String routeName) {
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        // Default route so owner traffic reaches the upstream fabric. VPC callers
        // pass a null override and keep the historical .1 heuristic; isolated
        // callers pass the real public VLAN gateway.
        final String nexthop = nexthopOverride != null ? nexthopOverride : pickGatewayFromNetworks(req.lrpNetworks);
        final String routeUuid = nb.addLogicalRouterStaticRoute(lrUuid, "0.0.0.0/0",
                nexthop, req.lrpName, "dst-ip",
                Map.of(OvnConstants.EXT_ID_KIND, routeKind.name(),
                        OvnConstants.EXT_ID_ID, String.valueOf(ownerId)));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(lrpKind, ownerId, controller.getId(),
                result.lrpUuid, req.lrpName));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(rspKind, ownerId, controller.getId(),
                result.lspUuid, req.lspName));
        // Persist the static-route mapping so unbind can drop it cleanly.
        // One default route per owner (keyed by owner id under routeKind).
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(routeKind, ownerId, controller.getId(),
                routeUuid, routeName));
    }

    /**
     * Idempotent wrapper around {@link #bindVpcToPublic(long, String, List, long)}
     * that accepts the public VLAN tag and physnet name (typically derived
     * by the caller from the CloudStack public Vlan / network). Re-running
     * with the same VPC id returns the existing LRP without re-creating it.
     */
    public String ensureVpcBoundToPublic(final long zoneId, final long vpcId, final String lrUuid,
                                         final String publicMac, final List<String> publicNetworks,
                                         final Integer publicVlanTag, final String physnetName) {
        return ensureOwnerBoundToPublic(zoneId, vpcId, lrUuid, publicMac, publicNetworks,
                publicVlanTag, physnetName,
                "lrp-public-vpc" + vpcId, "rsp-public-vpc" + vpcId, null,
                Kind.VPC_PUBLIC_LRP, Kind.VPC_PUBLIC_RSP, Kind.STATIC_ROUTE, "default-vpc" + vpcId);
    }

    /**
     * Phase B — idempotent public bind for a standalone Isolated network.
     * Isolated analogue of {@link #ensureVpcBoundToPublic}: keys every row
     * under the ISOLATED_* Kinds by {@code networkId}, names the ports
     * {@code lrp-public-net<id>} / {@code rsp-public-net<id>}, and points the
     * default route at the supplied real public VLAN gateway ({@code nexthopGateway}).
     */
    public String ensureIsolatedNetworkBoundToPublic(final long zoneId, final long networkId, final String lrUuid,
                                                      final String publicMac, final List<String> publicNetworks,
                                                      final Integer publicVlanTag, final String physnetName,
                                                      final String nexthopGateway) {
        return ensureOwnerBoundToPublic(zoneId, networkId, lrUuid, publicMac, publicNetworks,
                publicVlanTag, physnetName,
                "lrp-public-net" + networkId, "rsp-public-net" + networkId, nexthopGateway,
                Kind.ISOLATED_PUBLIC_LRP, Kind.ISOLATED_PUBLIC_RSP, Kind.ISOLATED_STATIC_ROUTE, "default-net" + networkId);
    }

    /**
     * Owner-agnostic idempotent public bind. VPC + isolated callers feed their
     * own Kind trio / port names / next-hop; the stale-mapping guard, public LS
     * pre-create and HA-chassis ensure are shared verbatim.
     */
    private String ensureOwnerBoundToPublic(final long zoneId, final long ownerId, final String lrUuid,
                                            final String publicMac, final List<String> publicNetworks,
                                            final Integer publicVlanTag, final String physnetName,
                                            final String lrpName, final String lspName,
                                            final String nexthopOverride,
                                            final Kind lrpKind, final Kind rspKind,
                                            final Kind routeKind, final String routeName) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no controller for zone " + zoneId);
        }
        final OvnNbClient nbExist = pluginManager.nbClient(zoneId);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(lrpKind, ownerId, controller.getId());
        if (existing != null) {
            if (nbExist.rowExistsByUuid("Logical_Router_Port", existing.getOvnUuid())) {
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnPublicNetworkManager: {} mapping owner={} -> {} stale; recreating",
                    lrpKind, ownerId, existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
            // A stale public LRP implies the paired default route + peer RSP
            // may also have been GC'd by cascade.
            dropStaleMapping(controller, routeKind, ownerId);
            dropStaleMapping(controller, rspKind, ownerId);
        }
        // Pre-create public LS with the supplied vlan/physnet so the localnet
        // port is correctly tagged on first creation. Subsequent calls hit the
        // idempotent path inside ensurePublicLogicalSwitch.
        ensurePublicLogicalSwitch(zoneId, publicVlanTag, physnetName);
        ensureHaChassisGroupForZone(zoneId);
        final OvnNbClient.BindResult bound = bindOwnerToPublic(zoneId, ownerId, lrUuid, publicMac, publicNetworks,
                lrpName, lspName, nexthopOverride, lrpKind, rspKind, routeKind, routeName);
        return bound.lrpUuid;
    }

    /**
     * Resolve the bare IP of a VPC's OVN public LRP (e.g. {@code 217.179.89.34}).
     * This is the LRP's OWN address — the next-hop {@code OvnBgpRedistributeManager}
     * uses for the {@code <publicIp>/32} kernel route that steers inbound N-S
     * traffic into OVN on the gateway chassis.
     *
     * <p>Deliberately NOT {@link #pickGatewayFromNetworks(List)}: that returns
     * the subnet's {@code .1} gateway (used to point the LR's default route at
     * the upstream), whereas the /32 route must target the LRP itself (its MAC
     * answers ARP on the localnet and performs the DNAT).
     *
     * @return the bare IPv4 (no prefix), or {@code null} when the VPC is not
     *         bound to public yet or the LRP row / networks are missing.
     */
    public String getVpcPublicGatewayIp(final long zoneId, final long vpcId) {
        final String cidr = getVpcPublicLrpCidr(zoneId, vpcId);   // "217.179.89.34/24"
        if (cidr == null) {
            return null;
        }
        final int slash = cidr.indexOf('/');
        return slash < 0 ? cidr : cidr.substring(0, slash);      // "217.179.89.34"
    }

    /**
     * Raw first network CIDR of the VPC's public LRP ({@code ip/prefix}, e.g.
     * {@code 217.179.89.34/24}), or {@code null} when the VPC is not
     * public-bound or the LRP row / its networks are missing.
     */
    private String getVpcPublicLrpCidr(final long zoneId, final long vpcId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return null;
        }
        final OvnLogicalIdMapVO lrp = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId());
        if (lrp == null) {
            return null;
        }
        final List<String> nets = pluginManager.nbClient(zoneId).getLogicalRouterPortNetworks(lrp.getOvnUuid());
        if (nets == null || nets.isEmpty() || nets.get(0) == null) {
            return null;
        }
        return nets.get(0);
    }

    /**
     * Derive the single on-link datapath anchor address (WITH prefix, e.g.
     * {@code 217.179.89.2/24}) for the VPC's public segment. The address is
     * NOT configured anywhere: it is computed as the FIRST usable address of
     * the public subnet that sits OUTSIDE CloudStack's allocation pool
     * ({@code vlan.ip4_range}) and is neither the subnet gateway nor the LRP
     * IP. Devops governs it purely by editing the public IP range in
     * CloudStack — change the range and the anchor follows; nothing is
     * hardcoded.
     *
     * @return the anchor {@code ip/prefix}, or {@code null} (→ caller falls
     *         back to advertise-/route-only, no anchor) when the VPC is not
     *         public-bound, or the matching CloudStack public VLAN / IPv4 pool
     *         for the LRP subnet cannot be resolved, or the subnet has no free
     *         out-of-pool address.
     */
    public String getVpcPublicAnchorCidr(final long zoneId, final long vpcId) {
        final String lrpCidr = getVpcPublicLrpCidr(zoneId, vpcId);      // "217.179.89.34/24"
        if (lrpCidr == null) {
            return null;
        }
        final int slash = lrpCidr.indexOf('/');
        if (slash <= 0 || slash == lrpCidr.length() - 1) {
            return null;
        }
        final String lrpIp = lrpCidr.substring(0, slash);
        final int prefix;
        try {
            prefix = Integer.parseInt(lrpCidr.substring(slash + 1).trim());
        } catch (NumberFormatException nfe) {
            return null;
        }
        if (prefix < 1 || prefix > 30) {
            return null;
        }
        final long mask = NetUtils.ip2Long(NetUtils.getCidrNetmask(prefix));
        final long network = NetUtils.ip2Long(lrpIp) & mask;
        final long broadcast = network | (~mask & 0xffffffffL);
        final String subnetCidr = NetUtils.long2Ip(network) + "/" + prefix;

        // Resolve the CloudStack public IPv4 pool whose gateway falls in this subnet.
        final VlanVO vlan = findPublicVlanForSubnet(zoneId, subnetCidr);
        if (vlan == null || StringUtils.isBlank(vlan.getIpRange())) {
            return null;
        }
        final long[] pool = parseIpRange(vlan.getIpRange());
        if (pool == null) {
            return null;
        }
        final long gatewayLong = StringUtils.isBlank(vlan.getVlanGateway())
                ? -1L : NetUtils.ip2Long(vlan.getVlanGateway());
        final long lrpLong = NetUtils.ip2Long(lrpIp);

        for (long cand = network + 1; cand < broadcast; cand++) {
            if (cand == gatewayLong || cand == lrpLong) {
                continue;
            }
            if (cand >= pool[0] && cand <= pool[1]) {
                continue;                    // inside the CloudStack allocation pool
            }
            return NetUtils.long2Ip(cand) + "/" + prefix;   // first address outside the pool
        }
        return null;
    }

    /**
     * Resolve the CloudStack public {@link VlanVO} that owns the VPC's public
     * LRP subnet — the single source for both the localnet VLAN tag and the
     * network gateway IP used to provision the gateway-chassis anchor port.
     * Mirrors the subnet-resolution prologue of {@link #getVpcPublicAnchorCidr}.
     *
     * @return the matching {@code VlanVO}, or {@code null} when the VPC is not
     *         public-bound or no public VLAN covers the LRP subnet.
     */
    private VlanVO resolvePublicVlan(final long zoneId, final long vpcId) {
        final String lrpCidr = getVpcPublicLrpCidr(zoneId, vpcId);   // "217.179.89.34/24"
        if (lrpCidr == null) {
            return null;
        }
        final int slash = lrpCidr.indexOf('/');
        if (slash <= 0 || slash == lrpCidr.length() - 1) {
            return null;
        }
        final int prefix;
        try {
            prefix = Integer.parseInt(lrpCidr.substring(slash + 1).trim());
        } catch (NumberFormatException nfe) {
            return null;
        }
        if (prefix < 1 || prefix > 30) {
            return null;
        }
        final long mask = NetUtils.ip2Long(NetUtils.getCidrNetmask(prefix));
        final long network = NetUtils.ip2Long(lrpCidr.substring(0, slash)) & mask;
        return findPublicVlanForSubnet(zoneId, NetUtils.long2Ip(network) + "/" + prefix);
    }

    /**
     * The public network's 802.1Q VLAN id (bare numeric, e.g. {@code "2988"})
     * for the VPC's public segment. The OVN provider localnet ingress is
     * matched on {@code dl_vlan=<tag>}, so the gateway-chassis {@code pub-anchor}
     * port MUST be an access port on this VLAN — otherwise host-originated
     * frames (untagged) never match the localnet ingress flow and are dropped,
     * breaking egress-return and FIP ingress.
     *
     * @return the numeric VLAN id, or {@code null} when the segment is untagged
     *         / not a plain {@code vlan://<n>} URI (caller keeps the anchor
     *         port untagged, preserving pre-fix behaviour).
     */
    public String getVpcPublicVlanTag(final long zoneId, final long vpcId) {
        final VlanVO vlan = resolvePublicVlan(zoneId, vpcId);
        if (vlan == null || StringUtils.isBlank(vlan.getVlanTag())) {
            return null;
        }
        final String tag = vlan.getVlanTag().replaceAll("(?i)^vlan://", "").trim();
        return tag.matches("\\d+") ? tag : null;
    }

    /**
     * The public network gateway IP (e.g. {@code "217.179.89.1"}) — the VPC
     * LR's default-route next-hop. There is no physical device on this address
     * in the BGP-to-host model; the gateway-chassis host must itself answer ARP
     * for it (hold it on {@code pub-anchor}) so VM egress lands on the host and
     * is forwarded upstream.
     *
     * @return the gateway IPv4, or {@code null} when not resolvable.
     */
    public String getVpcPublicNetworkGateway(final long zoneId, final long vpcId) {
        final VlanVO vlan = resolvePublicVlan(zoneId, vpcId);
        return (vlan == null || StringUtils.isBlank(vlan.getVlanGateway())) ? null : vlan.getVlanGateway();
    }

    /**
     * First public ({@link Vlan.VlanType#VirtualNetwork}) VLAN in the zone
     * whose gateway is within {@code subnetCidr}; {@code null} when none
     * matches (or the pool is IPv6-only, i.e. no IPv4 gateway).
     */
    private VlanVO findPublicVlanForSubnet(final long zoneId, final String subnetCidr) {
        final List<VlanVO> vlans = vlanDao.listByZoneAndType(zoneId, Vlan.VlanType.VirtualNetwork);
        if (vlans == null) {
            return null;
        }
        for (final VlanVO vlan : vlans) {
            final String gw = vlan.getVlanGateway();
            if (StringUtils.isNotBlank(gw) && NetUtils.isIpWithInCidrRange(gw, subnetCidr)) {
                return vlan;
            }
        }
        return null;
    }

    /** Parse {@code "start-end"} IPv4 range into {@code [startLong, endLong]}
     *  (ascending); {@code null} when malformed. */
    private static long[] parseIpRange(final String range) {
        final int dash = range.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        try {
            final long start = NetUtils.ip2Long(range.substring(0, dash).trim());
            final long end = NetUtils.ip2Long(range.substring(dash + 1).trim());
            return start <= end ? new long[] {start, end} : new long[] {end, start};
        } catch (RuntimeException re) {
            return null;
        }
    }

    /**
     * Drops the public RSP peer port, public LRP, and default route for a VPC
     * (called from VPC delete).
     *
     * <p>Enqueues every OVN row into {@code ovn_pending_deletion} BEFORE the
     * synchronous NB calls so the async retry queue holds the UUIDs even when
     * the sync delete fails.
     *
     * <p>Delete order is the reverse of {@link #bindVpcToPublic}: the RSP peer
     * LSP is dropped before the LRP it patches into, because
     * {@code deleteLogicalRouterPort} does not cascade to the peer port (no
     * OVSDB strong reference between an LRP and its peer LSP).
     */
    public void unbindVpcFromPublic(final long zoneId, final long vpcId, final String lrUuid) {
        unbindOwnerFromPublic(zoneId, vpcId, lrUuid,
                Kind.VPC_PUBLIC_LRP, Kind.VPC_PUBLIC_RSP, Kind.STATIC_ROUTE);
    }

    /**
     * Phase B — reverse of {@link #ensureIsolatedNetworkBoundToPublic}. Drops
     * the isolated network's default route, public peer RSP, and public LRP
     * (RSP before LRP — no cascade between them). Every ISOLATED_* row keyed
     * by {@code networkId}.
     */
    public void unbindIsolatedNetworkFromPublic(final long zoneId, final long networkId, final String lrUuid) {
        unbindOwnerFromPublic(zoneId, networkId, lrUuid,
                Kind.ISOLATED_PUBLIC_LRP, Kind.ISOLATED_PUBLIC_RSP, Kind.ISOLATED_STATIC_ROUTE);
    }

    /** Owner-agnostic public unbind. VPC + isolated callers feed their own Kind trio. */
    private void unbindOwnerFromPublic(final long zoneId, final long ownerId, final String lrUuid,
                                       final Kind lrpKind, final Kind rspKind, final Kind routeKind) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        // Route and RSP are reaped independently of the LRP mapping so a
        // half-torn-down bind (LRP mapping already gone, peers surviving)
        // is still fully cleaned synchronously instead of waiting for the
        // reconciler sweep. Every helper below is a no-op on a null mapping.
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        dropStaticRoute(nb, controller, zoneId, ownerId, lrUuid, routeKind);
        final OvnLogicalIdMapVO rspMapping = logicalIdMapDao.findByCsId(rspKind, ownerId, controller.getId());
        dropMappedRow(nb::deleteLogicalSwitchPort, controller, zoneId, ownerId, rspKind, rspMapping);
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(lrpKind, ownerId, controller.getId());
        if (dropMappedRow(nb::deleteLogicalRouterPort, controller, zoneId, ownerId, lrpKind, mapping)) {
            LOGGER.info("OvnPublicNetworkManager: unbound owner {} from public LS (lrp={})",
                    ownerId, mapping.getOvnUuid());
        }
    }

    /** Drops the default static route mapping for an owner (its own NB delete signature needs {@code lrUuid}). */
    private void dropStaticRoute(final OvnNbClient nb, final OvnControllerVO controller, final long zoneId,
                                 final long ownerId, final String lrUuid, final Kind routeKind) {
        final OvnLogicalIdMapVO routeMapping = logicalIdMapDao.findByCsId(routeKind, ownerId, controller.getId());
        if (routeMapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), zoneId, routeKind, routeMapping.getOvnUuid(), ownerId);
        try {
            nb.deleteLogicalRouterStaticRoute(lrUuid, routeMapping.getOvnUuid());
            logicalIdMapDao.remove(routeMapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(routeMapping.getOvnUuid(), routeKind.name());
        } catch (OvnException e) {
            LOGGER.warn("OvnPublicNetworkManager.unbind: route {} delete failed; mapping retained for retry: {}",
                    routeMapping.getOvnUuid(), e.getMessage());
        }
    }

    /**
     * Enqueues then deletes a single mapped OVN row via {@code nbDelete},
     * mirroring the enqueue-before-sync-delete contract shared by every
     * {@code unbind*} / {@code remove*} path in the plugin.
     *
     * @return {@code true} when the row existed and the NB delete succeeded.
     */
    private boolean dropMappedRow(final Consumer<String> nbDelete,
                                  final OvnControllerVO controller, final long zoneId, final long vpcId,
                                  final Kind kind, final OvnLogicalIdMapVO row) {
        if (row == null) {
            return false;
        }
        enqueueIfAbsent(controller.getId(), zoneId, kind, row.getOvnUuid(), vpcId);
        try {
            nbDelete.accept(row.getOvnUuid());
            logicalIdMapDao.remove(row.getId());
            pendingDeletionDao.markSucceededByOvnUuid(row.getOvnUuid(), kind.name());
            return true;
        } catch (OvnException e) {
            LOGGER.warn("OvnPublicNetworkManager.unbindVpc: {} {} delete failed; mapping retained for retry: {}",
                    kind, row.getOvnUuid(), e.getMessage());
            return false;
        }
    }

    /** Drops a stale paired mapping row (no NB call — the NB row is already gone). */
    private void dropStaleMapping(final OvnControllerVO controller, final Kind kind, final long vpcId) {
        final OvnLogicalIdMapVO stale = logicalIdMapDao.findByCsId(kind, vpcId, controller.getId());
        if (stale != null) {
            logicalIdMapDao.remove(stale.getId());
        }
    }

    private void enqueueIfAbsent(final long controllerId, final long zoneId, final Kind kind,
                                  final String ovnUuid, final long csId) {
        if (ovnUuid == null || ovnUuid.isEmpty()) {
            return;
        }
        if (pendingDeletionDao.isPendingByOvnUuid(ovnUuid, kind.name())) {
            return;
        }
        pendingDeletionDao.persist(new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), controllerId, zoneId, kind, ovnUuid, csId));
        LOGGER.info("OvnPublicNetworkManager: enqueued pending deletion kind={} ovn_uuid={} cs_id={}", kind, ovnUuid, csId);
    }

    /**
     * Strip the trailing CIDR mask off the first network entry to derive a
     * legal nexthop IP. {@code 192.168.100.10/24} → {@code 192.168.100.1};
     * for the simple case the caller supplies a single CIDR per LRP and the
     * static route just needs the first usable IP. Production deploys
     * override the static route via {@link OvnNbClient#addLogicalRouterStaticRoute}.
     */
    private static String pickGatewayFromNetworks(final List<String> networks) {
        if (networks == null || networks.isEmpty()) {
            return "0.0.0.0";
        }
        final String first = networks.get(0);
        if (first == null || !first.contains("/")) {
            return first;
        }
        final String addr = first.substring(0, first.indexOf('/'));
        // Naive .1 swap — stays within the same /24 for the lab default;
        // operators set explicit gateways via the BGP-shipping public IP.
        final int lastDot = addr.lastIndexOf('.');
        if (lastDot < 0) {
            return addr;
        }
        return addr.substring(0, lastDot + 1) + "1";
    }
}
