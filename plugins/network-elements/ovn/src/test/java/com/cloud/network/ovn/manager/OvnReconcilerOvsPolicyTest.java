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
 * Exercise the new OVS hairpin / tc-policy reconcile sweep on
 * {@link OvnReconcilerService}. The actual {@code ovs-vsctl} invocation
 * runs agent-side at every NIC plug; the management-side reconciler hook
 * simply records that the sweep ran by ticking the synthetic table
 * categories on the {@link OvnReconcilerService.Result}. The tests below
 * pin that contract so a future regression that drops the categories from
 * the API surface fails loudly here rather than silently in the admin
 * dashboard.
 */
public class OvnReconcilerOvsPolicyTest {

    @Test
    public void reassertOvsPolicyRecordsBothCategories() {
        OvnReconcilerService svc = new OvnReconcilerService();
        OvnReconcilerService.Result out = new OvnReconcilerService.Result(false);
        svc.reassertOvsPolicy(7L, false, out);
        assertNotNull(out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertNotNull(out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
        assertEquals(Integer.valueOf(1),
                out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertEquals(Integer.valueOf(1),
                out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }

    @Test
    public void reassertOvsPolicyAccumulatesAcrossMultipleZones() {
        OvnReconcilerService svc = new OvnReconcilerService();
        OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        svc.reassertOvsPolicy(1L, true, out);
        svc.reassertOvsPolicy(2L, true, out);
        svc.reassertOvsPolicy(3L, true, out);
        assertEquals(Integer.valueOf(3),
                out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertEquals(Integer.valueOf(3),
                out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }

    @Test
    public void reassertOvsPolicyTolerateNullResult() {
        OvnReconcilerService svc = new OvnReconcilerService();
        // Must not throw — defensive null check inside the hook.
        svc.reassertOvsPolicy(1L, false, null);
    }

    @Test
    public void synthethicTableKeysAreNamedDistinctly() {
        // The two new categories must not collide with the existing
        // localnet-vlan synthetic key — distinct rows in the admin output.
        assertTrue(!OvnReconcilerService.Result.OVS_HAIRPIN_TABLE
                .equals(OvnReconcilerService.Result.LOCALNET_VLAN_TABLE));
        assertTrue(!OvnReconcilerService.Result.OVS_TC_POLICY_TABLE
                .equals(OvnReconcilerService.Result.LOCALNET_VLAN_TABLE));
        assertTrue(!OvnReconcilerService.Result.OVS_HAIRPIN_TABLE
                .equals(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }
}
