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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer.TargetResult;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.vm.ItWorkDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VfPoolOwnershipLifecycleTest {

    private static final long NIC_ID = 8820L;
    private static final long VM_ID = 1553L;
    private static final long SOURCE_HOST = 269L;
    private static final long DESTINATION_HOST = 16L;

    @Mock
    private SriovVfPoolDao vfPoolDao;
    @Mock
    private NicDao nicDao;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private ItWorkDao workDao;
    @Mock
    private AgentManager agentManager;
    @InjectMocks
    private VfPoolManagerImpl manager;

    @Test
    public void commitPromotesDestinationAndReleasesOnlyConfirmedPriorRow() throws Exception {
        final NicVO nic = nic();
        final SriovVfPoolVO stale = row(2435L, SOURCE_HOST, "0000:01:07.2", State.SUSPECT, NIC_ID);
        when(nic.getMacAddress()).thenReturn("02:04:02:9b:00:07");
        when(nicDao.findByIdIncludingRemoved(NIC_ID)).thenReturn(nic);
        when(vfPoolDao.commitVmReservations(VM_ID, SOURCE_HOST, DESTINATION_HOST, "work-commit"))
                .thenReturn(Collections.singletonList(stale));
        when(agentManager.send(eq(SOURCE_HOST), any(HostVfPurgeOrphansCommand.class)))
                .thenReturn(successAnswer("0000:01:07.2", "ABSENT", null, null,
                        "work-commit", "OWNERSHIP_COMMIT", NIC_ID));
        when(vfPoolDao.releaseExact(2435L, NIC_ID)).thenReturn(true);

        manager.commitOwnershipForVm(VM_ID, SOURCE_HOST, DESTINATION_HOST, "work-commit");

        verify(vfPoolDao).commitVmReservations(VM_ID, SOURCE_HOST, DESTINATION_HOST, "work-commit");
        verify(vfPoolDao).releaseExact(2435L, NIC_ID);
        verify(vfPoolDao, never()).releaseByNicId(NIC_ID);
    }

    @Test
    public void rollbackLeavesReservationSuspectWithoutPositiveAgentEvidence() throws Exception {
        final NicVO nic = nic();
        final SriovVfPoolVO reservation = row(2435L, DESTINATION_HOST, "0000:01:07.2", State.RESERVED, NIC_ID);
        when(nic.getMacAddress()).thenReturn("02:04:02:9b:00:07");
        when(nicDao.findByIdIncludingRemoved(NIC_ID)).thenReturn(nic);
        when(vfPoolDao.quarantineVmDestinationRows(VM_ID, DESTINATION_HOST, false, "work-rollback"))
                .thenReturn(Collections.singletonList(reservation));
        when(agentManager.send(eq(DESTINATION_HOST), any(HostVfPurgeOrphansCommand.class)))
                .thenReturn(new HostVfPurgeOrphansAnswer(new HostVfPurgeOrphansCommand(), true, "legacy answer"));

        manager.rollbackReservationsForVm(VM_ID, DESTINATION_HOST, true, "work-rollback");

        verify(vfPoolDao, never()).releaseExact(2435L, NIC_ID);
    }

    @Test
    public void failedFirstStartCleansItsCanonicalAllocatedAttemptByExactRow() throws Exception {
        final NicVO nic = nic();
        final SriovVfPoolVO attempt = row(833L, DESTINATION_HOST, "0000:01:00.5", State.ALLOCATED, NIC_ID);
        when(nic.getMacAddress()).thenReturn("02:04:02:9b:00:07");
        when(nicDao.findByIdIncludingRemoved(NIC_ID)).thenReturn(nic);
        when(vfPoolDao.quarantineVmDestinationRows(VM_ID, DESTINATION_HOST, true, "work-start"))
                .thenReturn(Collections.singletonList(attempt));
        when(agentManager.send(eq(DESTINATION_HOST), any(HostVfPurgeOrphansCommand.class)))
                .thenReturn(successAnswer("0000:01:00.5", "ABSENT", null, null,
                        "work-start", "STAGE_ROLLBACK", NIC_ID));
        when(vfPoolDao.releaseExact(833L, NIC_ID)).thenReturn(true);

        manager.rollbackStartAttemptForVm(VM_ID, DESTINATION_HOST, true, "work-start");

        verify(vfPoolDao).releaseExact(833L, NIC_ID);
        verify(vfPoolDao, never()).releaseByNicId(NIC_ID);
    }

    @Test
    public void expungeCleansRowWhenNicVfMappingIsAlreadyNull() throws Exception {
        final NicVO nic = nic();
        final SriovVfPoolVO row = row(980L, SOURCE_HOST, "0000:01:04.1", State.ALLOCATED, NIC_ID);
        when(nic.getMacAddress()).thenReturn("02:04:02:a6:00:01");
        when(nic.getUuid()).thenReturn("db91cde8-e9ab-4f0a-a6f1-37f562be2536");
        when(nicDao.findByIdIncludingRemoved(NIC_ID)).thenReturn(nic);
        when(vfPoolDao.quarantineAndListByVmId(VM_ID)).thenReturn(Collections.singletonList(row));
        when(agentManager.send(eq(SOURCE_HOST), any(HostVfPurgeOrphansCommand.class)))
                .thenReturn(successAnswer("0000:01:04.1", "ABSENT", null, null,
                        "vm-release-" + VM_ID, "LIFECYCLE_RELEASE", NIC_ID,
                        "02:04:02:a6:00:01"));
        when(vfPoolDao.releaseExact(980L, NIC_ID)).thenReturn(true);

        assertEquals(1, manager.quarantineByVmId(VM_ID));

        verify(vfPoolDao).releaseExact(980L, NIC_ID);
        verify(vfPoolDao, never()).releaseByNicId(NIC_ID);
    }

    @Test
    public void repeatedVmCleanupIsIdempotentAndDoesNotReleaseUnknownRows() {
        when(vfPoolDao.quarantineAndListByVmId(VM_ID))
                .thenReturn(Collections.emptyList(), Collections.emptyList());

        assertEquals(0, manager.quarantineByVmId(VM_ID));
        assertEquals(0, manager.quarantineByVmId(VM_ID));

        verify(vfPoolDao, never()).releaseExact(anyLong(), anyLong());
    }

    private static NicVO nic() {
        return org.mockito.Mockito.mock(NicVO.class);
    }

    private static SriovVfPoolVO row(final long id, final long hostId, final String bdf,
                                     final State state, final Long nicId) throws Exception {
        final SriovVfPoolVO row = new SriovVfPoolVO(hostId, bdf, "dx6p1", "dx6p1vf24");
        final Field idField = SriovVfPoolVO.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(row, id);
        row.setState(state);
        row.setAllocatedToNicId(nicId);
        return row;
    }

    private static HostVfPurgeOrphansAnswer successAnswer(final String bdf, final String state,
                                                           final String mac, final String vdpaName,
                                                           final String operation, final String purpose,
                                                           final long nicId) {
        return successAnswer(bdf, state, mac, vdpaName, operation, purpose, nicId,
                "02:04:02:9b:00:07");
    }

    private static HostVfPurgeOrphansAnswer successAnswer(final String bdf, final String state,
                                                           final String mac, final String vdpaName,
                                                           final String operation, final String purpose,
                                                           final long nicId, final String expectedMac) {
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        final HostVfPurgeOrphansAnswer answer = new HostVfPurgeOrphansAnswer(command, true, "ok");
        final TargetResult result = new TargetResult(bdf, true, true, false, false, false, "ok");
        result.setBindingState(state);
        result.setCurrentMac(mac);
        result.setVdpaName(vdpaName);
        result.setObservationComplete(true);
        result.setMacObservation("UNASSIGNED_ZERO");
        result.setExpectedMac(expectedMac);
        result.setOwnerOperationId(operation);
        result.setOwnerPurpose(purpose);
        result.setOwnerToken(HostVfPurgeOrphansCommand.createOwnerToken(
                bdf, result.getExpectedMac(), operation, purpose));
        result.setLifecycleAuthorizationUsed(true);
        answer.setTargetResults(Arrays.asList(result));
        return answer;
    }
}
