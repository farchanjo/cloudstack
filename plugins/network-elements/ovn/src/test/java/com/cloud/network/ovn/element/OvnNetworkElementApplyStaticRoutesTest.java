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
package com.cloud.network.ovn.element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnNbClient.EcmpStaticRoute;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnNetworkElement.DesiredStaticRoute;
import com.cloud.network.ovn.element.OvnNetworkElement.StaticRoutePlan;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;

/**
 * Guard tests for multi-NH ECMP via {@code createStaticRoute}: pure
 * {@link OvnNetworkElement#planStaticRoutes} planner + the apply path that
 * stamps {@link OvnConstants#EXT_ID_STATIC_ROUTE} and never touches
 * {@link OvnConstants#EXT_ID_ECMP_ROUTE}.
 */
public class OvnNetworkElementApplyStaticRoutesTest {

    private static final long ZONE_ID = 7L;
    private static final long VPC_ID = 42L;
    private static final long CONTROLLER_ID = 11L;
    private static final String LR_UUID = "lr-vpc-uuid";
    private static final String PREFIX = "10.140.0.0/24";
    private static final String NH1 = "10.45.0.14";
    private static final String NH2 = "10.45.0.159";
    private static final String OWNER1 = "sr-uuid-1";
    private static final String OWNER2 = "sr-uuid-2";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnNetworkElement element;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);

        element = new OvnNetworkElement();
        inject(element, "pluginManager", pluginManager);
        inject(element, "logicalIdMapDao", logicalIdMapDao);
    }

    private static StaticRouteProfile profile(final String uuid, final String cidr, final String gateway,
                                              final StaticRoute.State state) {
        final StaticRouteProfile p = mock(StaticRouteProfile.class);
        when(p.getUuid()).thenReturn(uuid);
        when(p.getCidr()).thenReturn(cidr);
        when(p.getGateway()).thenReturn(gateway);
        when(p.getNextHop()).thenReturn(null);
        when(p.getState()).thenReturn(state);
        return p;
    }

    private static EcmpStaticRoute owned(final String uuid, final String owner, final String prefix,
                                         final String nexthop) {
        return new EcmpStaticRoute(uuid, prefix, nexthop, owner);
    }

    private static List<String> addOwners(final StaticRoutePlan plan) {
        return plan.getToAdd().stream().map(DesiredStaticRoute::getOwner).collect(Collectors.toList());
    }

    // ---------- planStaticRoutes ----------

    @Test
    public void plan_multiNhSameCidr_addsBoth() {
        final List<StaticRouteProfile> routes = Arrays.asList(
                profile(OWNER1, PREFIX, NH1, StaticRoute.State.Add),
                profile(OWNER2, PREFIX, NH2, StaticRoute.State.Add));

        final StaticRoutePlan plan = OvnNetworkElement.planStaticRoutes(routes, Collections.emptyList());

        assertEquals(2, plan.getToAdd().size());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(2, plan.getDesiredCount());
        assertTrue(addOwners(plan).containsAll(Arrays.asList(OWNER1, OWNER2)));
        for (final DesiredStaticRoute d : plan.getToAdd()) {
            assertEquals(PREFIX, d.getPrefix());
        }
    }

    @Test
    public void plan_idempotentWhenExistingMatches() {
        final List<StaticRouteProfile> routes = Arrays.asList(
                profile(OWNER1, PREFIX, NH1, StaticRoute.State.Active),
                profile(OWNER2, PREFIX, NH2, StaticRoute.State.Active));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("nb-1", OWNER1, PREFIX, NH1),
                owned("nb-2", OWNER2, PREFIX, NH2));

        final StaticRoutePlan plan = OvnNetworkElement.planStaticRoutes(routes, existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertTrue(plan.getToRemove().isEmpty());
        assertEquals(0, plan.size());
    }

    @Test
    public void plan_revokedRoute_removed() {
        final List<StaticRouteProfile> routes = Arrays.asList(
                profile(OWNER1, PREFIX, NH1, StaticRoute.State.Active),
                profile(OWNER2, PREFIX, NH2, StaticRoute.State.Revoke));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("nb-1", OWNER1, PREFIX, NH1),
                owned("nb-2", OWNER2, PREFIX, NH2));

        final StaticRoutePlan plan = OvnNetworkElement.planStaticRoutes(routes, existing);

        assertTrue(plan.getToAdd().isEmpty());
        assertEquals(Collections.singletonList("nb-2"), plan.getToRemove());
    }

    @Test
    public void plan_ignoresForeignOwnersAndEcmpNamespace() {
        // existing includes a cs-ecmp-route-style owner (network uuid) and another
        // VPC's static-route uuid — neither is in the managed set.
        final List<StaticRouteProfile> routes = Collections.singletonList(
                profile(OWNER1, PREFIX, NH1, StaticRoute.State.Add));
        final List<EcmpStaticRoute> existing = Arrays.asList(
                owned("ecmp-row", "network-uuid-not-static-route", PREFIX, NH1),
                owned("other-vpc", "sr-uuid-other-vpc", PREFIX, NH2));

        final StaticRoutePlan plan = OvnNetworkElement.planStaticRoutes(routes, existing);

        assertEquals(1, plan.getToAdd().size());
        assertEquals(OWNER1, plan.getToAdd().get(0).getOwner());
        assertTrue(plan.getToRemove().isEmpty());
    }

    @Test
    public void plan_driftReplace_removesAndAdds() {
        final List<StaticRouteProfile> routes = Collections.singletonList(
                profile(OWNER1, PREFIX, NH2, StaticRoute.State.Active));
        final List<EcmpStaticRoute> existing = Collections.singletonList(
                owned("nb-old", OWNER1, PREFIX, NH1));

        final StaticRoutePlan plan = OvnNetworkElement.planStaticRoutes(routes, existing);

        assertEquals(Collections.singletonList("nb-old"), plan.getToRemove());
        assertEquals(1, plan.getToAdd().size());
        assertEquals(NH2, plan.getToAdd().get(0).getNexthop());
    }

    @Test
    public void nextHopOf_prefersGatewayOverNextHop() {
        final StaticRouteProfile p = mock(StaticRouteProfile.class);
        when(p.getGateway()).thenReturn(NH1);
        when(p.getNextHop()).thenReturn(NH2);
        assertEquals(NH1, OvnNetworkElement.nextHopOf(p));
    }

    // ---------- applyStaticRoutes end-to-end with mocks ----------

    @Test
    public void applyStaticRoutes_multiNh_addsBothWithOwnershipMarker() {
        final OvnLogicalIdMapVO lrMapping = mock(OvnLogicalIdMapVO.class);
        when(lrMapping.getOvnUuid()).thenReturn(LR_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(lrMapping);
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_STATIC_ROUTE)).thenReturn(Collections.emptyList());
        when(nbClient.addLogicalRouterStaticRoute(anyString(), anyString(), anyString(), isNull(), isNull(), anyMap()))
                .thenReturn("new-nb-uuid");

        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(VPC_ID);
        when(vpc.getZoneId()).thenReturn(ZONE_ID);

        final List<StaticRouteProfile> routes = Arrays.asList(
                profile(OWNER1, PREFIX, NH1, StaticRoute.State.Add),
                profile(OWNER2, PREFIX, NH2, StaticRoute.State.Add));

        assertTrue(element.applyStaticRoutes(vpc, routes));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, String>> extCaptor = ArgumentCaptor.forClass(Map.class);
        verify(nbClient, times(2)).addLogicalRouterStaticRoute(
                eq(LR_UUID), eq(PREFIX), anyString(), isNull(), isNull(), extCaptor.capture());
        final List<Map<String, String>> allExt = extCaptor.getAllValues();
        assertEquals(2, allExt.size());
        for (final Map<String, String> ext : allExt) {
            assertTrue(ext.containsKey(OvnConstants.EXT_ID_STATIC_ROUTE));
            assertTrue(Arrays.asList(OWNER1, OWNER2).contains(ext.get(OvnConstants.EXT_ID_STATIC_ROUTE)));
            // Must never stamp the ConfigKey ECMP marker.
            assertTrue(!ext.containsKey(OvnConstants.EXT_ID_ECMP_ROUTE));
        }
        verify(nbClient, never()).deleteLogicalRouterStaticRouteDirect(anyString());
        // Must query only the cs-static-route namespace.
        verify(nbClient, times(1)).listEcmpStaticRoutes(OvnConstants.EXT_ID_STATIC_ROUTE);
        verify(nbClient, never()).listEcmpStaticRoutes(eq(OvnConstants.EXT_ID_ECMP_ROUTE));
    }

    @Test
    public void applyStaticRoutes_revoked_deletesOwnedRow() {
        final OvnLogicalIdMapVO lrMapping = mock(OvnLogicalIdMapVO.class);
        when(lrMapping.getOvnUuid()).thenReturn(LR_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(lrMapping);
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_STATIC_ROUTE)).thenReturn(
                Collections.singletonList(owned("nb-revoked", OWNER1, PREFIX, NH1)));

        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(VPC_ID);
        when(vpc.getZoneId()).thenReturn(ZONE_ID);

        final List<StaticRouteProfile> routes = Collections.singletonList(
                profile(OWNER1, PREFIX, NH1, StaticRoute.State.Revoke));

        assertTrue(element.applyStaticRoutes(vpc, routes));

        verify(nbClient, times(1)).deleteLogicalRouterStaticRouteDirect("nb-revoked");
        verify(nbClient, never()).addLogicalRouterStaticRoute(
                anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    public void applyStaticRoutes_noController_softSkip() {
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(null);
        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(VPC_ID);
        when(vpc.getZoneId()).thenReturn(ZONE_ID);

        assertTrue(element.applyStaticRoutes(vpc,
                Collections.singletonList(profile(OWNER1, PREFIX, NH1, StaticRoute.State.Add))));
        verify(nbClient, never()).listEcmpStaticRoutes(anyString());
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
