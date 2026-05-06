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

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetwork.IsolationMethod;
import com.cloud.network.guru.GuestNetworkGuru;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;

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

    public OvnGuestNetworkGuru() {
        _isolationMethods = new IsolationMethod[]{new IsolationMethod(OvnConstants.ISOLATION_METHOD)};
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
     * Creates the OVN logical switch backing the given CloudStack network.
     *
     * @return the OVN UUID of the created LS.
     */
    public String createLogicalSwitchFor(final Network network) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + network.getDataCenterId());
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        final Map<String, String> ext = buildExternalIds(network, Kind.NETWORK);
        final String uuid = nb.createLogicalSwitch(buildLsName(network), ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.NETWORK, network.getId(), controller.getId(), uuid, buildLsName(network)));
        LOGGER.info("OVN LS {} created for network id={} name={}", uuid, network.getId(), network.getName());
        return uuid;
    }

    /**
     * Removes the OVN logical switch backing the given CloudStack network.
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
        try {
            pluginManager.nbClient(network.getDataCenterId()).deleteLogicalSwitch(mapping.getOvnUuid());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
        LOGGER.info("OVN LS {} removed for network id={}", mapping.getOvnUuid(), network.getId());
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
