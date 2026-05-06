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

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Emits an OVN {@code snat} rule when a tier attaches to the VPC public-side
 * LR. The OVN NAT rule maps the tier's CIDR to the LR's public-side gateway
 * IP. Mirrors the rule shape of the live {@code lr-test} lab on production:
 *
 * <pre>
 *   ovn-nbctl lr-nat-add lr-test snat 192.168.100.1 10.101.0.0/24
 * </pre>
 */
@Component
public class OvnSourceNatService {

    private static final Logger LOGGER = LogManager.getLogger(OvnSourceNatService.class);

    /** OVN NAT type for source NAT. */
    public static final String NAT_TYPE_SNAT = "snat";

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;

    /**
     * Adds an SNAT rule for the given tier CIDR.
     *
     * @param zoneId       CloudStack zone (selects the controller)
     * @param sourceTierId CloudStack network id (used to key the mapping row)
     * @param lrUuid       OVN LR UUID
     * @param externalIp   public-side gateway IP (e.g. {@code 192.168.100.1})
     * @param logicalIp    tier CIDR (e.g. {@code 10.101.0.0/24})
     */
    public String addSnat(final long zoneId, final long sourceTierId, final String lrUuid,
                          final String externalIp, final String logicalIp) {
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        final String natUuid = nb.addNatRule(lrUuid, NAT_TYPE_SNAT, externalIp, logicalIp, null);
        if (controller != null) {
            logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.SOURCE_NAT, sourceTierId, controller.getId(), natUuid,
                    NAT_TYPE_SNAT + "-" + sourceTierId));
        }
        LOGGER.info("OVN SNAT {} added: {} -> {} on LR {}", natUuid, logicalIp, externalIp, lrUuid);
        return natUuid;
    }

    /** Removes the SNAT rule recorded for the given tier. */
    public void removeSnatForTier(final long zoneId, final long sourceTierId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.SOURCE_NAT, sourceTierId, controller.getId());
        if (mapping == null) {
            return;
        }
        try {
            pluginManager.nbClient(zoneId).deleteNatRule(mapping.getOvnUuid());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
    }

    // ------------------------------------------------------------------
    // VPC-level SourceNAT — one snat row per VPC mapping the parent CIDR
    // (e.g. 10.235.0.0/16) to the VPC's source-NAT public IP. Keyed under
    // Kind.VPC_SOURCE_NAT so it never collides with the per-tier rows
    // emitted from applyIps when an IP is explicitly associated to a tier.
    // ------------------------------------------------------------------

    /**
     * Idempotent VPC-level SNAT writer. Re-running with the same external
     * IP returns the existing UUID; running with a new external IP updates
     * the NAT row in place (UUID stays, external_ip column changes) so the
     * LR.nat strong-ref set does not need to be rewritten.
     */
    public String ensureVpcSourceNat(final long zoneId, final long vpcId, final String lrUuid,
                                     final String externalIp, final String vpcCidr) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new com.cloud.network.ovn.client.OvnException("no OVN controller for zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC_SOURCE_NAT, vpcId, controller.getId());
        if (existing != null) {
            // Update path: rewrite external_ip / logical_ip in place. Cheap
            // (one OVSDB update; LR.nat reference stays valid).
            nb.updateNatRule(existing.getOvnUuid(), externalIp, vpcCidr);
            LOGGER.info("OVN VPC SNAT {} updated: {} -> {} on LR {}",
                    existing.getOvnUuid(), vpcCidr, externalIp, lrUuid);
            return existing.getOvnUuid();
        }
        final String natUuid = nb.addNatRule(lrUuid, NAT_TYPE_SNAT, externalIp, vpcCidr, null);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC_SOURCE_NAT, vpcId, controller.getId(), natUuid,
                NAT_TYPE_SNAT + "-vpc-" + vpcId));
        LOGGER.info("OVN VPC SNAT {} added: {} -> {} on LR {}", natUuid, vpcCidr, externalIp, lrUuid);
        return natUuid;
    }

    /** Removes the VPC-level SNAT row when the VPC is shut down or its
     *  source-NAT IP is released. Best-effort — caller already drops the
     *  containing LR via cascade in most flows. */
    public void removeVpcSourceNat(final long zoneId, final long vpcId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC_SOURCE_NAT, vpcId, controller.getId());
        if (mapping == null) {
            return;
        }
        try {
            pluginManager.nbClient(zoneId).deleteNatRule(mapping.getOvnUuid());
        } catch (RuntimeException e) {
            LOGGER.warn("OvnSourceNatService.removeVpcSourceNat: NAT {} delete failed: {}",
                    mapping.getOvnUuid(), e.getMessage());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
    }
}
