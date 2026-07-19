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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Exercise the OVS hairpin / tc-policy reconcile sweep on
 * {@link OvnReconcilerService}. The actual {@code ovs-vsctl} invocation
 * runs agent-side at every NIC plug; the management-side reconciler hook
 * simply records that the sweep ran by ticking the synthetic table
 * categories on the {@link OvnReconcilerService.Result}. The tests below
 * pin that contract so a future regression that drops the categories from
 * the API surface fails loudly here rather than silently in the admin
 * dashboard.
 *
 * <p><b>Fix #3:</b> ACK counters (hairpin-swept, tc-policy-swept) are now
 * recorded under the separate {acks} map and do NOT inflate
 * {totalorphans}. A prior version merged these into {orphans}, which made
 * a clean zone report totalorphans=2 even when zero ports drifted.
 */
public class OvnReconcilerOvsPolicyTest {

    @Test
    public void reassertOvsPolicyRecordsBothCategories() {
        OvnReconcilerService svc = new OvnReconcilerService();
        OvnReconcilerService.Result out = new OvnReconcilerService.Result(false);
        svc.reassertOvsPolicy(7L, false, out);
        assertNotNull(out.getAcksByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertNotNull(out.getAcksByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
        assertEquals(Integer.valueOf(1),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertEquals(Integer.valueOf(1),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }

    @Test
    public void reassertOvsPolicyAccumulatesAcrossMultipleZones() {
        OvnReconcilerService svc = new OvnReconcilerService();
        OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        svc.reassertOvsPolicy(1L, true, out);
        svc.reassertOvsPolicy(2L, true, out);
        svc.reassertOvsPolicy(3L, true, out);
        assertEquals(Integer.valueOf(3),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertEquals(Integer.valueOf(3),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }

    @Test
    public void reassertOvsPolicyTolerateNullResult() {
        OvnReconcilerService svc = new OvnReconcilerService();
        // Must not throw - defensive null check inside the hook.
        svc.reassertOvsPolicy(1L, false, null);
    }

    @Test
    public void resolveSweepPortRegexNeverBlankAndCompiles() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        final String regex = svc.resolveSweepPortRegex();
        assertNotNull(regex);
        assertTrue("sweep regex must be non-blank", !regex.trim().isEmpty());
        // Must compile + match a VF representor; must never match infra ports.
        final java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        assertTrue(p.matcher("dx6p0vf3").matches());
        assertTrue(!p.matcher("pub-anchor").matches());
    }

    @Test
    public void sweepPortRegexDefaultCoversVfVdpaAndTapButNotInfra() {
        // The ConfigKey default is the whole point of the follow-up: the sweep
        // must cover all three NIC modes, not just VF.
        final java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                com.cloud.network.ovn.config.OvnNicConfig.OvsSweepPortRegex.defaultValue());
        // VF + vDPA both attach the VF representor dx<N>p<N>vf<N>.
        assertTrue(p.matcher("dx6p0vf3").matches());
        assertTrue(p.matcher("dx0p1vf15").matches());
        // virtio / tap (libvirt vnet<N>).
        assertTrue(p.matcher("vnet0").matches());
        assertTrue(p.matcher("vnet42").matches());
        // infra ports must stay out of scope (anchored, no substring hits).
        assertTrue(!p.matcher("pub-anchor").matches());
        assertTrue(!p.matcher("patch-lsp-public-localnet-to-br-overlay").matches());
        assertTrue(!p.matcher("cs-public").matches());
        assertTrue(!p.matcher("br-int").matches());
    }

    @Test
    public void synthethicTableKeysAreNamedDistinctly() {
        // The two new categories must not collide with the existing
        // localnet-vlan synthetic key - distinct rows in the admin output.
        assertTrue(!OvnReconcilerService.Result.OVS_HAIRPIN_TABLE
                .equals(OvnReconcilerService.Result.LOCALNET_VLAN_TABLE));
        assertTrue(!OvnReconcilerService.Result.OVS_TC_POLICY_TABLE
                .equals(OvnReconcilerService.Result.LOCALNET_VLAN_TABLE));
        assertTrue(!OvnReconcilerService.Result.OVS_HAIRPIN_TABLE
                .equals(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }

    // ------------------------------------------------------------------
    // Fix #3 regression: ACK rows do not inflate totalorphans.
    // ------------------------------------------------------------------

    @Test
    public void reassertOvsPolicyAckDoesNotInflateTotalOrphans() {
        OvnReconcilerService svc = new OvnReconcilerService();
        OvnReconcilerService.Result out = new OvnReconcilerService.Result(false);
        svc.reassertOvsPolicy(1L, false, out);
        // The ACK counters (hairpin-swept, tc-policy-swept) land in acks,
        // NOT orphans. A clean zone must report totalorphans=0.
        assertEquals("OVS policy ack must not inflate totalorphans", 0, out.totalOrphans());
    }

    @Test
    public void reassertOvsPolicyCleanZoneReportsZeroOrphans() {
        OvnReconcilerService svc = new OvnReconcilerService();
        OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        // Two zones swept, no drift on either -> orphans=0. The acks map
        // accumulates by key (HAIRPIN_TABLE, TC_POLICY_TABLE), so the map
        // size is 2 (one per category), not 4 (one per zone x category).
        // The VALUE of each key is the accumulated zone count.
        svc.reassertOvsPolicy(1L, true, out);
        svc.reassertOvsPolicy(2L, true, out);
        assertEquals(0, out.totalOrphans());
        assertEquals(2, out.getAcksByTable().size());
        assertEquals(Integer.valueOf(2),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertEquals(Integer.valueOf(2),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }
}
