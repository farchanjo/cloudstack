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
package com.cloud.network.lb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;

/**
 * Regression: public IPv6 LB create must not wrap the VIP with IPv4-only
 * {@link Ip#Ip(String)} (ip2Long). 2026-07-17 canary: Snape ::101 create NPE/exception.
 */
public class PublicIpv6LoadBalancerCreateSafetyTest {

    private static final String PUB6 = "2a13:8740:0:7::101";

    @Test
    public void netUtilsAcceptsPub6Vip() {
        assertTrue(NetUtils.isValidIp6(PUB6));
        assertFalse(NetUtils.isValidIp4(PUB6));
    }

    @Test
    public void ipv4OnlyIpConstructorDoesNotPreserveIpv6Vip() {
        // Document the hazard: Ip(String) always goes through NetUtils.ip2Long (IPv4).
        // Under -ea, assert tokens.length==4 can fire; without -ea, parse is wrong.
        // Either way the result is not a usable IPv6 VIP string.
        boolean threw = false;
        String rendered = null;
        try {
            rendered = new Ip(PUB6).addr();
        } catch (Throwable t) {
            threw = true;
        }
        if (!threw) {
            assertFalse("Ip(String) must not round-trip an IPv6 VIP",
                    PUB6.equalsIgnoreCase(rendered));
            assertFalse(rendered != null && rendered.contains(":"));
        }
    }

    @Test
    public void isPublicIpv6LoadBalancerWhenOnlyPub6Bound() {
        LoadBalancingRulesManagerImpl mgr = new LoadBalancingRulesManagerImpl();
        LoadBalancerVO lb = new LoadBalancerVO();
        lb.setPublicIpv6AddressId(42L);
        // sourceIpAddressId remains null
        assertTrue(mgr.isPublicIpv6LoadBalancer(lb));
        lb.setLbKind(LbKind.CT_LB);
        assertTrue(mgr.isPublicIpv6LoadBalancer(lb));
    }

    @Test
    public void isPublicIpv6LoadBalancerFalseWhenIpv4Bound() {
        LoadBalancingRulesManagerImpl mgr = new LoadBalancingRulesManagerImpl();
        LoadBalancerVO lb = new LoadBalancerVO("x", "n", "d", 7L, 80, 8080, "roundrobin",
                1L, 1L, 1L, "tcp", null);
        assertFalse(mgr.isPublicIpv6LoadBalancer(lb));
    }

    @Test
    public void loadBalancingRuleAllowsNullSourceIpForPub6Validate() {
        // Mirrors createPublicIpv6LoadBalancerRule: sourceIp left null for IPv6 VIP.
        LoadBalancerVO rule = new LoadBalancerVO("x", "n", "d", null, 99L, 80, 8080,
                "roundrobin", 1L, 1L, 1L, "tcp", null);
        rule.setLbKind(LbKind.CT_LB);
        LoadBalancingRule lbr = new LoadBalancingRule(rule, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null, null, "tcp");
        assertNull(lbr.getSourceIp());
        assertTrue(rule.getLbKind().isCtLb());
    }
}
