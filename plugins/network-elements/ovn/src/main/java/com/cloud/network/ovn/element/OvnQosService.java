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
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.vm.NicProfile;

/**
 * Per-NIC bandwidth shaping via OVN's {@code QoS} table. One QoS row per NIC,
 * matched on the LSP's {@code inport}; bandwidth shapes both directions
 * (rate + burst kbps). DSCP marking is layered via the {@code action} map
 * when the offering details supply {@code ovn.qos.dscp}.
 *
 * <p>Mapping is keyed by {@link Kind#QOS} + NIC id so the cleanup path is
 * idempotent.
 */
@Component
public class OvnQosService {

    private static final Logger LOGGER = LogManager.getLogger(OvnQosService.class);

    /** OVN QoS priority for our network-rate rules. Below ACL/PF priorities. */
    public static final int QOS_PRIORITY = 1000;

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Apply (or replace) a QoS row for this NIC capped at the offering's
     * network rate (Mbps). A {@code null}/non-positive rate clears any
     * existing row.
     */
    public boolean applyQosForNic(final Network network, final NicProfile nic, final Integer rateMbps,
                                  final Integer dscp) {
        if (network == null || nic == null) {
            return false;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return false;
        }
        final OvnLogicalIdMapVO lspMapping = logicalIdMapDao.findByCsId(Kind.NIC, nic.getId(), controller.getId());
        if (lspMapping == null) {
            return false;
        }
        final OvnLogicalIdMapVO lsMapping = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        if (lsMapping == null) {
            return false;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        // Drop any pre-existing QoS row for this NIC (fast wipe + reinsert
        // keeps the bandwidth/DSCP swap atomic).
        removeQosForNic(network, nic);
        if ((rateMbps == null || rateMbps <= 0) && (dscp == null || dscp < 0)) {
            return true;
        }
        final String match = "inport == \"" + lspMapping.getOvnName() + "\"";
        final Map<String, Integer> bandwidth = new HashMap<>();
        if (rateMbps != null && rateMbps > 0) {
            // OVN expects kbps — convert mbps directly.
            bandwidth.put("rate", rateMbps * 1024);
            bandwidth.put("burst", rateMbps * 1024 * 2);
        }
        final Map<String, Integer> action = new HashMap<>();
        if (dscp != null && dscp >= 0) {
            action.put("dscp", dscp);
        }
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.QOS.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(nic.getId()));
        try {
            final String qosUuid = nb.addQosToLogicalSwitch(lsMapping.getOvnUuid(),
                    OvnNbClient.QOS_DIRECTION_FROM_LPORT, QOS_PRIORITY, match, bandwidth, action, ext);
            logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.QOS, nic.getId(), controller.getId(),
                    qosUuid, "qos-nic-" + nic.getId()));
            LOGGER.info("OvnQosService: QoS {} applied (nic id={} rate={}mbps dscp={})",
                    qosUuid, nic.getId(), rateMbps, dscp);
            return true;
        } catch (OvnException e) {
            LOGGER.error("OvnQosService.applyQosForNic failed nic id={}: {}", nic.getId(), e.getMessage());
            return false;
        }
    }

    /** Drop the QoS row associated with a NIC (release path). */
    public void removeQosForNic(final Network network, final NicProfile nic) {
        if (network == null || nic == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.QOS, nic.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        final OvnLogicalIdMapVO lsMapping = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        if (lsMapping == null) {
            removeOrphanQosRow(network.getDataCenterId(), controller, mapping);
            return;
        }
        try {
            pluginManager.nbClient(network.getDataCenterId())
                    .removeQosFromLogicalSwitch(lsMapping.getOvnUuid(), mapping.getOvnUuid());
        } catch (OvnException e) {
            LOGGER.warn("OvnQosService.removeQosForNic: {} delete failed: {}", mapping.getOvnUuid(), e.getMessage());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
    }

    /**
     * Direct-by-UUID cleanup for a QoS row whose owning tier LS mapping is
     * already gone. {@link OvnNbClient#removeQosFromLogicalSwitch} requires a
     * live {@code lsUuid} to detach the row from {@code Logical_Switch.qos_rules}
     * first; with the parent mapping dead there is no other reachable NB
     * delete path, so without this direct route the QoS row (and its
     * mapping) would linger forever. Enqueued into {@code ovn_pending_deletion}
     * BEFORE the sync attempt so async retry covers a failed direct delete
     * too — the mapping row is retained (not removed) on failure so the
     * retry queue and the DAO stay consistent.
     */
    private void removeOrphanQosRow(final long zoneId, final OvnControllerVO controller, final OvnLogicalIdMapVO mapping) {
        enqueueIfAbsent(controller.getId(), zoneId, mapping.getOvnUuid(), mapping.getCsId());
        try {
            pluginManager.nbClient(zoneId).deleteQosRowDirect(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.QOS.name());
        } catch (OvnException e) {
            LOGGER.warn("OvnQosService.removeQosForNic: orphan QoS {} direct delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
        }
    }

    private void enqueueIfAbsent(final long controllerId, final long zoneId, final String ovnUuid, final long csId) {
        if (ovnUuid == null || ovnUuid.isEmpty()) {
            return;
        }
        if (pendingDeletionDao.isPendingByOvnUuid(ovnUuid, Kind.QOS.name())) {
            return;
        }
        pendingDeletionDao.persist(new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), controllerId, zoneId, Kind.QOS, ovnUuid, csId));
        LOGGER.info("OvnQosService: enqueued pending deletion kind={} ovn_uuid={} cs_id={}", Kind.QOS, ovnUuid, csId);
    }
}
