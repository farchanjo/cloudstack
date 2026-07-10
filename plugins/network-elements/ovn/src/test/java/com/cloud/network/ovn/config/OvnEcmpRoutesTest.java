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
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.network.ovn.config.OvnEcmpRoutes.Route;

/**
 * Unit tests for {@link OvnEcmpRoutes} — the per-network ECMP static-route
 * parser backing {@code ovn.lr.ecmp.static.routes} (PARSEL P5 LB-VIP routing).
 * Multi-stanza same UUID supports dual-stack (v4 + v6 VIP prefixes).
 */
public class OvnEcmpRoutesTest {

    private static final String SALAZAR = "a4226ad6-604a-4cd6-883e-777958562fe1";
    private static final String SNAPE = "d46c5f93-4f6f-47fc-89ad-b4b10fb30f90";

    private static Route first(final Map<String, List<Route>> map, final String uuid) {
        return map.get(uuid).get(0);
    }

    // ---------- parse: valid ----------

    @Test
    public void parseSingleNetworkThreeNextHops() {
        final Map<String, List<Route>> map =
                OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.159|10.45.0.253");
        assertEquals(1, map.size());
        assertEquals(1, map.get(SALAZAR).size());
        final Route r = first(map, SALAZAR);
        assertEquals("10.140.0.0/24", r.getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159", "10.45.0.253"), r.getNextHops());
    }

    @Test
    public void parseMultipleNetworksSemicolonSeparated() {
        final Map<String, List<Route>> map = OvnEcmpRoutes.parse(
                SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.159;"
                        + SNAPE + "=10.141.0.0/24->10.45.4.73|10.45.4.214|10.45.4.18");
        assertEquals(2, map.size());
        assertEquals(1, map.get(SALAZAR).size());
        assertEquals(1, map.get(SNAPE).size());
        assertEquals("10.140.0.0/24", first(map, SALAZAR).getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), first(map, SALAZAR).getNextHops());
        assertEquals(Arrays.asList("10.45.4.73", "10.45.4.214", "10.45.4.18"), first(map, SNAPE).getNextHops());
    }

    @Test
    public void parseIpv6PrefixAndNextHop() {
        final Map<String, List<Route>> map =
                OvnEcmpRoutes.parse(SALAZAR + "=fd00:cafe:2::/108->2a13:8740:0:a::5|2a13:8740:0:a::6");
        assertEquals("fd00:cafe:2::/108", first(map, SALAZAR).getPrefix());
        assertEquals(Arrays.asList("2a13:8740:0:a::5", "2a13:8740:0:a::6"), first(map, SALAZAR).getNextHops());
    }

    @Test
    public void parseDualStackSameUuidTwoPrefixes() {
        final Map<String, List<Route>> map = OvnEcmpRoutes.parse(
                SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.159;"
                        + SALAZAR + "=fd00:cafe:2::/108->2a13:8740:0:a::5|2a13:8740:0:a::6");
        assertEquals(1, map.size());
        final List<Route> routes = map.get(SALAZAR);
        assertEquals(2, routes.size());
        assertEquals("10.140.0.0/24", routes.get(0).getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), routes.get(0).getNextHops());
        assertEquals("fd00:cafe:2::/108", routes.get(1).getPrefix());
        assertEquals(Arrays.asList("2a13:8740:0:a::5", "2a13:8740:0:a::6"), routes.get(1).getNextHops());
    }

    @Test
    public void parseMergesSamePrefixNextHopsOrderStable() {
        final Map<String, List<Route>> map = OvnEcmpRoutes.parse(
                SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.159;"
                        + SALAZAR + "=10.140.0.0/24->10.45.0.159|10.45.0.253");
        assertEquals(1, map.get(SALAZAR).size());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159", "10.45.0.253"),
                first(map, SALAZAR).getNextHops());
    }

    @Test
    public void parseMultiNetworkEachDualStack() {
        final Map<String, List<Route>> map = OvnEcmpRoutes.parse(
                SALAZAR + "=10.140.0.0/24->10.45.0.14;"
                        + SNAPE + "=10.141.0.0/24->10.45.4.73;"
                        + SALAZAR + "=fd00:cafe:2::/108->2a13:8740:0:a::5;"
                        + SNAPE + "=fd00:cafe:2::/108->2a13:8740:0:9::5");
        assertEquals(2, map.size());
        assertEquals(2, map.get(SALAZAR).size());
        assertEquals(2, map.get(SNAPE).size());
        assertEquals("10.140.0.0/24", map.get(SALAZAR).get(0).getPrefix());
        assertEquals("fd00:cafe:2::/108", map.get(SALAZAR).get(1).getPrefix());
        assertEquals("10.141.0.0/24", map.get(SNAPE).get(0).getPrefix());
        assertEquals(Arrays.asList("2a13:8740:0:9::5"), map.get(SNAPE).get(1).getNextHops());
    }

    @Test
    public void parseToleratesWhitespaceAroundTokens() {
        final Map<String, List<Route>> map =
                OvnEcmpRoutes.parse("  " + SALAZAR + " = 10.140.0.0/24 -> 10.45.0.14 | 10.45.0.159 ");
        assertEquals("10.140.0.0/24", first(map, SALAZAR).getPrefix());
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), first(map, SALAZAR).getNextHops());
    }

    @Test
    public void parseDeduplicatesRepeatedNextHops() {
        final Map<String, List<Route>> map =
                OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->10.45.0.14|10.45.0.14|10.45.0.159");
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.159"), first(map, SALAZAR).getNextHops());
    }

    // ---------- parse: malformed ----------

    @Test
    public void parseSkipsMalformedNextHopsKeepsValidOnes() {
        final Map<String, List<Route>> map =
                OvnEcmpRoutes.parse(SALAZAR + "=10.140.0.0/24->10.45.0.14|not-an-ip|999.0.0.1|10.45.0.253");
        assertEquals(Arrays.asList("10.45.0.14", "10.45.0.253"), first(map, SALAZAR).getNextHops());
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
        final Map<String, List<Route>> map = OvnEcmpRoutes.parse(
                SALAZAR + "=bad-prefix->10.45.0.14;" + SNAPE + "=10.141.0.0/24->10.45.4.73");
        assertEquals(1, map.size());
        assertEquals("10.141.0.0/24", first(map, SNAPE).getPrefix());
    }

    @Test
    public void parseKeepsGoodPrefixWhenSiblingStanzaMalformed() {
        final Map<String, List<Route>> map = OvnEcmpRoutes.parse(
                SALAZAR + "=10.140.0.0/24->10.45.0.14;"
                        + SALAZAR + "=not-a-cidr->2a13:8740:0:a::5");
        assertEquals(1, map.size());
        assertEquals(1, map.get(SALAZAR).size());
        assertEquals("10.140.0.0/24", first(map, SALAZAR).getPrefix());
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
