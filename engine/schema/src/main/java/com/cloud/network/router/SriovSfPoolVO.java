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
package com.cloud.network.router;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.api.InternalIdentity;

/**
 * Tracks a single SR-IOV Sub-Function on a host. Unlike VFs (static, firmware
 * provisioned), SFs are dynamic: created and destroyed at runtime via devlink.
 * SFs support vDPA for live migration of the datapath.
 *
 * <p>Lifecycle: FREE -> SF_CREATED -> VDPA_READY -> ALLOCATED -> DESTROYING -> (removed)
 *
 * <p>Allocated to a NIC when a VR (or other VM) with SF+vDPA is plugged.
 * Released when the NIC is removed (FK ON DELETE SET NULL).
 */
@Entity
@Table(name = "sriov_sf_pool")
public class SriovSfPoolVO implements InternalIdentity {

    public enum State {
        FREE, SF_CREATED, VDPA_READY, ALLOCATED, DESTROYING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "host_id", nullable = false)
    private long hostId;

    @Column(name = "pf_index", nullable = false)
    private int pfIndex;

    @Column(name = "sf_index", nullable = false)
    private int sfIndex;

    @Column(name = "devlink_port_handle")
    private String devlinkPortHandle;

    @Column(name = "sf_netdev_name")
    private String sfNetdevName;

    @Column(name = "representor_name")
    private String representorName;

    @Column(name = "vdpa_device")
    private String vdpaDevice;

    @Column(name = "state", nullable = false)
    private String state = State.FREE.name();

    @Column(name = "allocated_to_nic_id")
    private Long allocatedToNicId;

    @Column(name = "created", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created;

    @Column(name = "updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date updated;

    public SriovSfPoolVO() {
    }

    public SriovSfPoolVO(long hostId, int pfIndex, int sfIndex) {
        this.hostId = hostId;
        this.pfIndex = pfIndex;
        this.sfIndex = sfIndex;
        this.created = new Date();
    }

    @Override
    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public long getHostId() {
        return hostId;
    }

    public int getPfIndex() {
        return pfIndex;
    }

    public int getSfIndex() {
        return sfIndex;
    }

    public String getDevlinkPortHandle() {
        return devlinkPortHandle;
    }

    public void setDevlinkPortHandle(String devlinkPortHandle) {
        this.devlinkPortHandle = devlinkPortHandle;
    }

    public String getSfNetdevName() {
        return sfNetdevName;
    }

    public void setSfNetdevName(String sfNetdevName) {
        this.sfNetdevName = sfNetdevName;
    }

    public String getRepresentorName() {
        return representorName;
    }

    public void setRepresentorName(String representorName) {
        this.representorName = representorName;
    }

    public String getVdpaDevice() {
        return vdpaDevice;
    }

    public void setVdpaDevice(String vdpaDevice) {
        this.vdpaDevice = vdpaDevice;
    }

    public String getState() {
        return state;
    }

    public State getStateEnum() {
        return State.valueOf(state);
    }

    public void setState(State newState) {
        this.state = newState.name();
        this.updated = new Date();
    }

    public void setState(String state) {
        this.state = state;
        this.updated = new Date();
    }

    public Long getAllocatedToNicId() {
        return allocatedToNicId;
    }

    public void setAllocatedToNicId(Long allocatedToNicId) {
        this.allocatedToNicId = allocatedToNicId;
    }

    public Date getCreated() {
        return created;
    }

    public Date getUpdated() {
        return updated;
    }
}
