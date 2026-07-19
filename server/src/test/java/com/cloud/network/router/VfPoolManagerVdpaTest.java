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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer.TargetResult;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Public-API behaviour tests for the vDPA branch of {@link VfPoolManagerImpl}.
 * The DAO is mocked; we only assert that the manager fans the call through
 * and propagates the row state correctly. End-to-end SQL behaviour (lockRows,
 * idempotency, releaseByVmId JOIN) is exercised in the schema integration
 * tests under {@code engine/schema/src/test/}.
 */
@RunWith(MockitoJUnitRunner.class)
public class VfPoolManagerVdpaTest {

    private static final long HOST_ID = 42L;
    private static final long NIC_ID = 4242L;
    private static final String MAC = "aa:bb:cc:dd:ee:ff";
    private static final int MAX_VQS = 33;

    @Mock
    private SriovVfPoolDao vfPoolDao;
    @Mock
    private NicDao nicDao;
    @Mock
    private AgentManager agentManager;

    @InjectMocks
    private VfPoolManagerImpl manager;

    @Before
    public void setUp() {
        // No-op: @InjectMocks wires the DAO into the manager.
    }

    @Test
    public void allocateForVdpaReturnsVdpaKindRowOnSuccess() {
        SriovVfPoolVO row = newAllocatedRow();
        when(vfPoolDao.allocateForVdpa(eq(HOST_ID), eq(NIC_ID), eq(MAC), eq(MAX_VQS)))
                .thenReturn(row);

        SriovVfPoolVO result = manager.allocateForVdpa(HOST_ID, NIC_ID, MAC, MAX_VQS);
        assertNotNull(result);
        assertEquals(State.ALLOCATED.name(), result.getState());
        assertEquals(VdpaKind.VDPA.name(), result.getVdpaKind());
        assertEquals(VdpaKind.VDPA, result.getVdpaKindEnum());
        assertEquals("vdpa-" + NIC_ID, result.getVdpaName());
        verify(vfPoolDao, times(1)).allocateForVdpa(HOST_ID, NIC_ID, MAC, MAX_VQS);
    }

