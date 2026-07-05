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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionDaoImpl;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.Network;
import com.cloud.network.vpc.Vpc;

/**
 * VPC-level operations: create the OVN logical router on VPC create, bind a
 * tier (LS) to it, and delete on VPC remove.
 *
 * <p>This is the OVN counterpart of the CloudStack {@code VpcProvider}
 * SPI; rather than implementing the SPI directly (which has 30+ abstract
 * methods we do not need for the MVP), the class is invoked from the
 * higher-level orchestration on VPC events. The wiring with CloudStack
 * VPC manager events is layered on top in subsequent phases — the methods
 * below are what the import flow (Phase I.5) and the SourceNat / StaticNat
 * services consume right away.
 */
@Component
public class OvnVpcElement {

    private static final Logger LOGGER = LogManager.getLogger(OvnVpcElement.class);

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Creates the LR backing the given VPC. Idempotent — re-running on a
     * VPC that already has an LR returns the existing UUID.
     */
    public String createLogicalRouterFor(final Vpc vpc) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + vpc.getZoneId());
        }
        final OvnNbClient nb = pluginManager.nbClient(vpc.getZoneId());
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard: cluster recovery / partial cleanup may
            // have left a CS row pointing at a NB row that no longer exists.
            // Recreate transparently instead of returning a dead UUID.
            if (nb.rowExistsByUuid("Logical_Router", existing.getOvnUuid())) {
                // Rename re-sync — VpcProvider has no updateVpc callback,
                // so refresh LR.external_ids on every idempotent touch.
                // updateVPC name -> LR external_ids[cs_name] eventually.
                try {
                    nb.updateLogicalRouterExternalIds(existing.getOvnUuid(), buildExternalIds(vpc));
                } catch (OvnException e) {
                    LOGGER.warn("OvnVpcElement.createLogicalRouterFor: re-sync external_ids failed for VPC {}: {}",
                            vpc.getId(), e.getMessage());
                }
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnVpcElement.createLogicalRouterFor: mapping VPC={} -> {} stale (NB gone); recreating",
                    vpc.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final String uuid = nb.createLogicalRouter(buildLrName(vpc), buildExternalIds(vpc));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC, vpc.getId(), controller.getId(), uuid, buildLrName(vpc)));
        LOGGER.info("OVN LR {} created for VPC id={} name={}", uuid, vpc.getId(), vpc.getName());
        return uuid;
    }

    /**
     * Cascade-removes the LR backing the given VPC + every CloudStack-managed
     * dependency: NAT rules, static_routes, attached load_balancers, public
     * LRPs. The {@code Logical_Router} schema marks {@code ports / nat /
     * static_routes} as strong refs (OVSDB cascades on parent delete), but
     * {@code load_balancer} is a weak ref — explicitly detach LBs first to
     * avoid leaving them attached to a dead LR.
     *
     * <p>When no controller is registered yet (controller == null), the LR
     * UUID (from the mapping row if it exists) is enqueued into
     * {@code ovn_pending_deletion} with the zone-sentinel
     * ({@code controller_id = 0}) so the background processor retries as
     * soon as a controller becomes available.
     *
     * <p>When a controller IS registered, the real OVN UUID is enqueued BEFORE
     * the synchronous NB delete so the async retry queue always holds the UUID
     * even when the sync delete fails or when the mapping row is wiped by a
     * concurrent cleanup path. The processor's idempotent retry absorbs the
     * success case (row already gone) gracefully.
     *
     * <p>Mapping row for the VPC LR is removed ONLY after a successful NB
     * delete. If the NB delete throws, the mapping survives so the reconciler
     * can detect the stale pair on its next pass and the processor can retry
     * via the pending-deletion queue.
     *
     * <p>Mapping rows in {@code ovn_logical_id_map} for this VPC's children
     * (PUBLIC_LRP, STATIC_ROUTE, SOURCE_NAT, STATIC_NAT, PORT_FORWARDING,
     * LOAD_BALANCER) are dropped on a best-effort basis after a successful
     * LR delete (OVN cascade already cleaned the NB rows).
     */
    public void deleteLogicalRouterFor(final Vpc vpc) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            enqueueLrDeletionNoController(vpc);
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), vpc.getZoneId(), Kind.VPC, mapping.getOvnUuid(), vpc.getId());
        detachLbsFromLr(mapping.getOvnUuid(), controller);
        deleteLrAndCleanup(vpc, mapping, controller);
    }

    private void enqueueLrDeletionNoController(final Vpc vpc) {
        // Try to find any mapping row across controllers using findByOvnUuid
        // round-trip is not possible without a controller; scan by VPC id
        // across all controllers is expensive. Best approach: log + record
        // in the queue with sentinel. If the mapping exists for any controller
        // we add a sentinel row so the processor resolves it later.
        LOGGER.warn("OvnVpcElement.deleteLogicalRouterFor: no OVN controller for zone {}; "
                + "queuing LR deletion for VPC id={}", vpc.getZoneId(), vpc.getId());
        // We do not have a controller to resolve the mapping against; insert a
        // sentinel row with ovn_uuid = "RESOLVE:" + vpc.getId() so the
        // processor can look it up when a controller becomes available.
        final String syntheticKey = "RESOLVE:vpc:" + vpc.getId();
        if (!pendingDeletionDao.isPendingByOvnUuid(syntheticKey, Kind.VPC.name())) {
            final OvnPendingDeletionVO entry = new OvnPendingDeletionVO(
                    UUID.randomUUID().toString(),
                    OvnPendingDeletionDaoImpl.CONTROLLER_SENTINEL,
                    vpc.getZoneId(),
                    Kind.VPC,
                    syntheticKey,
                    vpc.getId());
            pendingDeletionDao.persist(entry);
        }
    }

    private void detachLbsFromLr(final String lrUuid, final OvnControllerVO controller) {
        final OvnNbClient nb = pluginManager.nbClient(controller.getZoneId());
        for (final String lbUuid : nb.listLoadBalancersOnLogicalRouter(lrUuid)) {
            try {
                nb.detachLoadBalancerFromLogicalRouter(lrUuid, lbUuid);
            } catch (OvnException e) {
                LOGGER.warn("OvnVpcElement.deleteLR: detach LB {} from LR {} failed: {}",
                        lbUuid, lrUuid, e.getMessage());
            }
        }
    }

    private void deleteLrAndCleanup(final Vpc vpc, final OvnLogicalIdMapVO mapping, final OvnControllerVO controller) {
        final OvnNbClient nb = pluginManager.nbClient(vpc.getZoneId());
        try {
            nb.deleteLogicalRouter(mapping.getOvnUuid());
            // Only remove the mapping row after a confirmed successful NB delete
            // so the reconciler retains the stale-mapping evidence on failure.
            logicalIdMapDao.remove(mapping.getId());
            // Mark pending-deletion row as succeeded so the processor does not retry a no-op.
            markPendingSucceeded(mapping.getOvnUuid(), Kind.VPC);
            // Best-effort sweep child mapping rows; OVN cascade already dropped NB rows.
            sweepChildMappings(vpc.getId(), controller.getId(),
                    Kind.PUBLIC_LRP, Kind.VPC_PUBLIC_LRP, Kind.STATIC_ROUTE,
                    Kind.SOURCE_NAT, Kind.VPC_SOURCE_NAT,
                    Kind.STATIC_NAT, Kind.PORT_FORWARDING, Kind.LOAD_BALANCER);
            LOGGER.info("OVN LR {} removed for VPC id={} (cascade)", mapping.getOvnUuid(), vpc.getId());
        } catch (OvnException e) {
            // Do NOT remove the mapping row: leave it for the reconciler + processor.
            LOGGER.warn("OvnVpcElement.deleteLR: LR {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
            throw e;
        }
    }

    /**
     * Enqueue a pending deletion if no live row for the same UUID+kind exists.
     * Idempotency guard prevents double-enqueue on concurrent calls.
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
        LOGGER.info("OvnVpcElement: enqueued pending deletion kind={} ovn_uuid={} cs_id={}", kind, ovnUuid, csId);
    }

    /**
     * Marks the pending-deletion row for the given OVN UUID + kind as succeeded
     * when the synchronous NB delete completed so the processor does not retry a no-op.
     * Best-effort — if no row exists the call is a no-op.
     */
    private void markPendingSucceeded(final String ovnUuid, final Kind kind) {
        if (ovnUuid == null || ovnUuid.isEmpty()) {
            return;
        }
        pendingDeletionDao.markSucceededByOvnUuid(ovnUuid, kind.name());
    }

    /**
     * Drop every mapping row whose {@code cs_id} matches the given VPC id
     * across the supplied {@code Kind}s. Cheap because the table is small
     * (one row per OVN child entity).
     */
    private void sweepChildMappings(final long csId, final long controllerId, final Kind... kinds) {
        for (final Kind kind : kinds) {
            final OvnLogicalIdMapVO row = logicalIdMapDao.findByCsId(kind, csId, controllerId);
            if (row != null) {
                logicalIdMapDao.remove(row.getId());
            }
        }
    }

    /**
     * Connects an LR to an LS via an OVN router-patch pair.
     *
     * @param vpc        the VPC owning the LR
     * @param tierLsUuid the LS UUID returned by
     *                   {@code OvnGuestNetworkGuru.createLogicalSwitchFor()}
     * @param gatewayMac MAC address for the LRP gateway interface
     * @param networks   gateway networks (e.g.
     *                   {@code ["10.101.0.1/24"]})
     */
    public OvnNbClient.BindResult bindTierToVpc(final Vpc vpc, final String tierLsUuid, final String tierName,
                                                final String gatewayMac, final List<String> networks) {
        final OvnNbClient nb = pluginManager.nbClient(vpc.getZoneId());
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + vpc.getZoneId());
        }
        final String lrUuid = createLogicalRouterFor(vpc);
        final String lrpName = "lrp-" + tierName;
        final String lspName = "rsp-" + tierName;
        return nb.bindLrToLs(new OvnNbClient.BindRequest(lrUuid, tierLsUuid, lrpName, gatewayMac, networks, lspName));
    }

    // ------------------------------------------------------------------
    // Phase B — standalone (non-VPC) Isolated network L3.
    //
    // A per-network Logical_Router keyed by Kind.NETWORK_LR (name
    // "lr-net-<networkUuid>", never clashing with a VPC "lr-<vpcUuid>").
    // These methods mirror their VPC counterparts exactly but never touch
    // the Kind.VPC id space or the VPC sweep list, so the VPC datapath is
    // byte-for-byte unchanged.
    // ------------------------------------------------------------------

    /**
     * Creates the per-network LR backing a standalone Isolated network.
     * Idempotent — re-running on a network that already has an LR returns the
     * existing UUID (and re-syncs {@code external_ids}), mirroring
     * {@link #createLogicalRouterFor(Vpc)}.
     */
    public String createLogicalRouterForNetwork(final Network network) {
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final String lrName = buildNetworkLrName(network);
        final Map<String, String> ext = buildNetworkExternalIds(network);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.NETWORK_LR, network.getId(), controller.getId());
        if (existing != null) {
            if (nb.rowExistsByUuid("Logical_Router", existing.getOvnUuid())) {
                try {
                    nb.updateLogicalRouterExternalIds(existing.getOvnUuid(), ext);
                } catch (OvnException e) {
                    LOGGER.warn("OvnVpcElement.createLogicalRouterForNetwork: re-sync external_ids failed for network {}: {}",
                            network.getId(), e.getMessage());
                }
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnVpcElement.createLogicalRouterForNetwork: mapping network={} -> {} stale (NB gone); recreating",
                    network.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final String uuid = nb.createLogicalRouter(lrName, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.NETWORK_LR, network.getId(), controller.getId(), uuid, lrName));
        LOGGER.info("OVN LR {} created for isolated network id={} name={}", uuid, network.getId(), network.getName());
        return uuid;
    }

    /**
     * Connects the per-network LR to the network's own Logical_Switch via a
     * router-patch pair carrying the tier gateway IP/MAC. Isolated analogue of
     * {@link #bindTierToVpc}, but the caller supplies the resolved LR UUID
     * (created by {@link #createLogicalRouterForNetwork}).
     */
    public OvnNbClient.BindResult bindNetworkGateway(final Network network, final String lrUuid,
                                                     final String netLsUuid, final String tierName,
                                                     final String gatewayMac, final List<String> networks) {
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        final String lrpName = "lrp-" + tierName;
        final String lspName = "rsp-" + tierName;
        return nb.bindLrToLs(new OvnNbClient.BindRequest(lrUuid, netLsUuid, lrpName, gatewayMac, networks, lspName));
    }

    /**
     * Cascade-removes the per-network LR + its OVN children (gateway LRP,
     * public LRP, default route, snat — all strong refs on the LR) and sweeps
     * the isolated child mapping rows. Mirrors {@link #deleteLogicalRouterFor}
     * but scoped entirely to the Kind.NETWORK_LR / ISOLATED_* id space, so no
     * VPC row is ever touched.
     */
    public void deleteLogicalRouterForNetwork(final Network network) {
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.NETWORK_LR, network.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), zoneId, Kind.NETWORK_LR, mapping.getOvnUuid(), network.getId());
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        try {
            nb.deleteLogicalRouter(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            markPendingSucceeded(mapping.getOvnUuid(), Kind.NETWORK_LR);
            sweepChildMappings(network.getId(), controller.getId(),
                    Kind.NETWORK_GW_LRP, Kind.ISOLATED_PUBLIC_LRP, Kind.ISOLATED_PUBLIC_RSP,
                    Kind.ISOLATED_STATIC_ROUTE, Kind.SOURCE_NAT);
            LOGGER.info("OVN LR {} removed for isolated network id={} (cascade)", mapping.getOvnUuid(), network.getId());
        } catch (OvnException e) {
            LOGGER.warn("OvnVpcElement.deleteLogicalRouterForNetwork: LR {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
            throw e;
        }
    }

    private String buildNetworkLrName(final Network network) {
        return "lr-net-" + network.getUuid();
    }

    private Map<String, String> buildNetworkExternalIds(final Network network) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.NETWORK_LR.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(network.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(network.getDataCenterId()));
        if (network.getName() != null) {
            ext.put("cs_name", network.getName());
        }
        return ext;
    }

    private String buildLrName(final Vpc vpc) {
        return "lr-" + vpc.getUuid();
    }

    private Map<String, String> buildExternalIds(final Vpc vpc) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.VPC.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(vpc.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(vpc.getZoneId()));
        // Carry the live VPC name into LR.external_ids[cs_name]. Refreshed
        // on every idempotent createLogicalRouterFor invocation so that a
        // CloudStack updateVPC that changed the name shows up in NB DB on
        // the next plugin operation (no dedicated rename hook in
        // VpcProvider — see OvnNetworkElement docs for #2 update gap).
        if (vpc.getName() != null) {
            ext.put("cs_name", vpc.getName());
        }
        return ext;
    }
}
