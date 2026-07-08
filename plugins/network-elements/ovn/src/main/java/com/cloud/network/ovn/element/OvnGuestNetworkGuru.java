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

import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetwork.IsolationMethod;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.guru.GuestNetworkGuru;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;

/**
 * Owns the lifecycle of a per-tier OVN logical switch.
 *
 * <ul>
 *   <li>{@code design()} reuses {@link GuestNetworkGuru#design} to lay out
 *       the CloudStack {@code NetworkVO}.
 *   <li>{@code implement()} creates the LS in OVN and persists the mapping
 *       in {@link OvnLogicalIdMapDao}.
 *   <li>{@code shutdown()} / {@code trash()} delete the LS and the mapping.
 * </ul>
 *
 * <p>The CloudStack network only flows through the guru when the offering
 * was tagged {@code useOvn} (constant {@link OvnConstants#OFFERING_TAG}).
 * Without the tag the standard fork path keeps working untouched.
 */
public class OvnGuestNetworkGuru extends GuestNetworkGuru {

    private static final Logger LOGGER = LogManager.getLogger(OvnGuestNetworkGuru.class);

    @Inject
    protected NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    @Inject
    protected OvnPluginManager pluginManager;
    @Inject
    protected OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    protected OvnPendingDeletionDao pendingDeletionDao;

    public OvnGuestNetworkGuru() {
        // OVN encap is always Geneve regardless of CloudStack isolation label.
        // Accept both the plugin-native "OVN" method and pre-existing "VXLAN"
        // physical networks so operators can opt a tier into OVN by tagging the
        // network offering ({@link OvnConstants#OFFERING_TAG}) without having
        // to recreate the physical network.
        _isolationMethods = new IsolationMethod[]{
                new IsolationMethod(OvnConstants.ISOLATION_METHOD),
                new IsolationMethod("VXLAN"),
        };
    }

    @Override
    protected boolean canHandle(final NetworkOffering offering, final NetworkType networkType, final PhysicalNetwork physicalNetwork) {
        if (networkType != NetworkType.Advanced) {
            return false;
        }
        if (offering.getGuestType() != GuestType.Isolated) {
            return false;
        }
        if (!isMyTrafficType(offering.getTrafficType())) {
            return false;
        }
        if (!isMyIsolationMethod(physicalNetwork)) {
            return false;
        }
        if (!networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offering.getId(), Service.Connectivity)) {
            return false;
        }
        return networkOfferingServiceMapDao.isProviderForNetworkOffering(offering.getId(), Provider.Ovn);
    }

    /**
     * Extends the inherited {@link GuestNetworkGuru#design} with the IPv6
     * copy step the base implementation skips. The core auto-allocates a
     * /64 for Advanced+Isolated networks (see
     * {@code NetworkServiceImpl#preAllocateIpv6SubnetForNetwork}) and passes
     * it in via {@code userSpecified}; {@code updateNetworkDesignForIPv6IfNeeded}
     * copies {@code ip6Cidr}/{@code ip6Gateway} onto the designed
     * {@link NetworkVO} so {@code NetworkOrchestrator} persists it and calls
     * {@code Ipv6Service.assignIpv6SubnetToNetwork()}.
     */
    @Override
    public Network design(final NetworkOffering offering, final DeploymentPlan plan, final Network userSpecified,
                          final String name, final Long vpcId, final Account owner) {
        NetworkVO network = (NetworkVO) super.design(offering, plan, userSpecified, name, vpcId, owner);
        if (network == null) {
            return null;
        }
        return updateNetworkDesignForIPv6IfNeeded(network, userSpecified);
    }

    /**
     * Creates the OVN logical switch backing the given CloudStack network.
     * Idempotent: a second call for the same network returns the existing
     * UUID without touching OVN. Required for the network-element
     * {@code implement()} hook which fires on every VM start.
     *
     * @return the OVN UUID of the (possibly pre-existing) LS.
     */
    public String createLogicalSwitchFor(final Network network) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + network.getDataCenterId());
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when NB LS was deleted out-of-band.
            if (nb.rowExistsByUuid("Logical_Switch", existing.getOvnUuid())) {
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnGuestNetworkGuru: NETWORK mapping net={} -> {} stale; recreating",
                    network.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final Map<String, String> ext = buildExternalIds(network, Kind.NETWORK);
        final String uuid = nb.createLogicalSwitch(buildLsName(network), ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.NETWORK, network.getId(), controller.getId(), uuid, buildLsName(network)));
        // Enable IGMP/MLD snooping on every guest LS by default. Cuts the
        // broadcast tax on multicast-heavy guests (PIM/IGMPv3, mDNS/SSDP).
        // Cheap toggle; harmless when no multicast traffic exists.
        try {
            nb.lsSetMcastSnoop(uuid, true);
        } catch (OvnException e) {
            LOGGER.warn("OVN LS {} mcast_snoop toggle failed: {}", uuid, e.getMessage());
        }
        LOGGER.info("OVN LS {} created for network id={} name={}", uuid, network.getId(), network.getName());
        return uuid;
    }

    /**
     * Returns the OVN logical-switch UUID backing the given CloudStack
     * network, or {@code null} when no mapping exists yet. Read-only — does
     * not create the LS. Used by {@link OvnNetworkElement#prepare} to
     * resolve the parent LS before adding the per-NIC LSP.
     */
    public String findLogicalSwitchUuidFor(final Network network) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return null;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        return existing == null ? null : existing.getOvnUuid();
    }

    /**
     * Returns the deterministic OVN logical-switch name for the given
     * CloudStack network ({@code ls-<networkUuid>}). Useful for callers
     * that need to populate {@code NicTO.ovnLsName} without an OVN round
     * trip.
     */
    public String logicalSwitchNameFor(final Network network) {
        return buildLsName(network);
    }

    /**
     * Removes the OVN logical switch backing the given CloudStack network.
     *
     * <p>Enqueues the LS UUID into {@code ovn_pending_deletion} BEFORE the
     * synchronous NB call so the async retry queue holds the UUID even when
     * the sync delete fails. If the sync delete succeeds, the row is marked
     * succeeded so the processor does not retry a no-op.
     */
    public void deleteLogicalSwitchFor(final Network network) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), network.getDataCenterId(), Kind.NETWORK,
                mapping.getOvnUuid(), network.getId());
        try {
            pluginManager.nbClient(network.getDataCenterId()).deleteLogicalSwitch(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.NETWORK.name());
            LOGGER.info("OVN LS {} removed for network id={}", mapping.getOvnUuid(), network.getId());
        } catch (OvnException e) {
            // Mapping survives so reconciler + processor can retry.
            LOGGER.warn("OvnGuestNetworkGuru.deleteLogicalSwitchFor: LS {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
            throw e;
        }
    }

    private void enqueueIfAbsent(final long controllerId, final long zoneId, final Kind kind,
                                  final String ovnUuid, final Long csId) {
        if (ovnUuid == null || ovnUuid.isEmpty()) {
            return;
        }
        if (pendingDeletionDao.isPendingByOvnUuid(ovnUuid, kind.name())) {
            return;
        }
        pendingDeletionDao.persist(new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), controllerId, zoneId, kind, ovnUuid, csId));
        LOGGER.info("OvnGuestNetworkGuru: enqueued pending deletion kind={} ovn_uuid={} cs_id={}", kind, ovnUuid, csId);
    }

    private String buildLsName(final Network network) {
        return "ls-" + network.getUuid();
    }

    private Map<String, String> buildExternalIds(final Network network, final Kind kind) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, kind.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(network.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(network.getDataCenterId()));
        return ext;
    }
}
