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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;

/**
 * Verifies IPv6 RA + LR-port v6-gateway wiring introduced in
 * {@link OvnNetworkElement#ensureTierBoundToVpcLr} and
 * {@link OvnNetworkElement#ensureIsolatedNetworkGateway}: a dual-stack tier's
 * LRP {@code networks} list must carry both the v4 and the v6 gateway CIDR,
 * and {@link OvnNbClient#lrpSetIpv6RaConfigs} must be invoked with
 * {@code address_mode=dhcpv6_stateful}. An IPv4-only network must be a
 * strict no-op on both fronts.
 */
public class OvnNetworkElementV6RaTest {

    private static final String V4_GATEWAY = "10.0.0.1";
    private static final String V4_CIDR = "10.0.0.0/24";
    private static final String V6_GATEWAY = "fd00:1234:5678::1";
    private static final String V6_CIDR = "fd00:1234:5678::/64";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnGuestNetworkGuru guru;
    private OvnVpcElement vpcElement;
    private VpcDao vpcDao;
    private NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    private OvnNetworkElement element;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        guru = mock(OvnGuestNetworkGuru.class);
        vpcElement = mock(OvnVpcElement.class);
        vpcDao = mock(VpcDao.class);
        networkOfferingServiceMapDao = mock(NetworkOfferingServiceMapDao.class);

        when(controller.getId()).thenReturn(2L);
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(controller);
        when(pluginManager.nbClient(anyLong())).thenReturn(nbClient);
        when(guru.createLogicalSwitchFor(any(Network.class))).thenReturn("ls-uuid");

