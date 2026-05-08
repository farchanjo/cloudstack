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
package com.cloud.agent.api;

import java.util.HashSet;
import java.util.Set;

/**
 * Purge orphan host-level NIC bindings on a target KVM agent. Sent by
 * {@code VfPoolManagerImpl.forceReleaseByHostId} after the DB pool rows
 * have been flipped to {@code FREE}, so kernel state is brought back
 * into agreement with the management database.
 *
 * <p>Two cleanup paths run inside one round-trip:
 *
 * <ul>
 *   <li><b>vDPA</b> — every {@code vdpa dev show} entry whose name is NOT
 *       in {@link #keepVdpaNames} gets removed via {@code vdpa dev del}.
 *       Without this, a failed VR / VM start that ran {@code vdpa dev add}
 *       but never reached the unwind path leaves an orphan vdpa-net dev
 *       on a VF; mlx5_vdpa allows only one vdpa-net per VF, so the next
 *       allocator pick on the same PCI BDF fails with
 *       {@code kernel answers: No space left on device}.</li>
 *   <li><b>VF passthrough</b> — every PCI VF currently bound to
 *       {@code vfio-pci} whose BDF is NOT in {@link #keepPciBdfs} gets
 *       unbound and re-bound to {@code mlx5_core}. Without this, a VF
 *       stranded in {@code vfio-pci} after an abnormal VM termination
 *       cannot host a fresh hostdev passthrough on the next deploy.</li>
 * </ul>
 *
 * <p>Both keep-sets default to empty: empty ⇒ wipe everything. The
 * caller (force-release path) typically passes empty sets because the DB
 * rows have already been flipped to {@code FREE}; in operator-controlled
 * paths a non-empty keep-set protects active bindings.
 *
 * <p>{@code dryRun} reports without mutating.
 *
 * <p>Wire-compat: agents predating the matching wrapper return
 * {@code Unsupported command}; the management caller logs the warning
 * and keeps going (DB-only release is the legacy semantic).
 */
public class HostVfPurgeOrphansCommand extends Command {

    /** vdpa-net device names that must NOT be deleted. */
    private Set<String> keepVdpaNames = new HashSet<>();

    /** PCI BDFs (e.g. {@code 0000:01:04.3}) that must NOT be rebound. */
    private Set<String> keepPciBdfs = new HashSet<>();

    /** When {@code true}, only report what would be cleaned. */
    private boolean dryRun;

    /** When {@code true}, run the vDPA purge step. */
    private boolean purgeVdpa = true;

    /** When {@code true}, run the VF passthrough rebind step. */
    private boolean rebindPassthroughVfs = true;

    /** No-arg constructor for serialization frameworks. */
    public HostVfPurgeOrphansCommand() {
        // No-op.
    }

    public HostVfPurgeOrphansCommand(final Set<String> keepVdpaNames, final Set<String> keepPciBdfs,
                                     final boolean dryRun) {
        this.keepVdpaNames = keepVdpaNames == null ? new HashSet<>() : new HashSet<>(keepVdpaNames);
        this.keepPciBdfs = keepPciBdfs == null ? new HashSet<>() : new HashSet<>(keepPciBdfs);
        this.dryRun = dryRun;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public Set<String> getKeepVdpaNames() {
        return keepVdpaNames;
    }

    public void setKeepVdpaNames(final Set<String> keepVdpaNames) {
        this.keepVdpaNames = keepVdpaNames == null ? new HashSet<>() : new HashSet<>(keepVdpaNames);
    }

    public Set<String> getKeepPciBdfs() {
        return keepPciBdfs;
    }

    public void setKeepPciBdfs(final Set<String> keepPciBdfs) {
        this.keepPciBdfs = keepPciBdfs == null ? new HashSet<>() : new HashSet<>(keepPciBdfs);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(final boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isPurgeVdpa() {
        return purgeVdpa;
    }

    public void setPurgeVdpa(final boolean purgeVdpa) {
        this.purgeVdpa = purgeVdpa;
    }

    public boolean isRebindPassthroughVfs() {
        return rebindPassthroughVfs;
    }

    public void setRebindPassthroughVfs(final boolean rebindPassthroughVfs) {
        this.rebindPassthroughVfs = rebindPassthroughVfs;
    }
}
