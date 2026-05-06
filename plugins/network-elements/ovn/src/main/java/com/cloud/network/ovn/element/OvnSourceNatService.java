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
}
