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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.Network;
import com.cloud.network.dao.DsrLbDesiredStateDao;
import com.cloud.network.dao.DsrLbDesiredStateVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnNbClient.EcmpStaticRoute;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.class)
public class DsrSoftwareLbServiceTest {

    private static final String LR_UUID = "lr-snape-uuid";
    private static final String VIP4 = "217.179.89.38";
    private static final String VIP6 = "2a13:8740:0:7::101";
    private static final String B4A = "10.45.4.98";
    private static final String B4B = "10.45.4.143";
    private static final String B4C = "10.45.4.228";
    private static final String B6A = "2a13:8740:0:9::98";
    private static final String B6B = "2a13:8740:0:9::143";
    private static final String B6C = "2a13:8740:0:9::228";

    @Mock
    private DsrLbDesiredStateDao dsrLbDesiredStateDao;
    @Mock
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Mock
    private OvnBgpRedistributeManager bgpRedistributeManager;
    @Mock
    private OvnPluginManager pluginManager;
    @Mock
    private IPAddressDao ipAddressDao;
    @Mock
    private UserPublicIpv6AddressDao userPublicIpv6AddressDao;
    @Mock
    private LoadBalancerDao loadBalancerDao;
    @Mock
    private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private NicDao nicDao;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private Network network;
    @Mock
    private OvnNbClient nbClient;
    @Mock
    private OvnControllerVO controller;
    @Mock
    private OvnLogicalIdMapVO lrMapping;

    @InjectMocks
    private DsrSoftwareLbService service;

    private LoadBalancerVO dsrLb;
    private LoadBalancingRule dsrRule;

