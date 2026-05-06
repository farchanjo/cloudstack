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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
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

    /**
     * Ensure the per-zone public LS + localnet LSP exist. Idempotent. Returns
     * the LS UUID. Optional VLAN tag carried as the localnet's
     * {@code tag} column when the public physnet is trunked.
     */
    public String ensurePublicLogicalSwitch(final long zoneId, final Integer publicVlanTag, final String physnetName) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnPublicNetworkManager: no OVN controller for zone " + zoneId);
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.PUBLIC_LS, zoneId, controller.getId());
        if (existing != null) {
            return existing.getOvnUuid();
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
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
        nb.addLocalnetPort(lsUuid, PUBLIC_LOCALNET_LSP, publicVlanTag, physnet);
        LOGGER.info("OvnPublicNetworkManager: created public LS {} (vlan={}, physnet={})",
                lsUuid, publicVlanTag, physnet);
        return lsUuid;
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
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.HA_CHASSIS_GROUP, zoneId, controller.getId());
        if (existing != null) {
            return existing.getOvnUuid();
        }
        final List<OvnChassisMapVO> chassis = chassisMapDao.listByController(controller.getId());
        if (chassis.isEmpty()) {
            throw new OvnException("OvnPublicNetworkManager: no OVN chassis registered for controller id="
                    + controller.getId());
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final List<String> haChassisUuids = new ArrayList<>(chassis.size());
        // Priority decreases by host id order so failover deterministically
        // picks the lower-id chassis when multiple are healthy.
        int prio = 100;
        for (final OvnChassisMapVO row : chassis) {
            final String hacUuid = nb.createHaChassis(row.getChassisUuid(), prio--);
            haChassisUuids.add(hacUuid);
        }
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.HA_CHASSIS_GROUP.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(zoneId));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(zoneId));
        final String hagName = "hag-public-z" + zoneId;
        final String hagUuid = nb.createHaChassisGroup(hagName, haChassisUuids, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.HA_CHASSIS_GROUP, zoneId, controller.getId(), hagUuid, hagName));
        LOGGER.info("OvnPublicNetworkManager: created HA_Chassis_Group {} with {} chassis (zone={})",
                hagUuid, haChassisUuids.size(), zoneId);
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
        final OvnNbClient.BindResult result = nb.bindLrToLs(req);
        nb.lrpSetHaChassisGroup(result.lrpUuid, hagUuid);
        // Default route so VPC traffic reaches upstream BGP fabric. The
        // nexthop is the first IP on the public network — caller controls.
        final String routeUuid = nb.addLogicalRouterStaticRoute(lrUuid, "0.0.0.0/0",
                pickGatewayFromNetworks(publicNetworks),
                req.lrpName, "dst-ip",
                Map.of(OvnConstants.EXT_ID_KIND, Kind.STATIC_ROUTE.name(),
                        OvnConstants.EXT_ID_ID, String.valueOf(vpcId)));
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId(),
                result.lrpUuid, req.lrpName));
        // Persist the static-route mapping so unbind can drop it cleanly.
        // Uses Kind.STATIC_ROUTE keyed by vpcId — one default route per VPC.
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.STATIC_ROUTE, vpcId, controller.getId(),
                routeUuid, "default-vpc" + vpcId));
        LOGGER.info("OvnPublicNetworkManager: bound VPC LR {} to public LS {} (lrp={} hag={})",
                lrUuid, publicLsUuid, result.lrpUuid, hagUuid);
        return result;
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
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId());
        if (existing != null) {
            return existing.getOvnUuid();
        }
        // Pre-create public LS with the supplied vlan/physnet so the localnet
        // port is correctly tagged on first creation. Subsequent calls hit the
        // idempotent path inside ensurePublicLogicalSwitch.
        ensurePublicLogicalSwitch(zoneId, publicVlanTag, physnetName);
        ensureHaChassisGroupForZone(zoneId);
        final OvnNbClient.BindResult bound = bindVpcToPublic(zoneId, lrUuid, publicMac, publicNetworks, vpcId);
        return bound.lrpUuid;
    }

    /** Drops the public LRP + default route for a VPC (called from VPC delete). */
    public void unbindVpcFromPublic(final long zoneId, final long vpcId, final String lrUuid) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC_PUBLIC_LRP, vpcId, controller.getId());
        if (mapping == null) {
            return;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        // Drop default static route first (uses its own mapping row).
        final OvnLogicalIdMapVO routeMapping = logicalIdMapDao.findByCsId(Kind.STATIC_ROUTE, vpcId, controller.getId());
        if (routeMapping != null) {
            try {
                nb.deleteLogicalRouterStaticRoute(lrUuid, routeMapping.getOvnUuid());
            } catch (OvnException e) {
                LOGGER.warn("OvnPublicNetworkManager.unbindVpc: route {} delete failed: {}",
                        routeMapping.getOvnUuid(), e.getMessage());
            } finally {
                logicalIdMapDao.remove(routeMapping.getId());
            }
        }
        try {
            nb.deleteLogicalRouterPort(mapping.getOvnUuid());
            LOGGER.info("OvnPublicNetworkManager: unbound VPC {} from public LS (lrp={})",
                    vpcId, mapping.getOvnUuid());
        } catch (OvnException e) {
            LOGGER.warn("OvnPublicNetworkManager.unbindVpc: VPC {} unbind failed: {}",
                    vpcId, e.getMessage());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
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
