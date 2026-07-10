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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import com.cloud.network.ovn.client.OvnNbClient.EcmpStaticRoute;
import com.cloud.network.ovn.config.OvnEcmpRoutes;
import com.cloud.network.ovn.manager.OvnReconcilerService.EcmpPlan;
import com.cloud.network.ovn.manager.OvnReconcilerService.PlannedRoute;
import com.cloud.network.ovn.manager.OvnReconcilerService.ResolvedRoute;

/**
 * Guard tests for the ECMP static-route reconciler
 * ({@code ovn.lr.ecmp.static.routes}). Idempotency, add, dual-stack multi-prefix,
 * and removal-of-owned-only are proven through the pure {@link
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
    private static final String PREFIX6 = "fd00:cafe:2::/108";
    private static final String NH1 = "10.45.0.14";
    private static final String NH2 = "10.45.0.159";
    private static final String NH3 = "10.45.0.253";
    private static final String NH6_1 = "2a13:8740:0:a::5";
    private static final String NH6_2 = "2a13:8740:0:a::6";

    private static ResolvedRoute resolved(final String lr, final String prefix, final String... hops) {
        return new ResolvedRoute(lr, prefix, Arrays.asList(hops));
    }

    private static Map<String, List<ResolvedRoute>> single(final String owner, final ResolvedRoute rr) {
        final Map<String, List<ResolvedRoute>> m = new LinkedHashMap<>();
        m.put(owner, Collections.singletonList(rr));
        return m;
    }

    private static Map<String, List<ResolvedRoute>> dual(final String owner, final ResolvedRoute v4,
                                                         final ResolvedRoute v6) {
        final Map<String, List<ResolvedRoute>> m = new LinkedHashMap<>();
        m.put(owner, Arrays.asList(v4, v6));
        return m;
    }

    private static Set<String> ownerPrefixes(final Map<String, List<ResolvedRoute>> desired) {
        final Set<String> out = new HashSet<>();
        for (final Map.Entry<String, List<ResolvedRoute>> e : desired.entrySet()) {
            for (final ResolvedRoute rr : e.getValue()) {
                out.add(OvnReconcilerService.ownerPrefixKey(e.getKey(), rr.getPrefix()));
            }
        }
        return out;
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
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(
                desired, ownerPrefixes(desired), Collections.emptyList());

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

    @Test
    public void dualPrefixAddsAllNextHopsForBothFamilies() {
        final Map<String, List<ResolvedRoute>> desired = dual(SALAZAR,
                resolved(LR_SALAZAR, PREFIX, NH1, NH2),
                resolved(LR_SALAZAR, PREFIX6, NH6_1, NH6_2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(
                desired, ownerPrefixes(desired), Collections.emptyList());

        assertEquals(4, plan.getToAdd().size());
        assertTrue(plan.getToRemove().isEmpty());
        assertTrue(addKeys(plan).containsAll(Arrays.asList(
                SALAZAR + '|' + PREFIX + '|' + NH1,
                SALAZAR + '|' + PREFIX + '|' + NH2,
                SALAZAR + '|' + PREFIX6 + '|' + NH6_1,
                SALAZAR + '|' + PREFIX6 + '|' + NH6_2)));
    }

    // ---------- idempotency ----------

    @Test
    public void idempotentWhenExistingMatchesDesired() {
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, NH3));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(0, plan.size());
    }

    @Test
    public void addsOnlyTheMissingNextHop() {
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, NH3));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

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
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Collections.singletonList("u3"), plan.getToRemove());
    }

    @Test
    public void removesOnePrefixOnlyKeepsSibling() {
        // Config drops IPv6 prefix; IPv4 remains. Existing v6 rows must go; v4 stays.
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("v4-1", SALAZAR, PREFIX, NH1),
                owned("v4-2", SALAZAR, PREFIX, NH2),
                owned("v6-1", SALAZAR, PREFIX6, NH6_1),
                owned("v6-2", SALAZAR, PREFIX6, NH6_2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Arrays.asList("v6-1", "v6-2"), plan.getToRemove());
    }

    @Test
    public void keepsUnresolvedPrefixWhileSiblingResolves() {
        // v4 resolved this pass; v6 still in raw config but unresolved (network/LR
        // infra failure — not present in resolvedDesired). Existing v6 rows must
        // be KEPT (anti-flap). Empty hops are NOT this case — see
        // removesAllOwnedWhenResolvedWithEmptyHopList.
        final Map<String, List<ResolvedRoute>> resolved =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1));
        final Set<String> configured = new HashSet<>(Arrays.asList(
                OvnReconcilerService.ownerPrefixKey(SALAZAR, PREFIX),
                OvnReconcilerService.ownerPrefixKey(SALAZAR, PREFIX6)));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("v4-1", SALAZAR, PREFIX, NH1),
                owned("v6-1", SALAZAR, PREFIX6, NH6_1),
                owned("v6-2", SALAZAR, PREFIX6, NH6_2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(resolved, configured, existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
    }

    @Test
    public void removesAllOwnedWhenResolvedWithEmptyHopList() {
        // Network+LR resolved, but every next-hop was pruned (stopped VMs / out of
        // CIDR). Empty hop list still marks owner|prefix as resolved, so stale
        // owned rows must be REMOVED — not anti-flap-kept as blackholes.
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX /* empty hops */));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Arrays.asList("u1", "u2", "u3"), plan.getToRemove());
    }

    @Test
    public void emptyHopResolvedPrefixDoesNotKeepSiblingRemoval() {
        // v4 resolved empty (remove its rows); v6 resolved with hops (keep).
        final Map<String, List<ResolvedRoute>> desired = dual(SALAZAR,
                resolved(LR_SALAZAR, PREFIX /* empty */),
                resolved(LR_SALAZAR, PREFIX6, NH6_1));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("v4-1", SALAZAR, PREFIX, NH1),
                owned("v6-1", SALAZAR, PREFIX6, NH6_1));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Collections.singletonList("v4-1"), plan.getToRemove());
    }

    @Test
    public void addsAndRemovesTogetherOnNextHopChurn() {
        final String nhNew = "10.45.0.200";
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1, NH2, nhNew));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2),
                owned("u3", SALAZAR, PREFIX, NH3));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertEquals(1, plan.getToAdd().size());
        assertEquals(nhNew, plan.getToAdd().get(0).getNexthop());
        assertEquals(Collections.singletonList("u3"), plan.getToRemove());
    }

    @Test
    public void keepsRowsWhenOwnerPrefixConfiguredButUnresolved() {
        // salazar|prefix is still in the config (configuredOwnerPrefixes) but
        // could not be resolved this pass (network/LR lookup transiently failed
        // → not in resolvedDesired). Its owned rows must be left in place.
        final Set<String> configured =
                Collections.singleton(OvnReconcilerService.ownerPrefixKey(SALAZAR, PREFIX));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("u1", SALAZAR, PREFIX, NH1),
                owned("u2", SALAZAR, PREFIX, NH2));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(Collections.emptyMap(), configured, existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
    }

    @Test
    public void independentOwnersDoNotInterfere() {
        final Map<String, List<ResolvedRoute>> desired =
                single(SALAZAR, resolved(LR_SALAZAR, PREFIX, NH1));
        // snape dropped from config; its owned row must be removed while
        // salazar's newly-desired row is added.
        final List<EcmpStaticRoute> existing = new ArrayList<>();
        existing.add(owned("snape-u1", SNAPE, "10.141.0.0/24", "10.45.4.73"));

        final EcmpPlan plan = OvnReconcilerService.planEcmp(desired, ownerPrefixes(desired), existing);

        assertEquals(1, plan.getToAdd().size());
        assertEquals(SALAZAR, plan.getToAdd().get(0).getOwner());
        assertEquals(Collections.singletonList("snape-u1"), plan.getToRemove());
    }

    // ---------- strict no-op ----------

    @Test
    public void ensureIsNoOpWhenClientOrControllerNull() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        assertEquals(0, svc.ensureEcmpStaticRoutes(null, null, Collections.emptyMap(), false));
        final Map<String, List<OvnEcmpRoutes.Route>> desired = new LinkedHashMap<>();
        desired.put(SALAZAR, Collections.singletonList(new OvnEcmpRoutes.Route(
                PREFIX, Arrays.asList(NH1, NH2))));
        assertEquals(0, svc.ensureEcmpStaticRoutes(null, null, desired, true));
    }
}
