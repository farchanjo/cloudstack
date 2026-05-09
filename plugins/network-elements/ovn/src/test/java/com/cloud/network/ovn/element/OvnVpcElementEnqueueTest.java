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

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.Vpc;

/**
 * Verifies that OvnVpcElement.deleteLogicalRouterFor enqueues the LR UUID
 * into ovn_pending_deletion BEFORE the synchronous NB delete attempt.
 */
public class OvnVpcElementEnqueueTest {

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnVpcElement element;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);

        when(controller.getId()).thenReturn(1L);
        when(controller.getZoneId()).thenReturn(7L);
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(controller);
        when(pluginManager.nbClient(anyLong())).thenReturn(nbClient);
        // By default no LB on the LR.
        when(nbClient.listLoadBalancersOnLogicalRouter(anyString())).thenReturn(java.util.List.of());

        element = new OvnVpcElement();
        inject(element, "pluginManager", pluginManager);
        inject(element, "logicalIdMapDao", logicalIdMapDao);
        inject(element, "pendingDeletionDao", pendingDeletionDao);
    }

    /** Success path: enqueue fires, NB delete succeeds, row is marked succeeded. */
    @Test
    public void deleteLogicalRouterFor_enqueues_thenDeletes_onSuccess() {
        final OvnLogicalIdMapVO mapping = mappingFor("lr-uuid-abc");
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(42L), eq(1L))).thenReturn(mapping);
        // Not yet pending — allow enqueue.
        when(pendingDeletionDao.isPendingByOvnUuid("lr-uuid-abc", "VPC")).thenReturn(false);

        element.deleteLogicalRouterFor(vpcWith(42L, 7L));

        // Enqueue happened before NB call.
        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        // NB delete was called.
        verify(nbClient, times(1)).deleteLogicalRouter("lr-uuid-abc");
        // On success, mark succeeded.
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid("lr-uuid-abc", "VPC");
    }

    /** Failure path: enqueue fires, NB delete throws, row stays in queue for retry. */
    @Test(expected = OvnException.class)
    public void deleteLogicalRouterFor_enqueues_thenLeavesQueueOnFailure() {
        final OvnLogicalIdMapVO mapping = mappingFor("lr-fail-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(42L), eq(1L))).thenReturn(mapping);
        when(pendingDeletionDao.isPendingByOvnUuid("lr-fail-uuid", "VPC")).thenReturn(false);
        doThrow(new OvnException("ovsdb timeout")).when(nbClient).deleteLogicalRouter("lr-fail-uuid");

        try {
            element.deleteLogicalRouterFor(vpcWith(42L, 7L));
        } finally {
            // Enqueue was persisted.
            verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
            // NB was attempted.
            verify(nbClient, times(1)).deleteLogicalRouter("lr-fail-uuid");
            // markSucceeded was NOT called because delete failed.
            verify(pendingDeletionDao, never()).markSucceededByOvnUuid(anyString(), anyString());
            // Mapping row survives (not removed).
            verify(logicalIdMapDao, never()).remove(anyLong());
        }
    }

    /** Zone-sentinel path: controller == null → enqueue with sentinel controller_id=0. */
    @Test
    public void deleteLogicalRouterFor_enqueues_zoneSentinel_whenControllerNull() {
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(null);
        // No pending row for the synthetic key.
        when(pendingDeletionDao.isPendingByOvnUuid(anyString(), eq("VPC"))).thenReturn(false);

        element.deleteLogicalRouterFor(vpcWith(99L, 7L));

        // Sentinel row persisted.
        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        // No NB call when controller is absent.
        verify(nbClient, never()).deleteLogicalRouter(anyString());
    }

    /** Idempotency: if already pending, no second enqueue. */
    @Test
    public void deleteLogicalRouterFor_doesNotDoubleEnqueue_whenAlreadyPending() {
        final OvnLogicalIdMapVO mapping = mappingFor("lr-dup-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(42L), eq(1L))).thenReturn(mapping);
        // Already pending.
        when(pendingDeletionDao.isPendingByOvnUuid("lr-dup-uuid", "VPC")).thenReturn(true);

        element.deleteLogicalRouterFor(vpcWith(42L, 7L));

        // No new persist — already in queue.
        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        // NB delete still runs.
        verify(nbClient, times(1)).deleteLogicalRouter("lr-dup-uuid");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Vpc vpcWith(final long id, final long zoneId) {
        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(id);
        when(vpc.getZoneId()).thenReturn(zoneId);
        when(vpc.getUuid()).thenReturn("vpc-uuid-" + id);
        when(vpc.getName()).thenReturn("test-vpc-" + id);
        return vpc;
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
