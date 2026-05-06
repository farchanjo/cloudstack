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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.routing.UpdateHostVfInventoryCommand;
import com.cloud.alert.AlertManager;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.utils.component.ManagerBase;

/**
 * Mgmt-side reconciler for {@link UpdateHostVfInventoryCommand} payloads sent
 * by the KVM agent at startup and on a periodic timer. Phase H.1.
 *
 * <p>Per-host invariants enforced after each reconcile pass:
 * <ol>
 *   <li>Every VF the agent reports gets {@code last_seen=NOW()} on its pool
 *       row. Driven by {@link SriovVfPoolDao#touchLastSeen(long, String)}.
 *   <li>Pool rows in {@link State#ALLOCATED} whose {@code last_seen} is older
 *       than {@code vf.pool.suspect.timeout.seconds} are flipped to
 *       {@link State#SUSPECT} and an admin alert is emitted. No auto-release.
 *   <li>VFs the agent reports as vDPA-bound but the DB row still says
 *       {@link VdpaKind#PASSTHROUGH} are converted in place
 *       ({@code admin-adopt} path).
 *   <li>vDPA SFs the agent reports for which no pool row exists are inserted
 *       as synthetic {@link State#ORPHAN_MANUAL} rows so the operator can see
 *       them in the UI and decide whether to release.
 * </ol>
 *
 * <p>Stateless across calls; the DB is the single source of truth. Idempotent
 * — repeated identical advertises only refresh {@code last_seen}.
 */
@Component
public class VfPoolReconcilerImpl extends ManagerBase {

    private static final Logger LOGGER = LogManager.getLogger(VfPoolReconcilerImpl.class);

    /** Default timeout when {@code vf.pool.suspect.timeout.seconds} is unset. */
    static final int DEFAULT_SUSPECT_TIMEOUT_SECONDS = 900;

    @Inject
    private SriovVfPoolDao vfPoolDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private AlertManager alertMgr;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    /**
     * Reconcile a single agent inventory advertise against the DB. Called by
     * the answer dispatch on the mgmt server; tests inject a synthetic
     * {@link UpdateHostVfInventoryCommand} directly.
     *
     * @return per-action counters wrapped in {@link ReconcileResult}.
     */
    public ReconcileResult reconcile(UpdateHostVfInventoryCommand cmd) {
        if (cmd == null) {
            return ReconcileResult.empty();
        }
        HostVO host = hostDao.findByGuid(cmd.getHostUuid());
        if (host == null) {
            LOGGER.warn("VfPoolReconciler: ignoring inventory advertise from unknown host uuid={}",
                    cmd.getHostUuid());
            return ReconcileResult.empty();
        }
        long hostId = host.getId();

        int reconciledVfs = touchReportedVfs(hostId, cmd);
        int vdpaConverted = adoptVdpaBoundVfs(hostId, cmd);
        int orphanInserted = injectOrphanVdpaRows(hostId, cmd);
        int suspectFlipped = flipStaleAllocatedToSuspect(host);

        if (suspectFlipped > 0 && alertMgr != null) {
            alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST,
                    host.getDataCenterId(), host.getPodId(),
                    String.format("VF pool: %d allocated VF(s) on host %s flipped to SUSPECT",
                            suspectFlipped, host.getName()),
                    "The mgmt-side VfPoolReconciler did not see the VF(s) refreshed within the "
                            + "vf.pool.suspect.timeout.seconds threshold. Inspect the host and decide whether to "
                            + "force-release via the forceReleaseHostVfs admin API.");
        }

