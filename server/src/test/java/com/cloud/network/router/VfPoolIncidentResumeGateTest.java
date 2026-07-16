// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer.TargetResult;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.network.router.dao.VfReconciliationCandidate;
import com.cloud.vm.ItWorkDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

public class VfPoolIncidentResumeGateTest {

    @Test
    public void committedQuarantineResumesAfterProcessRestartAndFinalReplayIsNoOp() throws Exception {
        final IncidentFixture fixture = IncidentFixture.quarantined();
        final GateHarness harness = new GateHarness(fixture);

        harness.newManager().runOwnershipRepairGate();

        assertEquals(19, fixture.count(State.ALLOCATED));
        assertEquals(245, fixture.count(State.FREE));
        verify(harness.dao, times(1)).prepareReconciliationPlan(any());
        verify(harness.agents, times(11)).send(anyLong(), any(HostVfPurgeOrphansCommand.class));

        harness.newManager().runOwnershipRepairGate();

        verify(harness.dao, times(1)).prepareReconciliationPlan(any());
        verify(harness.agents, times(11)).send(anyLong(), any(HostVfPurgeOrphansCommand.class));
    }

    @Test
    public void partialCompletionResumesOnlyIncompleteCandidatesAfterRestart() throws Exception {
        final IncidentFixture fixture = IncidentFixture.partiallyCompleted(4);
        final GateHarness harness = new GateHarness(fixture);

        harness.newManager().runOwnershipRepairGate();

        assertEquals(19, fixture.count(State.ALLOCATED));
        assertEquals(245, fixture.count(State.FREE));
        verify(harness.agents, times(7)).send(anyLong(), any(HostVfPurgeOrphansCommand.class));
    }

    @Test
    public void fullyConvergedIncidentIsRepeatableNoOpOnlyAtFinalCounts() throws Exception {
        final IncidentFixture fixture = IncidentFixture.completed();
        final GateHarness harness = new GateHarness(fixture);

        harness.newManager().runOwnershipRepairGate();
        harness.newManager().runOwnershipRepairGate();

        assertEquals(19, fixture.count(State.ALLOCATED));
        assertEquals(245, fixture.count(State.FREE));
        verify(harness.dao, never()).prepareReconciliationPlan(any());
        verify(harness.agents, never()).send(anyLong(), any(HostVfPurgeOrphansCommand.class));
    }

    @Test
    public void countDriftBlocksCommittedIncidentBeforeQuarantineOrAgentCall() throws Exception {
        final IncidentFixture fixture = IncidentFixture.completed();
        fixture.allRows.add(fixture.row(777777L, 300L, "0000:7f:00.1", State.FREE, null));
        final GateHarness harness = new GateHarness(fixture);

        harness.newManager().runOwnershipRepairGate();

        verify(harness.dao, never()).prepareReconciliationPlan(any());
        verify(harness.agents, never()).send(anyLong(), any(HostVfPurgeOrphansCommand.class));
    }

    @Test
    public void mixedPendingQuarantinedCompletedEightPlusThreeProgressIsAccepted() throws Exception {
        final IncidentFixture fixture = IncidentFixture.mixedProgress();
        final GateHarness harness = new GateHarness(fixture);

        harness.newManager().runOwnershipRepairGate();

        assertEquals(19, fixture.count(State.ALLOCATED));
        assertEquals(245, fixture.count(State.FREE));
        verify(harness.dao).prepareReconciliationPlan(any());
    }

