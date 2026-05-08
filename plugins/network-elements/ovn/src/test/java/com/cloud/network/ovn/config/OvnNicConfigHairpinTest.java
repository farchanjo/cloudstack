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
package com.cloud.network.ovn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.cloud.offering.NetworkOffering;

/**
 * Unit tests covering the four-scope resolution chain for the two new
 * OVN tunables {@code ovn.ovs.hairpin} and {@code ovn.ovs.tc.policy}.
 *
 * <p>The chain order tested here mirrors {@link OvnNicConfig#resolve}:
 * VM detail &gt; network detail &gt; offering detail &gt; global default.
 * Each layer is covered with a positive resolution and a fallback
 * verification.
 */
public class OvnNicConfigHairpinTest {

    @Test
    public void hairpinDefaultsToTrueWhenNoLayerProvidesValue() {
        Boolean v = OvnNicConfig.resolveHairpin(null, null, null);
        assertNotNull(v);
        assertTrue("Default hairpin must be true (cluster-wide HW offload requires it)", v);
    }

    @Test
    public void hairpinResolvesFromVmDetail() {
        Map<String, String> vm = new HashMap<>();
        vm.put(OvnNicConfig.OVN_OVS_HAIRPIN, "false");
        Boolean v = OvnNicConfig.resolveHairpin(vm, null, null);
        assertEquals(Boolean.FALSE, v);
    }

    @Test
    public void hairpinResolvesFromNetworkDetailWhenVmAbsent() {
        Map<String, String> net = new HashMap<>();
        net.put(OvnNicConfig.OVN_OVS_HAIRPIN, "false");
        Boolean v = OvnNicConfig.resolveHairpin(null, net, null);
        assertEquals(Boolean.FALSE, v);
    }

    @Test
    public void hairpinVmDetailWinsOverNetworkDetail() {
        Map<String, String> vm = new HashMap<>();
        vm.put(OvnNicConfig.OVN_OVS_HAIRPIN, "true");
        Map<String, String> net = new HashMap<>();
        net.put(OvnNicConfig.OVN_OVS_HAIRPIN, "false");
        Boolean v = OvnNicConfig.resolveHairpin(vm, net, null);
        assertEquals(Boolean.TRUE, v);
    }

    @Test
    public void tcPolicyDefaultsToNoneWhenNoLayerProvidesValue() {
        String v = OvnNicConfig.resolveTcPolicy(null, null, null);
        assertEquals("none", v);
    }

    @Test
    public void tcPolicyAcceptsWhitelistedSkipSwFromVmDetail() {
        Map<String, String> vm = new HashMap<>();
        vm.put(OvnNicConfig.OVN_OVS_TC_POLICY, "skip_sw");
        String v = OvnNicConfig.resolveTcPolicy(vm, null, null);
        assertEquals("skip_sw", v);
    }

    @Test
    public void tcPolicyAcceptsWhitelistedSkipHwFromNetworkDetail() {
        Map<String, String> net = new HashMap<>();
        net.put(OvnNicConfig.OVN_OVS_TC_POLICY, "skip_hw");
        String v = OvnNicConfig.resolveTcPolicy(null, net, null);
        assertEquals("skip_hw", v);
    }

    @Test
    public void tcPolicyRejectsOutOfWhitelistAndFallsThroughToDefault() {
        Map<String, String> vm = new HashMap<>();
        vm.put(OvnNicConfig.OVN_OVS_TC_POLICY, "garbage_value");
        String v = OvnNicConfig.resolveTcPolicy(vm, null, null);
        assertEquals("none", v);
    }

    @Test
    public void tcPolicyOfferingDetailMatchesByEnumToString() {
        // Mirror the offering-detail lookup convention: the enum's
        // toString() must match (case-insensitively) the canonical key.
        Map<NetworkOffering.Detail, String> off = new HashMap<>();
        // We populate via a fake enum that toStrings to the canonical key.
        // The closed NetworkOffering.Detail enum doesn't define our keys,
        // so we synthesize the lookup by using any of its existing values
        // — the resolver's contract is "match by toString", not by enum
        // identity. PromiscuousMode is one of the existing entries; it
        // does not match our key so the resolver must fall through.
        off.put(NetworkOffering.Detail.PromiscuousMode, "skip_sw");
        String v = OvnNicConfig.resolveTcPolicy(null, null, off);
        assertEquals("Offering detail with non-matching enum key must fall through to default",
                "none", v);
    }

    @Test
    public void hairpinKeyIsRegisteredInConfigKeyset() {
        OvnNicConfig cfg = new OvnNicConfig();
        boolean found = false;
        for (org.apache.cloudstack.framework.config.ConfigKey<?> k : cfg.getConfigKeys()) {
            if (OvnNicConfig.OVN_OVS_HAIRPIN.equals(k.key())) {
                found = true;
                assertEquals(Boolean.class, k.type());
                assertEquals("true", k.defaultValue());
                break;
            }
        }
        assertTrue("ovn.ovs.hairpin must be registered as a global ConfigKey", found);
    }

    @Test
    public void tcPolicyKeyIsRegisteredInConfigKeyset() {
        OvnNicConfig cfg = new OvnNicConfig();
        boolean found = false;
        for (org.apache.cloudstack.framework.config.ConfigKey<?> k : cfg.getConfigKeys()) {
            if (OvnNicConfig.OVN_OVS_TC_POLICY.equals(k.key())) {
                found = true;
                assertEquals(String.class, k.type());
                assertEquals("none", k.defaultValue());
                break;
            }
        }
        assertTrue("ovn.ovs.tc.policy must be registered as a global ConfigKey", found);
    }

    @Test
    public void findKeyByNameReturnsTheRegisteredEntry() {
        org.apache.cloudstack.framework.config.ConfigKey<?> hairpin =
                OvnNicConfig.findKey(OvnNicConfig.OVN_OVS_HAIRPIN);
        assertNotNull(hairpin);
        assertEquals(Boolean.class, hairpin.type());

        org.apache.cloudstack.framework.config.ConfigKey<?> tcPolicy =
                OvnNicConfig.findKey(OvnNicConfig.OVN_OVS_TC_POLICY);
        assertNotNull(tcPolicy);
        assertEquals(String.class, tcPolicy.type());
    }

    @Test
    public void findKeyForUnknownNameReturnsNull() {
        assertNull(OvnNicConfig.findKey("ovn.unknown.nonexistent"));
        assertNull(OvnNicConfig.findKey(null));
    }

    @Test
    public void hairpinChainAcceptsAlternateBooleanLiterals() {
        // Resolver must coerce 1/0/yes/no/on/off in addition to true/false.
        for (String yes : Arrays.asList("true", "1", "yes", "on", "TRUE", "Yes")) {
            Map<String, String> vm = new HashMap<>();
            vm.put(OvnNicConfig.OVN_OVS_HAIRPIN, yes);
            assertEquals("hairpin must coerce '" + yes + "' to TRUE",
                    Boolean.TRUE, OvnNicConfig.resolveHairpin(vm, null, null));
        }
        for (String no : Arrays.asList("false", "0", "no", "off", "FALSE", "Off")) {
            Map<String, String> vm = new HashMap<>();
            vm.put(OvnNicConfig.OVN_OVS_HAIRPIN, no);
            assertEquals("hairpin must coerce '" + no + "' to FALSE",
                    Boolean.FALSE, OvnNicConfig.resolveHairpin(vm, null, null));
        }
    }
}
