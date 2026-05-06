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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.routing.UpdateHostVfInventoryCommand;
import com.cloud.alert.AlertManager;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
import com.cloud.network.router.dao.SriovVfPoolDao;

/**
 * Behaviour tests for {@link VfPoolReconcilerImpl}. Exercises the four
 * reconcile primitives (touch last_seen, adopt vDPA, inject orphan, flip
 * suspect) against a mocked {@link SriovVfPoolDao}, asserting the manager's
 * counters match the agent advertise.
 */
@RunWith(MockitoJUnitRunner.class)
public class VfPoolReconcilerImplTest {

    private static final String HOST_UUID = "host-uuid-42";
    private static final long HOST_ID = 42L;

    @Mock
    private SriovVfPoolDao vfPoolDao;

    @Mock
    private HostDao hostDao;

    @Mock
    private AlertManager alertMgr;

    @InjectMocks
    private VfPoolReconcilerImpl reconciler;

    @Before
    public void setUp() {
        HostVO host = Mockito.mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getPodId()).thenReturn(1L);
        when(host.getName()).thenReturn("aragog");
        when(hostDao.findByGuid(HOST_UUID)).thenReturn(host);
        when(vfPoolDao.listByHost(anyLong())).thenReturn(Collections.emptyList());
        when(vfPoolDao.findStaleAllocated(Mockito.anyInt())).thenReturn(Collections.emptyList());
    }

    @Test
    public void reconcileEmptyCommandReturnsZeros() {
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(null);
        assertEquals(0, r.getReconciledVfs());
        assertEquals(0, r.getSuspectFlipped());
        assertEquals(0, r.getOrphanInserted());
        assertEquals(0, r.getVdpaConverted());
    }

    @Test
    public void reconcileUnknownHostReturnsZeros() {
        when(hostDao.findByGuid("ghost")).thenReturn(null);
        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                "ghost",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(0, r.getReconciledVfs());
        verify(vfPoolDao, never()).touchLastSeen(anyLong(), anyString());
    }

    @Test
    public void reconcileTouchesEveryReportedVf() {
        when(vfPoolDao.touchLastSeen(eq(HOST_ID), anyString())).thenReturn(true);
        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                HOST_UUID,
                Collections.emptyList(),
                Arrays.asList(
                        new UpdateHostVfInventoryCommand.Vf("0000:01:00.2", "dx6p0vf0", "aa:bb:cc:00:00:01", "FREE"),
                        new UpdateHostVfInventoryCommand.Vf("0000:01:00.3", "dx6p0vf1", "aa:bb:cc:00:00:02", "FREE")),
                Collections.emptyList());
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(2, r.getReconciledVfs());
        verify(vfPoolDao, times(1)).touchLastSeen(HOST_ID, "0000:01:00.2");
        verify(vfPoolDao, times(1)).touchLastSeen(HOST_ID, "0000:01:00.3");
    }

    @Test
    public void reconcileFlipsAllocatedToSuspectAndAlerts() {
        SriovVfPoolVO stale = new SriovVfPoolVO(HOST_ID, "0000:01:00.4", "dx6p0", "dx6p0vf2");
        // Inject the row id so VfPoolReconcilerImpl.update() finds it.
        try {
            java.lang.reflect.Field idField = SriovVfPoolVO.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(stale, 7L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        stale.setState(State.ALLOCATED);
        when(vfPoolDao.findStaleAllocated(Mockito.anyInt())).thenReturn(
                new ArrayList<>(Arrays.asList(stale)));

        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                HOST_UUID,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(1, r.getSuspectFlipped());
        verify(alertMgr, atLeastOnce()).sendAlert(
                eq(AlertManager.AlertType.ALERT_TYPE_HOST),
                anyLong(), Mockito.anyLong(), anyString(), anyString());
    }

    @Test
    public void reconcileAdoptsPassthroughRowAsVdpa() {
        SriovVfPoolVO row = new SriovVfPoolVO(HOST_ID, "0000:01:00.5", "dx6p0", "dx6p0vf3");
        try {
            java.lang.reflect.Field idField = SriovVfPoolVO.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(row, 11L);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        row.setVdpaKind(VdpaKind.PASSTHROUGH);
        row.setState(State.ALLOCATED);
        row.setLastSeen(new Date());
        when(vfPoolDao.findByHostAndPci(HOST_ID, "0000:01:00.5")).thenReturn(row);

        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                HOST_UUID,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(
                        new UpdateHostVfInventoryCommand.VdpaSf(
                                "vdpa-7", "0000:01:00.5", "aa:bb:cc:00:00:05", 33, "/dev/vhost-vdpa0")));
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(1, r.getVdpaConverted());
        verify(vfPoolDao, atLeastOnce()).update(eq(11L), Mockito.any(SriovVfPoolVO.class));
    }

    @Test
    public void reconcileInjectsOrphanWhenSfIsUnknownToDb() {
        // No matching row by name OR by BDF.
        when(vfPoolDao.findByHostAndPci(HOST_ID, "0000:01:00.6")).thenReturn(null);

        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                HOST_UUID,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(
                        new UpdateHostVfInventoryCommand.VdpaSf(
                                "vdpa-orphan", "0000:01:00.6", "aa:bb:cc:00:00:06", 33, "/dev/vhost-vdpa1")));
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(1, r.getOrphanInserted());
        verify(vfPoolDao, times(1)).persist(Mockito.any(SriovVfPoolVO.class));
    }

    @Test
    public void reconcileSkipsAdoptionForRowAlreadyMarkedVdpa() {
        SriovVfPoolVO row = new SriovVfPoolVO(HOST_ID, "0000:01:00.7", "dx6p0", "dx6p0vf4");
        row.setVdpaKind(VdpaKind.VDPA);
        row.setState(State.ALLOCATED);
        when(vfPoolDao.findByHostAndPci(HOST_ID, "0000:01:00.7")).thenReturn(row);

        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                HOST_UUID,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(
                        new UpdateHostVfInventoryCommand.VdpaSf(
                                "vdpa-stable", "0000:01:00.7", "aa:bb:cc:00:00:07", 33, "/dev/vhost-vdpa2")));
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(0, r.getVdpaConverted());
    }

    @Test
    public void reconcileSkipsOrphanInsertWhenRowAlreadyKnowsTheVdpaName() {
        // Pool already lists the SF name — no synthetic insert.
        SriovVfPoolVO existing = new SriovVfPoolVO(HOST_ID, "0000:01:00.8", "dx6p0", "dx6p0vf5");
        existing.setVdpaName("vdpa-known");
        existing.setVdpaKind(VdpaKind.VDPA);
        existing.setState(State.ALLOCATED);
        when(vfPoolDao.listByHost(HOST_ID)).thenReturn(
                new ArrayList<>(Arrays.asList(existing)));

        UpdateHostVfInventoryCommand cmd = new UpdateHostVfInventoryCommand(
                HOST_UUID,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(
                        new UpdateHostVfInventoryCommand.VdpaSf(
                                "vdpa-known", "0000:02:00.0", null, 33, "/dev/vhost-vdpa9")));
        VfPoolReconcilerImpl.ReconcileResult r = reconciler.reconcile(cmd);
        assertEquals(0, r.getOrphanInserted());
        verify(vfPoolDao, never()).persist(Mockito.any(SriovVfPoolVO.class));
    }

    @Test
    public void emptyReconcileResultIsZero() {
        VfPoolReconcilerImpl.ReconcileResult r = VfPoolReconcilerImpl.ReconcileResult.empty();
        assertEquals(0, r.getReconciledVfs());
        assertEquals(0, r.getSuspectFlipped());
        assertEquals(0, r.getOrphanInserted());
        assertEquals(0, r.getVdpaConverted());
    }

    @Test
    public void suspectTimeoutDefaultsToFifteenMinutes() {
        // Subclass with no override: must yield the default 900-second value.
        VfPoolReconcilerImpl r = new VfPoolReconcilerImpl();
        assertEquals(900, r.suspectTimeoutSeconds());
        // The constant is defined in the impl; reaffirm it here so a careless
        // change does not silently lower the safety net.
        assertEquals(VfPoolReconcilerImpl.DEFAULT_SUSPECT_TIMEOUT_SECONDS, r.suspectTimeoutSeconds());
    }

    @Test
    public void unusedReturnsAreImports() {
        // Sanity: imported classes are referenced (silences the static
        // analyzer if it ever runs). No side effects.
        List<SriovVfPoolVO> empty = Collections.emptyList();
        assertEquals(0, empty.size());
    }
}
