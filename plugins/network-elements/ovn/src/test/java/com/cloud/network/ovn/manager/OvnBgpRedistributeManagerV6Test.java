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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.OvnBgpAnnounceAnswer;
import com.cloud.agent.api.OvnBgpAnnounceCommand;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnPublicNetworkManager;

/**
 * PARSEL-V6 — asserts the IPv6 tier-subnet announce path
 * ({@link OvnBgpRedistributeManager#announceSubnet6}): the toggle gate (default
 * off => no-op, zero regression), and, when enabled, the v6 command shape (v6
 * net address + /64 prefix + v6 GUA next-hop + v6 anchor/gateway) plus the
 * distinct {@link Kind#BGP_SUBNET_ANNOUNCE_V6} bookkeeping row.
 *
 * <p>The gate is spied ({@link OvnBgpRedistributeManager#isRoutedAnnounceIpv6Enabled()})
 * so the enabled path is exercised without wiring the static ConfigDepot.
 */
public class OvnBgpRedistributeManagerV6Test {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long VPC_ID = 9L;
    private static final long NET_ID = 55L;
    private static final long HOST_ID = 21L;
    private static final String V6_TIER_CIDR = "2a13:8740:0:a::/64";
    private static final String V6_TIER_NET = "2a13:8740:0:a::";
    private static final String V6_LRP_GUA = "2a13:8740:0:7::34";
    private static final String V6_ANCHOR = "2a13:8740:0:7::2/64";
    private static final String V6_GW = "2a13:8740:0:7::1";
    private static final String CHASSIS_NAME = "aragog-system-id";
    private static final String HAG_UUID = "hag-uuid-z7";

    private AgentManager agentManager;
    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnChassisMapDao chassisMapDao;
    private OvnPublicNetworkManager publicNetworkManager;
    private OvnNbClient nbClient;
    private OvnBgpRedistributeManager manager;

    @Before
    public void setUp() throws Exception {
        agentManager = mock(AgentManager.class);
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        chassisMapDao = mock(OvnChassisMapDao.class);
        publicNetworkManager = mock(OvnPublicNetworkManager.class);
        nbClient = mock(OvnNbClient.class);

        final OvnControllerVO controller = mock(OvnControllerVO.class);
        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);

        final OvnLogicalIdMapVO hagMapping = mock(OvnLogicalIdMapVO.class);
        when(hagMapping.getOvnUuid()).thenReturn(HAG_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.HA_CHASSIS_GROUP), eq(ZONE_ID), eq(CONTROLLER_ID)))
                .thenReturn(hagMapping);
        when(nbClient.findTopPriorityChassisName(HAG_UUID)).thenReturn(CHASSIS_NAME);
        final OvnChassisMapVO chassisRow = mock(OvnChassisMapVO.class);
        when(chassisRow.getHostId()).thenReturn(HOST_ID);
        when(chassisMapDao.findByChassisUuid(CHASSIS_NAME)).thenReturn(chassisRow);

        manager = spy(new OvnBgpRedistributeManager());
        injectField(manager, "agentManager", agentManager);
        injectField(manager, "pluginManager", pluginManager);
        injectField(manager, "logicalIdMapDao", logicalIdMapDao);
        injectField(manager, "chassisMapDao", chassisMapDao);
        injectField(manager, "publicNetworkManager", publicNetworkManager);
        injectField(manager, "ipAddressDao", mock(IPAddressDao.class));
    }

    @Test
    public void announceSubnet6IsNoOpWhenToggleOff() {
        // Default ConfigKey value is false — the real gate returns false.
        manager.announceSubnet6(V6_TIER_CIDR, NET_ID, VPC_ID, ZONE_ID);
        verify(agentManager, never()).easySend(any(), any());
        verify(logicalIdMapDao, never()).persist(any(OvnLogicalIdMapVO.class));
    }

    @Test
    public void announceSubnet6SendsV6CommandAndPersistsV6RowWhenEnabled() {
        doReturn(true).when(manager).isRoutedAnnounceIpv6Enabled();
        when(publicNetworkManager.getVpcPublicIpv6GatewayIp(ZONE_ID, VPC_ID)).thenReturn(V6_LRP_GUA);
        when(publicNetworkManager.getPublicIpv6AnchorCidr()).thenReturn(V6_ANCHOR);
        when(publicNetworkManager.getPublicIpv6Gateway()).thenReturn(V6_GW);
        when(agentManager.easySend(eq(HOST_ID), any(OvnBgpAnnounceCommand.class)))
                .thenReturn(new OvnBgpAnnounceAnswer(null, true, "ok", 4200000002L));

        manager.announceSubnet6(V6_TIER_CIDR, NET_ID, VPC_ID, ZONE_ID);

        final ArgumentCaptor<OvnBgpAnnounceCommand> captor =
                ArgumentCaptor.forClass(OvnBgpAnnounceCommand.class);
        verify(agentManager, times(1)).easySend(eq(HOST_ID), captor.capture());
        final OvnBgpAnnounceCommand sent = captor.getValue();
        assertEquals(V6_TIER_NET, sent.getPublicIp());
        assertEquals(Integer.valueOf(64), sent.getPrefixLength());
        assertEquals(V6_LRP_GUA, sent.getGatewayIp());
        assertEquals(V6_ANCHOR, sent.getAnchorCidr());
        assertEquals(V6_GW, sent.getNetworkGatewayIp());

        // Persisted under the distinct v6 kind so the v4 announce row is untouched.
        final ArgumentCaptor<OvnLogicalIdMapVO> rowCaptor =
                ArgumentCaptor.forClass(OvnLogicalIdMapVO.class);
        verify(logicalIdMapDao, times(1)).persist(rowCaptor.capture());
        assertEquals(Kind.BGP_SUBNET_ANNOUNCE_V6, rowCaptor.getValue().getKind());
        assertEquals(V6_TIER_CIDR, rowCaptor.getValue().getOvnName());
    }

    @Test
    public void announceSubnet6IgnoresNonV6Cidr() {
        doReturn(true).when(manager).isRoutedAnnounceIpv6Enabled();
        // A v4 CIDR passed by mistake must never enter the v6 path.
        manager.announceSubnet6("10.45.0.0/24", NET_ID, VPC_ID, ZONE_ID);
        verify(agentManager, never()).easySend(any(), any());
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field f = OvnBgpRedistributeManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
