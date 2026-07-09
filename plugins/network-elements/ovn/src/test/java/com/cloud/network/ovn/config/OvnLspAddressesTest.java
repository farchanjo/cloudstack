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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link OvnLspAddresses} — the per-network extra-CIDR parser
 * and the LSP {@code addresses}/{@code port_security} token composer backing
 * the PARSEL P4/P5 native-pod-traffic feature.
 */
public class OvnLspAddressesTest {

    private static final String SALAZAR = "a4226ad6-1111-2222-3333-444455556666";
    private static final String SNAPE = "b5337be7-aaaa-bbbb-cccc-ddddeeeeffff";
    private static final String MAC = "02:04:02:9b:00:06";
    private static final String V4 = "10.45.0.14";
    private static final String V6 = "2a13:8740:0:a:4:2ff:fe9b:6";

    // ---------- parse ----------

    @Test
    public void parseSingleNetworkValidCidrs() {
        final Map<String, List<String>> map =
                OvnLspAddresses.parse(SALAZAR + "=10.100.0.0/16,10.140.0.0/24,fd00:cafe:1::/64");
        assertEquals(1, map.size());
        assertEquals(Arrays.asList("10.100.0.0/16", "10.140.0.0/24", "fd00:cafe:1::/64"),
                map.get(SALAZAR));
    }

    @Test
    public void parseMultipleNetworksSemicolonSeparated() {
        final Map<String, List<String>> map = OvnLspAddresses.parse(
                SALAZAR + "=10.100.0.0/16,fd00:cafe:1::/64;" + SNAPE + "=10.101.0.0/16,10.141.0.0/24");
        assertEquals(2, map.size());
        assertEquals(Arrays.asList("10.100.0.0/16", "fd00:cafe:1::/64"), map.get(SALAZAR));
        assertEquals(Arrays.asList("10.101.0.0/16", "10.141.0.0/24"), map.get(SNAPE));
    }

    @Test
    public void parseSkipsMalformedCidrsKeepsValidOnes() {
        final Map<String, List<String>> map = OvnLspAddresses.parse(
                SALAZAR + "=10.100.0.0/16,not-a-cidr,999.0.0.0/8,fd00:cafe:1::/64");
        assertEquals(Arrays.asList("10.100.0.0/16", "fd00:cafe:1::/64"), map.get(SALAZAR));
    }

    @Test
    public void parseDropsEntryWithNoValidCidr() {
        final Map<String, List<String>> map = OvnLspAddresses.parse(SALAZAR + "=garbage,also-bad");
        assertTrue(map.isEmpty());
    }

    @Test
    public void parseDropsEntryWithoutSeparator() {
        assertTrue(OvnLspAddresses.parse(SALAZAR + "10.100.0.0/16").isEmpty());
        assertTrue(OvnLspAddresses.parse("=10.100.0.0/16").isEmpty());
        assertTrue(OvnLspAddresses.parse(SALAZAR + "=").isEmpty());
    }

    @Test
    public void parseEmptyAndNullYieldEmptyMap() {
        assertTrue(OvnLspAddresses.parse(null).isEmpty());
        assertTrue(OvnLspAddresses.parse("").isEmpty());
        assertTrue(OvnLspAddresses.parse("   ").isEmpty());
        assertTrue(OvnLspAddresses.parse(";;").isEmpty());
    }

    @Test
    public void parseToleratesWhitespaceAroundTokens() {
        final Map<String, List<String>> map =
                OvnLspAddresses.parse("  " + SALAZAR + " = 10.100.0.0/16 , fd00:cafe:1::/64 ");
        assertEquals(Arrays.asList("10.100.0.0/16", "fd00:cafe:1::/64"), map.get(SALAZAR));
    }

    // ---------- compose ----------

    @Test
    public void composeWithoutExtrasIsLegacyToken() {
        assertEquals(Arrays.asList(MAC + " " + V4 + " " + V6),
                OvnLspAddresses.compose(MAC, V4, V6, null));
        assertEquals(Arrays.asList(MAC + " " + V4 + " " + V6),
                OvnLspAddresses.compose(MAC, V4, V6, java.util.Collections.emptyList()));
    }

    @Test
    public void composeMacOnlyNoIpsNoExtras() {
        assertEquals(Arrays.asList(MAC), OvnLspAddresses.compose(MAC, null, "", null));
    }

    @Test
    public void composeWithExtrasAppendsToSingleToken() {
        final List<String> extras = Arrays.asList("10.100.0.0/16", "10.140.0.0/24", "fd00:cafe:1::/64");
        final List<String> out = OvnLspAddresses.compose(MAC, V4, V6, extras);
        assertEquals(1, out.size());
        assertEquals(MAC + " " + V4 + " " + V6 + " 10.100.0.0/16 10.140.0.0/24 fd00:cafe:1::/64",
                out.get(0));
    }

    @Test
    public void composeV4OnlyWithExtras() {
        final List<String> out = OvnLspAddresses.compose(MAC, V4, null, Arrays.asList("10.100.0.0/16"));
        assertEquals(Arrays.asList(MAC + " " + V4 + " 10.100.0.0/16"), out);
    }

    @Test
    public void composeIsDeterministicForIdempotentReapply() {
        final List<String> extras = Arrays.asList("10.100.0.0/16", "fd00:cafe:1::/64");
        final List<String> first = OvnLspAddresses.compose(MAC, V4, V6, extras);
        final List<String> second = OvnLspAddresses.compose(MAC, V4, V6, extras);
        assertEquals(first, second);
    }

    @Test
    public void composeSkipsBlankExtraEntries() {
        final List<String> out = OvnLspAddresses.compose(MAC, V4, V6,
                Arrays.asList("", "  ", "10.100.0.0/16"));
        assertEquals(Arrays.asList(MAC + " " + V4 + " " + V6 + " 10.100.0.0/16"), out);
        assertFalse(out.get(0).contains("  "));
    }
}