        return new ReconcileResult(reconciledVfs, suspectFlipped, orphanInserted, vdpaConverted);
    }

    /** Stamp {@code last_seen=NOW()} on every reported VF row. */
    private int touchReportedVfs(long hostId, UpdateHostVfInventoryCommand cmd) {
        int reconciled = 0;
        for (UpdateHostVfInventoryCommand.Vf vf : cmd.getVfList()) {
            if (vfPoolDao.touchLastSeen(hostId, vf.getPciAddress())) {
                reconciled++;
            }
        }
        return reconciled;
    }

    /**
     * Convert PASSTHROUGH rows to VDPA when the agent reports a vDPA SF whose
     * mgmtdev BDF matches the row's pci_address. Driven by the host's vDPA SF
     * list — DB rows with no SF mapping stay as PASSTHROUGH.
     */
    private int adoptVdpaBoundVfs(long hostId, UpdateHostVfInventoryCommand cmd) {
        int converted = 0;
        for (UpdateHostVfInventoryCommand.VdpaSf sf : cmd.getVdpaSfList()) {
            String bdf = sf.getMgmtdevPci();
            if (bdf == null || bdf.isEmpty()) {
                continue;
            }
            SriovVfPoolVO row = vfPoolDao.findByHostAndPci(hostId, bdf);
            if (row == null) {
                continue;
            }
            if (VdpaKind.VDPA.name().equals(row.getVdpaKind())) {
                continue;
            }
            row.setVdpaKind(VdpaKind.VDPA);
            row.setVdpaName(sf.getName());
            row.setVdpaDevice(sf.getDevicePath());
            vfPoolDao.update(row.getId(), row);
            LOGGER.info(
                    "VfPoolReconciler: adopted VF pci={} as VDPA (host={} vdpaName={} dev={})",
                    bdf, hostId, sf.getName(), sf.getDevicePath());
            converted++;
        }
        return converted;
    }

    /**
     * Inject synthetic ORPHAN_MANUAL rows for vDPA SFs the agent reports but
     * the DB has no row for. Operators see these in the UI and decide whether
     * to release; we never auto-delete.
     */
    private int injectOrphanVdpaRows(long hostId, UpdateHostVfInventoryCommand cmd) {
        Set<String> known = collectKnownVdpaNames(hostId);
        int inserted = 0;
        for (UpdateHostVfInventoryCommand.VdpaSf sf : cmd.getVdpaSfList()) {
            String name = sf.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (known.contains(name)) {
                continue;
            }
            // Skip if a row already exists for the BDF (the adopt path above
            // takes care of those).
            String bdf = sf.getMgmtdevPci();
            if (bdf != null && vfPoolDao.findByHostAndPci(hostId, bdf) != null) {
                continue;
            }
            SriovVfPoolVO row = new SriovVfPoolVO(hostId,
                    bdf != null ? bdf : "unknown",
                    "manual",
                    null);
            row.setState(State.ORPHAN_MANUAL);
            row.setVdpaKind(VdpaKind.VDPA);
            row.setVdpaName(name);
            row.setVdpaDevice(sf.getDevicePath());
            row.setLastSeen(new Date());
            vfPoolDao.persist(row);
            LOGGER.info(
                    "VfPoolReconciler: injected ORPHAN_MANUAL row for vdpaName={} pci={} dev={} on host={}",
                    name, bdf, sf.getDevicePath(), hostId);
            inserted++;
        }
        return inserted;
    }

    private Set<String> collectKnownVdpaNames(long hostId) {
        Set<String> known = new HashSet<>();
        for (SriovVfPoolVO row : vfPoolDao.listByHost(hostId)) {
            if (row.getVdpaName() != null && !row.getVdpaName().isEmpty()) {
                known.add(row.getVdpaName());
            }
        }
        return known;
    }

    /**
     * Flip every stale ALLOCATED row on this host to SUSPECT. Threshold is
     * {@code vf.pool.suspect.timeout.seconds} (read from the DB on every call;
     * tests stub via {@link #suspectTimeoutSeconds()}).
     */
    private int flipStaleAllocatedToSuspect(HostVO host) {
        int threshold = suspectTimeoutSeconds();
        List<SriovVfPoolVO> stale = vfPoolDao.findStaleAllocated(threshold);
        int flipped = 0;
        Map<Long, Boolean> seen = new HashMap<>();
        for (SriovVfPoolVO row : stale) {
            if (row.getHostId() != host.getId()) {
                continue;
            }
            if (seen.put(row.getId(), Boolean.TRUE) != null) {
                continue;
            }
            row.setState(State.SUSPECT);
            vfPoolDao.update(row.getId(), row);
            LOGGER.warn(
                    "VfPoolReconciler: row id={} (host={} pci={}) flipped ALLOCATED -> SUSPECT (last_seen={})",
                    row.getId(), row.getHostId(), row.getPciAddress(), row.getLastSeen());
            flipped++;
        }
        return flipped;
    }

    /**
     * Read the suspect timeout. Defaults to
     * {@link #DEFAULT_SUSPECT_TIMEOUT_SECONDS} when unset; tests override via
     * subclass.
     */
    protected int suspectTimeoutSeconds() {
        return DEFAULT_SUSPECT_TIMEOUT_SECONDS;
    }

    /** Counters returned to the caller (and surfaced in the agent answer). */
    public static final class ReconcileResult {
        private final int reconciledVfs;
        private final int suspectFlipped;
        private final int orphanInserted;
        private final int vdpaConverted;

        public ReconcileResult(int reconciledVfs, int suspectFlipped,
                int orphanInserted, int vdpaConverted) {
            this.reconciledVfs = reconciledVfs;
            this.suspectFlipped = suspectFlipped;
            this.orphanInserted = orphanInserted;
            this.vdpaConverted = vdpaConverted;
        }

        public static ReconcileResult empty() {
            return new ReconcileResult(0, 0, 0, 0);
        }

        public int getReconciledVfs() {
            return reconciledVfs;
        }

        public int getSuspectFlipped() {
            return suspectFlipped;
        }

        public int getOrphanInserted() {
            return orphanInserted;
        }

        public int getVdpaConverted() {
            return vdpaConverted;
        }
    }
}
