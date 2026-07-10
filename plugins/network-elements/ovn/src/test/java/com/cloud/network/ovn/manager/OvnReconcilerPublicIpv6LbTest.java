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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.cloud.network.ovn.client.OvnNbClient.OwnedLoadBalancer;
import com.cloud.network.ovn.config.OvnPublicIpv6Lb;
import com.cloud.network.ovn.config.OvnPublicIpv6Lb.Entry;
import com.cloud.network.ovn.config.OvnPublicIpv6Lb.HostPort;
import com.cloud.network.ovn.manager.OvnReconcilerService.Pub6LbPlan;
import com.cloud.network.ovn.manager.OvnReconcilerService.ResolvedPub6Lb;

/**
 * Guard tests for the public IPv6 LB reconciler ({@code ovn.lr.public.ipv6.lb}
 * dual-read with inventory). Idempotency, create, update, and removal-of-
 * owned-only are proven through the pure
 * {@link OvnReconcilerService#planPublicIpv6Lb} planner; dual-read merge via
 * {@link OvnReconcilerService#mergePublicIpv6LbDesired}; the strict no-op on
 * an absent client/controller is proven through
 * {@link OvnReconcilerService#ensurePublicIpv6Lb}.
 */
public class OvnReconcilerPublicIpv6LbTest {

    private static final String SALAZAR = "a4226ad6-604a-4cd6-883e-777958562fe1";
    private static final String SNAPE = "d46c5f93-4f6f-47fc-89ad-b4b10fb30f90";
    private static final String LR = "lr-765d9159-75bc-4fea-8335-6ee0152bf46f";
    private static final String LS = "ls-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String VIP = "2a13:8740:0:7::100";
    private static final String VIP2 = "2a13:8740:0:7::101";
    private static final String BE1 = "2a13:8740:0:a::14";
    private static final String BE2 = "2a13:8740:0:a::15";
    private static final String BE3 = "2a13:8740:0:a::16";

    private static String key(final String net, final String vip, final int port) {
        return OvnPublicIpv6Lb.entryKey(net, vip, port);
    }

    private static ResolvedPub6Lb resolved(final String net, final String vip, final int port,
                                           final String... beHosts) {
        final List<HostPort> hops = new ArrayList<>();
        for (final String h : beHosts) {
            hops.add(new HostPort(h, port));
        }
        return new ResolvedPub6Lb(key(net, vip, port), net, LR, LS, vip, port, hops, 1L, 1L);
    }

    private static OwnedLoadBalancer owned(final String uuid, final String owner,
                                           final Map<String, String> vips) {
        return new OwnedLoadBalancer(uuid, "cs-pub6-lb", vips, "tcp", owner);
    }

    // ---------- create ----------

