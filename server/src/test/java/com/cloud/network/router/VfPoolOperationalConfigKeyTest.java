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
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Operational VF ownership ConfigKeys must be dynamic so plan/approval/apply
 * transitions take effect on the next singleton sweep without a management restart.
 *
 * <p>Uses a hand-rolled {@link ConfigDepotImpl} subclass (not Mockito) so the suite
 * stays portable across JDKs where Byte Buddy cannot instrument the depot class.
 */
public class VfPoolOperationalConfigKeyTest {

    private FakeDepot depot;
    private final List<ConfigKey<?>> operationalKeys = new ArrayList<>();

    @Before
    public void setUp() {
        depot = new FakeDepot();
        // Constructor calls ConfigKey.init(this); keep the fake depot installed.
        ConfigKey.init(depot);

        operationalKeys.clear();
        operationalKeys.add(VfPoolManager.LegacyBroadVfOperationsEnabled);
        operationalKeys.add(VfPoolManager.OwnershipRepairPlanEnabled);
        operationalKeys.add(VfPoolManager.OwnershipRepairApplyEnabled);
        operationalKeys.add(VfPoolManager.OwnershipRepairApprovedCount);
        operationalKeys.add(VfPoolManager.OwnershipRepairApprovedIds);
        operationalKeys.add(VfPoolManager.OwnershipRepairApprovedHash);
        operationalKeys.add(VfPoolManager.OwnershipRepairApprovalToken);
        operationalKeys.add(VfPoolManager.OwnershipRepairIncidentId);
    }

    @After
    public void tearDown() {
        ConfigKey.init(null);
    }

    @Test
    public void everyOperationalKeyIsDynamic() {
        for (final ConfigKey<?> key : operationalKeys) {
            assertTrue(key.key() + " must be dynamic so gate transitions need no restart",
                    key.isDynamic());
        }
        assertEquals(8, operationalKeys.size());
    }

    @Test
    public void defaultsRemainFailClosedEmptyOrFalse() {
        assertEquals("false", VfPoolManager.LegacyBroadVfOperationsEnabled.defaultValue());
        assertEquals("false", VfPoolManager.OwnershipRepairPlanEnabled.defaultValue());
        assertEquals("false", VfPoolManager.OwnershipRepairApplyEnabled.defaultValue());
        assertEquals("0", VfPoolManager.OwnershipRepairApprovedCount.defaultValue());
        assertEquals("", VfPoolManager.OwnershipRepairApprovedIds.defaultValue());
        assertEquals("", VfPoolManager.OwnershipRepairApprovedHash.defaultValue());
        assertEquals("", VfPoolManager.OwnershipRepairApprovalToken.defaultValue());
        assertEquals("", VfPoolManager.OwnershipRepairIncidentId.defaultValue());

        assertFalse(VfPoolManager.LegacyBroadVfOperationsEnabled.value());
        assertFalse(VfPoolManager.OwnershipRepairPlanEnabled.value());
        assertFalse(VfPoolManager.OwnershipRepairApplyEnabled.value());
        assertEquals(Integer.valueOf(0), VfPoolManager.OwnershipRepairApprovedCount.value());
        assertEquals("", VfPoolManager.OwnershipRepairApprovedIds.value());
        assertEquals("", VfPoolManager.OwnershipRepairApprovedHash.value());
        assertEquals("", VfPoolManager.OwnershipRepairApprovalToken.value());
        assertEquals("", VfPoolManager.OwnershipRepairIncidentId.value());
    }

    @Test
    public void depotValueChangeIsObservedAcrossConsecutivePlanGateEvaluations() {
        depot.enqueue(VfPoolManager.OwnershipRepairPlanEnabled.key(), "false", "true", "false");
        depot.put(VfPoolManager.OwnershipRepairApplyEnabled.key(), "false");

        final TrackingManager manager = new TrackingManager();

        manager.runOwnershipRepairGate();
        assertEquals(0, manager.planBuildCount);

        manager.runOwnershipRepairGate();
        assertEquals(1, manager.planBuildCount);

        manager.runOwnershipRepairGate();
        assertEquals(1, manager.planBuildCount);

        assertEquals(3, depot.readCount(VfPoolManager.OwnershipRepairPlanEnabled.key()));
    }

