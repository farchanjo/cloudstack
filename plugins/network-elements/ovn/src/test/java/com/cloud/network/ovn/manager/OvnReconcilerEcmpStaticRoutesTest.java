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
package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import com.cloud.network.ovn.client.OvnNbClient.EcmpStaticRoute;
import com.cloud.network.ovn.manager.OvnReconcilerService.EcmpPlan;
import com.cloud.network.ovn.manager.OvnReconcilerService.PlannedRoute;
import com.cloud.network.ovn.manager.OvnReconcilerService.ResolvedRoute;

/**
 * Guard tests for the ECMP static-route reconciler
 * ({@code ovn.lr.ecmp.static.routes}). Idempotency, add, and
 * removal-of-owned-only are proven through the pure {@link
 * OvnReconcilerService#planEcmp} planner; the strict no-op on an absent
 * client/controller is proven through {@link
 * OvnReconcilerService#ensureEcmpStaticRoutes}. The NB apply path (row insert /
 * direct delete) is a thin wrapper over the already-tested {@code OvnNbClient}
 * operations, so it is not re-exercised here.
 */
public class OvnReconcilerEcmpStaticRoutesTest {

    private static final String SALAZAR = "a4226ad6-604a-4cd6-883e-777958562fe1";
    private static final String SNAPE = "d46c5f93-4f6f-47fc-89ad-b4b10fb30f90";
    private static final String LR_SALAZAR = "lr-765d9159-75bc-4fea-8335-6ee0152bf46f";
    private static final String PREFIX = "10.140.0.0/24";
    private static final String NH1 = "10.45.0.14";
    private static final String NH2 = "10.45.0.159";
    private static final String NH3 = "10.45.0.253";

    private static ResolvedRoute resolved(final String lr, final String prefix, final String... hops) {
        return new ResolvedRoute(lr, prefix, Arrays.asList(hops));
    }

    private static EcmpStaticRoute owned(final String uuid, final String owner, final String prefix,
                                         final String nexthop) {
        return new EcmpStaticRoute(uuid, prefix, nexthop, owner);
    }

    private static List<String> addKeys(final EcmpPlan plan) {
        return plan.getToAdd().stream()
                .map(p -> p.getOwner() + '|' + p.getPrefix() + '|' + p.getNexthop())
                .collect(Collectors.toList());
    }

    // ---------- add ----------

    @Test
    public void addsAllNextHopsWhenNoneExist() {
        final Map<String, ResolvedRoute> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(
                desired, desired.keySet(), Collections.emptyList());

        assertEquals(3, plan.getToAdd().size());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(3, plan.size());
        for (final PlannedRoute pr : plan.getToAdd()) {
            assertEquals(LR_SALAZAR, pr.getLrUuid());
            assertEquals(PREFIX, pr.getPrefix());
            assertEquals(SALAZAR, pr.getOwner());
        }
        assertTrue(addKeys(plan).containsAll(Arrays.asList(
                SALAZAR + '|' + PREFIX + '|' + NH1,
                SALAZAR + '|' + PREFIX + '|' + NH2,
                SALAZAR + '|' + PREFIX + '|' + NH3)));
    }

    // ---------- idempotency ----------

    @Test
    public void idempotentWhenExistingMatchesDesired() {
        final Map<String, ResolvedRoute> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, NH3));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, desired.keySet(), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(0, plan.size());
    }

    @Test
    public void addsOnlyTheMissingNextHop() {
        final Map<String, ResolvedRoute> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, NH3));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, desired.keySet(), existing);

        assertEquals(1, plan.getToAdd().size());
        assertEquals(NH3, plan.getToAdd().get(0).getNexthop());
        assertTrue(plan.getToRemove().isEmpty());
    }

    // ---------- removal of owned only ----------

    @Test
    public void removesAllOwnedRowsWhenConfigCleared() {
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(
                Collections.emptyMap(), Collections.emptySet(), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Arrays.asList("u1", "u2", "u3"), plan.getToRemove());
    }

    @Test
    public void removesOnlyTheDroppedNextHop() {
        final Map<String, ResolvedRoute> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, desired.keySet(), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Collections.singletonList("u3"), plan.getToRemove());
    }

    @Test
    public void addsAndRemovesTogetherOnNextHopChurn() {
        final String nhNew = "10.45.0.200";
        final Map<String, ResolvedRoute> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, nhNew));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, desired.keySet(), existing);

        assertEquals(1, plan.getToAdd().size());
        assertEquals(nhNew, plan.getToAdd().get(0).getNexthop());
        assertEquals(Collections.singletonList("u3"), plan.getToRemove());
    }

    @Test
    public void keepsRowsWhenOwnerConfiguredButUnresolved() {
        // salazar is still in the config (configuredOwners) but could not be
        // resolved this pass (network/LR lookup transiently failed → not in
        // resolvedDesired). Its owned rows must be left in place, not flapped.
        final Set<String> configured = Collections.singleton(SALAZAR);
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(Collections.emptyMap(), configured, existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
    }

    @Test
    public void independentOwnersDoNotInterfere() {
        final Map<String, ResolvedRoute> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1));
        // snape dropped from config; its owned row must be removed while
        // salazar's newly-desired row is added.
        final List<EcmpStaticRoute> existing = new ArrayList<>();
        existing.add(owned("snape-u1", SNAPE, "10.141.0.0/24", "10.45.4.73"));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, desired.keySet(), existing);

        assertEquals(1, plan.getToAdd().size());
        assertEquals(SALAZAR, plan.getToAdd().get(0).getOwner());
        assertEquals(Collections.singletonList("snape-u1"), plan.getToRemove());
    }

    // ---------- strict no-op ----------

    @Test
    public void ensureIsNoOpWhenClientOrControllerNull() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        assertEquals(0, svc.ensureEcmpStaticRoutes(null, null, Collections.emptyMap(), false));
        final Map<String, com.cloud.network.ovn.config.OvnEcmpRoutes.Route> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, new com.cloud.network.ovn.config.OvnEcmpRoutes.Route(
                PREFIX, Arrays.asList(NH1, NH2)));
        assertEquals(0, svc.ensureEcmpStaticRoutes(null, null, desired, true));
    }
}
