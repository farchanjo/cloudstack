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
package com.cloud.network.ovn.manager;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnSbClient;
import com.cloud.network.ovn.client.OvnSbClient.ChassisRow;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;

/**
 * Maps a CloudStack {@code host} to the OVN {@code Chassis} the agent has
 * registered. Two flows are supported:
 *
 * <ul>
 *   <li>{@link #registerByHostname(long)} — looks the chassis up in the SB
 *       DB by the host's hostname. Suitable for the regular agent
 *       registration path; safe to call repeatedly because it persists
 *       only when the {@code chassis_uuid} actually changed.
 *   <li>{@link #recordChassisUuid(long, String)} — direct write when the
 *       caller already knows the system-id (e.g. read from
 *       {@code Open_vSwitch:external_ids:system-id} on the agent).
 * </ul>
 *
 * <p>The MVP wires this service from the admin import flow + tests; full
 * subscription to the CloudStack messaging bus events lands in a follow-up.
 */
@Component
public class OvnChassisRegistrationService {

    private static final Logger LOGGER = LogManager.getLogger(OvnChassisRegistrationService.class);

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnChassisMapDao chassisMapDao;
    @Inject
    private HostDao hostDao;

    /**
     * Reads the SB DB looking for a chassis whose {@code hostname} matches
     * the CloudStack host. Persists / updates the mapping row.
     *
     * @return the persisted mapping, or {@code null} when no chassis matches.
     */
    public OvnChassisMapVO registerByHostname(final long hostId) {
        final HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new OvnException("no CloudStack host with id=" + hostId);
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(host.getDataCenterId());
        if (controller == null) {
            LOGGER.debug("no OVN controller for zone {}; chassis registration skipped", host.getDataCenterId());
            return null;
        }
        final OvnSbClient sb = pluginManager.sbClient(host.getDataCenterId());
        final ChassisRow row = sb.findChassisByHostname(host.getName());
        if (row == null) {
            LOGGER.warn("no OVN chassis with hostname={} on controller id={}", host.getName(), controller.getId());
            return null;
        }
        return recordChassisUuid(hostId, controller.getId(), row.name);
    }

    /**
     * Persists or updates the {@code (host_id, chassis_uuid)} mapping. The
     * {@code chassis_uuid} stored is the OVN {@code Chassis.name} (the
     * system-id reported by {@code ovs-vsctl get open_vswitch . external_ids:system-id}),
     * not the row's primary {@code _uuid}.
     */
    public OvnChassisMapVO recordChassisUuid(final long hostId, final long controllerId, final String chassisUuid) {
        final OvnChassisMapVO existing = chassisMapDao.findByHostId(hostId);
        if (existing != null && chassisUuid.equals(existing.getChassisUuid())) {
            return existing;
        }
        if (existing != null) {
            existing.setChassisUuid(chassisUuid);
            existing.setControllerId(controllerId);
            chassisMapDao.update(hostId, existing);
            LOGGER.info("OVN chassis updated: host={} chassis={}", hostId, chassisUuid);
            return existing;
        }
        final OvnChassisMapVO row = new OvnChassisMapVO(hostId, controllerId, chassisUuid);
        chassisMapDao.persist(row);
        LOGGER.info("OVN chassis registered: host={} chassis={}", hostId, chassisUuid);
        return row;
    }

    /** Convenience overload when only host + chassis-uuid are known. */
    public OvnChassisMapVO recordChassisUuid(final long hostId, final String chassisUuid) {
        final HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new OvnException("no CloudStack host with id=" + hostId);
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(host.getDataCenterId());
        if (controller == null) {
            throw new OvnException("no OVN controller for zone " + host.getDataCenterId());
        }
        return recordChassisUuid(hostId, controller.getId(), chassisUuid);
    }
}
