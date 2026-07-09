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
import java.util.Map;

import org.junit.Test;

import com.cloud.network.ovn.config.OvnEcmpRoutes.Route;

/**
 * Unit tests for {@link OvnEcmpRoutes} — the per-network ECMP static-route
 * parser backing {@code ovn.lr.ecmp.static.routes} (PARSEL P5 LB-VIP routing).
 */
public class OvnEcmpRoutesTest {

    private static final String SALAZAR = "a4226ad6-604a-4cd6-883e-777958562fe1";
    private static final String SNAPE = "d46c5f93-4f6f-47fc-89ad-b4b10fb30f90";

    // ---------- parse: valid ----------

    @Test
    public void parseSingleNetworkThreeNextHops() {
        final Map<String, Route> map =
                OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.159|10.45.0.253");
        assertEquals(1, map.size());
        final Route r = map.get(SALAZAR);
        assertEquals("10.140.0.0/24", r.getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159", "10.45.0.253"), r.getNextHops());
    }

    @Test
    public void parseMultipleNetworksSemicolonSeparated() {
        final Map<String, Route> map = OvnEcmpRoutes.parse(
                SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.159;"
                        + SNAPE + "=10.141.0.0/24->10.45.4.73|10.45.4.214|10.45.4.18");
        assertEquals(2, map.size());
        assertEquals("10.140.0.0/24", map.get(SALAZAR).getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), map.get(SALAZAR).getNextHops());
        assertEquals(Arrays.asList("10.45.4.73", "10.45.4.214", "10.45.4.18"), map.get(SNAPE).getNextHops());
    }

    @Test
    public void parseIpv6PrefixAndNextHop() {
        final Map<String, Route> map =
                OvnEcmpRoutes.parse(SALAZAR + "=fd00:cafe:2::/108->2a13:8740:0:a::5|2a13:8740:0:a::6");
        assertEquals("fd00:cafe:2::/108", map.get(SALAZAR).getPrefix());
        assertEquals(Arrays.asList("2a13:8740:0:a::5", "2a13:8740:0:a::6"), map.get(SALAZAR).getNextHops());
    }

    @Test
    public void parseToleratesWhitespaceAroundTokens() {
        final Map<String, Route> map =
                OvnEcmpRoutes.parse("  " + SALAZAR + " = 10.140.0.0/24 -> 10.45.0.14 | 10.45.0.159 ");
        assertEquals("10.140.0.0/24", map.get(SALAZAR).getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), map.get(SALAZAR).getNextHops());
    }

    @Test
    public void parseDeduplicatesRepeatedNextHops() {
        final Map<String, Route> map =
                OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.14|10.45.0.159");
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), map.get(SALAZAR).getNextHops());
    }

    // ---------- parse: malformed ----------

    @Test
    public void parseSkipsMalformedNextHopsKeepsValidOnes() {
        final Map<String, Route> map =
                OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->10.45.0.14|not-an-ip|999.0.0.1|10.45.0.253");
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.253"), map.get(SALAZAR).getNextHops());
    }

    @Test
    public void parseDropsEntryWithMalformedPrefix() {
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "=not-a-cidr->10.45.0.14").isEmpty());
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "=10.45.0.14->10.45.0.14").isEmpty());
    }

    @Test
    public void parseDropsEntryWithNoValidNextHop() {
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->bad|worse").isEmpty());
    }

    @Test
    public void parseDropsEntryWithoutSeparators() {
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "10.140.0.0/24->10.45.0.14").isEmpty());
        assertTrue(OvnEcmpRoutes.parse("=10.140.0.0/24->10.45.0.14").isEmpty());
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "=").isEmpty());
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24").isEmpty());
        assertTrue(OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->").isEmpty());
    }

    @Test
    public void parseEmptyAndNullYieldEmptyMap() {
        assertTrue(OvnEcmpRoutes.parse(null).isEmpty());
        assertTrue(OvnEcmpRoutes.parse("").isEmpty());
        assertTrue(OvnEcmpRoutes.parse("   ").isEmpty());
        assertTrue(OvnEcmpRoutes.parse(";;").isEmpty());
    }

    @Test
    public void parseKeepsGoodEntryWhenAnotherIsMalformed() {
        final Map<String, Route> map = OvnEcmpRoutes.parse(
                SALAZAR + "=bad-prefix->10.45.0.14;" + SNAPE + "=10.141.0.0/24->10.45.4.73");
        assertEquals(1, map.size());
        assertEquals("10.141.0.0/24", map.get(SNAPE).getPrefix());
    }

    // ---------- Route value semantics ----------

    @Test
    public void routeEqualityIsValueBased() {
        final Route a = new Route("10.140.0.0/24", Arrays.asList("10.45.0.14", "10.45.0.159"));
        final Route b = new Route("10.140.0.0/24", Arrays.asList("10.45.0.14", "10.45.0.159"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
