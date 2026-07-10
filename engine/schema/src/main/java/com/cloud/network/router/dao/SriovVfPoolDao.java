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
package com.cloud.network.router.dao;

import java.util.List;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.utils.db.GenericDao;

public interface SriovVfPoolDao extends GenericDao<SriovVfPoolVO, Long> {

    /** All VFs on a host, regardless of state. */
    List<SriovVfPoolVO> listByHost(long hostId);

    /** VFs in a specific state on a host. */
    List<SriovVfPoolVO> listByHostAndState(long hostId, State state);

    /** Lookup by PCI address (unique per host). */
    SriovVfPoolVO findByHostAndPci(long hostId, String pciAddress);

    /**
     * Atomically take a free VF on the host and bind it to a NIC as hostdev
     * PASSTHROUGH ({@code vdpa_kind=PASSTHROUGH}, blank vdpa_name/device,
     * {@code updated} bumped). Returns null if no FREE VF is available.
     * Caller is responsible for calling release on failure of subsequent steps.
     */
    SriovVfPoolVO allocate(long hostId, long nicId);

    /**
     * Release a VF back to the FREE pool: clear nic binding, blank
     * {@code vdpa_name}/{@code vdpa_device}, force {@code vdpa_kind=PASSTHROUGH},
     * bump {@code updated}. Idempotent.
     */
    boolean release(long vfPoolId);

    /**
     * Release VF by NIC id (used when NIC is being removed). Same free-state
     * wipe as {@link #release(long)} (including vdpa_* blanking).
     */
    boolean releaseByNicId(long nicId);

    /**
     * Release every VF whose {@code allocated_to_nic_id} refers to any NIC of
     * the given VM. Uses a JOIN into {@code nics} so removed NIC rows
     * ({@code nics.removed IS NOT NULL}) are still matched — covers the case
     * where a listener has already removed NICs before our hook ran. Also
     * blanks vdpa_* and forces {@code vdpa_kind=PASSTHROUGH}.
     *
     * @return number of rows updated.
     */
    int releaseByVmId(long vmId);

    /**
     * Sweep ALLOCATED VFs whose {@code allocated_to_nic_id} points at a NIC
     * that has {@code nics.removed IS NOT NULL} or whose VM has been removed.
     * Returns the number of rows swept back to FREE (with vdpa_* wiped).
     * Used by the periodic orphan GC.
     */
    int sweepOrphans();

    /** Counts of FREE/ALLOCATED/RESERVED/UNAVAILABLE per host (for capacity reporting). */
    int countByHostAndState(long hostId, State state);

    /**
     * Atomically take a free VF on the host, mark it as
     * {@link com.cloud.network.router.SriovVfPoolVO.VdpaKind#VDPA VDPA}, and
     * record the requested vDPA name + MAC. Returns null when no FREE VF is
     * available. The {@code allocated_to_nic_id} is set to {@code nicId} so
     * the row is also reachable via {@link #releaseByNicId} for normal
     * stop/migrate paths. {@code maxVqs} is captured separately on the
     * server-side wrapper so the agent can pass it to {@code vdpa dev add}.
     *
     * <p>Idempotent: if {@code (hostId, nicId)} already has an entry whose
     * vdpa_kind=VDPA, that row is returned unchanged.
     */
    SriovVfPoolVO allocateForVdpa(long hostId, long nicId, String mac, int maxVqs);

    /**
     * Find a FREE VF on the given host that is eligible for vDPA mgmt-device
     * binding. The server uses this when pre-allocating a VF before the VR
     * boots. Returns null when no FREE VF is available.
     */
    SriovVfPoolVO findFreeVdpaCapableVf(long hostId);

    /**
     * Release a vDPA-bound VF: clear vdpa_name / vdpa_device, flip
     * {@link com.cloud.network.router.SriovVfPoolVO.VdpaKind} back to
     * {@link com.cloud.network.router.SriovVfPoolVO.VdpaKind#PASSTHROUGH},
     * and {@link #release} the row. Idempotent.
     */
    boolean releaseVdpa(long vfPoolId);

    /**
     * Stamp {@code last_seen=NOW()} on the row identified by
     * {@code (hostId, pciAddress)}. Used by the mgmt-side reconciler when an
     * agent inventory advertise confirms the VF is still present. Idempotent;
     * returns {@code true} when the row was found and updated.
     */
    boolean touchLastSeen(long hostId, String pciAddress);

    /**
     * Flip every {@link com.cloud.network.router.SriovVfPoolVO.State#ALLOCATED}
     * row on the host to {@link com.cloud.network.router.SriovVfPoolVO.State#SUSPECT}.
     * Used when the host disconnects: the operator decides whether to force-
     * release. Idempotent. Returns the number of rows updated.
     */
    int markSuspectByHostId(long hostId);

    /**
     * Force every {@link com.cloud.network.router.SriovVfPoolVO.State#ALLOCATED}
     * or {@link com.cloud.network.router.SriovVfPoolVO.State#SUSPECT} row on the
     * host back to {@link com.cloud.network.router.SriovVfPoolVO.State#FREE},
     * clearing nic binding and vDPA fields. Used by the
     * {@code forceReleaseHostVfs} admin API. Returns the number of rows
     * released.
     */
    int forceReleaseByHostId(long hostId);

    /**
     * Re-bind every {@link com.cloud.network.router.SriovVfPoolVO.State#FREE}
     * pool entry on the host to the live NIC that still references it via
     * {@code nics.vf_pool_id}. Walks {@code nics} ⇒ {@code vm_instance}
     * filtering on live VMs ({@code v.removed IS NULL AND v.state IN
     * ('Running','Starting','Stopping','Migrating')}), flips the matching
     * pool row to {@link com.cloud.network.router.SriovVfPoolVO.State#ALLOCATED},
     * stamps {@code allocated_to_nic_id} from {@code nics.id}, and refreshes
     * {@code last_seen=NOW()}. Idempotent (no-op for already-allocated
     * rows).
     *
     * <p>Companion to {@link #forceReleaseByHostId(long)} — used by the
     * {@code recoverHostVfs} admin API to undo an over-zealous force-
     * release without bouncing live VMs / VRs.
     *
     * @return number of rows promoted from {@code FREE} to {@code ALLOCATED}.
     */
    int recoverByHostId(long hostId);

    /**
     * Find every {@link com.cloud.network.router.SriovVfPoolVO.State#ALLOCATED}
     * row whose {@code last_seen} is null or older than
     * {@code (NOW() - thresholdSeconds)}. Caller flips them to
     * {@link com.cloud.network.router.SriovVfPoolVO.State#SUSPECT}.
     */
    List<SriovVfPoolVO> findStaleAllocated(int thresholdSeconds);

    /**
     * Lookup by {@code (hostId, vdpaName)}. Returns null when no row matches.
     * Used by the reconciler when adopting a vDPA SF discovered on the host
     * — if a row already exists, mark it VDPA; otherwise insert a synthetic
     * {@link com.cloud.network.router.SriovVfPoolVO.State#ORPHAN_MANUAL} row.
     */
    SriovVfPoolVO findByHostAndVdpaName(long hostId, String vdpaName);
}
