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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class VfOwnershipRepairPlanTest {

    @Test
    public void validatedProductionPlanHasExactTransitionsAndCounts() {
        final List<VfOwnershipRepairPlan.Candidate> candidates = new ArrayList<>();
        final long[][] noncanonical = {{857L, 833L, 8820L}, {1427L, 833L, 8820L},
                {2435L, 833L, 8820L}, {764L, 827L, 8913L}, {896L, 827L, 8913L},
                {995L, 827L, 8913L}, {1469L, 827L, 8913L}, {2537L, 827L, 8913L}};
        for (final long[] transition : noncanonical) {
            candidates.add(candidate(VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE,
                    transition[0], transition[1], transition[2]));
        }
        candidates.add(candidate(VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL, 1625L, 1022L, 8829L));
        candidates.add(candidate(VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL, 818L, 749L, 8847L));
        candidates.add(candidate(VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL, 1016L, 2468L, 8925L));

        final VfOwnershipRepairPlan plan = new VfOwnershipRepairPlan(candidates, 27, 237);

        assertEquals(11, plan.getCandidateCount());
        assertEquals(27, plan.getAllocatedBefore());
        assertEquals(19, plan.getAllocatedAfter());
        assertEquals(237, plan.getFreeBefore());
        assertEquals(245, plan.getFreeAfter());
        assertTrue(plan.matchesApproval(plan.getCandidateCount(), plan.getCandidateIds(),
                plan.getHash(), plan.getApprovalToken()));
        assertTrue(plan.isExactIncidentScope());
    }

    @Test
    public void surpriseCandidateChangesIdsCountHashAndTokenAndBlocksApproval() {
        final List<VfOwnershipRepairPlan.Candidate> approved = new ArrayList<>();
        approved.add(candidate(VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE, 857L, 833L, 8820L));
        final VfOwnershipRepairPlan original = new VfOwnershipRepairPlan(approved, 27, 237);
        final List<VfOwnershipRepairPlan.Candidate> changed = new ArrayList<>(approved);
        changed.add(candidate(VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE, 9999L, 833L, 8820L));
        final VfOwnershipRepairPlan surprise = new VfOwnershipRepairPlan(changed, 27, 237);

        assertFalse(surprise.matchesApproval(original.getCandidateCount(), original.getCandidateIds(),
                original.getHash(), original.getApprovalToken()));
    }

    @Test
    public void candidateStateMachineAcceptsOnlyPendingQuarantinedCompletedStates() {
        final VfOwnershipRepairPlan.Candidate stale = candidate(
                VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE, 857L, 833L, 8820L);
        final VfOwnershipRepairPlan plan = new VfOwnershipRepairPlan(List.of(stale), 27, 237);
        assertEquals(VfOwnershipRepairPlan.CandidateState.PENDING,
                plan.state(stale, "ALLOCATED", 8820L, "ALLOCATED", 8820L));
        assertEquals(VfOwnershipRepairPlan.CandidateState.QUARANTINED,
                plan.state(stale, "ALLOCATED", 8820L, "SUSPECT", 8820L));
        assertEquals(VfOwnershipRepairPlan.CandidateState.COMPLETED,
                plan.state(stale, "ALLOCATED", 8820L, "FREE", null));
        assertEquals(VfOwnershipRepairPlan.CandidateState.INVALID,
                plan.state(stale, "ALLOCATED", 8820L, "SUSPECT", 9999L));
        assertEquals(VfOwnershipRepairPlan.CandidateState.INVALID,
                plan.state(stale, "ALLOCATED", 8820L, "RESERVED", 8820L));
    }

    @Test
    public void wrongHostStateMachineRejectsMixedStates() {
        final VfOwnershipRepairPlan.Candidate wrong = candidate(
                VfOwnershipRepairPlan.Kind.WRONG_HOST_CANONICAL, 1625L, 1022L, 8829L);
        final VfOwnershipRepairPlan plan = new VfOwnershipRepairPlan(List.of(wrong), 27, 237);
        assertEquals(VfOwnershipRepairPlan.CandidateState.PENDING,
                plan.state(wrong, "FREE", null, "ALLOCATED", 8829L));
        assertEquals(VfOwnershipRepairPlan.CandidateState.QUARANTINED,
                plan.state(wrong, "ALLOCATED", 8829L, "SUSPECT", 8829L));
        assertEquals(VfOwnershipRepairPlan.CandidateState.COMPLETED,
                plan.state(wrong, "ALLOCATED", 8829L, "FREE", null));
        assertEquals(VfOwnershipRepairPlan.CandidateState.INVALID,
                plan.state(wrong, "FREE", null, "SUSPECT", 8829L));
    }

    @Test
    public void progressDoesNotChangeLogicalPlanIdentity() {
        final VfOwnershipRepairPlan.Candidate candidate = candidate(
                VfOwnershipRepairPlan.Kind.NONCANONICAL_STALE, 857L, 833L, 8820L);
        final VfOwnershipRepairPlan first = new VfOwnershipRepairPlan(List.of(candidate), 27, 237);
        final VfOwnershipRepairPlan resumed = new VfOwnershipRepairPlan(List.of(candidate), 27, 237);
        assertEquals(first.getHash(), resumed.getHash());
        assertEquals(first.getApprovalToken(), resumed.getApprovalToken());
    }

    private static VfOwnershipRepairPlan.Candidate candidate(final VfOwnershipRepairPlan.Kind kind,
                                                              final long stale, final long current,
                                                              final long nicId) {
        return new VfOwnershipRepairPlan.Candidate(kind, 1553L, nicId, current, stale,
                16L, 269L, "0000:01:00.5", "0000:01:07.2", "02:04:02:9b:00:07");
    }
}
