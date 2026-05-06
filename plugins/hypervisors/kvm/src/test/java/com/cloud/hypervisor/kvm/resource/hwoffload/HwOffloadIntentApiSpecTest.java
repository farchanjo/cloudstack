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
package com.cloud.hypervisor.kvm.resource.hwoffload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.AclRule;
import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.IntentSpec;
import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.LbRule;
import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.NatRule;
import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.PfwRule;

import com.google.gson.Gson;

import java.util.Arrays;

import org.junit.Test;

/**
 * JSON wire-format round-trip tests for the {@link HwOffloadIntentApi.IntentSpec}
 * data classes. These DTOs are serialized by the systemvm-side
 * {@code CsHwOffloadIntent.py} and deserialized by Gson on the agent;
 * silent field drift between mgmt and agent breaks HW programming.
 */
public class HwOffloadIntentApiSpecTest {

    private final Gson gson = new Gson();

    @Test
    public void emptyIntentRoundtrips() {
        IntentSpec spec = new IntentSpec();
        spec.vrId = "r-9999-VM";
        spec.version = 1L;
        String json = gson.toJson(spec);
        IntentSpec back = gson.fromJson(json, IntentSpec.class);
        assertEquals(spec.vrId, back.vrId);
        assertEquals(spec.version, back.version);
        assertNull(back.natRules);
        assertNull(back.aclRules);
        assertNull(back.lbRules);
        assertNull(back.pfwRules);
    }

    @Test
    public void natRuleFieldsRoundtrip() {
        IntentSpec spec = new IntentSpec();
        spec.vrId = "r-1-VM";
        spec.version = 7L;
        spec.guestVfPci = "0000:01:00.3";
        spec.publicVfPci = "0000:01:00.5";
        spec.publicVlanId = 2988;
        spec.ctZone = 42;
        NatRule snat = new NatRule();
        snat.dir = "SNAT";
        snat.matchAddr = "10.10.0.0/24";
        snat.translateAddr = "64.34.88.231";
        snat.ipProto = "tcp";
        snat.prio = 100;
        spec.natRules = Arrays.asList(snat);

        String json = gson.toJson(spec);
        IntentSpec back = gson.fromJson(json, IntentSpec.class);
        assertNotNull(back.natRules);
        assertEquals(1, back.natRules.size());
        NatRule r = back.natRules.get(0);
        assertEquals("SNAT", r.dir);
        assertEquals("10.10.0.0/24", r.matchAddr);
        assertEquals("64.34.88.231", r.translateAddr);
        assertEquals("tcp", r.ipProto);
        assertEquals(Integer.valueOf(100), r.prio);
        assertEquals(Integer.valueOf(2988), back.publicVlanId);
        assertEquals(Integer.valueOf(42), back.ctZone);
    }

    @Test
    public void pfwRuleFieldsRoundtrip() {
        IntentSpec spec = new IntentSpec();
        PfwRule p = new PfwRule();
        p.publicIp = "64.34.88.231";
        p.publicPort = 8080;
        p.internalIp = "10.10.0.5";
        p.internalPort = 80;
        p.ipProto = "tcp";
        p.prio = 65;
        spec.pfwRules = Arrays.asList(p);
        String json = gson.toJson(spec);
        IntentSpec back = gson.fromJson(json, IntentSpec.class);
        PfwRule r = back.pfwRules.get(0);
        assertEquals("64.34.88.231", r.publicIp);
        assertEquals(Integer.valueOf(8080), r.publicPort);
        assertEquals("10.10.0.5", r.internalIp);
        assertEquals(Integer.valueOf(80), r.internalPort);
        assertEquals("tcp", r.ipProto);
        assertEquals(Integer.valueOf(65), r.prio);
    }

    @Test
    public void aclRuleStatefulFlagRoundtrips() {
        AclRule a = new AclRule();
        a.matchSrcIp = "10.10.0.0/24";
        a.matchDstIp = "0.0.0.0/0";
        a.matchPort = 443;
        a.ipProto = "tcp";
        a.action = "ACCEPT";
        a.stateful = Boolean.TRUE;
        a.prio = 200;
        IntentSpec spec = new IntentSpec();
        spec.aclRules = Arrays.asList(a);
        AclRule back = gson.fromJson(gson.toJson(spec), IntentSpec.class).aclRules.get(0);
        assertEquals("ACCEPT", back.action);
        assertTrue(back.stateful);
    }

    @Test
    public void lbRuleBackendsRoundtrip() {
        LbRule lb = new LbRule();
        lb.vip = "10.10.0.100";
        lb.port = 443;
        lb.backends = Arrays.asList("10.10.0.10", "10.10.0.11", "10.10.0.12");
        lb.method = "round_robin";
        lb.prio = 300;
        IntentSpec spec = new IntentSpec();
        spec.lbRules = Arrays.asList(lb);
        LbRule back = gson.fromJson(gson.toJson(spec), IntentSpec.class).lbRules.get(0);
        assertEquals(3, back.backends.size());
        assertEquals("round_robin", back.method);
    }

    @Test
    public void unknownFieldsAreToleratedOnDeserialize() {
        // Gson defaults to ignoring unknown fields. Mgmt MAY add new
        // optional fields without breaking older agents.
        String json = "{\"vrId\":\"r-7-VM\",\"version\":2,\"futureField\":\"x\"}";
        IntentSpec spec = gson.fromJson(json, IntentSpec.class);
        assertEquals("r-7-VM", spec.vrId);
        assertEquals(2L, spec.version);
    }

    @Test
    public void additionalGuestVfPcisListRoundtrips() {
        IntentSpec spec = new IntentSpec();
        spec.vrId = "r-multi-VM";
        spec.version = 1L;
        spec.guestVfPci = "0000:01:00.3";
        spec.additionalGuestVfPcis = Arrays.asList("0000:01:00.5", "0000:01:00.7");
        IntentSpec back = gson.fromJson(gson.toJson(spec), IntentSpec.class);
        assertEquals(2, back.additionalGuestVfPcis.size());
        assertEquals("0000:01:00.5", back.additionalGuestVfPcis.get(0));
        assertEquals("0000:01:00.7", back.additionalGuestVfPcis.get(1));
    }

    @Test
    public void absentBooleanDefaultsToNull() {
        AclRule a = new AclRule();
        a.matchSrcIp = "10.0.0.0/8";
        a.action = "DROP";
        IntentSpec spec = new IntentSpec();
        spec.aclRules = Arrays.asList(a);
        AclRule back = gson.fromJson(gson.toJson(spec), IntentSpec.class).aclRules.get(0);
        assertNull(back.stateful);
        assertFalse(Boolean.TRUE.equals(back.stateful));
    }
}
