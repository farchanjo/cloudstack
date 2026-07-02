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
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no controller for zone " + zoneId);
        }
        final String publicLsUuid = ensurePublicLogicalSwitch(zoneId, null, null);
        final String hagUuid = ensureHaChassisGroupForZone(zoneId);
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnNbClient.BindRequest req = new OvnNbClient.BindRequest(
                lrUuid, publicLsUuid,
                "lrp-public-vpc" + vpcId,
                publicMac,
                publicNetworks,
                "rsp-public-vpc" + vpcId);
        // Tag the peer LSP so OvnReconcilerService can classify it as a
        // VPC_PUBLIC_RSP row instead of an untaggable, invisible orphan.
        final OvnNbClient.BindResult result = nb.bindLrToLs(req, rspExternalIds(zoneId, vpcId));
        nb.lrpSetHaChassisGroup(result.lrpUuid, hagUuid);
        persistVpcPublicBind(controller, zoneId, vpcId, lrUuid, req, result);
        LOGGER.info("OvnPublicNetworkManager: bound VPC LR {} to public LS {} (lrp={} hag={})",
                lrUuid, publicLsUuid, result.lrpUuid, hagUuid);
        return result;
    }

    /** {@code external_ids} tag for the VPC public peer LSP (rsp-public-vpc&lt;id&gt;). */
    private Map<String, String> rspExternalIds(final long zoneId, final long vpcId) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.VPC_PUBLIC_RSP.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(vpcId));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(zoneId));
        return ext;
    }

    /**
     * Persists the default route plus the VPC_PUBLIC_LRP / VPC_PUBLIC_RSP
     * mapping rows created by {@link #bindVpcToPublic} so
     * {@link #unbindVpcFromPublic} can resolve and drop all three cleanly.
     */
    private void persistVpcPublicBind(final OvnControllerVO controller, final long zoneId, final long vpcId,
                                      final String lrUuid, final OvnNbClient.BindRequest req,
                                      final OvnNbClient.BindResult result) {
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        // Default route so VPC traffic reaches upstream BGP fabric. The
        // nexthop is the first IP on the public network — caller controls.
        final String routeUuid = nb.addLogicalRouterStaticRoute(lrUuid, "0.0.0.0/0",
                pickGatewayFromNetworks(req.lrpNetworks),
                req.lrpName, "dst-ip",
                Map.of(OvnConstants.EXT_ID_KIND, Kind.STATIC_ROUTE.name(),
                        OvnConstants.EXT_ID_ID, String.valueOf(vpcId)));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId(),
                result.lrpUuid, req.lrpName));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC_PUBLIC_RSP, vpcId, controller.getId(),
                result.lspUuid, req.lspName));
        // Persist the static-route mapping so unbind can drop it cleanly.
        // Uses Kind.STATIC_ROUTE keyed by vpcId — one default route per VPC.
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.STATIC_ROUTE, vpcId, controller.getId(),
                routeUuid, "default-vpc" + vpcId));
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
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no controller for zone " + zoneId);
        }
        final OvnNbClient nbExist = pluginManager.nbClient(zoneId);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId());
        if (existing != null) {
            if (nbExist.rowExistsByUuid("Logical_Router_Port", existing.getOvnUuid())) {
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnPublicNetworkManager: VPC_PUBLIC_LRP mapping vpc={} -> {} stale; recreating",
                    vpcId, existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
            // A stale public LRP implies the paired default route + peer RSP
            // may also have been GC'd by cascade.
            dropStaleMapping(controller, Kind.STATIC_ROUTE, vpcId);
            dropStaleMapping(controller, Kind.VPC_PUBLIC_RSP, vpcId);
        }
        // Pre-create public LS with the supplied vlan/physnet so the localnet
        // port is correctly tagged on first creation. Subsequent calls hit the
        // idempotent path inside ensurePublicLogicalSwitch.
        ensurePublicLogicalSwitch(zoneId, publicVlanTag, physnetName);
        ensureHaChassisGroupForZone(zoneId);
        final OvnNbClient.BindResult bound = bindVpcToPublic(zoneId, lrUuid, publicMac, publicNetworks, vpcId);
        return bound.lrpUuid;
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
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        // Route and RSP are reaped independently of the LRP mapping so a
        // half-torn-down bind (LRP mapping already gone, peers surviving)
        // is still fully cleaned synchronously instead of waiting for the
        // reconciler sweep. Every helper below is a no-op on a null mapping.
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        dropStaticRoute(nb, controller, zoneId, vpcId, lrUuid);
        final OvnLogicalIdMapVO rspMapping = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_RSP, vpcId, controller.getId());
        dropMappedRow(nb::deleteLogicalSwitchPort, controller, zoneId, vpcId, Kind.VPC_PUBLIC_RSP, rspMapping);
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId());
        if (dropMappedRow(nb::deleteLogicalRouterPort, controller, zoneId, vpcId, Kind.VPC_PUBLIC_LRP, mapping)) {
            LOGGER.info("OvnPublicNetworkManager: unbound VPC {} from public LS (lrp={})",
                    vpcId, mapping.getOvnUuid());
        }
    }

    /** Drops the default static route mapping for a VPC (its own NB delete signature needs {@code lrUuid}). */
    private void dropStaticRoute(final OvnNbClient nb, final OvnControllerVO controller, final long zoneId,
                                 final long vpcId, final String lrUuid) {
        final OvnLogicalIdMapVO routeMapping = logicalIdMapDao.findByCsId(Kind.STATIC_ROUTE, vpcId, controller.getId());
        if (routeMapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), zoneId, Kind.STATIC_ROUTE, routeMapping.getOvnUuid(), vpcId);
        try {
            nb.deleteLogicalRouterStaticRoute(lrUuid, routeMapping.getOvnUuid());
            logicalIdMapDao.remove(routeMapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(routeMapping.getOvnUuid(), Kind.STATIC_ROUTE.name());
        } catch (OvnException e) {
            LOGGER.warn("OvnPublicNetworkManager.unbindVpc: route {} delete failed; mapping retained for retry: {}",
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
