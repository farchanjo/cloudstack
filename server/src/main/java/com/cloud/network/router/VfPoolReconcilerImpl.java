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

import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.routing.UpdateHostVfInventoryCommand;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.utils.component.ManagerBase;

/**
 * Legacy inventory consumer retained for wire compatibility. It only refreshes
 * {@code last_seen}; it cannot adopt, create, quarantine, release, or repair VF
 * ownership. The singleton exact-plan gate is the sole repair entry point.
 */
@Component
public class VfPoolReconcilerImpl extends ManagerBase {

    private static final Logger LOGGER = LogManager.getLogger(VfPoolReconcilerImpl.class);

    @Inject
    private SriovVfPoolDao vfPoolDao;

    @Inject
    private HostDao hostDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    /**
     * Consume a single legacy inventory advertisement in read-mostly mode.
     * This component is not wired into ownership repair and therefore only
     * refreshes last-seen timestamps. All ownership changes are routed through
     * {@link VfPoolManagerImpl}'s leader/GlobalLock/plan approval gate.
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
        return new ReconcileResult(reconciledVfs, 0, 0, 0);
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
