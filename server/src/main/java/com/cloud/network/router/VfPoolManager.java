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

    /**
     * Discover VFs reported by the host (called by the StartupRoutingCommand processor)
     * and reconcile against the pool table. Idempotent.
     */
    void registerHostVfs(long hostId, String pfName, int totalVfs, java.util.List<String> pciAddresses);

    /**
     * Allocate one FREE VF on the given host and bind it to the NIC.
     *
     * @return the allocated VF row.
     * @throws InsufficientCapacityException when no FREE VF is available on the host.
     */
    SriovVfPoolVO allocate(long hostId, long nicId) throws InsufficientCapacityException;

    /** Release a VF back to FREE. Idempotent. */
    boolean release(long vfPoolId);

    /** Release any VF currently bound to the NIC. Used when the NIC is being removed. */
    boolean releaseByNicId(long nicId);

    /**
     * Release every VF whose {@code allocated_to_nic_id} belongs to any NIC of the given VM.
     *
     * <p>Complements {@link #releaseByNicId(long)} for the VR-expunge path where the
     * caller only has the VM id and wants to guarantee no VF remains bound — even
     * if individual NIC rows are in a transient state or the listener missed some.
     *
     * @return number of VFs released (0 if none were bound).
     */
    int releaseByVmId(long vmId);

    /**
     * Garbage-collect stuck ALLOCATED VFs whose {@code allocated_to_nic_id} points at
     * a NIC that has been removed (or whose VM has been removed). Safety net for
     * cases where the postStateTransition listener missed its window (race, crash,
     * mgmt restart between DestroyRequested and ExpungeOperation).
     *
     * @return number of VFs swept back to FREE.
     */
    int sweepOrphans();

    /** Count of FREE VFs on a host (used for capacity scheduling). */
    int countFree(long hostId);

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
}
