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
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
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
     * Allocate on a new host without stealing canonical ownership. When the
     * NIC already has a valid canonical row on another host, the destination
     * row is RESERVED and {@code nics.vf_pool_id} remains unchanged.
     */
    SriovVfPoolVO allocateOrReserve(long hostId, long nicId, VdpaKind kind, String vdpaName);

    /** All pool rows that currently reference one NIC, across hosts. */
    List<SriovVfPoolVO> listByNicId(long nicId);

    /**
     * Atomically commit every VF NIC of one VM. Lock order is VM, sorted NICs,
     * then sorted pool rows. The exact work id and destination are revalidated
     * before any row is changed; stale operation generations are refused.
     *
     * @return prior rows atomically marked SUSPECT for later exact cleanup.
     */
    List<SriovVfPoolVO> commitVmReservations(long vmId, Long expectedSourceHostId,
                                             long destinationHostId, String workId);

    /**
     * Atomically quarantine destination rows for an explicit VM operation.
     * RESERVED rows, and optionally first-start ALLOCATED rows, become SUSPECT
     * before any remote cleanup is attempted. Repeated calls are idempotent.
     */
    List<SriovVfPoolVO> quarantineVmDestinationRows(long vmId, long destinationHostId,
                                                    boolean includeAllocated, String workId);

    /** Mark one exact row SUSPECT while it remains bound to the expected NIC. */
    boolean markSuspect(long vfPoolId, long expectedNicId);

    /** Release one exact row and conditionally clear only its own reverse pointer. */
    boolean releaseExact(long vfPoolId, long expectedNicId);

    /**
     * Atomically revalidate the complete approved reconciliation plan under
     * sorted VM/NIC/pool locks, reject RESERVED/conflicting work, promote exact
     * current-host rows, and quarantine every stale target before host cleanup.
     */
    boolean prepareReconciliationPlan(List<VfReconciliationCandidate> candidates);

    /**
     * Recheck the same plan predicates after host cleanup and return only the
     * exact pre-quarantined stale row to FREE.
     */
    boolean completeReconciliation(long vmId, long nicId, long currentHostId,
                                   long currentPoolId, long stalePoolId);

    /**
     * Legacy DAO quarantine entry. It never returns a row to FREE; use
     * {@link #releaseExact(long, long)} after exact agent evidence.
     */
    boolean release(long vfPoolId);

    /**
     * Legacy DAO quarantine by NIC id. It never returns rows to FREE.
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
    int quarantineByVmId(long vmId);

    /**
     * Mark ALLOCATED VFs SUSPECT when their NIC or VM has been removed. Exact
     * agent cleanup must be confirmed before a row becomes FREE.
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
     * Legacy named vDPA quarantine entry; exact cleanup is still required.
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
     * Legacy host quarantine. No row is returned to FREE.
     */
    int forceReleaseByHostId(long hostId);

    /**
     * Deactivated broad recovery primitive; returns zero.
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