    @Test
    public void allocateForVdpaReturnsNullWhenCapacityExhausted() {
        when(vfPoolDao.allocateForVdpa(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(null);

        assertNull(manager.allocateForVdpa(HOST_ID, NIC_ID, MAC, MAX_VQS));
        verify(vfPoolDao, times(1)).allocateForVdpa(HOST_ID, NIC_ID, MAC, MAX_VQS);
    }

    @Test
    public void countFreeForVdpaUsesFreePoolCount() {
        when(vfPoolDao.countFreeVdpaCapable(eq(HOST_ID))).thenReturn(3);

        assertEquals(3, manager.countFreeForVdpa(HOST_ID));
        verify(vfPoolDao).countFreeVdpaCapable(HOST_ID);
    }

    @Test
    public void releaseVdpaUsesExactAgentEvidenceBeforeRelease() throws Exception {
        final SriovVfPoolVO row = rowWithId(7L, "0000:01:00.5", "dx6p1", "dx6p1vf1", State.ALLOCATED);
        row.setAllocatedToNicId(NIC_ID);
        final NicVO nic = org.mockito.Mockito.mock(NicVO.class);
        when(nic.getMacAddress()).thenReturn(MAC);
        when(nic.getUuid()).thenReturn("nic-uuid-7");
        when(vfPoolDao.findById(7L)).thenReturn(row);
        when(nicDao.findByIdIncludingRemoved(NIC_ID)).thenReturn(nic);
        when(agentManager.send(eq(HOST_ID), any(HostVfPurgeOrphansCommand.class)))
                .thenReturn(successAnswer("0000:01:00.5"));
        when(vfPoolDao.releaseExact(7L, NIC_ID)).thenReturn(true);

        assertTrue(manager.releaseVdpa(7L));
        verify(vfPoolDao).releaseExact(7L, NIC_ID);
    }

    @Test
    public void releaseVdpaReturnsFalseOnUnknownRow() {
        assertFalse(manager.releaseVdpa(404L));
        verify(vfPoolDao, never()).releaseExact(anyLong(), anyLong());
    }

    @Test
    public void releaseVdpaNeverInvokesDbOnlyFreePrimitive() {
        manager.releaseVdpa(404L);
        verify(vfPoolDao, never()).releaseVdpa(anyLong());
        verify(vfPoolDao, never()).release(anyLong());
    }

    @Test
    public void setPfCarrierDownMarksOnlyFreeRowsUnavailable() throws Exception {
        SriovVfPoolVO freeOnDeadPf = rowWithId(10L, "0000:01:00.4", "dx6p1", "dx6p1vf0", State.FREE);
        SriovVfPoolVO allocatedOnDeadPf = rowWithId(11L, "0000:01:00.5", "dx6p1", "dx6p1vf1", State.ALLOCATED);
        SriovVfPoolVO freeOnGoodPf = rowWithId(12L, "0000:01:00.3", "dx6p0", "dx6p0vf1", State.FREE);

        when(vfPoolDao.listByHost(HOST_ID)).thenReturn(List.of(freeOnDeadPf, allocatedOnDeadPf, freeOnGoodPf));
        when(vfPoolDao.createForUpdate()).thenAnswer(inv -> new SriovVfPoolVO());

        manager.setPfCarrierAvailability(HOST_ID, "dx6p1", false);

        ArgumentCaptor<Long> idCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<SriovVfPoolVO> voCap = ArgumentCaptor.forClass(SriovVfPoolVO.class);
        verify(vfPoolDao, times(1)).update(idCap.capture(), voCap.capture());
        assertEquals(Long.valueOf(10L), idCap.getValue());
        assertEquals(State.UNAVAILABLE.name(), voCap.getValue().getState());
    }

    @Test
    public void setPfCarrierUpRestoresUnavailableToFree() throws Exception {
        SriovVfPoolVO unavailable = rowWithId(20L, "0000:01:00.4", "dx6p1", "dx6p1vf0", State.UNAVAILABLE);
        SriovVfPoolVO allocated = rowWithId(21L, "0000:01:00.5", "dx6p1", "dx6p1vf1", State.ALLOCATED);

        when(vfPoolDao.listByHost(HOST_ID)).thenReturn(List.of(unavailable, allocated));
        when(vfPoolDao.createForUpdate()).thenAnswer(inv -> new SriovVfPoolVO());

        manager.setPfCarrierAvailability(HOST_ID, "dx6p1", true);

        ArgumentCaptor<Long> idCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<SriovVfPoolVO> voCap = ArgumentCaptor.forClass(SriovVfPoolVO.class);
        verify(vfPoolDao, times(1)).update(idCap.capture(), voCap.capture());
        assertEquals(Long.valueOf(20L), idCap.getValue());
        assertEquals(State.FREE.name(), voCap.getValue().getState());
    }

    @Test
    public void setPfCarrierIgnoresBlankPfName() {
        manager.setPfCarrierAvailability(HOST_ID, "", false);
        manager.setPfCarrierAvailability(HOST_ID, "   ", false);
        manager.setPfCarrierAvailability(HOST_ID, null, false);
        verify(vfPoolDao, never()).listByHost(anyLong());
        verify(vfPoolDao, never()).update(anyLong(), any());
    }

    @Test
    public void setPfCarrierDownFlipsAllFreeOnPfLeavesReservedSuspect() throws Exception {
        SriovVfPoolVO free1 = rowWithId(30L, "0000:01:00.4", "dx6p1", "dx6p1vf0", State.FREE);
        SriovVfPoolVO free2 = rowWithId(31L, "0000:01:00.5", "dx6p1", "dx6p1vf1", State.FREE);
        SriovVfPoolVO reserved = rowWithId(32L, "0000:01:00.6", "dx6p1", "dx6p1vf2", State.RESERVED);
        SriovVfPoolVO suspect = rowWithId(33L, "0000:01:00.7", "dx6p1", "dx6p1vf3", State.SUSPECT);

        when(vfPoolDao.listByHost(HOST_ID)).thenReturn(List.of(free1, free2, reserved, suspect));
        when(vfPoolDao.createForUpdate()).thenAnswer(inv -> new SriovVfPoolVO());

        manager.setPfCarrierAvailability(HOST_ID, " dx6p1 ", false);

        ArgumentCaptor<Long> idCap = ArgumentCaptor.forClass(Long.class);
        verify(vfPoolDao, times(2)).update(idCap.capture(), any(SriovVfPoolVO.class));
        assertEquals(List.of(30L, 31L), idCap.getAllValues());
    }

    /**
     * Build a row in the shape the DAO returns from {@code allocateForVdpa}
     * after it has flipped state and stamped the vdpa fields on the chosen
     * row.
     */
    private static SriovVfPoolVO newAllocatedRow() {
        SriovVfPoolVO row = new SriovVfPoolVO(HOST_ID, "0000:01:00.3", "dx6p0", "dx6p0vf1");
        row.setState(State.ALLOCATED);
        row.setAllocatedToNicId(NIC_ID);
        row.setVdpaKind(VdpaKind.VDPA);
        row.setVdpaName("vdpa-" + NIC_ID);
        return row;
    }

    private static HostVfPurgeOrphansAnswer successAnswer(final String bdf) {
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        final HostVfPurgeOrphansAnswer answer = new HostVfPurgeOrphansAnswer(command, true, "ok");
        final TargetResult result = new TargetResult(bdf, true, false, false, false, false, "absent");
        result.setObservationComplete(true);
        result.setMacObservation("UNASSIGNED_ZERO");
        result.setExpectedMac(MAC);
        result.setOwnerOperationId("release-7");
        result.setOwnerPurpose("LIFECYCLE_RELEASE");
        result.setOwnerToken(HostVfPurgeOrphansCommand.createOwnerToken(
                bdf, MAC, "release-7", "LIFECYCLE_RELEASE"));
        result.setLifecycleAuthorizationUsed(true);
        answer.setTargetResults(List.of(result));
        return answer;
    }

    private static SriovVfPoolVO rowWithId(long id, String pci, String pf, String rep, State state) throws Exception {
        SriovVfPoolVO row = new SriovVfPoolVO(HOST_ID, pci, pf, rep);
        row.setState(state);
        final java.lang.reflect.Field idField = SriovVfPoolVO.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setLong(row, id);
        return row;
    }
}