    @Test
    public void approvalInputsRefreshFromDepotOnEachGateEvaluation() {
        // plan.enabled once per gate; apply.enabled twice when planning runs (log + check).
        depot.put(VfPoolManager.OwnershipRepairPlanEnabled.key(), "true");
        depot.enqueue(VfPoolManager.OwnershipRepairApplyEnabled.key(),
                "false", "false", "true", "true");
        depot.put(VfPoolManager.OwnershipRepairApprovedCount.key(), "11");
        depot.put(VfPoolManager.OwnershipRepairApprovedIds.key(), "id-a,id-b");
        depot.put(VfPoolManager.OwnershipRepairApprovedHash.key(), "hash-a");
        depot.put(VfPoolManager.OwnershipRepairApprovalToken.key(), "token-a");
        depot.put(VfPoolManager.OwnershipRepairIncidentId.key(), VfOwnershipRepairPlan.INCIDENT_PLAN_ID);

        final TrackingManager manager = new TrackingManager();

        manager.runOwnershipRepairGate();
        assertEquals(1, manager.planBuildCount);
        assertFalse(manager.applyReached);
        assertEquals(0, manager.snapshotCount);
        assertEquals(null, manager.snapshotIds);
        assertEquals(0, depot.readCount(VfPoolManager.OwnershipRepairApprovedCount.key()));

        manager.runOwnershipRepairGate();
        assertEquals(2, manager.planBuildCount);
        assertTrue(manager.applyReached);
        assertEquals(11, manager.snapshotCount);
        assertEquals("id-a,id-b", manager.snapshotIds);
        assertEquals("hash-a", manager.snapshotHash);
        assertEquals("token-a", manager.snapshotToken);
        assertEquals(VfOwnershipRepairPlan.INCIDENT_PLAN_ID, manager.snapshotIncidentId);
        assertEquals(1, depot.readCount(VfPoolManager.OwnershipRepairApprovedCount.key()));
        assertEquals(1, depot.readCount(VfPoolManager.OwnershipRepairIncidentId.key()));
    }

    /**
     * Minimal depot that bypasses LazyCache so each ConfigKey.value() sees the
     * latest put/enqueue. Avoids Mockito instrumentation of ConfigDepotImpl.
     */
    private static final class FakeDepot extends ConfigDepotImpl {
        private final Map<String, String> fixed = new HashMap<>();
        private final Map<String, Queue<String>> sequences = new HashMap<>();
        private final Map<String, Integer> reads = new HashMap<>();

        void put(final String key, final String value) {
            fixed.put(key, value);
            sequences.remove(key);
        }

        void enqueue(final String key, final String... values) {
            final Queue<String> queue = new ArrayDeque<>();
            for (final String value : values) {
                queue.add(value);
            }
            sequences.put(key, queue);
            fixed.remove(key);
        }

        int readCount(final String key) {
            return reads.getOrDefault(key, 0);
        }

        @Override
        public String getConfigStringValue(final String key, final ConfigKey.Scope scope, final Long scopeId) {
            reads.merge(key, 1, Integer::sum);
            final Queue<String> queue = sequences.get(key);
            if (queue != null && !queue.isEmpty()) {
                return queue.poll();
            }
            return fixed.get(key);
        }
    }

    /**
     * Exercises real ConfigKey.value() reads (no gate overrides) so a depot change
     * is observed across consecutive plan-gate evaluations without manager-side cache.
     */
    private static final class TrackingManager extends VfPoolManagerImpl {
        private int planBuildCount;
        private boolean applyReached;
        private int snapshotCount;
        private String snapshotIds;
        private String snapshotHash;
        private String snapshotToken;
        private String snapshotIncidentId;
        private final VfOwnershipRepairPlan plan = new VfOwnershipRepairPlan(
                java.util.Collections.emptyList(), 0, 0);

        @Override
        VfOwnershipRepairPlan buildOwnershipRepairPlan() {
            planBuildCount++;
            return plan;
        }

        @Override
        protected boolean isIncidentScopeApproved(final VfOwnershipRepairPlan built) {
            applyReached = true;
            snapshotCount = approvedCandidateCount();
            snapshotIds = approvedCandidateIds();
            snapshotHash = approvedPlanHash();
            snapshotToken = approvedPlanToken();
            snapshotIncidentId = approvedIncidentId();
            // Never enter mutation; this test only validates dynamic gate inputs.
            return false;
        }
    }
}
