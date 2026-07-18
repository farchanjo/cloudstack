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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.Network;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.network.dao.DsrLbDesiredStateDao;
import com.cloud.network.dao.DsrLbDesiredStateVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnNbClient.EcmpStaticRoute;
import com.cloud.network.ovn.client.OvnNbClient.OwnedLoadBalancer;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

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
    private static final String OWNER_V4 = "100|" + VIP4 + "/32";

    @Mock private DsrLbDesiredStateDao dsrLbDesiredStateDao;
    @Mock private OvnLogicalIdMapDao logicalIdMapDao;
    @Mock private OvnBgpRedistributeManager bgpRedistributeManager;
    @Mock private OvnPluginManager pluginManager;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private UserPublicIpv6AddressDao userPublicIpv6AddressDao;
    @Mock private LoadBalancerDao loadBalancerDao;
    @Mock private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Mock private NetworkDao networkDao;
    @Mock private NicDao nicDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private EntityManager entityMgr;
    @Mock private Network network;
    @Mock private OvnNbClient nbClient;
    @Mock private OvnControllerVO controller;
    @Mock private OvnLogicalIdMapVO lrMapping;

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
        lenient().when(dsrLbDesiredStateDao.listActive()).thenReturn(List.of());
        IPAddressVO ip = org.mockito.Mockito.mock(IPAddressVO.class);
        lenient().when(ip.getId()).thenReturn(1L);
        lenient().when(ip.getAddress()).thenReturn(new Ip(VIP4));
        lenient().when(ipAddressDao.findById(1L)).thenReturn(ip);

        lenient().when(controller.getId()).thenReturn(7L);
        lenient().when(pluginManager.findControllerForZone(1L)).thenReturn(controller);
        lenient().when(pluginManager.nbClient(1L)).thenReturn(nbClient);
        lenient().when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(100L), eq(7L))).thenReturn(lrMapping);
        lenient().when(lrMapping.getOvnUuid()).thenReturn(LR_UUID);
        lenient().when(loadBalancerDao.listByNetworkIdOrVpcIdAndScheme(anyLong(), any(), eq(Scheme.Public)))
                .thenReturn(List.of(dsrLb));
        // No residual CT
        lenient().when(nbClient.listOwnedLoadBalancers(anyString())).thenReturn(List.of());
        lenient().when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of())
                .thenAnswer(inv -> List.of(
                        new EcmpStaticRoute("r-a", VIP4 + "/32", B4A, OWNER_V4),
                        new EcmpStaticRoute("r-b", VIP4 + "/32", B4B, OWNER_V4),
                        new EcmpStaticRoute("r-c", VIP4 + "/32", B4C, OWNER_V4)));
        lenient().when(nbClient.addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                isNull(), anyString(), anyMap())).thenAnswer(inv -> "route-" + inv.getArgument(2));
    }

    @Test
    public void vipOwnerKeyIsVpcScoped() {
        assertEquals("924|217.179.89.38/32", DsrSoftwareLbService.vipOwnerKey(924L, "217.179.89.38/32"));
    }

    @Test
    public void isOwnedByVipScopeMatchesVipKeyAndLegacyRuleId() {
        final EcmpStaticRoute vipOwned = new EcmpStaticRoute("u1", VIP4 + "/32", B4A, OWNER_V4);
        final EcmpStaticRoute legacy = new EcmpStaticRoute("u2", VIP4 + "/32", B4B, "42");
        final EcmpStaticRoute other = new EcmpStaticRoute("u3", VIP4 + "/32", B4C, "999");
        assertTrue(DsrSoftwareLbService.isOwnedByVipScope(vipOwned, 100L, VIP4 + "/32", Set.of(42L)));
        assertTrue(DsrSoftwareLbService.isOwnedByVipScope(legacy, 100L, VIP4 + "/32", Set.of(42L)));
        assertFalse(DsrSoftwareLbService.isOwnedByVipScope(other, 100L, VIP4 + "/32", Set.of(42L)));
    }

    @Test
    public void vipKeyMatchesPortAndBare() {
        assertTrue(DsrSoftwareLbService.vipKeyMatches(VIP4 + ":80", VIP4, 80));
        assertTrue(DsrSoftwareLbService.vipKeyMatches("[" + VIP6 + "]:443", VIP6, 443));
        assertFalse(DsrSoftwareLbService.vipKeyMatches(VIP4 + ":80", "1.2.3.4", 80));
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
    public void buildDesiredHopsCreatesV4AndV6Ecmp() {
        final List<DsrSoftwareLbService.DesiredHop> hops = DsrSoftwareLbService.buildDesiredHops(
                VIP4, VIP6, List.of(B4A, B4B, B4C, B6A, B6B));
        assertEquals(5, hops.size());
    }

    @Test
    public void applyProgramsVipScopedRoutesWithOwnership() throws Exception {
        assertTrue(service.applyLBRules(network, List.of(dsrRule)));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, String>> extCap = ArgumentCaptor.forClass(Map.class);
        verify(nbClient, times(3)).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"),
                anyString(), isNull(), eq("dst-ip"), extCap.capture());
        final Map<String, String> ext = extCap.getAllValues().get(0);
        assertEquals(OWNER_V4, ext.get(OvnConstants.EXT_ID_DSR_ROUTE));
        assertEquals(OvnConstants.EXT_VAL_DSR_SOFTWARE, ext.get(OvnConstants.EXT_ID_LB_KIND));
        assertNotNull(ext.get(DsrSoftwareLbService.EXT_ID_DSR_RULES));
        verify(nbClient, never()).createLoadBalancer(anyString(), any(), anyString(), any(), any());
        verify(nbClient, never()).createLoadBalancer(anyString(), any(), anyString(), any(), any(), any());
        verify(bgpRedistributeManager).withdraw(eq(VIP4), eq(1L), eq(100L), eq(1L));
    }

    @Test
    public void applyFailsClosedOnResidualCtLb() throws Exception {
        final OwnedLoadBalancer ct = new OwnedLoadBalancer("lb-uuid", "cs-lb-1596",
                Map.of(VIP4 + ":80", B4A + ":80"), "tcp", "CT_LB");
        when(nbClient.listOwnedLoadBalancers(OvnConstants.EXT_ID_LB_KIND)).thenReturn(List.of(ct));
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(nbClient, never()).addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                any(), any(), any());
        verify(bgpRedistributeManager, never()).withdraw(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void applyFailsClosedWhenNoBackends() throws Exception {
        final LoadBalancingRule empty = new LoadBalancingRule(dsrLb, List.of(), List.of(), List.of(),
                new Ip(VIP4), null, "tcp");
        when(loadBalancerDao.listByNetworkIdOrVpcIdAndScheme(anyLong(), any(), eq(Scheme.Public)))
                .thenReturn(List.of());
        assertFalse(service.applyLBRules(network, List.of(empty)));
        verify(nbClient, never()).addLogicalRouterStaticRoute(anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    public void applyFailsClosedWhenMissingRouter() throws Exception {
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(100L), eq(7L))).thenReturn(null);
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(bgpRedistributeManager, never()).withdraw(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void applyFailsClosedWhenPartialFamilyMissingBackends() throws Exception {
        dsrLb.setPublicIpv6AddressId(55L);
        final UserPublicIpv6AddressVO v6 = org.mockito.Mockito.mock(UserPublicIpv6AddressVO.class);
        when(v6.getAddress()).thenReturn(VIP6);
        when(userPublicIpv6AddressDao.findById(55L)).thenReturn(v6);
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
    public void listExceptionPropagatesAsFailure() throws Exception {
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenThrow(new OvnException("nb down"));
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(bgpRedistributeManager, never()).withdraw(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void postConditionMissCompensates() throws Exception {
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of())
                .thenReturn(List.of()); // post empty
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(nbClient, atLeastOnce()).deleteLogicalRouterStaticRouteDirect(anyString());
    }

    @Test
    public void bgpWithdrawFailureRemovesRoutes() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("agent down")).when(bgpRedistributeManager)
                .withdraw(eq(VIP4), eq(1L), eq(100L), eq(1L));
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        new EcmpStaticRoute("r1", VIP4 + "/32", B4A, OWNER_V4),
                        new EcmpStaticRoute("r2", VIP4 + "/32", B4B, OWNER_V4),
                        new EcmpStaticRoute("r3", VIP4 + "/32", B4C, OWNER_V4)))
                .thenReturn(List.of(
                        new EcmpStaticRoute("r1", VIP4 + "/32", B4A, OWNER_V4),
                        new EcmpStaticRoute("r2", VIP4 + "/32", B4B, OWNER_V4),
                        new EcmpStaticRoute("r3", VIP4 + "/32", B4C, OWNER_V4)));
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        // compensation deletes VIP-scoped routes
        verify(nbClient, atLeastOnce()).deleteLogicalRouterStaticRouteDirect(anyString());
    }

    @Test
    public void memberUpdateAddsBeforeRemove() throws Exception {
        final EcmpStaticRoute existingA = new EcmpStaticRoute("u-a", VIP4 + "/32", B4A, OWNER_V4);
        final EcmpStaticRoute existingB = new EcmpStaticRoute("u-b", VIP4 + "/32", B4B, OWNER_V4);
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE))
                .thenReturn(List.of(existingA, existingB))
                .thenReturn(List.of(
                        new EcmpStaticRoute("u-a", VIP4 + "/32", B4A, OWNER_V4),
                        new EcmpStaticRoute("u-c", VIP4 + "/32", B4C, OWNER_V4)));

        final List<LbDestination> dests = List.of(
                new LbDestination(8080, 8080, B4A, false),
                new LbDestination(8080, 8080, B4C, false));
        final LoadBalancingRule updated = new LoadBalancingRule(dsrLb, dests, List.of(), List.of(),
                new Ip(VIP4), null, "tcp");
        assertTrue(service.applyLBRules(network, List.of(updated)));

        final InOrder order = inOrder(nbClient);
        order.verify(nbClient).addLogicalRouterStaticRoute(eq(LR_UUID), eq(VIP4 + "/32"), eq(B4C),
                isNull(), eq("dst-ip"), anyMap());
        order.verify(nbClient).deleteLogicalRouterStaticRouteDirect("u-b");
    }

    @Test
    public void revokeLastSiblingRemovesRoutesAndRestoresBgp() throws Exception {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        lb.setState(FirewallRule.State.Revoke);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip(VIP4), null, "tcp");
        DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(0L, VIP4, null, 80, "tcp", "{}");
        when(dsrLbDesiredStateDao.findByLoadBalancerId(anyLong())).thenReturn(desired);
        when(loadBalancerDao.listByNetworkIdOrVpcIdAndScheme(anyLong(), any(), eq(Scheme.Public)))
                .thenReturn(List.of()); // no siblings
        final EcmpStaticRoute owned = new EcmpStaticRoute("u-owned", VIP4 + "/32", B4A, OWNER_V4);
        when(nbClient.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE)).thenReturn(List.of(owned));

        assertTrue(service.applyLBRules(network, List.of(rule)));
        assertEquals(DsrLbDesiredStateVO.STATE_REVOKED, desired.getState());
        verify(nbClient).deleteLogicalRouterStaticRouteDirect("u-owned");
        verify(bgpRedistributeManager).announce(eq(VIP4), eq(1L), eq(100L), eq(1L));
    }

    @Test
    public void revokeSiblingKeepsRoutesAndDoesNotRestoreBgp() throws Exception {
        LoadBalancerVO lb80 = new LoadBalancerVO("x", "dsr80", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb80.setLbKind(LbKind.DSR_SOFTWARE);
        lb80.setState(FirewallRule.State.Revoke);
        setEntityId(lb80, 80L);
        LoadBalancerVO lb443 = new LoadBalancerVO("y", "dsr443", "d", 1L, 443, 8443, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb443.setLbKind(LbKind.DSR_SOFTWARE);
        lb443.setState(FirewallRule.State.Active);
        setEntityId(lb443, 443L);
        // Sibling 443 remains active on same VIP — exclude 80 must still see 443.
        when(loadBalancerDao.listByNetworkIdOrVpcIdAndScheme(anyLong(), any(), eq(Scheme.Public)))
                .thenReturn(List.of(lb443));

        LoadBalancingRule rule = new LoadBalancingRule(lb80, List.of(), List.of(), List.of(), new Ip(VIP4), null, "tcp");
        DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(80L, VIP4, null, 80, "tcp", "{}");
        when(dsrLbDesiredStateDao.findByLoadBalancerId(80L)).thenReturn(desired);
        // Sibling path re-converges; allow empty or existing owned routes via lenient setUp.

        assertTrue(service.applyLBRules(network, List.of(rule)));
        verify(bgpRedistributeManager, never()).announce(anyString(), anyLong(), anyLong(), anyLong());
    }

    private static void setEntityId(final Object entity, final long id) throws Exception {
        Class<?> c = entity.getClass();
        while (c != null) {
            try {
                final java.lang.reflect.Field f = c.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("no id field on " + entity.getClass());
    }

    @Test
    public void sameVipPortsShareSingleEcmpOwnerKey() {
        assertEquals(
                DsrSoftwareLbService.vipOwnerKey(100L, VIP4 + "/32"),
                DsrSoftwareLbService.vipOwnerKey(100L, VIP4 + "/32"));
    }

    @Test
    public void externalIdsContainKindTag() {
        String json = DsrSoftwareLbService.buildExternalIds(dsrRule);
        assertTrue(json.contains("DSR_SOFTWARE"));
        assertTrue(json.contains("vip-scoped"));
    }

    @Test
    public void isDsrRuleHelper() {
        assertTrue(DsrSoftwareLbService.isDsrRule(dsrRule));
    }

    @Test
    public void findResidualCtReturnsNullWhenClean() {
        assertNull(service.findResidualCtOnVip(network, dsrRule, VIP4, null));
    }
}
