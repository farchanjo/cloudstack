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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Unit tests for {@link OvnPublicNetworkManager#getVpcPublicAnchorCidr}: the
 * datapath anchor address is DERIVED (first usable address of the public
 * subnet outside CloudStack's allocation pool, not the gateway or the LRP),
 * never configured or hardcoded. Exercises the real {@code NetUtils} math with
 * a mocked LRP-CIDR (OVN NB) and public VLAN pool (CloudStack).
 */
public class OvnPublicNetworkManagerTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long VPC_ID = 9L;
    private static final String LRP_UUID = "lrp-uuid";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private VlanDao vlanDao;
    private OvnNbClient nbClient;
    private OvnPublicNetworkManager mgr;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        vlanDao = mock(VlanDao.class);
        nbClient = mock(OvnNbClient.class);

        final OvnControllerVO controller = mock(OvnControllerVO.class);
        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);

        final OvnLogicalIdMapVO lrp = mock(OvnLogicalIdMapVO.class);
        when(lrp.getOvnUuid()).thenReturn(LRP_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC_PUBLIC_LRP), eq(VPC_ID), eq(CONTROLLER_ID)))
                .thenReturn(lrp);
        when(nbClient.getLogicalRouterPortNetworks(LRP_UUID))
                .thenReturn(List.of("217.179.89.34/24"));

        mgr = new OvnPublicNetworkManager();
        injectField(mgr, "pluginManager", pluginManager);
        injectField(mgr, "logicalIdMapDao", logicalIdMapDao);
        injectField(mgr, "vlanDao", vlanDao);
    }

    @Test
    public void anchorCidrIsFirstAddressOutsideThePoolNotGatewayNotLrp() {
        // Public range: gateway .1, allocation pool .32-.254 -> first out-of-pool
        // usable address is .2 (derived from the CloudStack range; no hardcode).
        final VlanVO vlan = mock(VlanVO.class);
        when(vlan.getVlanGateway()).thenReturn("217.179.89.1");
        when(vlan.getIpRange()).thenReturn("217.179.89.32-217.179.89.254");
        when(vlanDao.listByZoneAndType(ZONE_ID, Vlan.VlanType.VirtualNetwork))
                .thenReturn(List.of(vlan));

        assertEquals("217.179.89.2/24", mgr.getVpcPublicAnchorCidr(ZONE_ID, VPC_ID));
    }

    @Test
    public void anchorCidrNullWhenNoGapBetweenGatewayAndPool() {
        // Operator widened the pool to start right after the gateway (.2): the
        // only non-pool hosts are the gateway (.1) and the broadcast — no
        // derivable anchor -> null (caller degrades to advertise-/route-only).
        final VlanVO vlan = mock(VlanVO.class);
        when(vlan.getVlanGateway()).thenReturn("217.179.89.1");
        when(vlan.getIpRange()).thenReturn("217.179.89.2-217.179.89.254");
        when(vlanDao.listByZoneAndType(ZONE_ID, Vlan.VlanType.VirtualNetwork))
                .thenReturn(List.of(vlan));

        assertNull(mgr.getVpcPublicAnchorCidr(ZONE_ID, VPC_ID));
    }

    @Test
    public void anchorCidrNullWhenNoMatchingPublicVlan() {
        when(vlanDao.listByZoneAndType(ZONE_ID, Vlan.VlanType.VirtualNetwork))
                .thenReturn(Collections.emptyList());
        assertNull(mgr.getVpcPublicAnchorCidr(ZONE_ID, VPC_ID));
    }

    /**
     * Regression for sticky HA failover: priorities must step by
     * {@link OvnPublicNetworkManager#HA_CHASSIS_PRIORITY_STEP} (not 1), so a
     * flap does not thrash gateway ownership between chassis of near-equal prio.
     */
    @Test
    public void buildHaChassisMembersUsesSteppedPriorities() {
        final OvnChassisMapVO a = new OvnChassisMapVO(1L, CONTROLLER_ID, "norbert");
        final OvnChassisMapVO b = new OvnChassisMapVO(2L, CONTROLLER_ID, "aragog");
        final OvnChassisMapVO c = new OvnChassisMapVO(3L, CONTROLLER_ID, "fluffy");

        final List<Map.Entry<String, Integer>> members =
                OvnPublicNetworkManager.buildHaChassisMembers(List.of(a, b, c));

        assertEquals(3, members.size());
        assertEquals("norbert", members.get(0).getKey());
        assertEquals(Integer.valueOf(OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_TOP), members.get(0).getValue());
        assertEquals(Integer.valueOf(OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_TOP
                - OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_STEP), members.get(1).getValue());
        assertEquals(Integer.valueOf(OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_TOP
                - 2 * OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_STEP), members.get(2).getValue());
        // Sticky gap: adjacent members differ by STEP, not 1.
        assertEquals(OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_STEP,
                members.get(0).getValue() - members.get(1).getValue());
        assertTrue(members.get(2).getValue() >= 1);
    }

    @Test
    public void buildHaChassisMembersEmptyInput() {
        assertTrue(OvnPublicNetworkManager.buildHaChassisMembers(List.of()).isEmpty());
    }

    @Test
    public void buildHaChassisMembersDoesNotGoBelowOne() {
        // Many members would underflow if not clamped with Math.max(1, …).
        final List<OvnChassisMapVO> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new OvnChassisMapVO(i, CONTROLLER_ID, "chassis-" + i));
        }
        final List<Map.Entry<String, Integer>> members = OvnPublicNetworkManager.buildHaChassisMembers(many);
        assertEquals(20, members.size());
        assertEquals(Integer.valueOf(OvnPublicNetworkManager.HA_CHASSIS_PRIORITY_TOP), members.get(0).getValue());
        for (final Map.Entry<String, Integer> e : members) {
            assertTrue("priority must stay >= 1, got " + e.getValue() + " for " + e.getKey(),
                    e.getValue() >= 1);
        }
        // After floor, trailing members share prio 1 (sticky less important than invalid 0).
        assertEquals(Integer.valueOf(1), members.get(members.size() - 1).getValue());
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
