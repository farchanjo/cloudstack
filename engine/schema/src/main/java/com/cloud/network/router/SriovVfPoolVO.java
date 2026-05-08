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
 * Tracks a single SR-IOV Virtual Function on a host. Allocated to a NIC when
 * a VR (or other VM) with HW offload is plugged. Released when the NIC is
 * removed (FK ON DELETE SET NULL).
 * */
@Entity
@Table(name = "sriov_vf_pool")
public class SriovVfPoolVO implements InternalIdentity {

    /**
     * Pool-row lifecycle state.
     * <ul>
     *   <li>{@link #FREE}: VF is unallocated and available.
     *   <li>{@link #ALLOCATED}: VF is bound to a NIC ({@code allocated_to_nic_id}
     *       set) and the agent has confirmed it.
     *   <li>{@link #RESERVED}: VF is staged for allocation but not yet plugged
     *       (rare, used for two-phase allocation paths).
     *   <li>{@link #UNAVAILABLE}: VF cannot be used (driver bind error,
     *       hardware fault). Operator action required.
     *   <li>{@link #SUSPECT}: VF is allocated in the DB but the agent has not
     *       confirmed it for longer than {@code vf.pool.suspect.timeout.seconds}
     *       (host disconnect, VR fault, agent restart in progress). The mgmt
     *       reconciler raises an alert and waits for operator action; no
     *       auto-release.
     *   <li>{@link #ORPHAN_MANUAL}: vDPA SF reported by the agent that has no
     *       backing pool row — typically a manually-provisioned SF the
     *       operator set up before adopting the orchestrator. Synthetic rows
     *       are inserted in this state by the reconciler so the operator can
     *       see them in the UI and decide whether to release.
     * </ul>
     */
    public enum State {
        FREE, ALLOCATED, RESERVED, UNAVAILABLE, SUSPECT, ORPHAN_MANUAL
    }

    /**
     * How the VF is currently bound on the host.
     * <ul>
     *   <li>{@link #PASSTHROUGH}: VF is detached from the host and assigned
     *       directly to the guest via {@code <interface type='hostdev'>}.
     *   <li>{@link #VDPA}: a vDPA management device sits on top of the VF
     *       ({@code vdpa dev add ... mgmtdev pci/<vfPci>}). Guest sees a
     *       {@code <interface type='vdpa'>} pointing at /dev/vhost-vdpa-N.
     * </ul>
     * Default is {@link #PASSTHROUGH} for backward compatibility with rows
     * pre-dating Upgrade42422to42423.
     */
    public enum VdpaKind {
        PASSTHROUGH, VDPA
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "host_id", nullable = false)
    private long hostId;

    @Column(name = "pci_address", nullable = false)
    private String pciAddress;

    @Column(name = "pf_name", nullable = false)
    private String pfName;

    @Column(name = "representor_name")
    private String representorName;

    @Column(name = "state", nullable = false)
    private String state = State.FREE.name();

    @Column(name = "allocated_to_nic_id")
    private Long allocatedToNicId;

    /**
     * Backing kind of the VF binding. Default {@link VdpaKind#PASSTHROUGH}
     * for backward compatibility (rows pre-dating Upgrade42422to42423 have
     * the column populated by the SQL DEFAULT).
     */
    @Column(name = "vdpa_kind", nullable = false)
    private String vdpaKind = VdpaKind.PASSTHROUGH.name();

    /** vDPA mgmt-device name (e.g. {@code vdpa-vmA2}) when {@link #vdpaKind} == VDPA. */
    @Column(name = "vdpa_name")
    private String vdpaName;

    /** Host-side {@code /dev/vhost-vdpa-N} path bound to this VF (VDPA only). */
    @Column(name = "vdpa_device")
    private String vdpaDevice;

    @Column(name = "created", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created;

    @Column(name = "updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date updated;

    /**
     * Last time the host agent confirmed this VF in its inventory advertise.
     * Driven by {@code UpdateHostVfInventoryCommand} processing on the mgmt
     * side. The mgmt reconciler flips {@link State#ALLOCATED} rows to
     * {@link State#SUSPECT} when this column is older than
     * {@code vf.pool.suspect.timeout.seconds}. Null until the first inventory
     * arrives — fresh installs and pre-Phase-H.1 rows therefore start
     * unconfirmed and are included in the first sweep.
     */
    @Column(name = "last_seen")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastSeen;

    public SriovVfPoolVO() {
    }

    public SriovVfPoolVO(long hostId, String pciAddress, String pfName, String representorName) {
        this.hostId = hostId;
        this.pciAddress = pciAddress;
        this.pfName = pfName;
        this.representorName = representorName;
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

    public String getPciAddress() {
        return pciAddress;
    }

    public String getPfName() {
        return pfName;
    }

    public String getRepresentorName() {
        return representorName;
    }

    public void setRepresentorName(String representorName) {
        this.representorName = representorName;
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

    public String getVdpaKind() {
        return vdpaKind;
    }

    public VdpaKind getVdpaKindEnum() {
        return VdpaKind.valueOf(vdpaKind);
    }

    public void setVdpaKind(VdpaKind kind) {
        this.vdpaKind = kind.name();
        this.updated = new Date();
    }

    /**
     * String overload — REQUIRED for {@code createForUpdate()} proxy usage.
     *
     * <p>The proxy returned by {@code createForUpdate()} (CGLib) intercepts
     * setters and stores the raw argument in its diff map. When the diff is
     * later flushed via {@code update(id, vo)}, {@code GenericDaoBase} feeds
     * each captured value into a {@code PreparedStatement.setObject(...)}
     * call. If we hand the proxy a {@code VdpaKind} enum, the JDBC driver
     * falls back to Java object serialization and emits {@code \xAC\xED...}
     * bytes that MySQL utf8mb4 rejects with "Incorrect string value" — same
     * failure mode as the {@code state} column would have without its
     * String overload.
     *
     * <p>Callers using a real VO instance keep using
     * {@link #setVdpaKind(VdpaKind)}; callers using {@code createForUpdate()}
     * MUST pass {@code VdpaKind.VDPA.name()} via this overload.
     */
    public void setVdpaKind(String kind) {
        this.vdpaKind = kind;
        this.updated = new Date();
    }

    public String getVdpaName() {
        return vdpaName;
    }

    public void setVdpaName(String vdpaName) {
        this.vdpaName = vdpaName;
        this.updated = new Date();
    }

    public String getVdpaDevice() {
        return vdpaDevice;
    }

    public void setVdpaDevice(String vdpaDevice) {
        this.vdpaDevice = vdpaDevice;
        this.updated = new Date();
    }

    public Date getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Date lastSeen) {
        this.lastSeen = lastSeen;
    }
}
