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

import java.util.UUID;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Emits an OVN {@code dnat_and_snat} rule for a 1:1 floating IP. Mirrors the
 * lab rule on production:
 *
 * <pre>
 *   ovn-nbctl lr-nat-add lr-test dnat_and_snat 192.168.100.20 10.101.0.10
 * </pre>
 */
@Component
public class OvnStaticNatService {

    private static final Logger LOGGER = LogManager.getLogger(OvnStaticNatService.class);

    /** OVN NAT type for 1:1 floating-IP DNAT+SNAT. */
    public static final String NAT_TYPE_DNAT_AND_SNAT = "dnat_and_snat";

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Adds a 1:1 NAT rule mapping {@code externalIp} to {@code logicalIp}.
     *
     * @param zoneId    CloudStack zone (selects the controller)
     * @param ipAddrId  CloudStack IP-address id (mapping key)
     * @param lrUuid    OVN LR UUID
     * @param externalIp public-side IP (the floating IP)
     * @param logicalIp tier-side IP (the VM nic IP)
     * @param logicalPort optional OVN logical port name; OVN uses it for ARP
     *                    proxy and reverse path resolution
     */
    public String addStaticNat(final long zoneId, final long ipAddrId, final String lrUuid,
                               final String externalIp, final String logicalIp, final String logicalPort) {
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        final String natUuid = nb.addNatRule(lrUuid, NAT_TYPE_DNAT_AND_SNAT, externalIp, logicalIp, logicalPort);
        if (controller != null) {
            logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.STATIC_NAT, ipAddrId, controller.getId(), natUuid,
                    NAT_TYPE_DNAT_AND_SNAT + "-" + ipAddrId));
        }
        LOGGER.info("OVN dnat_and_snat {} added: {} <-> {} on LR {}", natUuid, externalIp, logicalIp, lrUuid);
        return natUuid;
    }

    /**
     * Removes the floating-IP rule recorded for the given IP-address id.
     *
     * <p>Enqueues the NAT UUID into {@code ovn_pending_deletion} BEFORE the
     * synchronous NB call so the async retry queue holds the UUID even when
     * the sync delete fails.
     */
    public void removeStaticNat(final long zoneId, final long ipAddrId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.STATIC_NAT, ipAddrId, controller.getId());
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        if (!pendingDeletionDao.isPendingByOvnUuid(mapping.getOvnUuid(), Kind.STATIC_NAT.name())) {
            pendingDeletionDao.persist(new OvnPendingDeletionVO(
                    UUID.randomUUID().toString(), controller.getId(), zoneId,
                    Kind.STATIC_NAT, mapping.getOvnUuid(), ipAddrId));
            LOGGER.info("OvnStaticNatService: enqueued pending deletion kind=STATIC_NAT ovn_uuid={} cs_id={}",
                    mapping.getOvnUuid(), ipAddrId);
        }
        try {
            pluginManager.nbClient(zoneId).deleteNatRule(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.STATIC_NAT.name());
        } catch (RuntimeException e) {
            // Mapping survives so reconciler + processor can retry.
            LOGGER.warn("OvnStaticNatService.removeStaticNat: NAT {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
            throw e;
        }
    }
}
