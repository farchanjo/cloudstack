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
package com.cloud.network.router.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.junit.Test;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;

/**
 * Bookkeeping invariants for {@code sriov_vf_pool} free/allocate stamping.
 * Covers the three VF-chaos bugs: free must blank {@code vdpa_name}, allocate
 * must bump {@code updated}, and hostdev PASSTHROUGH must force
 * {@code vdpa_kind=PASSTHROUGH} (not inherit a stale VDPA kind).
 */
public class SriovVfPoolBookkeepingTest {

    private static final long NIC_ID = 99L;

    @Test
    public void applyFreeStateClearsVdpaFieldsAndForcesPassthrough() {
        SriovVfPoolVO row = staleVdpaRow();

        SriovVfPoolDaoImpl.applyFreeState(row);

        assertEquals(State.FREE.name(), row.getState());
        assertNull(row.getAllocatedToNicId());
        assertEquals(VdpaKind.PASSTHROUGH.name(), row.getVdpaKind());
        assertNull(row.getVdpaName());
        assertNull(row.getVdpaDevice());
        assertNotNull("free must bump updated", row.getUpdated());
    }

    @Test
    public void applyPassthroughAllocatedForcesKindAndBlanksVdpaName() {
        SriovVfPoolVO row = staleVdpaRow();
        Date before = new Date(System.currentTimeMillis() - 60_000L);
        row.setUpdated(before);

        SriovVfPoolDaoImpl.applyPassthroughAllocated(row, NIC_ID);

        assertEquals(State.ALLOCATED.name(), row.getState());
        assertEquals(Long.valueOf(NIC_ID), row.getAllocatedToNicId());
        assertEquals("hostdev path must never leave kind=VDPA",
                VdpaKind.PASSTHROUGH.name(), row.getVdpaKind());
        assertNull(row.getVdpaName());
        assertNull(row.getVdpaDevice());
        assertNotNull(row.getUpdated());
        assertTrue("allocate must bump updated past prior stamp",
                row.getUpdated().after(before));
    }

    @Test
    public void setStateBumpsUpdatedViaSetter() {
        SriovVfPoolVO row = new SriovVfPoolVO(1L, "0000:01:00.1", "dx6p0", "dx6p0r0");
        assertNull(row.getUpdated());

        row.setState(State.ALLOCATED.name());

        assertEquals(State.ALLOCATED.name(), row.getState());
        assertNotNull("setState must call setUpdated so createForUpdate proxies capture it",
                row.getUpdated());
    }

    @Test
    public void reservedVdpaOwnershipKeepsNicBindingWithoutClaimingAllocatedState() {
        SriovVfPoolVO row = staleVdpaRow();

        SriovVfPoolDaoImpl.applyOwnershipState(row, NIC_ID, State.RESERVED, VdpaKind.VDPA, "vdpa-99");

        assertEquals(State.RESERVED.name(), row.getState());
        assertEquals(Long.valueOf(NIC_ID), row.getAllocatedToNicId());
        assertEquals(VdpaKind.VDPA.name(), row.getVdpaKind());
        assertEquals("vdpa-99", row.getVdpaName());
        assertNull(row.getVdpaDevice());
    }

    @Test
    public void allocatedPassthroughOwnershipClearsPriorVdpaIdentity() {
        SriovVfPoolVO row = staleVdpaRow();

        SriovVfPoolDaoImpl.applyOwnershipState(row, NIC_ID, State.ALLOCATED, VdpaKind.PASSTHROUGH, null);

        assertEquals(State.ALLOCATED.name(), row.getState());
        assertEquals(VdpaKind.PASSTHROUGH.name(), row.getVdpaKind());
        assertNull(row.getVdpaName());
        assertNull(row.getVdpaDevice());
    }

    @Test
    public void exactReleasePredicateRejectsDirectAllocatedReservedAndWrongOwnerRows() {
        SriovVfPoolVO allocated = staleVdpaRow();
        allocated.setState(State.ALLOCATED);
        assertFalse(SriovVfPoolDaoImpl.exactSuspectOwnershipMatches(allocated, NIC_ID));

        SriovVfPoolVO reserved = staleVdpaRow();
        reserved.setState(State.RESERVED);
        assertFalse(SriovVfPoolDaoImpl.exactSuspectOwnershipMatches(reserved, NIC_ID));

        SriovVfPoolVO wrongOwner = staleVdpaRow();
        wrongOwner.setState(State.SUSPECT);
        assertFalse(SriovVfPoolDaoImpl.exactSuspectOwnershipMatches(wrongOwner, NIC_ID + 1));
    }

    @Test
    public void exactReleasePredicateAcceptsSuspectAndRejectsReplayAfterFree() {
        SriovVfPoolVO row = staleVdpaRow();
        row.setAllocatedToNicId(NIC_ID);
        row.setState(State.SUSPECT);
        assertTrue(SriovVfPoolDaoImpl.exactSuspectOwnershipMatches(row, NIC_ID));
        SriovVfPoolDaoImpl.applyFreeState(row);
        assertFalse(SriovVfPoolDaoImpl.exactSuspectOwnershipMatches(row, NIC_ID));
    }

    @Test
    public void sameHostAllocationRejectsPassthroughVdpaKindMismatchBothDirections() {
        SriovVfPoolVO vdpa = staleVdpaRow();
        assertFalse(SriovVfPoolDaoImpl.sameHostModeMatches(vdpa, VdpaKind.PASSTHROUGH));
        assertTrue(SriovVfPoolDaoImpl.sameHostModeMatches(vdpa, VdpaKind.VDPA));

        SriovVfPoolVO passthrough = staleVdpaRow();
        passthrough.setVdpaKind(VdpaKind.PASSTHROUGH);
        assertFalse(SriovVfPoolDaoImpl.sameHostModeMatches(passthrough, VdpaKind.VDPA));
        assertTrue(SriovVfPoolDaoImpl.sameHostModeMatches(passthrough, VdpaKind.PASSTHROUGH));
    }

    /** Row shaped like a leak after vDPA allocate + incomplete free. */
    private static SriovVfPoolVO staleVdpaRow() {
        SriovVfPoolVO row = new SriovVfPoolVO(1L, "0000:01:00.3", "dx6p0", "dx6p0r1");
        row.setState(State.ALLOCATED);
        row.setAllocatedToNicId(42L);
        row.setVdpaKind(VdpaKind.VDPA);
        row.setVdpaName("vdpa-42");
        row.setVdpaDevice("/dev/vhost-vdpa-0");
        return row;
    }
}