    @Test
    public void createsWhenNoneExist() {
        final Map<String, ResolvedPub6Lb> desired = new LinkedHashMap<>();
        final ResolvedPub6Lb rr = resolved(SALAZAR, VIP, 80, BE1, BE2);
        desired.put(rr.getEntryKey(), rr);

        final Pub6LbPlan plan = OvnReconcilerService.planPublicIpv6Lb(
                desired, desired.keySet(), Collections.emptyList());

        assertEquals(1, plan.getToCreate().size());
        assertTrue(plan.getToUpdate().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(1, plan.size());
        assertEquals(VIP, plan.getToCreate().get(0).getVip());
    }

    // ---------- idempotency ----------

    @Test
    public void idempotentWhenExistingMatchesDesired() {
        final Map<String, ResolvedPub6Lb> desired = new LinkedHashMap<>();
        final ResolvedPub6Lb rr = resolved(SALAZAR, VIP, 80, BE1, BE2);
        desired.put(rr.getEntryKey(), rr);
        final List<OwnedLoadBalancer> existing = Collections.singletonList(
                owned("u1", rr.getEntryKey(), rr.toVipsMap()));

        final Pub6LbPlan plan = OvnReconcilerService.planPublicIpv6Lb(desired, desired.keySet(), existing);

        assertTrue(plan.getToCreate().isEmpty());
        assertTrue(plan.getToUpdate().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(0, plan.size());
    }

    @Test
    public void updatesWhenBackendsChange() {
        final Map<String, ResolvedPub6Lb> desired = new LinkedHashMap<>();
        final ResolvedPub6Lb rr = resolved(SALAZAR, VIP, 80, BE1, BE2, BE3);
        desired.put(rr.getEntryKey(), rr);
        final Map<String, String> oldVips = resolved(SALAZAR, VIP, 80, BE1, BE2).toVipsMap();
        final List<OwnedLoadBalancer> existing = Collections.singletonList(
                owned("u1", rr.getEntryKey(), oldVips));

        final Pub6LbPlan plan = OvnReconcilerService.planPublicIpv6Lb(desired, desired.keySet(), existing);

        assertTrue(plan.getToCreate().isEmpty());
        assertEquals(1, plan.getToUpdate().size());
        assertEquals("u1", plan.getToUpdate().get(0).getUuid());
        assertTrue(plan.getToRemove().isEmpty());
    }

    // ---------- removal of owned only ----------

    @Test
    public void removesAllOwnedWhenConfigCleared() {
        final String owner = key(SALAZAR, VIP, 80);
        final List<OwnedLoadBalancer> existing = Arrays.asList(
                owned("u1", owner, resolved(SALAZAR, VIP, 80, BE1).toVipsMap()),
                owned("u2", key(SNAPE, VIP2, 443), resolved(SNAPE, VIP2, 443, BE1).toVipsMap()));

        final Pub6LbPlan plan = OvnReconcilerService.planPublicIpv6Lb(
                Collections.emptyMap(), Collections.emptySet(), existing);

        assertTrue(plan.getToCreate().isEmpty());
        assertEquals(2, plan.getToRemove().size());
    }

    @Test
    public void keepsRowsWhenOwnerConfiguredButUnresolved() {
        final String owner = key(SALAZAR, VIP, 80);
        final Set<String> configured = Collections.singleton(owner);
        final List<OwnedLoadBalancer> existing = Collections.singletonList(
                owned("u1", owner, resolved(SALAZAR, VIP, 80, BE1).toVipsMap()));

        final Pub6LbPlan plan = OvnReconcilerService.planPublicIpv6Lb(
                Collections.emptyMap(), configured, existing);

        assertTrue(plan.getToCreate().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
    }

    @Test
    public void independentOwnersDoNotInterfere() {
        final Map<String, ResolvedPub6Lb> desired = new LinkedHashMap<>();
        final ResolvedPub6Lb sal = resolved(SALAZAR, VIP, 80, BE1);
        desired.put(sal.getEntryKey(), sal);
        final List<OwnedLoadBalancer> existing = Collections.singletonList(
                owned("snape-u1", key(SNAPE, VIP2, 80), resolved(SNAPE, VIP2, 80, BE1).toVipsMap()));

        final Pub6LbPlan plan = OvnReconcilerService.planPublicIpv6Lb(desired, desired.keySet(), existing);

        assertEquals(1, plan.getToCreate().size());
        assertEquals(SALAZAR, plan.getToCreate().get(0).getNetworkUuid());
        assertEquals(1, plan.getToRemove().size());
        assertEquals("snape-u1", plan.getToRemove().get(0).getUuid());
    }

    // ---------- helpers ----------

    @Test
    public void entryKeyParsers() {
        final String owner = key(SALAZAR, VIP, 80);
        assertEquals(SALAZAR, OvnReconcilerService.networkUuidFromEntryKey(owner));
        assertEquals(VIP, OvnReconcilerService.vipFromEntryKey(owner));
    }

    @Test
    public void vipFromOwnedLbPrefersMarkerThenVipsMap() {
        final String owner = key(SALAZAR, VIP, 80);
        final OwnedLoadBalancer withMarker = owned("u1", owner, resolved(SALAZAR, VIP, 80, BE1).toVipsMap());
        assertEquals(VIP, OvnReconcilerService.vipFromOwnedLb(withMarker));

        // Marker unparseable — fall back to [vip]:port vips map key.
        final Map<String, String> vips = Collections.singletonMap(
                OvnPublicIpv6Lb.formatVipKey(VIP2, 443), BE1 + ":443");
        final OwnedLoadBalancer markerless = owned("u2", "bad-marker", vips);
        assertEquals(VIP2, OvnReconcilerService.vipFromOwnedLb(markerless));
    }

    @Test
    public void vipFromVipsMapKeyStripsBracketsAndPort() {
        assertEquals(VIP, OvnReconcilerService.vipFromVipsMapKey("[" + VIP + "]:80"));
        assertEquals("10.1.2.3", OvnReconcilerService.vipFromVipsMapKey("10.1.2.3:80"));
        assertEquals(null, OvnReconcilerService.vipFromVipsMapKey(null));
    }

    // ---------- strict no-op ----------

    @Test
    public void ensureIsNoOpWhenClientOrControllerNull() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        assertEquals(0, svc.ensurePublicIpv6Lb(null, null, 1L, Collections.emptyList(), false));
        final List<OvnPublicIpv6Lb.Entry> desired = Collections.singletonList(
                new OvnPublicIpv6Lb.Entry(SALAZAR, VIP, 80, Arrays.asList(new HostPort(BE1, 80))));
        assertEquals(0, svc.ensurePublicIpv6Lb(null, null, 1L, desired, true));
    }

    // ---------- dual-read merge (ConfigKey ∪ inventory) ----------

    private static Entry entry(final String net, final String vip, final int port, final String... beHosts) {
        final List<HostPort> hops = new ArrayList<>();
        for (final String h : beHosts) {
            hops.add(new HostPort(h, port));
        }
        return new Entry(net, vip, port, hops);
    }

    @Test
    public void mergeConfigOnlyPreservesConfigEntries() {
        final Entry cfg = entry(SALAZAR, VIP, 80, BE1, BE2);
        final List<Entry> merged = OvnReconcilerService.mergePublicIpv6LbDesired(
                Collections.singletonList(cfg), Collections.emptyList());

        assertEquals(1, merged.size());
        assertEquals(cfg, merged.get(0));
        assertEquals(key(SALAZAR, VIP, 80), merged.get(0).entryKey());
    }

    @Test
    public void mergeInventoryOnlyPreservesInventoryEntries() {
        final Entry inv = entry(SNAPE, VIP2, 443, BE3);
        final List<Entry> merged = OvnReconcilerService.mergePublicIpv6LbDesired(
                Collections.emptyList(), Collections.singletonList(inv));

        assertEquals(1, merged.size());
        assertEquals(inv, merged.get(0));
        assertEquals(key(SNAPE, VIP2, 443), merged.get(0).entryKey());
    }

    @Test
    public void mergeUnionKeepsDistinctKeysFromBothSources() {
        final Entry cfg = entry(SALAZAR, VIP, 80, BE1);
        final Entry inv = entry(SNAPE, VIP2, 443, BE2);
        final List<Entry> merged = OvnReconcilerService.mergePublicIpv6LbDesired(
                Collections.singletonList(cfg), Collections.singletonList(inv));

        assertEquals(2, merged.size());
        // Deterministic order by entryKey.
        assertEquals(key(SALAZAR, VIP, 80), merged.get(0).entryKey());
        assertEquals(key(SNAPE, VIP2, 443), merged.get(1).entryKey());
    }

    @Test
    public void mergeConflictPrefersInventory() {
        final Entry cfg = entry(SALAZAR, VIP, 80, BE1, BE2);
        final Entry inv = entry(SALAZAR, VIP, 80, BE3); // same key, different backends
        assertEquals(cfg.entryKey(), inv.entryKey());
        assertNotEquals(cfg, inv);

        final List<Entry> merged = OvnReconcilerService.mergePublicIpv6LbDesired(
                Collections.singletonList(cfg), Collections.singletonList(inv));

        assertEquals(1, merged.size());
        assertEquals(inv, merged.get(0));
        assertEquals(Collections.singletonList(new HostPort(BE3, 80)), merged.get(0).getBackends());
    }

    @Test
    public void mergeIdenticalSourcesIsIdempotent() {
        final Entry cfg = entry(SALAZAR, VIP, 80, BE1, BE2);
        final Entry inv = entry(SALAZAR, VIP, 80, BE1, BE2);
        final List<Entry> merged = OvnReconcilerService.mergePublicIpv6LbDesired(
                Collections.singletonList(cfg), Collections.singletonList(inv));

        assertEquals(1, merged.size());
        assertEquals(inv, merged.get(0));
    }

    @Test
    public void mergeNullAndEmptyYieldEmpty() {
        assertTrue(OvnReconcilerService.mergePublicIpv6LbDesired(null, null).isEmpty());
        assertTrue(OvnReconcilerService.mergePublicIpv6LbDesired(
                Collections.emptyList(), null).isEmpty());
        assertTrue(OvnReconcilerService.mergePublicIpv6LbDesired(
                null, Collections.emptyList()).isEmpty());
    }

    @Test
    public void mergeOrdersDeterministicallyByEntryKey() {
        // Insert reverse of natural entryKey order; output must still sort.
        final Entry snape = entry(SNAPE, VIP2, 443, BE2);
        final Entry salazar = entry(SALAZAR, VIP, 80, BE1);
        final List<Entry> merged = OvnReconcilerService.mergePublicIpv6LbDesired(
                Arrays.asList(snape, salazar), Collections.emptyList());

        assertEquals(2, merged.size());
        assertTrue(merged.get(0).entryKey().compareTo(merged.get(1).entryKey()) < 0);
        assertEquals(salazar.entryKey(), merged.get(0).entryKey());
        assertSame(salazar, merged.get(0));
        assertSame(snape, merged.get(1));
    }

    @Test
    public void loadInventoryIsEmptyWhenDaosUninjected() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        assertTrue(svc.loadInventoryPublicIpv6Lbs(1L).isEmpty());
    }
}
