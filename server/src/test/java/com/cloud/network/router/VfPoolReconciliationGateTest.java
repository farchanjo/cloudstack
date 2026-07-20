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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer.TargetResult;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

public class VfPoolReconciliationGateTest {

    @Test
    public void postCleanupMigrationRaceLeavesRowSuspectThroughFullSingletonGate() throws Exception {
        final long vmId = 1553L;
        final long nicId = 8820L;
        final long currentHost = 16L;
        final long staleHost = 269L;
        final String staleBdf = "0000:01:07.2";
        final String mac = "02:04:02:9b:00:07";
        final VfOwnershipRepairPlan.Candidate candidate = new VfOwnershipRepairPlan.Candidate(
                VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE, vmId, nicId, 833L, 2435L,
                currentHost, staleHost, "0000:01:00.5", staleBdf, mac);
        final VfOwnershipRepairPlan plan = new VfOwnershipRepairPlan(
                Collections.singletonList(candidate), 27, 237);
        final GlobalLock lock = mock(GlobalLock.class);
        final ApprovedManager manager = new ApprovedManager(lock, plan);
        final VfPoolReconcileLeader leader = mock(VfPoolReconcileLeader.class);
        final SriovVfPoolDao dao = mock(SriovVfPoolDao.class);
        final AgentManager agents = mock(AgentManager.class);
        final NicDao nics = mock(NicDao.class);
        final NicVO nic = mock(NicVO.class);
        final SriovVfPoolVO stale = mock(SriovVfPoolVO.class);
        ReflectionTestUtils.setField(manager, "reconcileLeader", leader);
        ReflectionTestUtils.setField(manager, "vfPoolDao", dao);
        ReflectionTestUtils.setField(manager, "agentMgr", agents);
        ReflectionTestUtils.setField(manager, "nicDao", nics);
        when(leader.isLeader()).thenReturn(true);
        when(lock.lock(1)).thenReturn(true);
        when(dao.prepareReconciliationPlan(any())).thenReturn(true);
        when(stale.getId()).thenReturn(2435L);
        when(stale.getHostId()).thenReturn(staleHost);
        when(stale.getPciAddress()).thenReturn(staleBdf);
        when(stale.getState()).thenReturn(State.SUSPECT.name());
        when(stale.getAllocatedToNicId()).thenReturn(nicId);
        when(stale.getRepresentorName()).thenReturn("rep2435");
        when(dao.findById(2435L)).thenReturn(stale);
        when(nic.getMacAddress()).thenReturn(mac);
        when(nic.getUuid()).thenReturn("nic-uuid");
        when(nics.findByIdIncludingRemoved(nicId)).thenReturn(nic);
        when(agents.send(eq(staleHost), any(HostVfPurgeOrphansCommand.class)))
                .thenReturn(successAnswer(staleBdf, plan.getHash(), mac));
        when(dao.completeReconciliation(vmId, nicId, currentHost, 833L, 2435L)).thenReturn(false);

        manager.runSweepIfLeader(manager);

        verify(dao).sweepOrphans();
        verify(dao).prepareReconciliationPlan(any());
        verify(dao).completeReconciliation(vmId, nicId, currentHost, 833L, 2435L);
        verify(dao, never()).releaseExact(2435L, nicId);
        final InOrder ownershipOrder = inOrder(dao, agents);
        ownershipOrder.verify(dao).prepareReconciliationPlan(any());
        ownershipOrder.verify(agents).send(eq(staleHost), any(HostVfPurgeOrphansCommand.class));
        ownershipOrder.verify(dao).completeReconciliation(vmId, nicId, currentHost, 833L, 2435L);
        verify(lock).unlock();
        verify(lock).releaseRef();
    }

    private static HostVfPurgeOrphansAnswer successAnswer(final String bdf, final String operation,
                                                          final String mac) {
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        final HostVfPurgeOrphansAnswer answer = new HostVfPurgeOrphansAnswer(command, true, "ok");
        final TargetResult result = new TargetResult(bdf, true, false, false, false, false, "absent");
        result.setObservationComplete(true);
        result.setMacObservation("UNASSIGNED_ZERO");
        result.setExpectedMac(mac);
        result.setOwnerOperationId(operation);
        result.setOwnerPurpose("RECONCILE");
        result.setOwnerToken(HostVfPurgeOrphansCommand.createOwnerToken(bdf, mac, operation, "RECONCILE"));
        result.setLifecycleAuthorizationUsed(true);
        answer.setTargetResults(Collections.singletonList(result));
        return answer;
    }

    private static final class ApprovedManager extends VfPoolManagerImpl {
        private final GlobalLock lock;
        private final VfOwnershipRepairPlan plan;

        private ApprovedManager(final GlobalLock lock, final VfOwnershipRepairPlan plan) {
            this.lock = lock;
            this.plan = plan;
        }

        @Override
        protected GlobalLock getSweepLock() {
            return lock;
        }

        @Override
        protected boolean isOwnershipRepairPlanEnabled() {
            return true;
        }

        @Override
        protected boolean isOwnershipRepairApplyEnabled() {
            return true;
        }

        @Override
        protected int approvedCandidateCount() {
            return plan.getCandidateCount();
        }

        @Override
        protected String approvedCandidateIds() {
            return plan.getCandidateIds();
        }

        @Override
        protected String approvedPlanHash() {
            return plan.getHash();
        }

        @Override
        protected String approvedPlanToken() {
            return plan.getApprovalToken();
        }

        @Override
        protected boolean isIncidentScopeApproved(final VfOwnershipRepairPlan ignored) {
            return true;
        }

        @Override
        VfOwnershipRepairPlan buildOwnershipRepairPlan() {
            return plan;
        }
    }
}
