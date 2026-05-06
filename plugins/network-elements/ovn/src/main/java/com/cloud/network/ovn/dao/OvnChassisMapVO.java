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
package com.cloud.network.ovn.dao;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/**
 * Maps a CloudStack {@code host} row to its OVN chassis UUID
 * ({@code Open_vSwitch:external_ids:system-id} on the agent).
 *
 * <p>One row per host; the chassis UUID is unique cluster-wide. Populated at
 * agent registration by {@code OvnChassisRegistrationListener} (Phase I.6).
 */
@Entity
@Table(name = "ovn_chassis_map")
public class OvnChassisMapVO {

    /**
     * Primary key — also the FK to {@code host.id}. JPA does not require a
     * separate {@code @Id} column, so the host id doubles as the row id.
     */
    @Id
    @Column(name = "host_id")
    private long hostId;

    @Column(name = "controller_id", nullable = false)
    private long controllerId;

    @Column(name = "chassis_uuid", nullable = false, length = 64)
    private String chassisUuid;

    @Column(name = "created", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();

    public OvnChassisMapVO() {
    }

    public OvnChassisMapVO(final long hostId, final long controllerId, final String chassisUuid) {
        this.hostId = hostId;
        this.controllerId = controllerId;
        this.chassisUuid = chassisUuid;
    }

    public long getHostId() {
        return hostId;
    }

    public void setHostId(final long hostId) {
        this.hostId = hostId;
    }

    public long getControllerId() {
        return controllerId;
    }

    public void setControllerId(final long controllerId) {
        this.controllerId = controllerId;
    }

    public String getChassisUuid() {
        return chassisUuid;
    }

    public void setChassisUuid(final String chassisUuid) {
        this.chassisUuid = chassisUuid;
    }

    public Date getCreated() {
        return created;
    }
}
