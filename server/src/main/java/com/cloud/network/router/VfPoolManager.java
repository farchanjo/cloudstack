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

import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.utils.component.Manager;

/**
 * Service responsible for inventorying and allocating SR-IOV Virtual Functions
 * for VRs (and other VMs) that need HW offload.
 *
 * <p>VFs are discovered from each host via the agent's startup capability report
 * (host_details["sriov.vfs.<pf>"]) and mirrored into the {@code sriov_vf_pool} table.
 *
 * <p>Allocation is per-NIC: when a VR with HW offload is created, this manager picks
 * a FREE VF on the chosen host and binds it to the NIC. Failure to allocate raises
 * {@link InsufficientCapacityException} so the orchestrator can retry on another host
 * or fall back to SW VR (depending on offering settings).
 * */
public interface VfPoolManager extends Manager {

    // Dynamic: plan/approval/apply gates must take effect on the next singleton sweep
    // without a management restart. Defaults stay fail-closed. Do not cache these
    // values outside ConfigKey; always re-read via .value() on each gate evaluation.
    ConfigKey<Boolean> LegacyBroadVfOperationsEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "vf.legacy.broad.operations.enabled", "false",
            "Emergency compatibility gate for legacy broad VF release/recovery. Keep false during rolling rollout.",
            true);

    ConfigKey<Boolean> OwnershipRepairPlanEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "vf.ownership.repair.plan.enabled", "false",
            "Build and log an exact non-mutating VF ownership repair plan. Does not authorize apply.", true);

    ConfigKey<Boolean> OwnershipRepairApplyEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "vf.ownership.repair.apply.enabled", "false",
            "Allow application of an exact separately approved VF ownership repair plan.", true);

    ConfigKey<Integer> OwnershipRepairApprovedCount = new ConfigKey<>("Advanced", Integer.class,
            "vf.ownership.repair.approved.count", "0", "Exact approved reconciliation candidate count.", true);

    ConfigKey<String> OwnershipRepairApprovedIds = new ConfigKey<>("Advanced", String.class,
            "vf.ownership.repair.approved.ids", "", "Sorted comma-separated exact approved candidate ids.", true);

    ConfigKey<String> OwnershipRepairApprovedHash = new ConfigKey<>("Advanced", String.class,
            "vf.ownership.repair.approved.hash", "", "Exact SHA-256 hash of the approved repair plan.", true);

    ConfigKey<String> OwnershipRepairApprovalToken = new ConfigKey<>("Advanced", String.class,
            "vf.ownership.repair.approval.token", "", "Exact token emitted with the approved repair plan.", true);

    ConfigKey<String> OwnershipRepairIncidentId = new ConfigKey<>("Advanced", String.class,
            "vf.ownership.repair.incident.id", "", "Exact one-time internal incident plan ID; never generic authorization.", true);

    /**
     * Discover VFs reported by the host (called by the StartupRoutingCommand processor)
     * and reconcile against the pool table. Idempotent.
     */
    void registerHostVfs(long hostId, String pfName, int totalVfs, java.util.List<String> pciAddresses);

    /**
     * Reflect PF physical carrier into the pool. When {@code carrierUp} is
     * false, FREE rows for {@code pfName} become UNAVAILABLE so allocate()
     * will not hand out VFs on a dead uplink (e.g. LACP partner missing).
     * When carrier returns, UNAVAILABLE rows for that PF return to FREE.
     * ALLOCATED / SUSPECT rows are left alone (live VMs must keep their binding).
     */
    void setPfCarrierAvailability(long hostId, String pfName, boolean carrierUp);

    /**
     * Allocate one FREE VF on the given host and bind it to the NIC.
     *
     * @return the allocated VF row.
     * @throws InsufficientCapacityException when no FREE VF is available on the host.
     */
    SriovVfPoolVO allocate(long hostId, long nicId) throws InsufficientCapacityException;

    /** Quarantine, clean by exact BDF, and release only after positive agent evidence. */
    boolean release(long vfPoolId);

    /** Apply the same exact-evidence release protocol to rows bound to one NIC. */
    boolean releaseByNicId(long nicId);

    /** Atomically commit all destination VF reservations for one authoritative VM work item. */
    void commitOwnershipForVm(long vmId, Long expectedSourceHostId, long destinationHostId, String workId);

    /**
     * Roll back exact destination reservations. When cleanup is uncertain the
     * rows become SUSPECT instead of being guessed FREE.
     */
    void rollbackReservationsForVm(long vmId, long destinationHostId, boolean cleanupAuthorized, String workId);

    /** Roll back a failed start attempt, including its first canonical allocation. */
    void rollbackStartAttemptForVm(long vmId, long destinationHostId, boolean cleanupAuthorized, String workId);

    /**
     * Mark every active/reserved VF row of the VM SUSPECT. This is safe for
     * failed-start and expunge paths that no longer have enough exact host
     * evidence to release hardware.
     *
     * @return number of rows released to FREE after exact cleanup. Rows that
     * remain SUSPECT are unresolved and are not counted.
     */
    int quarantineByVmId(long vmId);

    /**
     * Quarantine stuck ALLOCATED VFs whose {@code allocated_to_nic_id} points at
     * a NIC that has been removed (or whose VM has been removed). Safety net for
     * cases where the postStateTransition listener missed its window (race, crash,
     * mgmt restart between DestroyRequested and ExpungeOperation).
     *
     * @return number of VFs marked SUSPECT.
     */
    int sweepOrphans();

    /** Count of FREE VFs on a host (used for capacity scheduling). */
    int countFree(long hostId);

    /**
     * Count FREE rows eligible for a vDPA allocation. This advisory value is
     * checked before migration; the atomic allocation remains the hard gate.
     */
    int countFreeForVdpa(long hostId);

    /**
     * Allocate one FREE VF on the given host and bind it to the NIC as a vDPA
     * mgmt-device. The row is flagged {@code vdpa_kind=VDPA} and a canonical
     * {@code vdpa_name} ({@code vdpa-<nicId>}) is stamped on it. The agent
     * later runs {@code vdpa dev add ... mac <mac> max_vqs <maxVqs>} and
     * patches {@code vdpa_device} ({@code /dev/vhost-vdpa-N}) once the SF
     * comes up.
     *
     * @return the allocated VF row, or {@code null} when capacity is exhausted.
     */
    SriovVfPoolVO allocateForVdpa(long hostId, long nicId, String mac, int maxVqs);

    /**
     * Release a vDPA-bound VF: clear vdpa_name / vdpa_device, flip
     * {@link com.cloud.network.router.SriovVfPoolVO.VdpaKind} back to
     * {@link com.cloud.network.router.SriovVfPoolVO.VdpaKind#PASSTHROUGH},
     * and free the row. Idempotent.
     */
    boolean releaseVdpa(long vfPoolId);

    /**
     * Mark every {@link com.cloud.network.router.SriovVfPoolVO.State#ALLOCATED}
     * row owned by the host as
     * {@link com.cloud.network.router.SriovVfPoolVO.State#SUSPECT}. Used when
     * the host disconnects: operator must inspect and force-release. No
     * auto-release. Returns the number of rows affected.
     */
    int markSuspectByHostId(long hostId);

    /**
     * Legacy admin entry point. It is default-off and, even when explicitly
     * enabled for compatibility, only quarantines rows. Ownership repair must
     * pass the leader/GlobalLock/exact-plan gate.
     */
    int forceReleaseByHostId(long hostId);

    /**
     * Deactivated legacy recovery entry point. Returns zero; operators must
     * use an exact approved repair plan instead of a broad DB-only promotion.
     */
    int recoverByHostId(long hostId);
}
