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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine;

/**
 * Pure unit tests for vmidipmap IPv4/IPv6 validation on assignToLoadBalancerRule.
 */
public class AssignToLoadBalancerRuleCmdTest {

    private static final String VM_UUID = "11111111-2222-3333-4444-555555555555";
    private static final long VM_ID = 42L;

    private AssignToLoadBalancerRuleCmd cmd;
    private EntityManager entityMgr;
    private VirtualMachine vm;

    @Before
    public void setUp() {
        cmd = new AssignToLoadBalancerRuleCmd();
        entityMgr = mock(EntityManager.class);
        vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(entityMgr.findByUuid(eq(VirtualMachine.class), eq(VM_UUID))).thenReturn(vm);
        ReflectionTestUtils.setField(cmd, "_entityMgr", entityMgr);
    }

    private void setVmIdIpMap(String vmIp) {
        Map<String, HashMap<String, String>> outer = new HashMap<>();
        HashMap<String, String> entry = new HashMap<>();
        entry.put("vmid", VM_UUID);
        entry.put("vmip", vmIp);
        outer.put("0", entry);
        ReflectionTestUtils.setField(cmd, "vmIdIpMap", outer);
    }

    @Test
    public void getVmIdIpListMap_acceptsIpv4() {
        setVmIdIpMap("10.1.1.75");
        Pair<Map<Long, List<String>>, Map<Long, Long>> result = cmd.getVmIdIpListMapAndVmIdNetworkMap();
        assertEquals(1, result.first().size());
        assertEquals("10.1.1.75", result.first().get(VM_ID).get(0));
    }

    @Test
    public void getVmIdIpListMap_acceptsIpv6() {
        setVmIdIpMap("2a13:8740:0:a::10");
        Pair<Map<Long, List<String>>, Map<Long, Long>> result = cmd.getVmIdIpListMapAndVmIdNetworkMap();
        assertEquals(1, result.first().size());
        assertEquals("2a13:8740:0:a::10", result.first().get(VM_ID).get(0));
    }

    @Test
    public void getVmIdIpListMap_acceptsCompressedIpv6() {
        setVmIdIpMap("2001:db8::1");
        Pair<Map<Long, List<String>>, Map<Long, Long>> result = cmd.getVmIdIpListMapAndVmIdNetworkMap();
        assertTrue(result.first().get(VM_ID).contains("2001:db8::1"));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getVmIdIpListMap_rejectsGarbage() {
        setVmIdIpMap("not-an-ip");
        cmd.getVmIdIpListMapAndVmIdNetworkMap();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getVmIdIpListMap_rejectsNullVmIp() {
        setVmIdIpMap(null);
        cmd.getVmIdIpListMapAndVmIdNetworkMap();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getVmIdIpListMap_rejectsUnknownVm() {
        setVmIdIpMap("10.0.0.1");
        when(entityMgr.findByUuid(eq(VirtualMachine.class), eq(VM_UUID))).thenReturn(null);
        cmd.getVmIdIpListMapAndVmIdNetworkMap();
    }
}
