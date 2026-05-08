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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
import com.cloud.network.router.dao.SriovVfPoolDao;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    public void releaseVdpaDelegatesToDao() {
        when(vfPoolDao.releaseVdpa(7L)).thenReturn(true);
        assertTrue(manager.releaseVdpa(7L));
        verify(vfPoolDao, times(1)).releaseVdpa(7L);
    }

    @Test
    public void releaseVdpaReturnsFalseOnUnknownRow() {
        when(vfPoolDao.releaseVdpa(404L)).thenReturn(false);
        assertFalse(manager.releaseVdpa(404L));
        verify(vfPoolDao, times(1)).releaseVdpa(404L);
    }

    @Test
    public void releaseVdpaDoesNotInvokePassthroughRelease() {
        // Defensive: the manager must not silently re-route a vDPA release
        // through release() — that would leave vdpa_name / vdpa_device set
        // on the row.
        when(vfPoolDao.releaseVdpa(7L)).thenReturn(true);
        manager.releaseVdpa(7L);
        verify(vfPoolDao, never()).release(anyLong());
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
}
