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
package org.apache.cloudstack.api.command.user.loadbalancer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;

/**
 * Focused unit tests for public IPv4/IPv6 VIP XOR on createLoadBalancerRule.
 * No Spring context — pure field + private-method invoke.
 */
public class CreateLoadBalancerRuleCmdTest {

    private CreateLoadBalancerRuleCmd cmd;

    @Before
    public void setUp() {
        cmd = new CreateLoadBalancerRuleCmd();
    }

    @Test
    public void isPublicIpv6Path_falseWhenUnset() {
        assertFalse(cmd.isPublicIpv6Path());
    }

    @Test
    public void isPublicIpv6Path_trueWhenPublicIpv6IdSet() {
        ReflectionTestUtils.setField(cmd, "publicIpv6Id", 42L);
        assertTrue(cmd.isPublicIpv6Path());
    }

    @Test
    public void validatePublicVipXor_allowsIpv4Only() {
        ReflectionTestUtils.setField(cmd, "publicIpId", 1L);
        ReflectionTestUtils.setField(cmd, "publicIpv6Id", null);
        ReflectionTestUtils.invokeMethod(cmd, "validatePublicVipXor");
    }

    @Test
    public void validatePublicVipXor_allowsIpv6Only() {
        ReflectionTestUtils.setField(cmd, "publicIpId", null);
        ReflectionTestUtils.setField(cmd, "publicIpv6Id", 2L);
        ReflectionTestUtils.invokeMethod(cmd, "validatePublicVipXor");
    }

    @Test
    public void validatePublicVipXor_allowsNeitherForElasticPath() {
        ReflectionTestUtils.setField(cmd, "publicIpId", null);
        ReflectionTestUtils.setField(cmd, "publicIpv6Id", null);
        ReflectionTestUtils.invokeMethod(cmd, "validatePublicVipXor");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validatePublicVipXor_rejectsBothPublicIpAndPublicIpv6() {
        ReflectionTestUtils.setField(cmd, "publicIpId", 1L);
        ReflectionTestUtils.setField(cmd, "publicIpv6Id", 2L);
        ReflectionTestUtils.invokeMethod(cmd, "validatePublicVipXor");
    }
}
