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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.network.ovn.config.OvnPublicIpv6Lb.Entry;
import com.cloud.network.ovn.config.OvnPublicIpv6Lb.HostPort;

/**
 * Unit tests for {@link OvnPublicIpv6Lb} — the pure parser backing
 * {@code ovn.lr.public.ipv6.lb}.
 */
public class OvnPublicIpv6LbTest {

    private static final String SALAZAR = "a4226ad6-604a-4cd6-883e-777958562fe1";
    private static final String SNAPE = "d46c5f93-4f6f-47fc-89ad-b4b10fb30f90";
    private static final String VIP_S = "2a13:8740:0:7::100";
    private static final String VIP_N = "2a13:8740:0:7::101";
    private static final String BE1 = "2a13:8740:0:a::14";
    private static final String BE2 = "2a13:8740:0:a::15";
    private static final String BE3 = "2a13:8740:0:9::20";

    @Test
    public void parseSingleNetworkTwoBackends() {
        final List<Entry> list = OvnPublicIpv6Lb.parse(
                SALAZAR + "=[" + VIP_S + "]:80->[" + BE1 + "]:80|[" + BE2 + "]:80");
        assertEquals(1, list.size());
        final Entry e = list.get(0);
        assertEquals(SALAZAR, e.getNetworkUuid());
        assertEquals(VIP_S, e.getVip());
        assertEquals(80, e.getVipPort());
        assertEquals(Arrays.asList(new HostPort(BE1, 80), new HostPort(BE2, 80)), e.getBackends());
        assertEquals(SALAZAR + '|' + VIP_S + "|80", e.entryKey());
    }

    @Test
    public void parseMultipleNetworksSemicolonSeparated() {
        final List<Entry> list = OvnPublicIpv6Lb.parse(
                SALAZAR + "=[" + VIP_S + "]:80->[" + BE1 + "]:80;"
                        + SNAPE + "=[" + VIP_N + "]:443->[" + BE3 + "]:443");
        assertEquals(2, list.size());
        assertEquals(SALAZAR, list.get(0).getNetworkUuid());
        assertEquals(443, list.get(1).getVipPort());
        assertEquals(VIP_N, list.get(1).getVip());
    }

    @Test
    public void parseToleratesWhitespaceAroundTokens() {
        final List<Entry> list = OvnPublicIpv6Lb.parse(
                "  " + SALAZAR + " = [" + VIP_S + "]:80 -> [" + BE1 + "]:80 | [" + BE2 + "]:80 ");
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).getBackends().size());
    }

    @Test
    public void parseDeduplicatesRepeatedBackends() {
        final List<Entry> list = OvnPublicIpv6Lb.parse(
                SALAZAR + "=[" + VIP_S + "]:80->[" + BE1 + "]:80|[" + BE1 + "]:80|[" + BE2 + "]:80");
        assertEquals(Arrays.asList(new HostPort(BE1, 80), new HostPort(BE2, 80)), list.get(0).getBackends());
    }

    @Test
    public void parseRejectsIpv4Vip() {
        assertTrue(OvnPublicIpv6Lb.parse(SALAZAR + "=203.0.113.10:80->[2a13:8740:0:a::1]:80").isEmpty());
    }

    @Test
    public void parseRejectsIpv4BackendKeepsValid() {
        final List<Entry> list = OvnPublicIpv6Lb.parse(
                SALAZAR + "=[" + VIP_S + "]:80->10.45.0.14:80|[" + BE1 + "]:80");
        assertEquals(1, list.size());
        assertEquals(Collections.singletonList(new HostPort(BE1, 80)), list.get(0).getBackends());
    }

    @Test
    public void parseRejectsUnbracketedIpv6() {
        assertTrue(OvnPublicIpv6Lb.parse(
                SALAZAR + "=" + VIP_S + ":80->[" + BE1 + "]:80").isEmpty());
    }

    @Test
    public void parseDropsEntryWithNoValidBackend() {
        assertTrue(OvnPublicIpv6Lb.parse(
                SALAZAR + "=[" + VIP_S + "]:80->10.0.0.1:80|not-an-ip").isEmpty());
    }

    @Test
    public void parseEmptyAndNullYieldEmptyList() {
        assertTrue(OvnPublicIpv6Lb.parse(null).isEmpty());
        assertTrue(OvnPublicIpv6Lb.parse("").isEmpty());
        assertTrue(OvnPublicIpv6Lb.parse("   ").isEmpty());
        assertTrue(OvnPublicIpv6Lb.parse(";;").isEmpty());
    }

    @Test
    public void parseKeepsGoodEntryWhenAnotherIsMalformed() {
        final List<Entry> list = OvnPublicIpv6Lb.parse(
                SALAZAR + "=bad->[" + BE1 + "]:80;"
                        + SNAPE + "=[" + VIP_N + "]:80->[" + BE3 + "]:80");
        assertEquals(1, list.size());
        assertEquals(SNAPE, list.get(0).getNetworkUuid());
    }

    @Test
    public void toVipsMapUsesBracketedKeys() {
        final Entry e = new Entry(SALAZAR, VIP_S, 80,
                Arrays.asList(new HostPort(BE1, 80), new HostPort(BE2, 6443)));
        final Map<String, String> vips = e.toVipsMap();
        assertEquals(1, vips.size());
        assertEquals("[" + BE1 + "]:80,[" + BE2 + "]:6443", vips.get("[" + VIP_S + "]:80"));
    }

    @Test
    public void formatVipKeyBracketsIpv6Only() {
        assertEquals("[2a13:8740:0:7::100]:80", OvnPublicIpv6Lb.formatVipKey("2a13:8740:0:7::100", 80));
        assertEquals("10.45.0.14:80", OvnPublicIpv6Lb.formatVipKey("10.45.0.14", 80));
    }

    @Test
    public void entryEqualityIsValueBased() {
        final Entry a = new Entry(SALAZAR, VIP_S, 80, Arrays.asList(new HostPort(BE1, 80)));
        final Entry b = new Entry(SALAZAR, VIP_S, 80, Arrays.asList(new HostPort(BE1, 80)));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