    /**
     * Production initial incident: ALLOCATED=27 FREE=237 with three WRONG_HOST_CANONICAL
     * promotion rows FREE + allocated_to_nic_id=NULL (749/1022/2468). listByNicId omits
     * them; exact findById must still pass the apply gate through prepare.
     */
    @Test
    public void productionInitialTwentySevenTwoThirtySevenPassesApplyGateWithFreePromotions()
            throws Exception {
        final IncidentFixture fixture = IncidentFixture.initialPending();
        final GateHarness harness = new GateHarness(fixture);

        assertTrue(fixture.plan.isExactIncidentScope());
        assertEquals(27, fixture.count(State.ALLOCATED));
        assertEquals(237, fixture.count(State.FREE));
        for (final long freePromotionId : IncidentFixture.WRONG_HOST_CURRENT_IDS) {
            final SriovVfPoolVO promotion = fixture.rowById(freePromotionId);
            assertEquals(State.FREE.name(), promotion.getState());
            assertEquals(null, promotion.getAllocatedToNicId());
            // FREE + null owner is invisible to listByNicId (production root cause).
            assertTrue(fixture.listByNicIdProduction(8829L).stream()
                    .noneMatch(row -> row.getId() == freePromotionId));
            assertTrue(fixture.listByNicIdProduction(8847L).stream()
                    .noneMatch(row -> row.getId() == freePromotionId));
            assertTrue(fixture.listByNicIdProduction(8925L).stream()
                    .noneMatch(row -> row.getId() == freePromotionId));
        }
        for (final VfOwnershipRepairPlan.Candidate candidate : fixture.plan.getCandidates()) {
            if (candidate.getKind() != VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL) {
                continue;
            }
            final List<SriovVfPoolVO> byNic = fixture.listByNicIdProduction(candidate.getNicId());
            assertTrue("listByNicId must omit FREE current " + candidate.getCurrentPoolId(),
                    byNic.stream().noneMatch(row -> row.getId() == candidate.getCurrentPoolId()));
            assertEquals(candidate.getCurrentPoolId(),
                    fixture.rowById(candidate.getCurrentPoolId()).getId());
        }

        harness.newManager().runOwnershipRepairGate();

        assertEquals(19, fixture.count(State.ALLOCATED));
        assertEquals(245, fixture.count(State.FREE));
        verify(harness.dao, times(1)).prepareReconciliationPlan(any());
        verify(harness.dao, atLeast(22)).findById(anyLong());
        verify(harness.agents, times(11)).send(anyLong(), any(HostVfPurgeOrphansCommand.class));
    }

    @Test
    public void freePromotionRowsResolvedByFindByIdNotListByNicIdForAllThreeWrongHostCandidates()
            throws Exception {
        final IncidentFixture fixture = IncidentFixture.initialPending();
        final GateHarness harness = new GateHarness(fixture);
        final List<VfOwnershipRepairPlan.Candidate> wrongHost = fixture.plan.getCandidates().stream()
                .filter(c -> c.getKind() == VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL)
                .toList();
        assertEquals(3, wrongHost.size());

        for (final VfOwnershipRepairPlan.Candidate candidate : wrongHost) {
            assertTrue(fixture.listByNicIdProduction(candidate.getNicId()).stream()
                    .noneMatch(row -> row.getId() == candidate.getCurrentPoolId()));
            final SriovVfPoolVO exact = harness.dao.findById(candidate.getCurrentPoolId());
            assertEquals(State.FREE.name(), exact.getState());
            assertEquals(null, exact.getAllocatedToNicId());
            assertEquals(candidate.getCurrentHostId(), exact.getHostId());
            assertEquals(candidate.getCurrentBdf().toLowerCase(),
                    exact.getPciAddress().toLowerCase());
        }

        harness.newManager().runOwnershipRepairGate();
        verify(harness.dao).prepareReconciliationPlan(any());
    }

