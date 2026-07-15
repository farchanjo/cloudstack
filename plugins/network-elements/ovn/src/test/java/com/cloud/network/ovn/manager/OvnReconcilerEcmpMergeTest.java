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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.network.ovn.config.OvnEcmpRoutes.Route;

/**
 * Unit tests for ECMP route merge helpers used by CKS auto + static overlay.
 */
public class OvnReconcilerEcmpMergeTest {

    private static final String NET = "a4226ad6-604a-4cd6-883e-777958562fe1";

    @Test
    public void mergeSamePrefixUnionsHopsOrderStable() {
        final List<Route> list = new ArrayList<>();
        OvnReconcilerService.mergeOneRoute(list,
                new Route("10.140.0.0/24", Arrays.asList("10.45.0.1", "10.45.0.2")));
        OvnReconcilerService.mergeOneRoute(list,
                new Route("10.140.0.0/24", Arrays.asList("10.45.0.2", "10.45.0.3")));
        assertEquals(1, list.size());
        assertEquals(Arrays.asList("10.45.0.1", "10.45.0.2", "10.45.0.3"), list.get(0).getNextHops());
    }

    @Test
    public void mergeDifferentPrefixesAppends() {
        final List<Route> list = new ArrayList<>();
        OvnReconcilerService.mergeOneRoute(list, new Route("10.140.0.0/24", List.of("10.45.0.1")));
        OvnReconcilerService.mergeOneRoute(list, new Route("2a13:8740:0:10::/64", List.of("2a13:8740:0:a::1")));
        assertEquals(2, list.size());
        assertEquals("10.140.0.0/24", list.get(0).getPrefix());
        assertEquals("2a13:8740:0:10::/64", list.get(1).getPrefix());
    }

    @Test
    public void mergeEcmpRoutesAutoThenStatic() {
        final Map<String, List<Route>> dest = new LinkedHashMap<>();
        final Map<String, List<Route>> auto = new LinkedHashMap<>();
        auto.put(NET, List.of(new Route("10.140.0.0/24", List.of("10.45.0.10", "10.45.0.11"))));
        final Map<String, List<Route>> manual = new LinkedHashMap<>();
        manual.put(NET, List.of(new Route("10.140.0.0/24", List.of("10.45.0.11", "10.45.0.99"))));
        OvnReconcilerService.mergeEcmpRoutes(dest, auto);
        OvnReconcilerService.mergeEcmpRoutes(dest, manual);
        assertEquals(Arrays.asList("10.45.0.10", "10.45.0.11", "10.45.0.99"),
                dest.get(NET).get(0).getNextHops());
    }

    @Test
    public void mergeEmptySrcIsNoOp() {
        final Map<String, List<Route>> dest = new LinkedHashMap<>();
        dest.put(NET, new ArrayList<>(List.of(new Route("10.140.0.0/24", List.of("10.45.0.1")))));
        OvnReconcilerService.mergeEcmpRoutes(dest, null);
        OvnReconcilerService.mergeEcmpRoutes(dest, Map.of());
        assertEquals(1, dest.get(NET).size());
        assertEquals(List.of("10.45.0.1"), dest.get(NET).get(0).getNextHops());
    }

    @Test
    public void emptyHopRouteAllowed() {
        final List<Route> list = new ArrayList<>();
        OvnReconcilerService.mergeOneRoute(list, new Route("10.140.0.0/24", List.of()));
        assertTrue(list.get(0).getNextHops().isEmpty());
    }
}
