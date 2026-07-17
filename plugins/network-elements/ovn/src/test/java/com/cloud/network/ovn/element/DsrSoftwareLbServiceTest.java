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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.Network;
import com.cloud.network.dao.DsrLbDesiredStateDao;
import com.cloud.network.dao.DsrLbDesiredStateVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;

@RunWith(MockitoJUnitRunner.class)
public class DsrSoftwareLbServiceTest {

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
    private EntityManager entityMgr;
    @Mock
    private Network network;
    @Mock
    private com.cloud.network.ovn.client.OvnNbClient nbClient;

    @InjectMocks
    private DsrSoftwareLbService service;

    private LoadBalancerVO dsrLb;
    private LoadBalancingRule dsrRule;

    @Before
    public void setUp() {
        dsrLb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        dsrLb.setLbKind(LbKind.DSR_SOFTWARE);
        dsrLb.setState(FirewallRule.State.Add);
        dsrRule = new LoadBalancingRule(dsrLb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"), null, "tcp");
        lenient().when(network.getId()).thenReturn(10L);
        lenient().when(network.getVpcId()).thenReturn(100L);
        lenient().when(network.getDataCenterId()).thenReturn(1L);
        lenient().when(dsrLbDesiredStateDao.findByLoadBalancerId(anyLong())).thenReturn(null);
        lenient().when(dsrLbDesiredStateDao.persist(any(DsrLbDesiredStateVO.class))).thenAnswer(inv -> inv.getArgument(0));
        IPAddressVO ip = org.mockito.Mockito.mock(IPAddressVO.class);
        lenient().when(ip.getId()).thenReturn(1L);
        lenient().when(ip.getAddress()).thenReturn(new Ip("1.2.3.4"));
        lenient().when(ipAddressDao.findById(1L)).thenReturn(ip);
    }

    @Test
    public void validateAcceptsDsrRoundRobin() {
        assertTrue(service.validateLBRule(network, dsrRule));
    }

    @Test
    public void validateRejectsLeastconn() {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "leastconn", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"), null, "tcp");
        assertFalse(service.validateLBRule(network, rule));
    }

    @Test
    public void validateRejectsCtLbMisdispatch() {
        LoadBalancerVO lb = new LoadBalancerVO("x", "ct", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.CT_LB);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"), null, "tcp");
        assertFalse(service.validateLBRule(network, rule));
    }

    @Test
    public void applyNeverTouchesNbClient() throws Exception {
        // Force Active state via mock LoadBalancer if needed — VO may default Add
        assertTrue(service.applyLBRules(network, List.of(dsrRule)));
        // No createLoadBalancer / NAT: nbClient is never injected here; verify BGP withdraw only.
        verify(bgpRedistributeManager).withdraw(eq("1.2.3.4"), eq(1L), eq(100L), eq(1L));
        verify(dsrLbDesiredStateDao).persist(any(DsrLbDesiredStateVO.class));
        // Explicit: never call OVN NB through plugin manager for DSR apply
        verify(pluginManager, never()).nbClient(anyLong());
    }

    @Test
    public void applyFailsClosedWhenBgpWithdrawThrows() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("agent down")).when(bgpRedistributeManager)
                .withdraw(eq("1.2.3.4"), eq(1L), eq(100L), eq(1L));
        assertFalse(service.applyLBRules(network, List.of(dsrRule)));
        verify(pluginManager, never()).nbClient(anyLong());
    }

    @Test
    public void applyRevokeMarksDesiredRevoked() throws Exception {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        lb.setState(FirewallRule.State.Revoke);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"), null, "tcp");
        DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(0L, "1.2.3.4", null, 80, "tcp", "{}");
        when(dsrLbDesiredStateDao.findByLoadBalancerId(anyLong())).thenReturn(desired);
        assertTrue(service.applyLBRules(network, List.of(rule)));
        assertEquals(DsrLbDesiredStateVO.STATE_REVOKED, desired.getState());
        verify(dsrLbDesiredStateDao).update(eq(desired.getId()), eq(desired));
        verify(pluginManager, never()).nbClient(anyLong());
    }

    @Test
    public void reconcileIdempotentWhenAlreadyProgrammed() {
        DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(5L, "1.2.3.4", null, 80, "tcp", "{}");
        desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
        desired.setBackendReady(true);
        assertTrue(service.reconcileOne(desired));
        verify(dsrLbDesiredStateDao, never()).update(anyLong(), any());
    }

    @Test
    public void isDsrRuleHelper() {
        assertTrue(DsrSoftwareLbService.isDsrRule(dsrRule));
        LoadBalancerVO ct = new LoadBalancerVO("x", "ct", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        assertFalse(DsrSoftwareLbService.isDsrRule(
                new LoadBalancingRule(ct, List.of(), List.of(), List.of(), new Ip("1.2.3.4"))));
    }

    @Test
    public void externalIdsContainKindTag() {
        String json = DsrSoftwareLbService.buildExternalIds(dsrRule);
        assertTrue(json.contains("DSR_SOFTWARE"));
        assertTrue(json.contains(DsrSoftwareLbService.EXT_CS_LB_KIND));
    }
}