    @Test
    public void applyGateRejectsWrongPoolIdHostBdfNicOwnershipOrState() throws Exception {
        // Fixed approved plan + drifted exact rows exercises incidentCandidateState fail-closed
        // (not merely plan-hash mismatch from rebuild).
        assertBlockedWithFixedPlan(fixture -> fixture.setHostId(1022L, 999L));
        assertBlockedWithFixedPlan(fixture -> fixture.setPciAddress(749L, "0000:ff:00.0"));
        assertBlockedWithFixedPlan(fixture -> {
            final SriovVfPoolVO stale = fixture.rowById(1625L);
            stale.setState(State.ALLOCATED);
            stale.setAllocatedToNicId(99999L);
        });
        assertBlockedWithFixedPlan(fixture -> {
            // PENDING wrong-host requires FREE+null; ALLOCATED+nic is invalid mid-state.
            final SriovVfPoolVO current = fixture.rowById(2468L);
            current.setState(State.ALLOCATED);
            current.setAllocatedToNicId(8925L);
        });
        assertBlockedWithFixedPlan(fixture -> fixture.rowById(1016L).setState(State.RESERVED));
        assertBlockedWithFixedPlan(fixture -> fixture.removeRow(818L));
    }

    private static void assertBlockedWithFixedPlan(final FixtureMutator mutator) throws Exception {
        final IncidentFixture fixture = IncidentFixture.initialPending();
        mutator.mutate(fixture);
        final GateHarness harness = new GateHarness(fixture);
        harness.newFixedPlanManager().runOwnershipRepairGate();
        verify(harness.dao, never()).prepareReconciliationPlan(any());
        verify(harness.agents, never()).send(anyLong(), any(HostVfPurgeOrphansCommand.class));
    }

    @FunctionalInterface
    private interface FixtureMutator {
        void mutate(IncidentFixture fixture) throws Exception;
    }

    private static final class GateHarness {
        private final IncidentFixture fixture;
        private final SriovVfPoolDao dao = mock(SriovVfPoolDao.class);
        private final AgentManager agents = mock(AgentManager.class);
        private final NicDao nics = mock(NicDao.class);
        private final VMInstanceDao vms = mock(VMInstanceDao.class);
        private final ItWorkDao work = mock(ItWorkDao.class);