        element = new OvnNetworkElement();
        inject(element, "pluginManager", pluginManager);
        inject(element, "logicalIdMapDao", logicalIdMapDao);
        inject(element, "guru", guru);
        inject(element, "vpcElement", vpcElement);
        inject(element, "vpcDao", vpcDao);
        inject(element, "networkOfferingServiceMapDao", networkOfferingServiceMapDao);
    }

    private static Network dualStackVpcTier() {
        final Network network = mock(Network.class);
        when(network.getId()).thenReturn(10L);
        when(network.getUuid()).thenReturn("tier-uuid");
        when(network.getDataCenterId()).thenReturn(1L);
        when(network.getVpcId()).thenReturn(5L);
        when(network.getGateway()).thenReturn(V4_GATEWAY);
        when(network.getCidr()).thenReturn(V4_CIDR);
        when(network.getIp6Gateway()).thenReturn(V6_GATEWAY);
        when(network.getIp6Cidr()).thenReturn(V6_CIDR);
        return network;
    }

    private static Network ipv4OnlyVpcTier() {
        final Network network = mock(Network.class);
        when(network.getId()).thenReturn(11L);
        when(network.getUuid()).thenReturn("tier-uuid-v4");
        when(network.getDataCenterId()).thenReturn(1L);
        when(network.getVpcId()).thenReturn(5L);
        when(network.getGateway()).thenReturn(V4_GATEWAY);
        when(network.getCidr()).thenReturn(V4_CIDR);
        when(network.getIp6Gateway()).thenReturn(null);
        when(network.getIp6Cidr()).thenReturn(null);
        return network;
    }

    /** Create path: LRP is brand new (no PUBLIC_LRP mapping yet). */
    @Test
    public void dualStackVpcTier_createPath_appendsV6AndSetsRaConfig() throws Exception {
        final Network network = dualStackVpcTier();
        final VpcVO vpc = mock(VpcVO.class);
        when(vpcDao.findById(5L)).thenReturn(vpc);
        when(logicalIdMapDao.findByCsId(eq(Kind.PUBLIC_LRP), eq(10L), eq(2L))).thenReturn(null);
        when(vpcElement.bindTierToVpc(eq(vpc), eq("ls-uuid"), eq("tier-uuid"), anyString(), anyList()))
                .thenReturn(new OvnNbClient.BindResult("lrp-uuid", "lsp-uuid"));

        assertTrue(element.implement(network, null, null, null));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<String>> networksCaptor = ArgumentCaptor.forClass(List.class);
        verify(vpcElement, times(1)).bindTierToVpc(eq(vpc), eq("ls-uuid"), eq("tier-uuid"), anyString(),
                networksCaptor.capture());
        assertTrue(networksCaptor.getValue().contains(V4_GATEWAY + "/24"));
        assertTrue(networksCaptor.getValue().contains(V6_GATEWAY + "/64"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, String>> raCaptor = ArgumentCaptor.forClass(Map.class);
        verify(nbClient, times(1)).lrpSetIpv6RaConfigs(eq("lrp-uuid"), raCaptor.capture());
        assertEquals("dhcpv6_stateful", raCaptor.getValue().get("address_mode"));
    }

    /** Reconcile path: LRP already bound; RA must be (re)applied on every reconcile. */
    @Test
    public void dualStackVpcTier_reconcilePath_reappliesRaConfig() throws Exception {
        final Network network = dualStackVpcTier();
        final VpcVO vpc = mock(VpcVO.class);
        when(vpcDao.findById(5L)).thenReturn(vpc);
        final com.cloud.network.ovn.dao.OvnLogicalIdMapVO existing =
                mock(com.cloud.network.ovn.dao.OvnLogicalIdMapVO.class);
        when(existing.getOvnUuid()).thenReturn("existing-lrp-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.PUBLIC_LRP), eq(10L), eq(2L))).thenReturn(existing);
        when(nbClient.rowExistsByUuid(eq("Logical_Router_Port"), eq("existing-lrp-uuid"))).thenReturn(true);
        when(nbClient.ensureRouterPeerLsp(anyString(), anyString(), anyString())).thenReturn("rsp-uuid");

        assertTrue(element.implement(network, null, null, null));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<String>> networksCaptor = ArgumentCaptor.forClass(List.class);
        verify(nbClient, times(1)).updateLogicalRouterPortNetworks(eq("existing-lrp-uuid"), networksCaptor.capture(),
                anyString());
        assertTrue(networksCaptor.getValue().contains(V4_GATEWAY + "/24"));
        assertTrue(networksCaptor.getValue().contains(V6_GATEWAY + "/64"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, String>> raCaptor = ArgumentCaptor.forClass(Map.class);
        verify(nbClient, times(1)).lrpSetIpv6RaConfigs(eq("existing-lrp-uuid"), raCaptor.capture());
        assertEquals("dhcpv6_stateful", raCaptor.getValue().get("address_mode"));
    }

    /** Regression: an IPv4-only tier must not append v6 nor call lrpSetIpv6RaConfigs. */
    @Test
    public void ipv4OnlyVpcTier_doesNotAppendV6OrSetRaConfig() throws Exception {
        final Network network = ipv4OnlyVpcTier();
        final VpcVO vpc = mock(VpcVO.class);
        when(vpcDao.findById(5L)).thenReturn(vpc);
        when(logicalIdMapDao.findByCsId(eq(Kind.PUBLIC_LRP), eq(11L), eq(2L))).thenReturn(null);
        when(vpcElement.bindTierToVpc(eq(vpc), eq("ls-uuid"), eq("tier-uuid-v4"), anyString(), anyList()))
                .thenReturn(new OvnNbClient.BindResult("lrp-uuid-v4", "lsp-uuid-v4"));

        assertTrue(element.implement(network, null, null, null));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<String>> networksCaptor = ArgumentCaptor.forClass(List.class);
        verify(vpcElement, times(1)).bindTierToVpc(eq(vpc), eq("ls-uuid"), eq("tier-uuid-v4"), anyString(),
                networksCaptor.capture());
        assertEquals(1, networksCaptor.getValue().size());
        assertFalse(networksCaptor.getValue().contains(V6_GATEWAY + "/64"));

        verify(nbClient, never()).lrpSetIpv6RaConfigs(anyString(), any());
    }

    /** Standalone (non-VPC) isolated network create path exercises the second call-site. */
    @Test
    public void dualStackIsolatedNetwork_createPath_appendsV6AndSetsRaConfig() throws Exception {
        final Network network = mock(Network.class);
        when(network.getId()).thenReturn(20L);
        when(network.getUuid()).thenReturn("isolated-uuid");
        when(network.getDataCenterId()).thenReturn(1L);
        when(network.getVpcId()).thenReturn(null);
        when(network.getGateway()).thenReturn(V4_GATEWAY);
        when(network.getCidr()).thenReturn(V4_CIDR);
        when(network.getIp6Gateway()).thenReturn(V6_GATEWAY);
        when(network.getIp6Cidr()).thenReturn(V6_CIDR);
        when(network.getNetworkOfferingId()).thenReturn(30L);

        when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(eq(30L), eq(Service.SourceNat)))
                .thenReturn(true);
        when(vpcElement.createLogicalRouterForNetwork(network)).thenReturn("lr-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_GW_LRP), eq(20L), eq(2L))).thenReturn(null);
        // NETWORK_LR lookup (ensureIsolatedNetworkPublic) intentionally left
        // unstubbed (returns null) so that path defers without extra mocks.
        when(vpcElement.bindNetworkGateway(eq(network), eq("lr-uuid"), eq("ls-uuid"), eq("isolated-uuid"),
                anyString(), anyList())).thenReturn(new OvnNbClient.BindResult("lrp-uuid-iso", "lsp-uuid-iso"));

        assertTrue(element.implement(network, null, null, null));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<String>> networksCaptor = ArgumentCaptor.forClass(List.class);
        verify(vpcElement, times(1)).bindNetworkGateway(eq(network), eq("lr-uuid"), eq("ls-uuid"),
                eq("isolated-uuid"), anyString(), networksCaptor.capture());
        assertTrue(networksCaptor.getValue().contains(V4_GATEWAY + "/24"));
        assertTrue(networksCaptor.getValue().contains(V6_GATEWAY + "/64"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, String>> raCaptor = ArgumentCaptor.forClass(Map.class);
        verify(nbClient, times(1)).lrpSetIpv6RaConfigs(eq("lrp-uuid-iso"), raCaptor.capture());
        assertEquals("dhcpv6_stateful", raCaptor.getValue().get("address_mode"));
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception {
        Field f;
        try {
            f = target.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = target.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(target, value);
    }
}
