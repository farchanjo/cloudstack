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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.utils.db.GlobalLock;

public class VfPoolSingletonSweepTest {

    @Test
    public void longestRunningManagementServerIsLeader() {
        final VfPoolReconcileLeader leader = new VfPoolReconcileLeader();
        final ManagementServerHostDao dao = mock(ManagementServerHostDao.class);
        final ManagementServerHostVO host = mock(ManagementServerHostVO.class);
        when(host.getMsid()).thenReturn(ManagementServerNode.getManagementServerId());
        when(dao.findOneByLongestRuntime()).thenReturn(host);
        ReflectionTestUtils.setField(leader, "managementServerHostDao", dao);

        assertTrue(leader.isLeader());
    }

    @Test
    public void leaderElectionFailsClosedOnUncertainty() {
        final VfPoolReconcileLeader leader = new VfPoolReconcileLeader();
        final ManagementServerHostDao dao = mock(ManagementServerHostDao.class);
        when(dao.findOneByLongestRuntime()).thenThrow(new IllegalStateException("db unavailable"));
        ReflectionTestUtils.setField(leader, "managementServerHostDao", dao);

        assertFalse(leader.isLeader());
    }

    @Test
    public void nonLeaderDoesNotAttemptSweep() {
        final VfPoolReconcileLeader leader = mock(VfPoolReconcileLeader.class);
        final GlobalLock lock = mock(GlobalLock.class);
        final TestManager manager = new TestManager(lock);
        final VfPoolManager delegate = mock(VfPoolManager.class);
        ReflectionTestUtils.setField(manager, "reconcileLeader", leader);
        when(leader.isLeader()).thenReturn(false);

        manager.runSweepIfLeader(delegate);

        verify(delegate, never()).sweepOrphans();
        verify(lock, never()).lock(1);
    }

    @Test
    public void lockContentionFailsClosed() {
        final VfPoolReconcileLeader leader = mock(VfPoolReconcileLeader.class);
        final GlobalLock lock = mock(GlobalLock.class);
        final TestManager manager = new TestManager(lock);
        final VfPoolManager delegate = mock(VfPoolManager.class);
        ReflectionTestUtils.setField(manager, "reconcileLeader", leader);
        when(leader.isLeader()).thenReturn(true);
        when(lock.lock(1)).thenReturn(false);

        manager.runSweepIfLeader(delegate);

        verify(delegate, never()).sweepOrphans();
        verify(lock).releaseRef();
    }

    @Test
    public void leaderAcquiresLockSweepsUnlocksAndReleasesReference() {
        final VfPoolReconcileLeader leader = mock(VfPoolReconcileLeader.class);
        final GlobalLock lock = mock(GlobalLock.class);
        final TestManager manager = new TestManager(lock);
        final VfPoolManager delegate = mock(VfPoolManager.class);
        ReflectionTestUtils.setField(manager, "reconcileLeader", leader);
        when(leader.isLeader()).thenReturn(true);
        when(lock.lock(1)).thenReturn(true);

        manager.runSweepIfLeader(delegate);

        verify(delegate).sweepOrphans();
        verify(lock).unlock();
        verify(lock).releaseRef();
    }

    @Test
    public void ownershipPlanningAndMutationConfigDefaultsAreOff() {
        assertTrue("false".equals(VfPoolManager.OwnershipRepairPlanEnabled.defaultValue()));
        assertTrue("false".equals(VfPoolManager.OwnershipRepairApplyEnabled.defaultValue()));
        assertTrue("false".equals(VfPoolManager.LegacyBroadVfOperationsEnabled.defaultValue()));
    }

    @Test
    public void ownershipOperationalConfigKeysAreDynamic() {
        assertTrue(VfPoolManager.LegacyBroadVfOperationsEnabled.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairPlanEnabled.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairApplyEnabled.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairApprovedCount.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairApprovedIds.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairApprovedHash.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairApprovalToken.isDynamic());
        assertTrue(VfPoolManager.OwnershipRepairIncidentId.isDynamic());
    }

    private static final class TestManager extends VfPoolManagerImpl {
        private final GlobalLock lock;

        private TestManager(final GlobalLock lock) {
            this.lock = lock;
        }

        @Override
        protected GlobalLock getSweepLock() {
            return lock;
        }
    }

}