    @Before
    public void setUp() {
        dsrLb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        dsrLb.setLbKind(LbKind.DSR_SOFTWARE);
        dsrLb.setState(FirewallRule.State.Add);
        final List<LbDestination> dests = List.of(
                new LbDestination(8080, 8080, B4A, false),
                new LbDestination(8080, 8080, B4B, false),
                new LbDestination(8080, 8080, B4C, false));
        dsrRule = new LoadBalancingRule(dsrLb, dests, List.of(), List.of(), new Ip(VIP4), null, "tcp");
        lenient().when(network.getId()).thenReturn(10L);
        lenient().when(network.getVpcId()).thenReturn(100L);
        lenient().when(network.getDataCenterId()).thenReturn(1L);
        lenient().when(dsrLbDesiredStateDao.findByLoadBalancerId(anyLong())).thenReturn(null);
        lenient().when(dsrLbDesiredStateDao.persist(any(DsrLbDesiredStateVO.class))).thenAnswer(inv -> inv.getArgument(0));
        IPAddressVO ip = org.mockito.Mockito.mock(IPAddressVO.class);
        lenient().when(ip.getId()).thenReturn(1L);
        lenient().when(ip.getAddress()).thenReturn(new Ip(VIP4));
        lenient().when(ipAddressDao.findById(1L)).thenReturn(ip);

        lenient().when(controller.getId()).thenReturn(7L);
        lenient().when(pluginManager.findControllerForZone(1L)).thenReturn(controller);
        lenient().when(pluginManager.nbClient(1L)).thenReturn(nbClient);
        lenient().when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(100L), eq(7L))).thenReturn(lrMapping);
        lenient().when(lrMapping.getOvnUuid()).thenReturn(LR_UUID);
        // Default: empty existing; after adds, post-condition list returns programmed hops.
        lenient().when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of())
                .thenAnswer(inv -> List.of(
                        new EcmpStaticRoute("r-a", VIP4 + "/32", B4A, "0"),
                        new EcmpStaticRoute("r-b", VIP4 + "/32", B4B, "0"),
                        new EcmpStaticRoute("r-c", VIP4 + "/32", B4C, "0")));
        lenient().when(nbClient.addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                isNull(), anyString(), anyMap())).thenAnswer(inv -> "route-" + inv.getArgument(2));
    }

    @Test
    public void validateAcceptsDsrRoundRobin() {
        assertTrue(service.validateLBRule(network, dsrRule));
    }

    @Test
    public void validateRejectsLeastconn() {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "leastconn", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip(VIP4), null, "tcp");
        assertFalse(service.validateLBRule(network, rule));
    }

    @Test
    public void validateRejectsCtLbMisdispatch() {
        LoadBalancerVO lb = new LoadBalancerVO("x", "ct", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.CT_LB);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip(VIP4), null, "tcp");
        assertFalse(service.validateLBRule(network, rule));
    }

    @Test
    public void buildDesiredHopsCreatesV4AndV6Ecmp() {
        final List<DsrSoftwareLbService.DesiredHop> hops = DsrSoftwareLbService.buildDesiredHops(
                VIP4, VIP6, List.of(B4A, B4B, B4C, B6A, B6B, B6C));
        assertEquals(6, hops.size());
        long v4 = hops.stream().filter(h -> "v4".equals(h.family)).count();
        long v6 = hops.stream().filter(h -> "v6".equals(h.family)).count();
        assertEquals(3, v4);
        assertEquals(3, v6);
        assertTrue(hops.stream().anyMatch(h -> (VIP4 + "/32").equals(h.prefix) && B4A.equals(h.nexthop)));
        assertTrue(hops.stream().anyMatch(h -> (VIP6 + "/128").equals(h.prefix) && B6A.equals(h.nexthop)));
    }

    @Test
    public void buildDesiredHopsIgnoresCrossFamilyBackends() {
        final List<DsrSoftwareLbService.DesiredHop> hops = DsrSoftwareLbService.buildDesiredHops(
                VIP4, null, List.of(B4A, B6A));
        assertEquals(1, hops.size());
        assertEquals(B4A, hops.get(0).nexthop);
    }

    @Test
    public void applyProgramsV4EcmpRoutesWithOwnershipExternalIds() throws Exception {
        assertTrue(service.applyLBRules(network, List.of(dsrRule)));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, String>> extCap = ArgumentCaptor.forClass(Map.class);
        verify(nbClient, times(3)).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"),
                anyString(), isNull(), eq("dst-ip"), extCap.capture());
        final Map<String, String> ext = extCap.getAllValues().get(0);
        assertEquals(OvnConstants.EXT_VAL_DSR_SOFTWARE, ext.get(OvnConstants.EXT_ID_LB_KIND));
        assertEquals(String.valueOf(dsrRule.getId()), ext.get(OvnConstants.EXT_ID_DSR_ROUTE));
        assertEquals("v4", ext.get(OvnConstants.EXT_ID_VIP_FAMILY));
        assertNotNull(ext.get(OvnConstants.EXT_ID_BACKEND));
        assertEquals("DSR_LB_ROUTE", ext.get(OvnConstants.EXT_ID_KIND));

        // Never create OVN LB / NAT — only static routes + BGP withdraw.
        verify(nbClient, never()).createLoadBalancer(anyString(), any(), anyString(), any(), any());
        verify(nbClient, never()).createLoadBalancer(anyString(), any(), anyString(), any(), any(), any());
        verify(bgpRedistributeManager).withdraw(eq(VIP4), eq(1L), eq(100L), eq(1L));
        verify(dsrLbDesiredStateDao).persist(any(DsrLbDesiredStateVO.class));
    }

    @Test
    public void applyDualStackProgramsBothFamilies() throws Exception {
        dsrLb.setPublicIpv6AddressId(55L);
        final UserPublicIpv6AddressVO v6 = org.mockito.Mockito.mock(UserPublicIpv6AddressVO.class);
        when(v6.getAddress()).thenReturn(VIP6);
        when(userPublicIpv6AddressDao.findById(55L)).thenReturn(v6);
        final List<LbDestination> dests = List.of(
                new LbDestination(8080, 8080, B4A, false),
                new LbDestination(8080, 8080, B4B, false),
                new LbDestination(8080, 8080, B6A, false),
                new LbDestination(8080, 8080, B6B, false));
        final LoadBalancingRule dual = new LoadBalancingRule(dsrLb, dests, List.of(), List.of(),
                new Ip(VIP4), null, "tcp");
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        new EcmpStaticRoute("r1", VIP4 + "/32", B4A, "0"),
                        new EcmpStaticRoute("r2", VIP4 + "/32", B4B, "0"),
                        new EcmpStaticRoute("r3", VIP6 + "/128", B6A, "0"),
                        new EcmpStaticRoute("r4", VIP6 + "/128", B6B, "0")));

        assertTrue(service.applyLBRules(network, List.of(dual)));
        verify(nbClient, times(2)).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"),
                anyString(), isNull(), eq("dst-ip"), anyMap());
        verify(nbClient, times(2)).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP6 + "/128"),
                anyString(), isNull(), eq("dst-ip"), anyMap());
        verify(bgpRedistributeManager).withdrawHost6(eq(VIP6), eq(100L), eq(1L));
    }

    @Test
    public void applyFailsClosedWhenNoBackends() throws Exception {
        final LoadBalancingRule empty = new LoadBalancingRule(dsrLb, List.of(), List.of(), List.of(),
                new Ip(VIP4), null, "tcp");
        assertFalse(service.applyLBRules(network, List.of(empty)));
        verify(nbClient, never()).addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                any(), any(), any());
        verify(bgpRedistributeManager, never()).withdraw(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void applyFailsClosedWhenMissingRouter() throws Exception {
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(100L), eq(7L))).thenReturn(null);
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(nbClient, never()).addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                any(), any(), any());
        verify(bgpRedistributeManager, never()).withdraw(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void applyFailsClosedWhenPartialFamilyMissingBackends() throws Exception {
        dsrLb.setPublicIpv6AddressId(55L);
        final UserPublicIpv6AddressVO v6 = org.mockito.Mockito.mock(UserPublicIpv6AddressVO.class);
        when(v6.getAddress()).thenReturn(VIP6);
        when(userPublicIpv6AddressDao.findById(55L)).thenReturn(v6);
        // Only v4 backends — v6 VIP present => fail closed, no partial program.
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(nbClient, never()).addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    public void applyRollsBackAddedRoutesOnPartialFailure() throws Exception {
        when(nbClient.addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"), eq(B4A),
                isNull(), eq("dst-ip"), anyMap())).thenReturn("r-a");
        when(nbClient.addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"), eq(B4B),
                isNull(), eq("dst-ip"), anyMap())).thenThrow(new RuntimeException("ovsdb down"));

        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(nbClient).deleteLogicalRouterStaticRouteDirect("r-a");
        verify(bgpRedistributeManager, never()).withdraw(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void memberUpdateConvergesRoutes() throws Exception {
        // Existing A+B owned; desired A+C => remove B, add C.
        final EcmpStaticRoute existingA = new EcmpStaticRoute("u-a", VIP4 + "/32", B4A, "0");
        final EcmpStaticRoute existingB = new EcmpStaticRoute("u-b", VIP4 + "/32", B4B, "0");
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of(existingA, existingB))
                .thenReturn(List.of(
                        new EcmpStaticRoute("u-a", VIP4 + "/32", B4A, "0"),
                        new EcmpStaticRoute("u-c", VIP4 + "/32", B4C, "0")));

        final List<LbDestination> dests = List.of(
                new LbDestination(8080, 8080, B4A, false),
                new LbDestination(8080, 8080, B4C, false));
        final LoadBalancingRule updated = new LoadBalancingRule(dsrLb, dests, List.of(), List.of(),
                new Ip(VIP4), null, "tcp");
        assertTrue(service.applyLBRules(network, List.of(updated)));
        verify(nbClient).deleteLogicalRouterStaticRouteDirect("u-b");
        verify(nbClient).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"), eq(B4C),
                isNull(), eq("dst-ip"), anyMap());
        // A kept — not re-added
        verify(nbClient, never()).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"), eq(B4A),
                isNull(), eq("dst-ip"), anyMap());
    }

    @Test
    public void revokeRemovesOwnedRoutesOnlyAndRestoresBgp() throws Exception {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        lb.setState(FirewallRule.State.Revoke);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip(VIP4), null, "tcp");
        DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(0L, VIP4, null, 80, "tcp", "{}");
        when(dsrLbDesiredStateDao.findByLoadBalancerId(anyLong())).thenReturn(desired);

        final EcmpStaticRoute owned = new EcmpStaticRoute("u-owned", VIP4 + "/32", B4A, "0");
        final EcmpStaticRoute other = new EcmpStaticRoute("u-other", VIP4 + "/32", B4B, "999");
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE)).thenReturn(List.of(owned, other));

        assertTrue(service.applyLBRules(network, List.of(rule)));
        assertEquals(DsrLbDesiredStateVO.STATE_REVOKED, desired.getState());
        verify(nbClient).deleteLogicalRouterStaticRouteDirect("u-owned");
        verify(nbClient, never()).deleteLogicalRouterStaticRouteDirect("u-other");
        verify(bgpRedistributeManager).announce(eq(VIP4), eq(1L), eq(100L), eq(1L));
    }

    @Test
    public void applyFailsClosedWhenBgpWithdrawThrowsAfterRoutes() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("agent down")).when(bgpRedistributeManager)
                .withdraw(eq(VIP4), eq(1L), eq(100L), eq(1L));
        // Routes succeed first; BGP fails — overall apply false.
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        new EcmpStaticRoute("r1", VIP4 + "/32", B4A, "0"),
                        new EcmpStaticRoute("r2", VIP4 + "/32", B4B, "0"),
                        new EcmpStaticRoute("r3", VIP4 + "/32", B4C, "0")));
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(nbClient, times(3)).addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                isNull(), eq("dst-ip"), anyMap());
    }

    @Test
    public void reconcileIdempotentWhenAlreadyProgrammed() {
        DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(5L, VIP4, null, 80, "tcp", "{}");
        desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
        desired.setBackendReady(true);
        // No inventory LB — reprogramFromInventory no-ops when LB missing.
        when(loadBalancerDao.findById(5L)).thenReturn(null);
        assertTrue(service.reconcileOne(desired));
        verify(dsrLbDesiredStateDao, never()).update(anyLong(), any());
    }

    @Test
    public void isDsrRuleHelper() {
        assertTrue(DsrSoftwareLbService.isDsrRule(dsrRule));
        LoadBalancerVO ct = new LoadBalancerVO("x", "ct", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        assertFalse(DsrSoftwareLbService.isDsrRule(
                new LoadBalancingRule(ct, List.of(), List.of(), List.of(), new Ip(VIP4))));
    }

    @Test
    public void externalIdsContainKindTag() {
        String json = DsrSoftwareLbService.buildExternalIds(dsrRule);
        assertTrue(json.contains("DSR_SOFTWARE"));
        assertTrue(json.contains(DsrSoftwareLbService.EXT_CS_LB_KIND));
    }

    @Test
    public void collectActiveBackendsSkipsRevoked() {
        final List<LbDestination> dests = List.of(
                new LbDestination(8080, 8080, B4A, false),
                new LbDestination(8080, 8080, B4B, true));
        final LoadBalancingRule rule = new LoadBalancingRule(dsrLb, dests, List.of(), List.of(),
                new Ip(VIP4), null, "tcp");
        assertEquals(List.of(B4A), DsrSoftwareLbService.collectActiveBackends(rule));
    }
}
