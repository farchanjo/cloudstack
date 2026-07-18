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
 * {@code ovn.lb.auto.cks} must never force-rewrite hostNetwork Istio/accounting
 * public edge membership to the full CKS worker set. Those rules use explicit
 * Ready-only {@code assignToLoadBalancerRule} membership.
 */
public class OvnReconcilerLbAutoCksGuardTest {

    @Test
    public void nullRuleNotExplicit() {
        assertFalse(OvnReconcilerService.isExplicitMembershipOnlyLbRule(null));
    }

    @Test
    public void blankNameNotExplicit() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setName(" ");
        assertFalse(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
        rule.setName(null);
        assertFalse(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
    }

    @Test
    public void apiLbNotExplicit() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setName("api-lb");
        assertFalse(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
    }

    @Test
    public void istioPublicExplicit() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setName("istio-public-http");
        assertTrue(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
        rule.setName("istio-public-https");
        assertTrue(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
    }

    @Test
    public void istioAccountingExplicit() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setName("istio-accounting-http");
        assertTrue(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
        rule.setName("istio-accounting-https6");
        assertTrue(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
    }

    @Test
    public void pub6IstioExplicit() {
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setName("pub6-istio-http");
        assertTrue(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
        rule.setName("pub6-istio-https");
        assertTrue(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
    }

    @Test
    public void snapeDsrNotCaughtByIstioNames() {
        // DSR is refused via isCtLbInventoryRule, not the name guard.
        final LoadBalancerVO rule = new LoadBalancerVO();
        rule.setName("snape-dsr-http");
        rule.setLbKind(LbKind.DSR_SOFTWARE);
        assertFalse(OvnReconcilerService.isExplicitMembershipOnlyLbRule(rule));
        assertFalse(OvnReconcilerService.isCtLbInventoryRule(rule));
    }
}
