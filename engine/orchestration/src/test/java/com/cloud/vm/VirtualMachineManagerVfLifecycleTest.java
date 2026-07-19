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
package com.cloud.vm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.AgentManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.agent.api.StartAnswer;
import com.cloud.network.router.VfPoolManager;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.vm.dao.NicDao;

public class VirtualMachineManagerVfLifecycleTest {

    private VirtualMachineManagerImpl manager;
    private VfPoolManager vfPoolManager;

    @Before
    public void setUp() {
        manager = new VirtualMachineManagerImpl();
        vfPoolManager = mock(VfPoolManager.class);
        ReflectionTestUtils.setField(manager, "vfPoolManager", vfPoolManager);
    }

    @Test
    public void migrationSuccessDelegatesExactSourceAndDestinationCommit() {
        manager.commitVfOwnership(1553L, 269L, 16L, "migration", "work-1");

        verify(vfPoolManager).commitOwnershipForVm(1553L, 269L, 16L, "work-1");
    }

    @Test
    public void ownershipCommitFailureIsPropagated() {
        doThrow(new IllegalStateException("ownership conflict")).when(vfPoolManager)
                .commitOwnershipForVm(1553L, 269L, 16L, "work-fail");

        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> manager.commitVfOwnership(1553L, 269L, 16L, "migration", "work-fail"));
    }

    @Test
    public void strictRollbackFailureIsPropagatedForColdRecovery() {
        doThrow(new IllegalStateException("restore conflict")).when(vfPoolManager)
                .rollbackReservationsForVm(1553L, 16L, true, "cold-migration");

        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> manager.rollbackVfReservationsStrict(1553L, 16L, true,
                        "cold-migration", "cold-migration"));
    }

    @Test
    public void migrationTimeoutRollsBackWithoutAuthorizingDestructiveCleanup() {
        manager.rollbackVfReservationsBestEffort(1553L, 16L, false, "migration-timeout", "work-2");

        verify(vfPoolManager).rollbackReservationsForVm(1553L, 16L, false, "work-2");
    }

    @Test
    public void failedStartDelegatesAllocatedAttemptRollback() {
        manager.rollbackVfStartAttemptBestEffort(1553L, 16L, true, "work-3");

        verify(vfPoolManager).rollbackStartAttemptForVm(1553L, 16L, true, "work-3");
    }

    @Test
    public void timedOutMigrationBranchNeverCommitsEvenWhenDestinationIsLaterObserved() {
        manager.finalizeVfOwnershipAfterMigration(1553L, 269L, 16L,
                false, true, "migration", "work-timeout");

        verify(vfPoolManager, never()).commitOwnershipForVm(1553L, 269L, 16L, "work-timeout");
        verify(vfPoolManager).rollbackReservationsForVm(1553L, 16L, false, "work-timeout");
    }

    @Test
    public void inconclusiveStorageOrScaleDestinationBranchNeverCommits() {
        manager.finalizeVfOwnershipAfterMigration(1553L, 269L, 16L,
                true, false, "storage-migration", "work-check-timeout");

        verify(vfPoolManager, never()).commitOwnershipForVm(1553L, 269L, 16L, "work-check-timeout");
        verify(vfPoolManager).rollbackReservationsForVm(1553L, 16L, false, "work-check-timeout");
    }

    @Test
    public void successfulStartAnswerFollowedByFinalizeFailureDoesNotAuthorizeHardwareCleanupUntilStopConfirmed() {
        final StartAnswer answer = mock(StartAnswer.class);
        when(answer.getResult()).thenReturn(true);

        assertFalse(VirtualMachineManagerImpl.authorizeFailedStartHardwareCleanup(answer, false, true));
        assertTrue(VirtualMachineManagerImpl.authorizeFailedStartHardwareCleanup(answer, true, true));
    }

    @Test
    public void coldDestinationProfileRejectsOmittedAuthoritativeNic() {
        final NetworkOrchestrationService networkManager = mock(NetworkOrchestrationService.class);
        final NicDao nicDao = mock(NicDao.class);
        final VMInstanceVO vm = mock(VMInstanceVO.class);
        final NicProfile profileNic = mock(NicProfile.class);
        final NicVO inventoryNic = mock(NicVO.class);
        when(vm.getId()).thenReturn(1553L);
        when(vm.getUuid()).thenReturn("vm-1");
        when(networkManager.getNicProfiles(vm)).thenReturn(java.util.List.of(profileNic));
        when(profileNic.getId()).thenReturn(1L);
        when(inventoryNic.getId()).thenReturn(1L);
        when(nicDao.listByVmId(1553L)).thenReturn(java.util.List.of(inventoryNic, mock(NicVO.class)));
        ReflectionTestUtils.setField(manager, "_networkMgr", networkManager);
        ReflectionTestUtils.setField(manager, "_nicsDao", nicDao);

        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(manager, "migrationProfile", vm, mock(Host.class)));
    }

    @Test
    public void coldDestinationProfileRejectsDuplicateAuthoritativeNicIdentity() {
        final NetworkOrchestrationService networkManager = mock(NetworkOrchestrationService.class);
        final NicDao nicDao = mock(NicDao.class);
        final VMInstanceVO vm = mock(VMInstanceVO.class);
        final NicProfile first = mock(NicProfile.class);
        final NicProfile second = mock(NicProfile.class);
        final NicVO inventory = mock(NicVO.class);
        when(vm.getId()).thenReturn(1553L);
        when(vm.getUuid()).thenReturn("vm-1");
        when(networkManager.getNicProfiles(vm)).thenReturn(java.util.List.of(first, second));
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(1L);
        when(inventory.getId()).thenReturn(1L);
        when(nicDao.listByVmId(1553L)).thenReturn(java.util.List.of(inventory, mock(NicVO.class)));
        ReflectionTestUtils.setField(manager, "_networkMgr", networkManager);
        ReflectionTestUtils.setField(manager, "_nicsDao", nicDao);

        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(manager, "migrationProfile", vm, mock(Host.class)));
    }

    @Test
    public void destinationStampFailureIsFatalForVdpa() throws Exception {
        final AgentManager agentManager = mock(AgentManager.class);
        final Answer failedStamp = mock(Answer.class);
        when(failedStamp.getResult()).thenReturn(false);
        when(agentManager.send(org.mockito.ArgumentMatchers.eq(16L),
                org.mockito.ArgumentMatchers.any(Command.class))).thenReturn(failedStamp);
        final MigrationVfPreflight preflight = mock(MigrationVfPreflight.class);
        final VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        when(vm.getInstanceName()).thenReturn("i-1-VM");
        final NicTO nic = new NicTO();
        nic.setUseVdpa(true);
        nic.setUuid("nic-1");
        final VirtualMachineTO to = mock(VirtualMachineTO.class);
        when(to.getNics()).thenReturn(new NicTO[]{nic});
        ReflectionTestUtils.setField(manager, "_agentMgr", agentManager);
        ReflectionTestUtils.setField(manager, "migrationVfPreflight", preflight);

        assertFalse(manager.dispatchPostMigrateOvnStamp(vm, to, 16L));
    }

    @Test
    public void reorderedStampVerifierFailureIsFatalAfterStampAnswer() throws Exception {
        final AgentManager agentManager = mock(AgentManager.class);
        final Answer stamp = mock(Answer.class);
        final Answer verifier = mock(Answer.class);
        when(stamp.getResult()).thenReturn(true);
        when(verifier.getResult()).thenReturn(false);
        when(agentManager.send(org.mockito.ArgumentMatchers.eq(16L),
                org.mockito.ArgumentMatchers.any(Command.class))).thenReturn(stamp, verifier);
        final HostDao hostDao = mock(HostDao.class);
        final HostVO host = mock(HostVO.class);
        final MigrationVfPreflight preflight = mock(MigrationVfPreflight.class);
        when(hostDao.findById(16L)).thenReturn(host);
        when(preflight.expectedChassis(host)).thenReturn("chassis-1");
        final VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        when(vm.getInstanceName()).thenReturn("i-1-VM");
        final NicTO nic = new NicTO();
        nic.setUseVdpa(true);
        nic.setUuid("nic-1");
        final VirtualMachineTO to = mock(VirtualMachineTO.class);
        when(to.getNics()).thenReturn(new NicTO[]{nic});
        ReflectionTestUtils.setField(manager, "_agentMgr", agentManager);
        ReflectionTestUtils.setField(manager, "_hostDao", hostDao);
        ReflectionTestUtils.setField(manager, "migrationVfPreflight", preflight);

        assertFalse(manager.dispatchPostMigrateOvnStamp(vm, to, 16L));
    }
}
