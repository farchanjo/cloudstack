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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.utils.component.ManagerBase;

@Component
public class VfPoolManagerImpl extends ManagerBase implements VfPoolManager, VfPoolService {

    private static final Logger LOGGER = LogManager.getLogger(VfPoolManagerImpl.class);

    @Inject
    private SriovVfPoolDao vfPoolDao;

    @Inject
    private AgentManager agentMgr;

    @Override
    public boolean configure(String name, java.util.Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    @Override
    public void registerHostVfs(long hostId, String pfName, int totalVfs, List<String> pciAddresses) {
        if (pciAddresses == null || pciAddresses.isEmpty()) {
            LOGGER.warn(String.format("registerHostVfs called for host %d pf=%s with no PCI addresses", hostId, pfName));
            return;
        }

        // Build a set of currently-known PCI addresses on this host (for any PF).
        Set<String> known = new HashSet<>();
        for (SriovVfPoolVO vf : vfPoolDao.listByHost(hostId)) {
            known.add(vf.getPciAddress());
        }

        int added = 0;
        for (int i = 0; i < pciAddresses.size(); i++) {
            String pci = pciAddresses.get(i);
            if (known.contains(pci)) {
                continue;
            }
            String repName = derivePortRepresentor(pfName, i);
            SriovVfPoolVO vf = new SriovVfPoolVO(hostId, pci, pfName, repName);
            vfPoolDao.persist(vf);
            added++;
        }
        if (added > 0) {
            LOGGER.info(String.format("Registered %d new VFs on host %d for PF %s (total reported: %d)",
                    added, hostId, pfName, totalVfs));
        }
    }

    @Override
    public SriovVfPoolVO allocate(long hostId, long nicId) throws InsufficientCapacityException {
        SriovVfPoolVO vf = vfPoolDao.allocate(hostId, nicId);
        if (vf == null) {
            throw new InsufficientServerCapacityException(
                "No FREE SR-IOV VF available on host " + hostId, Host.class, hostId);
        }
        LOGGER.debug(String.format("Allocated VF %s (PCI %s, rep %s) on host %d for NIC %d",
                vf.getUuid(), vf.getPciAddress(), vf.getRepresentorName(), hostId, nicId));
        return vf;
    }

    @Override
    public boolean release(long vfPoolId) {
        boolean ok = vfPoolDao.release(vfPoolId);
        if (ok) {
            LOGGER.debug(String.format("Released VF pool id=%d", vfPoolId));
        }
        return ok;
    }

    @Override
    public boolean releaseByNicId(long nicId) {
        boolean ok = vfPoolDao.releaseByNicId(nicId);
        if (ok) {
            LOGGER.debug(String.format("Released VF(s) bound to NIC %d", nicId));
        }
        return ok;
    }

    @Override
    public int releaseByVmId(long vmId) {
        int affected = vfPoolDao.releaseByVmId(vmId);
        if (affected > 0) {
            LOGGER.info(String.format("Released %d VF(s) bound to VM %d via releaseByVmId", affected, vmId));
        }
        return affected;
    }

    @Override
    public int sweepOrphans() {
        int affected = vfPoolDao.sweepOrphans();
        if (affected > 0) {
            LOGGER.info(String.format("Swept %d orphan VF(s) back to FREE", affected));
        }
        return affected;
    }

    @Override
    public int countFree(long hostId) {
        return vfPoolDao.countByHostAndState(hostId, State.FREE);
    }

    @Override
    public SriovVfPoolVO allocateForVdpa(long hostId, long nicId, String mac, int maxVqs) {
        SriovVfPoolVO vf = vfPoolDao.allocateForVdpa(hostId, nicId, mac, maxVqs);
        if (vf == null) {
            LOGGER.warn(String.format(
                "allocateForVdpa: no FREE VF available on host %d for NIC %d (mac=%s)",
                hostId, nicId, mac));
            return null;
        }
        LOGGER.info(String.format(
            "allocateForVdpa: host=%d nic=%d mac=%s maxVqs=%d -> vf=%s pci=%s vdpaName=%s",
            hostId, nicId, mac, maxVqs, vf.getUuid(), vf.getPciAddress(), vf.getVdpaName()));
        return vf;
    }

    @Override
    public boolean releaseVdpa(long vfPoolId) {
        boolean ok = vfPoolDao.releaseVdpa(vfPoolId);
        if (ok) {
            LOGGER.debug(String.format("Released vDPA VF pool id=%d", vfPoolId));
        }
        return ok;
    }

    @Override
    public int markSuspectByHostId(long hostId) {
        int affected = vfPoolDao.markSuspectByHostId(hostId);
        if (affected > 0) {
            LOGGER.warn("Marked {} ALLOCATED VF row(s) on host {} as SUSPECT (host disconnect or stale inventory)",
                    affected, hostId);
        }
        return affected;
    }

    @Override
    public int forceReleaseByHostId(long hostId) {
        int affected = vfPoolDao.forceReleaseByHostId(hostId);
        if (affected > 0) {
            LOGGER.warn("Force-released {} VF row(s) on host {} (operator action)", affected, hostId);
        }
        // After the DB rows are flipped to FREE, instruct the agent to bring
        // the kernel back into agreement with the database: delete every
        // orphan vdpa-net device AND rebind every VF stranded on vfio-pci
        // back to mlx5_core. Without this lock-step the next allocator pick
        // on a previously-bound PCI BDF fails with either
        //   'kernel answers: No space left on device' (mlx5_vdpa allows one
        //    vdpa-net per VF, prior failure left the slot occupied), or
        //   'device is busy' / hostdev attach refused (VF still on vfio-pci
        //    with a stale driver_override).
        // See HostVfPurgeOrphansCommand javadoc.
        purgeKernelOrphans(hostId, false);
        return affected;
    }

    /**
     * Send {@link HostVfPurgeOrphansCommand} so the kernel state mirrors
     * the DB after a force-release. Best-effort: agent unreachable or
     * pre-dating the wrapper degrades to a warning, never aborts the
     * caller. Empty keep-sets ⇒ wipe every vdpa-net dev and rebind every
     * vfio-pci VF on the host.
     */
    private void purgeKernelOrphans(long hostId, boolean dryRun) {
        HostVfPurgeOrphansCommand cmd = new HostVfPurgeOrphansCommand(
                new HashSet<>(), new HashSet<>(), dryRun);
        try {
            Answer answer = agentMgr.send(hostId, cmd);
            if (answer instanceof HostVfPurgeOrphansAnswer) {
                HostVfPurgeOrphansAnswer purge = (HostVfPurgeOrphansAnswer) answer;
                LOGGER.info(
                        "HostVfPurgeOrphans on host {}: vdpa[found={} kept={} deleted={}] vfio[scanned={} bound={} kept={} rebound={}] dryRun={}",
                        hostId,
                        purge.getVdpaFound(), purge.getVdpaKept(), purge.getVdpaDeleted(),
                        purge.getVfsScanned(), purge.getVfsBoundVfio(), purge.getVfsKept(), purge.getVfsRebound(),
                        dryRun);
            } else if (answer != null) {
                LOGGER.warn("HostVfPurgeOrphans on host {}: agent answered {} (unexpected): {}",
                        hostId, answer.getClass().getSimpleName(), answer.getDetails());
            } else {
                LOGGER.warn("HostVfPurgeOrphans on host {}: null answer from agent", hostId);
            }
        } catch (AgentUnavailableException e) {
            LOGGER.warn("HostVfPurgeOrphans on host {}: agent unavailable ({}); kernel state may diverge from DB",
                    hostId, e.getMessage());
        } catch (OperationTimedoutException e) {
            LOGGER.warn("HostVfPurgeOrphans on host {}: timed out ({}); kernel state may diverge from DB",
                    hostId, e.getMessage());
        }
    }

    @Override
    public int recoverByHostId(long hostId) {
        int affected = vfPoolDao.recoverByHostId(hostId);
        if (affected > 0) {
            LOGGER.info("Recovered {} VF row(s) on host {} from FREE → ALLOCATED via live-NIC join", affected, hostId);
        } else {
            LOGGER.info("recoverByHostId: nothing to recover on host {} (no FREE pool entries with live NIC binding)", hostId);
        }
        return affected;
    }

    /**
     * Derive the representor netdev name for a VF index on a given PF.
     * Mirrors the udev/script naming applied in {@code mlx-switchdev.sh}:
     *   pf=dx6p0 vfIndex=0 → dx6p0r0
     *   pf=dx6p1 vfIndex=15 → dx6p1r15
     */
    static String derivePortRepresentor(String pfName, int vfIndex) {
        if (pfName == null || pfName.isEmpty()) {
            return null;
        }
        return pfName + "r" + vfIndex;
    }
}