        private GateHarness(final IncidentFixture fixture) throws Exception {
            this.fixture = fixture;
            when(dao.listAll()).thenAnswer(invocation -> fixture.allRows);
            // Production semantics: only rows with allocated_to_nic_id = nicId.
            when(dao.listByNicId(anyLong())).thenAnswer(invocation ->
                    fixture.listByNicIdProduction(invocation.getArgument(0)));
            when(dao.findById(anyLong())).thenAnswer(invocation ->
                    fixture.rowById(invocation.getArgument(0)));
            when(dao.prepareReconciliationPlan(any())).thenAnswer(invocation -> {
                fixture.quarantine(invocation.getArgument(0));
                return true;
            });
            when(dao.completeReconciliation(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                    .thenAnswer(invocation -> fixture.complete(invocation.getArgument(4)));
            when(nics.findByIdIncludingRemoved(anyLong())).thenAnswer(invocation -> fixture.nic(
                    invocation.getArgument(0)));
            when(vms.findById(anyLong())).thenAnswer(invocation -> fixture.vm(invocation.getArgument(0)));
            when(agents.send(anyLong(), any(HostVfPurgeOrphansCommand.class))).thenAnswer(invocation ->
                    successfulAnswer(invocation.getArgument(1), fixture.plan));
        }

        private ApprovedManager newManager() {
            return wire(new ApprovedManager(fixture.plan));
        }

        /** Keeps the immutable approved plan while exact DAO rows may drift. */
        private ApprovedManager newFixedPlanManager() {
            return wire(new FixedPlanManager(fixture.plan));
        }

        private ApprovedManager wire(final ApprovedManager manager) {
            ReflectionTestUtils.setField(manager, "vfPoolDao", dao);
            ReflectionTestUtils.setField(manager, "agentMgr", agents);
            ReflectionTestUtils.setField(manager, "nicDao", nics);
            ReflectionTestUtils.setField(manager, "vmDao", vms);
            ReflectionTestUtils.setField(manager, "workDao", work);
            return manager;
        }
    }

    private static HostVfPurgeOrphansAnswer successfulAnswer(final HostVfPurgeOrphansCommand command,
                                                              final VfOwnershipRepairPlan plan) {
        final String bdf = command.getTargetPciBdfs().iterator().next();
        final String mac = command.getExpectedMacsByPciBdf().get(bdf);
        final HostVfPurgeOrphansAnswer answer = new HostVfPurgeOrphansAnswer(command, true, "ok");
        final TargetResult result = new TargetResult(bdf, true, false, false, false, false, "absent");
        result.setObservationComplete(true);
        result.setMacObservation("UNASSIGNED_ZERO");
        result.setExpectedMac(mac);
        result.setOwnerOperationId(plan.getHash());
        result.setOwnerPurpose("RECONCILE");
        result.setOwnerToken(HostVfPurgeOrphansCommand.createOwnerToken(
                bdf, mac, plan.getHash(), "RECONCILE"));
        result.setLifecycleAuthorizationUsed(true);
        answer.setTargetResults(List.of(result));
        return answer;
    }

    private static class ApprovedManager extends VfPoolManagerImpl {
        private final VfOwnershipRepairPlan plan;

        private ApprovedManager(final VfOwnershipRepairPlan plan) {
            this.plan = plan;
        }

        @Override protected boolean isOwnershipRepairPlanEnabled() { return true; }
        @Override protected boolean isOwnershipRepairApplyEnabled() { return true; }
        @Override protected int approvedCandidateCount() { return plan.getCandidateCount(); }
        @Override protected String approvedCandidateIds() { return plan.getCandidateIds(); }
        @Override protected String approvedPlanHash() { return plan.getHash(); }
        @Override protected String approvedPlanToken() { return plan.getApprovalToken(); }
        @Override protected boolean isIncidentScopeApproved(final VfOwnershipRepairPlan ignored) { return true; }
    }

    /** Returns the pre-built approved plan so row drift is validated by exact findById. */
    private static final class FixedPlanManager extends ApprovedManager {
        private final VfOwnershipRepairPlan fixed;

        private FixedPlanManager(final VfOwnershipRepairPlan plan) {
            super(plan);
            this.fixed = plan;
        }

        @Override
        VfOwnershipRepairPlan buildOwnershipRepairPlan() {
            return fixed;
        }
    }

    private static final class IncidentFixture {
        private static final long[][] SPECS = {{857, 833, 8820}, {1427, 833, 8820}, {2435, 833, 8820},
                {764, 827, 8913}, {896, 827, 8913}, {995, 827, 8913}, {1469, 827, 8913},
                {2537, 827, 8913}, {1625, 1022, 8829}, {818, 749, 8847}, {1016, 2468, 8925}};
        /** Production WRONG_HOST_CANONICAL current promotion pool IDs (FREE + null owner). */
        private static final long[] WRONG_HOST_CURRENT_IDS = {1022L, 749L, 2468L};
        private final VfOwnershipRepairPlan plan;
        private final List<SriovVfPoolVO> allRows = new ArrayList<>();
        private final Map<Long, SriovVfPoolVO> rowsById = new LinkedHashMap<>();
        private final Map<Long, NicVO> nics = new HashMap<>();
        private final Map<Long, VMInstanceVO> vms = new HashMap<>();

        private IncidentFixture(final VfOwnershipRepairPlan.CandidateState[] states) throws Exception {
            final List<VfOwnershipRepairPlan.Candidate> candidates = new ArrayList<>();
            final Map<Long, SriovVfPoolVO> currentById = new HashMap<>();
            for (int index = 0; index < SPECS.length; index++) {
                final long staleId = SPECS[index][0];
                final long currentId = SPECS[index][1];
                final long nicId = SPECS[index][2];
                final boolean wrongHost = index >= 8;
                final VfOwnershipRepairPlan.CandidateState state = states[index];
                final State currentState = wrongHost && state == VfOwnershipRepairPlan.CandidateState.PENDING
                        ? State.FREE : State.ALLOCATED;
                final Long currentOwner = currentState == State.ALLOCATED ? nicId : null;
                final SriovVfPoolVO current = currentById.computeIfAbsent(currentId, ignored -> {
                    try {
                        return row(currentId, 16L, pci(currentId), currentState, currentOwner);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                registerRow(current);
                final State staleState = state == VfOwnershipRepairPlan.CandidateState.PENDING
                        ? State.ALLOCATED : state == VfOwnershipRepairPlan.CandidateState.QUARANTINED
                        ? State.SUSPECT : State.FREE;
                final SriovVfPoolVO stale = row(staleId, 269L, pci(staleId), staleState,
                        staleState == State.FREE ? null : nicId);
                registerRow(stale);
                final VfOwnershipRepairPlan.Kind kind = wrongHost
                        ? VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL
                        : VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE;
                candidates.add(new VfOwnershipRepairPlan.Candidate(kind, 1500L + nicId, nicId,
                        currentId, staleId, 16L, 269L, current.getPciAddress(), stale.getPciAddress(), mac(nicId)));
                final long vmId = 1500L + nicId;
                nics.computeIfAbsent(nicId, ignored -> {
                    try {
                        return realNic(nicId, mac(nicId), vmId, currentId);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                vms.computeIfAbsent(vmId, ignored -> realVm(vmId));
            }
            addFillers();
            plan = new VfOwnershipRepairPlan(candidates, 27, 237);
            assertTrue(plan.isExactIncidentScope());
        }

        private static IncidentFixture initialPending() throws Exception {
            return new IncidentFixture(states(VfOwnershipRepairPlan.CandidateState.PENDING));
        }
        private static IncidentFixture quarantined() throws Exception {
            return new IncidentFixture(states(VfOwnershipRepairPlan.CandidateState.QUARANTINED));
        }
        private static IncidentFixture partiallyCompleted(final int count) throws Exception {
            final VfOwnershipRepairPlan.CandidateState[] states = states(
                    VfOwnershipRepairPlan.CandidateState.QUARANTINED);
            for (int index = 0; index < count; index++) {
                states[index] = VfOwnershipRepairPlan.CandidateState.COMPLETED;
            }
            return new IncidentFixture(states);
        }
        private static IncidentFixture completed() throws Exception {
            return new IncidentFixture(states(VfOwnershipRepairPlan.CandidateState.COMPLETED));
        }
        private static IncidentFixture mixedProgress() throws Exception {
            final VfOwnershipRepairPlan.CandidateState[] states = states(
                    VfOwnershipRepairPlan.CandidateState.COMPLETED);
            states[0] = VfOwnershipRepairPlan.CandidateState.PENDING;
            states[1] = VfOwnershipRepairPlan.CandidateState.PENDING;
            states[2] = VfOwnershipRepairPlan.CandidateState.QUARANTINED;
            states[3] = VfOwnershipRepairPlan.CandidateState.QUARANTINED;
            states[4] = VfOwnershipRepairPlan.CandidateState.QUARANTINED;
            states[8] = VfOwnershipRepairPlan.CandidateState.PENDING;
            states[9] = VfOwnershipRepairPlan.CandidateState.QUARANTINED;
            return new IncidentFixture(states);
        }

        private static VfOwnershipRepairPlan.CandidateState[] states(
                final VfOwnershipRepairPlan.CandidateState state) {
            final VfOwnershipRepairPlan.CandidateState[] states = new VfOwnershipRepairPlan.CandidateState[11];
            java.util.Arrays.fill(states, state);
            return states;
        }

        private void registerRow(final SriovVfPoolVO row) {
            if (!rowsById.containsKey(row.getId())) {
                rowsById.put(row.getId(), row);
                allRows.add(row);
            }
        }

        private void addFillers() throws Exception {
            for (int index = 0; index < 14; index++) {
                registerRow(row(5000L + index, 300L, pci(5000L + index), State.ALLOCATED, 9000L + index));
            }
            for (int index = 0; index < 234; index++) {
                registerRow(row(10000L + index, 300L, pci(10000L + index), State.FREE, null));
            }
        }

        /** Production listByNicId: only rows whose allocated_to_nic_id equals nicId. */
        private List<SriovVfPoolVO> listByNicIdProduction(final long nicId) {
            final List<SriovVfPoolVO> owned = new ArrayList<>();
            for (final SriovVfPoolVO row : allRows) {
                if (Long.valueOf(nicId).equals(row.getAllocatedToNicId())) {
                    owned.add(row);
                }
            }
            return owned;
        }

        private SriovVfPoolVO rowById(final long id) {
            return rowsById.get(id);
        }

        private void removeRow(final long id) {
            final SriovVfPoolVO removed = rowsById.remove(id);
            if (removed != null) {
                allRows.remove(removed);
            }
        }

        private void setHostId(final long id, final long hostId) throws Exception {
            final Field field = SriovVfPoolVO.class.getDeclaredField("hostId");
            field.setAccessible(true);
            field.setLong(rowById(id), hostId);
        }

        private void setPciAddress(final long id, final String bdf) throws Exception {
            final Field field = SriovVfPoolVO.class.getDeclaredField("pciAddress");
            field.setAccessible(true);
            field.set(rowById(id), bdf);
        }

        private boolean complete(final long staleId) {
            final SriovVfPoolVO stale = rowsById.get(staleId);
            if (stale == null || State.FREE.name().equals(stale.getState())) {
                return false;
            }
            stale.setState(State.FREE);
            stale.setAllocatedToNicId(null);
            return true;
        }

        private void quarantine(final List<VfReconciliationCandidate> candidates) {
            for (final VfReconciliationCandidate candidate : candidates) {
                final SriovVfPoolVO current = rowsById.get(candidate.getCurrentPoolId());
                final SriovVfPoolVO stale = rowsById.get(candidate.getStalePoolId());
                current.setState(State.ALLOCATED);
                current.setAllocatedToNicId(candidate.getNicId());
                stale.setState(State.SUSPECT);
                stale.setAllocatedToNicId(candidate.getNicId());
            }
        }

        private int count(final State state) {
            return (int) allRows.stream().filter(row -> state.name().equals(row.getState())).count();
        }

        private NicVO nic(final long nicId) {
            return nics.get(nicId);
        }

        private VMInstanceVO vm(final long vmId) {
            return vms.get(vmId);
        }

        private SriovVfPoolVO row(final long id, final long hostId, final String bdf,
                                  final State state, final Long nicId) throws Exception {
            final SriovVfPoolVO row = new SriovVfPoolVO(hostId, bdf, "pf", "rep" + id);
            final Field idField = SriovVfPoolVO.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.setLong(row, id);
            row.setState(state);
            row.setAllocatedToNicId(nicId);
            return row;
        }

        /** Real VO — avoid Mockito/Byte Buddy instrumentation of JPA entities on JDK 25. */
        private static NicVO realNic(final long nicId, final String mac, final long vmId,
                                      final long vfPoolId) throws Exception {
            final NicVO nic = new NicVO("test", vmId, 1L, VirtualMachine.Type.User);
            final Field idField = NicVO.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.setLong(nic, nicId);
            nic.setMacAddress(mac);
            nic.setVfPoolId(vfPoolId);
            return nic;
        }

        /** Real VO — avoid Mockito/Byte Buddy instrumentation of JPA entities on JDK 25. */
        private static VMInstanceVO realVm(final long vmId) {
            final VMInstanceVO vm = new VMInstanceVO();
            // id is set via protected field on construction path; use reflection for empty ctor.
            try {
                final Field idField = VMInstanceVO.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.setLong(vm, vmId);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            vm.setHostId(16L);
            vm.setState(VirtualMachine.State.Running);
            return vm;
        }

        private static String pci(final long id) {
            return String.format("0000:%02x:%02x.%d", id / 256 & 255, id / 8 & 31, id & 7);
        }

        private static String mac(final long id) {
            return String.format("02:00:00:%02x:%02x:%02x", id >> 16 & 255, id >> 8 & 255, id & 255);
        }
    }
}
