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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.poll.BackgroundPollManager;
import org.apache.cloudstack.poll.BackgroundPollTask;
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
import com.cloud.network.router.dao.VfReconciliationCandidate;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.vm.ItWorkDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VfPoolManagerImpl extends ManagerBase implements VfPoolManager, VfPoolService, Configurable {

    private static final Logger LOGGER = LogManager.getLogger(VfPoolManagerImpl.class);

    /** Period between sweepOrphans() background ticks: 15 minutes in milliseconds. */
    private static final long SWEEP_ORPHANS_PERIOD_MS = 15L * 60L * 1000L;
    private static final long[][] INCIDENT_TRANSITIONS = {
            {1427, 833}, {1469, 827}, {2435, 833}, {2537, 827},
            {764, 827}, {857, 833}, {896, 827}, {995, 827},
            {1016, 2468}, {1625, 1022}, {818, 749}};

    @Inject
    private SriovVfPoolDao vfPoolDao;

    @Inject
    private AgentManager agentMgr;

    @Inject
    private BackgroundPollManager backgroundPollManager;

    @Inject
    private NicDao nicDao;

    @Inject
    private VMInstanceDao vmDao;

    @Inject
    private ItWorkDao workDao;

    @Inject
    private VfPoolReconcileLeader reconcileLeader;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        backgroundPollManager.submitTask(new SweepOrphansTask(this));
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
    public void setPfCarrierAvailability(long hostId, String pfName, boolean carrierUp) {
        if (pfName == null || pfName.trim().isEmpty()) {
            return;
        }
        final String pf = pfName.trim();
        int flipped = 0;
        for (SriovVfPoolVO vf : vfPoolDao.listByHost(hostId)) {
            if (!pf.equals(vf.getPfName())) {
                continue;
            }
            final String state = vf.getState();
            // Only FREE ↔ UNAVAILABLE. ALLOCATED/SUSPECT/RESERVED keep their
            // binding so live VMs and in-flight staging are not unplugged here.
            if (!carrierUp && State.FREE.name().equals(state)) {
                SriovVfPoolVO updateVo = vfPoolDao.createForUpdate();
                updateVo.setState(State.UNAVAILABLE.name());
                vfPoolDao.update(vf.getId(), updateVo);
                flipped++;
            } else if (carrierUp && State.UNAVAILABLE.name().equals(state)) {
                SriovVfPoolVO updateVo = vfPoolDao.createForUpdate();
                updateVo.setState(State.FREE.name());
                vfPoolDao.update(vf.getId(), updateVo);
                flipped++;
            }
        }
        if (flipped > 0) {
            LOGGER.info("PF carrier availability host={} pf={} carrierUp={} flipped {} pool row(s)",
                    hostId, pf, carrierUp, flipped);
        } else {
            LOGGER.debug("PF carrier availability host={} pf={} carrierUp={} (no FREE/UNAVAILABLE rows to flip)",
                    hostId, pf, carrierUp);
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
        final SriovVfPoolVO row = vfPoolDao.findById(vfPoolId);
        if (row == null || row.getAllocatedToNicId() == null) {
            return false;
        }
        vfPoolDao.markSuspect(row.getId(), row.getAllocatedToNicId());
        return cleanupAndRelease(row, row.getAllocatedToNicId(), "release-" + vfPoolId, "LIFECYCLE_RELEASE");
    }

    @Override
    public boolean releaseByNicId(long nicId) {
        boolean released = false;
        for (final SriovVfPoolVO row : vfPoolDao.listByNicId(nicId)) {
            if (State.FREE.name().equals(row.getState())) {
                continue;
            }
            vfPoolDao.markSuspect(row.getId(), nicId);
            released |= cleanupAndRelease(row, nicId, "nic-release-" + nicId, "LIFECYCLE_RELEASE");
        }
        return released;
    }

    @Override
    public void commitOwnershipForVm(final long vmId, final Long expectedSourceHostId,
                                     final long destinationHostId, final String workId) {
        final List<SriovVfPoolVO> prior = vfPoolDao.commitVmReservations(
                vmId, expectedSourceHostId, destinationHostId, workId);
        for (final SriovVfPoolVO row : prior) {
            cleanupAndRelease(row, row.getAllocatedToNicId(), workId, "OWNERSHIP_COMMIT");
        }
    }

    @Override
    public void rollbackReservationsForVm(final long vmId, final long destinationHostId,
                                           final boolean cleanupAuthorized, final String workId) {
        rollbackOwnershipAttempt(vmId, destinationHostId, cleanupAuthorized, false, workId);
    }

    @Override
    public void rollbackStartAttemptForVm(final long vmId, final long destinationHostId,
                                          final boolean cleanupAuthorized, final String workId) {
        rollbackOwnershipAttempt(vmId, destinationHostId, cleanupAuthorized, true, workId);
    }

    private void rollbackOwnershipAttempt(final long vmId, final long destinationHostId,
                                          final boolean cleanupAuthorized, final boolean includeAllocated,
                                          final String workId) {
        final List<SriovVfPoolVO> quarantined = vfPoolDao.quarantineVmDestinationRows(
                vmId, destinationHostId, includeAllocated, workId);
        if (!cleanupAuthorized) {
            return;
        }
        for (final SriovVfPoolVO row : quarantined) {
            if (row.getAllocatedToNicId() != null) {
                cleanupAndRelease(row, row.getAllocatedToNicId(), workId, "STAGE_ROLLBACK");
            }
        }
    }

    private boolean cleanupAndRelease(final SriovVfPoolVO row, final long nicId,
                                      final String operationId, final String purpose) {
        if (row.getPciAddress() == null || !targetedCleanupSucceeded(
                row.getHostId(), row.getPciAddress(), row.getRepresentorName(), nicId, operationId, purpose)) {
            vfPoolDao.markSuspect(row.getId(), nicId);
            LOGGER.warn("VF pool row {} host={} pci={} remains SUSPECT: exact agent cleanup was not confirmed",
                    row.getId(), row.getHostId(), row.getPciAddress());
            return false;
        }
        if (!vfPoolDao.releaseExact(row.getId(), nicId)) {
            LOGGER.warn("VF pool row {} was not released after cleanup because ownership changed", row.getId());
            return false;
        }
        return true;
    }

    private boolean targetedCleanupSucceeded(final long hostId, final String pciBdf, final String representorName,
                                             final long nicId,
                                             final String operationId, final String purpose) {
        final NicVO nic = nicDao.findByIdIncludingRemoved(nicId);
        if (nic == null || nic.getMacAddress() == null || nic.getUuid() == null
                || representorName == null || representorName.isBlank()) {
            return false;
        }
        final String safeOperationId = operationId == null ? "vf-operation-" + nicId : operationId;
        final String expectedInterfaceId = "lsp-" + nic.getUuid();
        final HostVfPurgeOrphansCommand cmd = new HostVfPurgeOrphansCommand();
        cmd.setTargetPciBdfs(java.util.Collections.singleton(pciBdf));
        cmd.setExpectedMacsByPciBdf(java.util.Collections.singletonMap(pciBdf, nic.getMacAddress()));
        cmd.setExpectedRepresentorsByPciBdf(java.util.Collections.singletonMap(pciBdf, representorName));
        cmd.setExpectedInterfaceIdsByPciBdf(java.util.Collections.singletonMap(pciBdf, expectedInterfaceId));
        cmd.setOwnerOperationIdsByPciBdf(java.util.Collections.singletonMap(pciBdf, safeOperationId));
        cmd.setOwnerPurposesByPciBdf(java.util.Collections.singletonMap(pciBdf, purpose));
        cmd.setOwnerTokensByPciBdf(java.util.Collections.singletonMap(pciBdf,
                HostVfPurgeOrphansCommand.createOwnerToken(pciBdf, nic.getMacAddress(), safeOperationId, purpose)));
        cmd.setPurgeVdpa(false);
        cmd.setRebindPassthroughVfs(false);
        cmd.setPurgeStaleOvsReps(false);
        try {
            final Answer answer = agentMgr.send(hostId, cmd);
            if (!(answer instanceof HostVfPurgeOrphansAnswer) || !answer.getResult()) {
                return false;
            }
            final HostVfPurgeOrphansAnswer targeted = (HostVfPurgeOrphansAnswer) answer;
            if (targeted.getTargetResults().size() != 1) {
                return false;
            }
            final HostVfPurgeOrphansAnswer.TargetResult result = targeted.getTargetResults().get(0);
            return validCleanupEvidence(result, pciBdf, nic.getMacAddress(), safeOperationId, purpose);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            LOGGER.warn("Targeted VF cleanup host={} pci={} was not confirmed: {}", hostId, pciBdf, e.getMessage());
        }
        return false;
    }

    private boolean validCleanupEvidence(final HostVfPurgeOrphansAnswer.TargetResult result,
                                         final String bdf, final String expectedMac,
                                         final String operationId, final String purpose) {
        if (result == null || !result.isSuccess() || !result.isObservationComplete()
                || result.isDomainReferenced() || !normalizedBdfEquals(result.getPciBdf(), bdf)
                || !normalizedBdf(result.getPciBdf())
                || !expectedMac.equalsIgnoreCase(result.getExpectedMac())
                || !safeEquals(operationId, result.getOwnerOperationId())
                || !safeEquals(purpose, result.getOwnerPurpose())
                || !safeEquals(HostVfPurgeOrphansCommand.createOwnerToken(
                        bdf, expectedMac, operationId, purpose), result.getOwnerToken())) {
            return false;
        }
        if ("NONZERO".equals(result.getMacObservation())) {
            return expectedMac.equalsIgnoreCase(result.getCurrentMac());
        }
        return "UNASSIGNED_ZERO".equals(result.getMacObservation())
                && result.isLifecycleAuthorizationUsed()
                && HostVfPurgeOrphansCommand.createOwnerToken(bdf, expectedMac, operationId, purpose)
                .equalsIgnoreCase(result.getOwnerToken());
    }

    private boolean normalizedBdfEquals(final String left, final String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean normalizedBdf(final String bdf) {
        return bdf != null && bdf.trim().matches("[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-9a-fA-F]");
    }

    private boolean safeEquals(final String left, final String right) {
        return left != null && right != null && left.equals(right);
    }

    @Override
    public int quarantineByVmId(long vmId) {
        final List<SriovVfPoolVO> rows = vfPoolDao.quarantineAndListByVmId(vmId);
        int released = 0;
        for (final SriovVfPoolVO row : rows) {
            if (row.getAllocatedToNicId() != null && cleanupAndRelease(
                    row, row.getAllocatedToNicId(), "vm-release-" + vmId, "LIFECYCLE_RELEASE")) {
                released++;
            }
        }
        if (rows.size() > released) {
            LOGGER.warn("VM {} VF cleanup released {}/{} owned rows; {} remain SUSPECT and retryable",
                    vmId, released, rows.size(), rows.size() - released);
        }
        if (released == rows.size() && released > 0) {
            LOGGER.info("VM {} VF cleanup released all {} owned rows", vmId, released);
        }
        return released;
    }

    @Override
    public int sweepOrphans() {
        int affected = vfPoolDao.sweepOrphans();
        if (affected > 0) {
            LOGGER.warn("Marked {} orphan VF row(s) SUSPECT pending exact agent cleanup", affected);
        }
        return affected;
    }

    void runOwnershipRepairGate() {
        if (!isOwnershipRepairPlanEnabled()) {
            LOGGER.debug("VF ownership repair planning is disabled by default");
            return;
        }
        final VfOwnershipRepairPlan plan = buildOwnershipRepairPlan();
        LOGGER.warn("VF ownership repair plan hash={} token={} candidates={} ids={} ALLOCATED {}->{} FREE {}->{} applyEnabled={}",
                plan.getHash(), plan.getApprovalToken(), plan.getCandidateCount(), plan.getCandidateIds(),
                plan.getAllocatedBefore(), plan.getAllocatedAfter(), plan.getFreeBefore(), plan.getFreeAfter(),
                isOwnershipRepairApplyEnabled());
        if (!isOwnershipRepairApplyEnabled()) {
            return;
        }
        if (!isIncidentScopeApproved(plan)) {
            LOGGER.error("VF ownership repair apply blocked: plan is not the immutable approved 2026-07-16 incident scope");
            return;
        }
        if (plan.isExactIncidentScope() && !incidentProgressValid(plan)) {
            LOGGER.error("VF ownership repair apply blocked: incident progress has state, pointer, or count drift");
            return;
        }
        if (!plan.matchesApproval(approvedCandidateCount(), approvedCandidateIds(),
                approvedPlanHash(), approvedPlanToken())) {
            LOGGER.error("VF ownership repair apply blocked: current plan does not exactly match count/ids/hash/token approval");
            return;
        }
        final List<VfOwnershipRepairPlan.Candidate> activeCandidates = new ArrayList<>();
        for (final VfOwnershipRepairPlan.Candidate candidate : plan.getCandidates()) {
            if (!plan.isExactIncidentScope()) {
                activeCandidates.add(candidate);
                continue;
            }
            final VfOwnershipRepairPlan.CandidateState state = incidentCandidateState(plan, candidate);
            if (state == VfOwnershipRepairPlan.CandidateState.INVALID) {
                LOGGER.error("VF ownership repair apply blocked: invalid state for exact candidate {}", candidate.getId());
                return;
            }
            if (state != VfOwnershipRepairPlan.CandidateState.COMPLETED) {
                activeCandidates.add(candidate);
            }
        }
        if (activeCandidates.isEmpty()) {
            LOGGER.info("VF ownership incident {} is already complete; no-op", VfOwnershipRepairPlan.INCIDENT_PLAN_ID);
            return;
        }
        final List<VfReconciliationCandidate> dbCandidates = new ArrayList<>();
        for (final VfOwnershipRepairPlan.Candidate candidate : activeCandidates) {
            dbCandidates.add(new VfReconciliationCandidate(candidate.getVmId(), candidate.getNicId(),
                    candidate.getCurrentHostId(), candidate.getCurrentPoolId(), candidate.getStalePoolId(),
                    candidate.getKind() == VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL));
        }
        if (!vfPoolDao.prepareReconciliationPlan(dbCandidates)) {
            LOGGER.error("VF ownership repair apply blocked: at least one approved candidate changed before quarantine");
            return;
        }
        for (final VfOwnershipRepairPlan.Candidate candidate : activeCandidates) {
            applyApprovedCandidate(plan, candidate);
        }
    }

    /**
     * Resolve exact stale/current rows by immutable candidate pool IDs.
     * Do not use {@code listByNicId} as existence authority: WRONG_HOST_CANONICAL
     * PENDING promotions are FREE with {@code allocated_to_nic_id=NULL} and are
     * omitted from nic-scoped listings (production rows 749/1022/2468).
     */
    private VfOwnershipRepairPlan.CandidateState incidentCandidateState(
            final VfOwnershipRepairPlan plan, final VfOwnershipRepairPlan.Candidate candidate) {
        final SriovVfPoolVO current = vfPoolDao.findById(candidate.getCurrentPoolId());
        final SriovVfPoolVO stale = vfPoolDao.findById(candidate.getStalePoolId());
        if (!exactCandidateRow(current, candidate.getCurrentPoolId(), candidate.getCurrentHostId(),
                candidate.getCurrentBdf())
                || !exactCandidateRow(stale, candidate.getStalePoolId(), candidate.getStaleHostId(),
                candidate.getStaleBdf())) {
            return VfOwnershipRepairPlan.CandidateState.INVALID;
        }
        // FREE current + null owner is accepted only via plan.state() for
        // WRONG_HOST_CANONICAL PENDING; other kinds/states fail closed there.
        return plan.state(candidate, current.getState(), current.getAllocatedToNicId(),
                stale.getState(), stale.getAllocatedToNicId());
    }

    /** Fail closed unless the locked row matches the immutable candidate identity. */
    private boolean exactCandidateRow(final SriovVfPoolVO row, final long expectedId,
                                      final long expectedHostId, final String expectedBdf) {
        return row != null
                && row.getId() == expectedId
                && row.getHostId() == expectedHostId
                && !State.RESERVED.name().equals(row.getState())
                && safeEqualsIgnoreCase(row.getPciAddress(), expectedBdf);
    }

    private boolean incidentProgressValid(final VfOwnershipRepairPlan plan) {
        final List<SriovVfPoolVO> allRows = vfPoolDao.listAll();
        int allocated = 0;
        int free = 0;
        for (final SriovVfPoolVO row : allRows) {
            if (State.ALLOCATED.name().equals(row.getState())) {
                allocated++;
            } else if (State.FREE.name().equals(row.getState())) {
                free++;
            }
        }
        int noncanonicalQuarantined = 0;
        int noncanonicalCompleted = 0;
        int wrongHostQuarantined = 0;
        for (final VfOwnershipRepairPlan.Candidate candidate : plan.getCandidates()) {
            final VfOwnershipRepairPlan.CandidateState state = incidentCandidateState(plan, candidate);
            if (state == VfOwnershipRepairPlan.CandidateState.INVALID) {
                return false;
            }
            if (candidate.getKind() == VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE) {
                if (state == VfOwnershipRepairPlan.CandidateState.QUARANTINED) {
                    noncanonicalQuarantined++;
                } else if (state == VfOwnershipRepairPlan.CandidateState.COMPLETED) {
                    noncanonicalCompleted++;
                }
            } else if (state == VfOwnershipRepairPlan.CandidateState.QUARANTINED) {
                wrongHostQuarantined++;
            }
        }
        /*
         * The incident plan's before counts describe the persisted boundary before
         * any candidate was applied.  PENDING and QUARANTINED are both in-flight
         * boundaries.  A noncanonical quarantine changes one stale row from
         * ALLOCATED to SUSPECT; completion changes it from ALLOCATED to FREE.  A
         * wrong-host quarantine consumes its previously FREE canonical row, while
         * completion swaps the stale allocation for the canonical allocation and
         * has no net count effect.
         *
         * Thus the persisted replay equations are:
         *   ALLOCATED = allocatedBefore - noncanonicalQuarantined
         *                            - noncanonicalCompleted
         *   FREE      = freeBefore + noncanonicalCompleted - wrongHostQuarantined
         * and the completed count is derived from the locked row states above.
         * The per-candidate state predicates remain the state/pointer contract:
         * only those exact boundaries can contribute to these equations.
         */
        final int expectedAllocated = plan.getAllocatedBefore() - noncanonicalQuarantined
                - noncanonicalCompleted;
        final int expectedFree = plan.getFreeBefore() + noncanonicalCompleted - wrongHostQuarantined;
        return allocated == expectedAllocated && free == expectedFree;
    }

    private boolean safeEqualsIgnoreCase(final String left, final String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    VfOwnershipRepairPlan buildOwnershipRepairPlan() {
        final List<SriovVfPoolVO> allRows = vfPoolDao.listAll();
        final VfOwnershipRepairPlan incidentPlan = buildIncidentResumePlan(allRows);
        if (incidentPlan != null) {
            return incidentPlan;
        }
        final Map<Long, List<SriovVfPoolVO>> rowsByNic = new HashMap<>();
        int allocated = 0;
        int free = 0;
        for (final SriovVfPoolVO row : allRows) {
            if (State.ALLOCATED.name().equals(row.getState())) {
                allocated++;
            } else if (State.FREE.name().equals(row.getState())) {
                free++;
            }
            if (row.getAllocatedToNicId() != null) {
                rowsByNic.computeIfAbsent(row.getAllocatedToNicId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        final Map<String, HostVfPurgeOrphansAnswer.TargetResult> inventory = queryExactInventory(allRows);
        final List<VfOwnershipRepairPlan.Candidate> candidates = new ArrayList<>();
        for (final Map.Entry<Long, List<SriovVfPoolVO>> entry : rowsByNic.entrySet()) {
            addPlanCandidates(entry.getKey(), entry.getValue(), allRows, inventory, candidates);
        }
        return new VfOwnershipRepairPlan(candidates, allocated, free);
    }

    private VfOwnershipRepairPlan buildIncidentResumePlan(final List<SriovVfPoolVO> allRows) {
        final Map<Long, SriovVfPoolVO> rows = new HashMap<>();
        for (final SriovVfPoolVO row : allRows) {
            rows.put(row.getId(), row);
        }
        final List<VfOwnershipRepairPlan.Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < INCIDENT_TRANSITIONS.length; index++) {
            final long staleId = INCIDENT_TRANSITIONS[index][0];
            final long currentId = INCIDENT_TRANSITIONS[index][1];
            final SriovVfPoolVO stale = rows.get(staleId);
            final SriovVfPoolVO current = rows.get(currentId);
            if (stale == null || current == null || State.RESERVED.name().equals(stale.getState())
                    || State.RESERVED.name().equals(current.getState())) {
                return null;
            }
            final Long nicId = stale.getAllocatedToNicId() != null
                    ? stale.getAllocatedToNicId() : current.getAllocatedToNicId();
            if (nicId == null) {
                return null;
            }
            final NicVO nic = nicDao.findByIdIncludingRemoved(nicId);
            if (nic == null || nic.getRemoved() != null || invalidMac(nic.getMacAddress())) {
                return null;
            }
            final VMInstanceVO vm = vmDao.findById(nic.getInstanceId());
            if (!stableRunningVm(vm) || hasConflictingWork(vm.getId())) {
                return null;
            }
            final VfOwnershipRepairPlan.Kind kind = index < 8
                    ? VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE
                    : VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL;
            final VfOwnershipRepairPlan.Candidate candidate = new VfOwnershipRepairPlan.Candidate(kind,
                    vm.getId(), nicId, currentId, staleId, current.getHostId(), stale.getHostId(),
                    current.getPciAddress(), stale.getPciAddress(), nic.getMacAddress());
            final VfOwnershipRepairPlan probe = new VfOwnershipRepairPlan(List.of(candidate), 27, 237);
            if (probe.state(candidate, current.getState(), current.getAllocatedToNicId(),
                    stale.getState(), stale.getAllocatedToNicId())
                    == VfOwnershipRepairPlan.CandidateState.INVALID) {
                return null;
            }
            candidates.add(candidate);
        }
        return new VfOwnershipRepairPlan(candidates, 27, 237);
    }

    private void addPlanCandidates(final long nicId, final List<SriovVfPoolVO> rows,
                                   final List<SriovVfPoolVO> allRows,
                                   final Map<String, HostVfPurgeOrphansAnswer.TargetResult> inventory,
                                   final List<VfOwnershipRepairPlan.Candidate> candidates) {
        final NicVO nic = nicDao.findByIdIncludingRemoved(nicId);
        if (nic == null || nic.getRemoved() != null || invalidMac(nic.getMacAddress()) || containsReserved(rows)) {
            return;
        }
        final VMInstanceVO vm = vmDao.findById(nic.getInstanceId());
        if (!stableRunningVm(vm) || hasConflictingWork(vm.getId())) {
            return;
        }
        final SriovVfPoolVO canonical = rowById(rows, nic.getVfPoolId());
        if (canonical == null) {
            return;
        }
        final SriovVfPoolVO current = canonical.getHostId() == vm.getHostId() ? canonical
                : findCurrentInventoryRow(allRows, vm.getHostId(), nic, inventory);
        if (current == null || !confirmedCurrentOwner(current, nic, inventory)) {
            return;
        }
        if (canonical.getHostId() != vm.getHostId()) {
            final boolean currentStateSafe = State.FREE.name().equals(current.getState())
                    || State.ALLOCATED.name().equals(current.getState())
                    && Long.valueOf(nicId).equals(current.getAllocatedToNicId());
            if (currentStateSafe && State.ALLOCATED.name().equals(canonical.getState())
                    && cleanupObservable(canonical, nic, inventory)) {
                candidates.add(candidate(VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL,
                        vm, nic, current, canonical));
            }
            return;
        }
        if (!State.ALLOCATED.name().equals(canonical.getState())) {
            return;
        }
        for (final SriovVfPoolVO stale : rows) {
            if (stale.getId() != canonical.getId()
                    && (State.ALLOCATED.name().equals(stale.getState())
                    || State.SUSPECT.name().equals(stale.getState()))
                    && cleanupObservable(stale, nic, inventory)) {
                candidates.add(candidate(VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE,
                        vm, nic, canonical, stale));
            }
        }
    }

    private VfOwnershipRepairPlan.Candidate candidate(final VfOwnershipRepairPlan.Kind kind,
                                                       final VMInstanceVO vm, final NicVO nic,
                                                       final SriovVfPoolVO current,
                                                       final SriovVfPoolVO stale) {
        return new VfOwnershipRepairPlan.Candidate(kind, vm.getId(), nic.getId(), current.getId(),
                stale.getId(), current.getHostId(), stale.getHostId(), current.getPciAddress(),
                stale.getPciAddress(), nic.getMacAddress());
    }

    private Map<String, HostVfPurgeOrphansAnswer.TargetResult> queryExactInventory(
            final List<SriovVfPoolVO> rows) {
        final Map<Long, Set<String>> bdfsByHost = new HashMap<>();
        for (final SriovVfPoolVO row : rows) {
            if (row.getPciAddress() != null && !State.RESERVED.name().equals(row.getState())) {
                bdfsByHost.computeIfAbsent(row.getHostId(), ignored -> new java.util.TreeSet<>())
                        .add(row.getPciAddress());
            }
        }
        final Map<String, HostVfPurgeOrphansAnswer.TargetResult> inventory = new HashMap<>();
        for (final Map.Entry<Long, Set<String>> entry : bdfsByHost.entrySet()) {
            final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
            command.setTargetPciBdfs(entry.getValue());
            command.setDryRun(true);
            try {
                final Answer answer = agentMgr.send(entry.getKey(), command);
                if (!(answer instanceof HostVfPurgeOrphansAnswer)) {
                    continue;
                }
                for (final HostVfPurgeOrphansAnswer.TargetResult result
                        : ((HostVfPurgeOrphansAnswer) answer).getTargetResults()) {
                    if (result.isSuccess() && result.isObservationComplete()) {
                        inventory.put(inventoryKey(entry.getKey(), result.getPciBdf()), result);
                    }
                }
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                LOGGER.debug("Exact VF inventory unavailable for host {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return inventory;
    }

    private void applyApprovedCandidate(final VfOwnershipRepairPlan plan,
                                        final VfOwnershipRepairPlan.Candidate candidate) {
        final SriovVfPoolVO stale = vfPoolDao.findById(candidate.getStalePoolId());
        if (!exactCandidateRow(stale, candidate.getStalePoolId(), candidate.getStaleHostId(),
                candidate.getStaleBdf())
                || !State.SUSPECT.name().equals(stale.getState())
                || !Long.valueOf(candidate.getNicId()).equals(stale.getAllocatedToNicId())) {
            LOGGER.error("Approved VF repair candidate {} remains SUSPECT after stale-row identity drift",
                    candidate.getId());
            return;
        }
        if (!targetedCleanupSucceeded(candidate.getStaleHostId(), candidate.getStaleBdf(),
                stale.getRepresentorName(), candidate.getNicId(), plan.getHash(), "RECONCILE")) {
            LOGGER.error("Approved VF repair candidate {} remains SUSPECT after unconfirmed cleanup", candidate.getId());
            return;
        }
        if (!vfPoolDao.completeReconciliation(candidate.getVmId(), candidate.getNicId(),
                candidate.getCurrentHostId(), candidate.getCurrentPoolId(), candidate.getStalePoolId())) {
            LOGGER.error("Approved VF repair candidate {} remains SUSPECT after post-cleanup race recheck",
                    candidate.getId());
        }
    }

    private boolean stableRunningVm(final VMInstanceVO vm) {
        return vm != null && vm.getRemoved() == null && vm.getState() == VirtualMachine.State.Running
                && vm.getHostId() != null;
    }

    private boolean hasConflictingWork(final long vmId) {
        return workDao.findByOutstandingWork(vmId, VirtualMachine.State.Migrating) != null
                || workDao.findByOutstandingWork(vmId, VirtualMachine.State.Starting) != null
                || workDao.findByOutstandingWork(vmId, VirtualMachine.State.Stopping) != null;
    }

    private boolean confirmedCurrentOwner(final SriovVfPoolVO row, final NicVO nic,
                                          final Map<String, HostVfPurgeOrphansAnswer.TargetResult> inventory) {
        final HostVfPurgeOrphansAnswer.TargetResult result = inventory.get(
                inventoryKey(row.getHostId(), row.getPciAddress()));
        return result != null && result.isDevicePresent()
                && nic.getMacAddress().equalsIgnoreCase(result.getCurrentMac())
                && ("VDPA_BOUND".equals(result.getBindingState())
                || "PASSTHROUGH_BOUND".equals(result.getBindingState()));
    }

    private boolean cleanupObservable(final SriovVfPoolVO row, final NicVO nic,
                                      final Map<String, HostVfPurgeOrphansAnswer.TargetResult> inventory) {
        final HostVfPurgeOrphansAnswer.TargetResult result = inventory.get(
                inventoryKey(row.getHostId(), row.getPciAddress()));
        if (result == null || result.isDomainReferenced()) {
            return false;
        }
        final String observedMac = result.getCurrentMac();
        return !result.isDevicePresent() || observedMac == null || observedMac.trim().isEmpty()
                || "00:00:00:00:00:00".equalsIgnoreCase(observedMac)
                || nic.getMacAddress().equalsIgnoreCase(observedMac);
    }

    private boolean containsReserved(final List<SriovVfPoolVO> rows) {
        return rows.stream().anyMatch(row -> State.RESERVED.name().equals(row.getState()));
    }

    private SriovVfPoolVO findCurrentInventoryRow(final List<SriovVfPoolVO> allRows, final long hostId,
                                                  final NicVO nic,
                                                  final Map<String, HostVfPurgeOrphansAnswer.TargetResult> inventory) {
        SriovVfPoolVO found = null;
        for (final SriovVfPoolVO row : allRows) {
            if (row.getHostId() != hostId || State.RESERVED.name().equals(row.getState())
                    || !confirmedCurrentOwner(row, nic, inventory)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = row;
        }
        return found;
    }

    private SriovVfPoolVO rowById(final List<SriovVfPoolVO> rows, final Long id) {
        if (id == null) {
            return null;
        }
        for (final SriovVfPoolVO row : rows) {
            if (row.getId() == id) {
                return row;
            }
        }
        return null;
    }

    private boolean invalidMac(final String mac) {
        return mac == null || mac.trim().isEmpty() || "00:00:00:00:00:00".equalsIgnoreCase(mac);
    }

    private String inventoryKey(final long hostId, final String bdf) {
        return hostId + "|" + (bdf == null ? "" : bdf.toLowerCase(Locale.ROOT));
    }

    protected boolean isOwnershipRepairPlanEnabled() {
        return OwnershipRepairPlanEnabled.value();
    }

    protected boolean isOwnershipRepairApplyEnabled() {
        return OwnershipRepairApplyEnabled.value();
    }

    protected int approvedCandidateCount() {
        return OwnershipRepairApprovedCount.value();
    }

    protected String approvedCandidateIds() {
        return OwnershipRepairApprovedIds.value();
    }

    protected String approvedPlanHash() {
        return OwnershipRepairApprovedHash.value();
    }

    protected String approvedPlanToken() {
        return OwnershipRepairApprovalToken.value();
    }

    protected String approvedIncidentId() {
        return OwnershipRepairIncidentId.value();
    }

    protected boolean isIncidentScopeApproved(final VfOwnershipRepairPlan plan) {
        return plan.isExactIncidentScope() && VfOwnershipRepairPlan.INCIDENT_PLAN_ID.equals(approvedIncidentId());
    }

    @Override
    public int countFree(long hostId) {
        return vfPoolDao.countByHostAndState(hostId, State.FREE);
    }

    @Override
    public int countFreeForVdpa(long hostId) {
        return vfPoolDao.countFreeVdpaCapable(hostId);
    }

    @Override
    public VfPoolStatus getHostVfPoolStatus(final long hostId) {
        final java.util.List<VfDeviceStatus> devices = vfPoolDao.listByHost(hostId).stream()
                .map(row -> new VfDeviceStatus(row.getId(), row.getPciAddress(),
                        row.getAllocatedToNicId(), row.getState(), row.getVdpaKind()))
                .toList();
        return new VfPoolStatus(hostId,
                vfPoolDao.countByHostAndState(hostId, State.FREE),
                vfPoolDao.countFreeVdpaCapable(hostId),
                vfPoolDao.countByHostAndState(hostId, State.RESERVED),
                vfPoolDao.countByHostAndState(hostId, State.ALLOCATED),
                vfPoolDao.countByHostAndState(hostId, State.SUSPECT), devices);
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
        return release(vfPoolId);
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
        if (!LegacyBroadVfOperationsEnabled.value()) {
            LOGGER.warn("forceReleaseByHostId denied: vf.legacy.broad.operations.enabled is false");
            return 0;
        }
        final int affected = vfPoolDao.markSuspectByHostId(hostId);
        LOGGER.warn("Legacy host force-release is deactivated; marked {} row(s) SUSPECT on host {}. "
                + "Use the leader/lock/exact-plan approval gate for repair.", affected, hostId);
        return affected;
    }

    @Override
    public int recoverByHostId(long hostId) {
        if (!LegacyBroadVfOperationsEnabled.value()) {
            LOGGER.warn("recoverByHostId denied: vf.legacy.broad.operations.enabled is false");
            return 0;
        }
        LOGGER.warn("Legacy recoverByHostId is deactivated for host {}; use an exact approved repair plan", hostId);
        return 0;
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

    /**
     * Periodic singleton task. It first performs DB-only orphan quarantine,
     * then optionally builds an exact non-mutating ownership plan. Mutation is
     * default-off and requires an exact count/ids/hash/token approval match.
     *
     * <p>Fires every {@value #SWEEP_ORPHANS_PERIOD_MS} ms (15 minutes). Exceptions
     * are logged and swallowed — a single failed tick must not crash the executor.
     */
    protected final class SweepOrphansTask extends ManagedContextRunnable implements BackgroundPollTask {

        private final VfPoolManager manager;

        SweepOrphansTask(final VfPoolManager manager) {
            this.manager = manager;
        }

        @Override
        protected void runInContext() {
            runSweepIfLeader(manager);
        }

        @Override
        public Long getDelay() {
            return SWEEP_ORPHANS_PERIOD_MS;
        }
    }

    void runSweepIfLeader(final VfPoolManager manager) {
        if (reconcileLeader == null || !reconcileLeader.isLeader()) {
            LOGGER.debug("SweepOrphansTask: skipped on non-leader management server");
            return;
        }
        final GlobalLock lock = getSweepLock();
        try {
            if (!lock.lock(1)) {
                LOGGER.debug("SweepOrphansTask: global lock is held by another management server");
                return;
            }
            try {
                final int swept = manager.sweepOrphans();
                LOGGER.debug("SweepOrphansTask: marked {} orphan VF row(s) SUSPECT", swept);
                if (manager == this) {
                    runOwnershipRepairGate();
                }
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("SweepOrphansTask: failed closed (will retry next tick): {}", e.getMessage(), e);
        } finally {
            lock.releaseRef();
        }
    }

    protected GlobalLock getSweepLock() {
        return GlobalLock.getInternLock("vf.pool.reconcile");
    }

    @Override
    public String getConfigComponentName() {
        return VfPoolManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {LegacyBroadVfOperationsEnabled, OwnershipRepairPlanEnabled,
                OwnershipRepairApplyEnabled, OwnershipRepairApprovedCount, OwnershipRepairApprovedIds,
                OwnershipRepairApprovedHash, OwnershipRepairApprovalToken, OwnershipRepairIncidentId};
    }
}
