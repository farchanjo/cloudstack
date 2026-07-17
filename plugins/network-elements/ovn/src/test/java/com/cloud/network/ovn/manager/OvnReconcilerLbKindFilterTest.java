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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;

/**
 * Public IPv6 inventory → OVN Load_Balancer desired set must only include CT_LB
 * (null/legacy kind counts as CT_LB). DSR_SOFTWARE must never be programmed.
 */
public class OvnReconcilerLbKindFilterTest {

    @Test
    public void nullKindIsLegacyCtLb() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        // default VO getter returns CT_LB when field null
        assertTrue(OvnReconcilerService.isCtLbInventoryRule(rule));
    }

    @Test
    public void explicitCtLbIncluded() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setLbKind(LbKind.CT_LB);
        assertTrue(OvnReconcilerService.isCtLbInventoryRule(rule));
    }

    @Test
    public void dsrSoftwareExcluded() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setLbKind(LbKind.DSR_SOFTWARE);
        assertFalse(OvnReconcilerService.isCtLbInventoryRule(rule));
    }

    @Test
    public void nullRuleExcluded() {
        assertFalse(OvnReconcilerService.isCtLbInventoryRule(null));
    }
}
