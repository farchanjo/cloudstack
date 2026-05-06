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
import com.cloud.network.ovn.manager.OvnPluginManager;
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

    /**
     * Creates the LR backing the given VPC. Idempotent — re-running on a
     * VPC that already has an LR returns the existing UUID.
     */
    public String createLogicalRouterFor(final Vpc vpc) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + vpc.getZoneId());
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (existing != null) {
            return existing.getOvnUuid();
        }
        final OvnNbClient nb = pluginManager.nbClient(vpc.getZoneId());
        final String uuid = nb.createLogicalRouter(buildLrName(vpc), buildExternalIds(vpc));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC, vpc.getId(), controller.getId(), uuid, buildLrName(vpc)));
        LOGGER.info("OVN LR {} created for VPC id={} name={}", uuid, vpc.getId(), vpc.getName());
        return uuid;
    }

    /**
     * Removes the LR backing the given VPC. Cleans up the mapping row even
     * when the OVN call fails (best-effort delete).
     */
    public void deleteLogicalRouterFor(final Vpc vpc) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(vpc.getZoneId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        try {
            pluginManager.nbClient(vpc.getZoneId()).deleteLogicalRouter(mapping.getOvnUuid());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
        LOGGER.info("OVN LR {} removed for VPC id={}", mapping.getOvnUuid(), vpc.getId());
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

    private String buildLrName(final Vpc vpc) {
        return "lr-" + vpc.getUuid();
    }

    private Map<String, String> buildExternalIds(final Vpc vpc) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.VPC.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(vpc.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(vpc.getZoneId()));
        return ext;
    }
}
