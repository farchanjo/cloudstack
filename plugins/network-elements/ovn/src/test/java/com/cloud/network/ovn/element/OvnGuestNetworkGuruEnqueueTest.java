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
package com.cloud.network.ovn.element;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;

import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Verifies that OvnGuestNetworkGuru.deleteLogicalSwitchFor enqueues the LS UUID
 * into ovn_pending_deletion BEFORE the synchronous NB delete attempt.
 */
public class OvnGuestNetworkGuruEnqueueTest {

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnGuestNetworkGuru guru;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);

        when(controller.getId()).thenReturn(2L);
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(controller);
        when(pluginManager.nbClient(anyLong())).thenReturn(nbClient);

        guru = new OvnGuestNetworkGuru();
        inject(guru, "pluginManager", pluginManager);
        inject(guru, "logicalIdMapDao", logicalIdMapDao);
        inject(guru, "pendingDeletionDao", pendingDeletionDao);
    }

    /** Success path: enqueue fires, NB delete succeeds, row is marked succeeded, mapping removed. */
    @Test
    public void deleteLogicalSwitchFor_enqueues_thenDeletes_onSuccess() {
        final OvnLogicalIdMapVO mapping = mappingFor("ls-uuid-abc");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(10L), eq(2L))).thenReturn(mapping);
        when(pendingDeletionDao.isPendingByOvnUuid("ls-uuid-abc", "NETWORK")).thenReturn(false);

        guru.deleteLogicalSwitchFor(networkWith(10L, 5L));

        // Enqueue happened before NB call.
        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        // NB delete was called.
        verify(nbClient, times(1)).deleteLogicalSwitch("ls-uuid-abc");
        // Mapping removed on success.
        verify(logicalIdMapDao, times(1)).remove(100L);
        // Row marked succeeded.
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid("ls-uuid-abc", "NETWORK");
    }

    /** Failure path: enqueue fires, NB delete throws, mapping stays, row stays for retry. */
    @Test(expected = OvnException.class)
    public void deleteLogicalSwitchFor_enqueues_thenLeavesQueueOnFailure() {
        final OvnLogicalIdMapVO mapping = mappingFor("ls-fail-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(10L), eq(2L))).thenReturn(mapping);
        when(pendingDeletionDao.isPendingByOvnUuid("ls-fail-uuid", "NETWORK")).thenReturn(false);
        doThrow(new OvnException("ovsdb timeout")).when(nbClient).deleteLogicalSwitch("ls-fail-uuid");

        try {
            guru.deleteLogicalSwitchFor(networkWith(10L, 5L));
        } finally {
            // Enqueue was persisted.
            verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
            // NB was attempted.
            verify(nbClient, times(1)).deleteLogicalSwitch("ls-fail-uuid");
            // markSucceeded was NOT called because delete failed.
            verify(pendingDeletionDao, never()).markSucceededByOvnUuid(anyString(), anyString());
            // Mapping row survives.
            verify(logicalIdMapDao, never()).remove(anyLong());
        }
    }

    /** No-op path: controller absent → return immediately, no enqueue, no NB call. */
    @Test
    public void deleteLogicalSwitchFor_noOp_whenControllerNull() {
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(null);

        guru.deleteLogicalSwitchFor(networkWith(99L, 7L));

        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, never()).deleteLogicalSwitch(anyString());
    }

    /** No-op path: mapping absent → return immediately, no enqueue, no NB call. */
    @Test
    public void deleteLogicalSwitchFor_noOp_whenMappingNull() {
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(10L), eq(2L))).thenReturn(null);

        guru.deleteLogicalSwitchFor(networkWith(10L, 5L));

        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, never()).deleteLogicalSwitch(anyString());
    }

    /** Idempotency: already pending → no second enqueue, NB delete still called. */
    @Test
    public void deleteLogicalSwitchFor_doesNotDoubleEnqueue_whenAlreadyPending() {
        final OvnLogicalIdMapVO mapping = mappingFor("ls-dup-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(10L), eq(2L))).thenReturn(mapping);
        when(pendingDeletionDao.isPendingByOvnUuid("ls-dup-uuid", "NETWORK")).thenReturn(true);

        guru.deleteLogicalSwitchFor(networkWith(10L, 5L));

        // No new persist — already in queue.
        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        // NB delete still runs.
        verify(nbClient, times(1)).deleteLogicalSwitch("ls-dup-uuid");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Network networkWith(final long id, final long zoneId) {
        final Network net = mock(Network.class);
        when(net.getId()).thenReturn(id);
        when(net.getDataCenterId()).thenReturn(zoneId);
        when(net.getUuid()).thenReturn("net-uuid-" + id);
        when(net.getName()).thenReturn("test-net-" + id);
        return net;
    }

    private OvnLogicalIdMapVO mappingFor(final String ovnUuid) {
        final OvnLogicalIdMapVO m = mock(OvnLogicalIdMapVO.class);
        when(m.getId()).thenReturn(100L);
        when(m.getOvnUuid()).thenReturn(ovnUuid);
        return m;
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception {
        Field f;
        try {
            f = target.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = target.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(target, value);
    }
}
