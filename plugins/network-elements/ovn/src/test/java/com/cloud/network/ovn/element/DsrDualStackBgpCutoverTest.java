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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.Network;
import com.cloud.network.dao.DsrLbDesiredStateDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;

/**
 * Atomic dual-stack BGP cutover for DSR: both v4 /32 and v6 /128 must withdraw
 * before ownership; partial failure rolls back the successful side.
 */
@RunWith(MockitoJUnitRunner.class)
public class DsrDualStackBgpCutoverTest {

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
    private EntityManager entityMgr;
    @Mock
    private Network network;

    @InjectMocks
    private DsrSoftwareLbService service;

    private LoadBalancerVO dualLb;
    private LoadBalancingRule dualRule;
    private IPAddressVO ip4;
    private UserPublicIpv6AddressVO ip6;

    @Before
    public void setUp() {
        dualLb = new LoadBalancerVO("x", "dsr", "d", 10L, 80, 8080, "roundrobin", 1L, 1L, 1L, "tcp", null);
        dualLb.setLbKind(LbKind.DSR_SOFTWARE);
        dualLb.setState(FirewallRule.State.Add);
        dualLb.setPublicIpv6AddressId(20L);
        dualRule = new LoadBalancingRule(dualLb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"), null, "tcp");

        lenient().when(network.getVpcId()).thenReturn(100L);
        lenient().when(network.getDataCenterId()).thenReturn(1L);

        ip4 = org.mockito.Mockito.mock(IPAddressVO.class);
        lenient().when(ip4.getId()).thenReturn(10L);
        lenient().when(ip4.getAddress()).thenReturn(new Ip("1.2.3.4"));
        lenient().when(ipAddressDao.findById(10L)).thenReturn(ip4);

        ip6 = org.mockito.Mockito.mock(UserPublicIpv6AddressVO.class);
        lenient().when(ip6.getAddress()).thenReturn("2a13:8740:0:7::100");
        lenient().when(userPublicIpv6AddressDao.findById(20L)).thenReturn(ip6);
    }

    @Test
    public void dualStackWithdrawBothFamilies() {
        final DsrSoftwareLbService.DualStackBgpResult r = service.withdrawCtLbBgpDualStack(network, dualRule);
        assertTrue(r.ok);
        assertTrue(r.withdrewV4);
        assertTrue(r.withdrewV6);
        assertFalse(r.rolledBack);
        verify(bgpRedistributeManager).withdraw(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        verify(bgpRedistributeManager).withdrawHost6(eq("2a13:8740:0:7::100"), eq(100L), eq(1L));
        verify(bgpRedistributeManager, never()).announce(anyString(), anyLong(), anyLong(), anyLong());
        verify(bgpRedistributeManager, never()).announceHost6(anyString(), anyLong(), anyLong());
    }

    @Test
    public void v4OnlyWhenNoV6() {
        dualLb.setPublicIpv6AddressId(null);
        final LoadBalancingRule v4Only = new LoadBalancingRule(dualLb, List.of(), List.of(), List.of(),
                new Ip("1.2.3.4"), null, "tcp");
        final DsrSoftwareLbService.DualStackBgpResult r = service.withdrawCtLbBgpDualStack(network, v4Only);
        assertTrue(r.ok);
        assertTrue(r.withdrewV4);
        assertFalse(r.withdrewV6);
        verify(bgpRedistributeManager).withdraw(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        verify(bgpRedistributeManager, never()).withdrawHost6(anyString(), anyLong(), anyLong());
    }

    @Test
    public void partialV6FailureRollsBackV4() {
        doThrow(new RuntimeException("v6 agent down")).when(bgpRedistributeManager)
                .withdrawHost6(eq("2a13:8740:0:7::100"), eq(100L), eq(1L));
        final DsrSoftwareLbService.DualStackBgpResult r = service.withdrawCtLbBgpDualStack(network, dualRule);
        assertFalse(r.ok);
        assertTrue(r.rolledBack);
        verify(bgpRedistributeManager).withdraw(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        verify(bgpRedistributeManager).announce(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        verify(bgpRedistributeManager, never()).announceHost6(anyString(), anyLong(), anyLong());
    }

    @Test
    public void partialV4FailureDoesNotWithdrawV6WithoutRollbackOfV6() {
        doThrow(new RuntimeException("v4 agent down")).when(bgpRedistributeManager)
                .withdraw(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        // v6 still attempted after v4 failure in current ordering — if v6 succeeds, rollback re-announces v6
        final DsrSoftwareLbService.DualStackBgpResult r = service.withdrawCtLbBgpDualStack(network, dualRule);
        assertFalse(r.ok);
        verify(bgpRedistributeManager).withdrawHost6(eq("2a13:8740:0:7::100"), eq(100L), eq(1L));
        verify(bgpRedistributeManager).announceHost6(eq("2a13:8740:0:7::100"), eq(100L), eq(1L));
        verify(bgpRedistributeManager, never()).announce(eq("1.2.3.4"), anyLong(), anyLong(), anyLong());
    }

    @Test
    public void idempotentWhenNoBgpManager() {
        service = new DsrSoftwareLbService();
        // no injects — bgp null
        final DsrSoftwareLbService.DualStackBgpResult r = service.withdrawCtLbBgpDualStack(network, dualRule);
        assertTrue(r.ok);
    }

    @Test
    public void restoreReannouncesBoth() {
        service.restoreCtLbBgpDualStack(network, dualRule);
        verify(bgpRedistributeManager).announce(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        verify(bgpRedistributeManager).announceHost6(eq("2a13:8740:0:7::100"), eq(100L), eq(1L));
    }

    @Test
    public void restoreIdempotent() {
        service.restoreCtLbBgpDualStack(network, dualRule);
        service.restoreCtLbBgpDualStack(network, dualRule);
        verify(bgpRedistributeManager, times(2)).announce(eq("1.2.3.4"), eq(10L), eq(100L), eq(1L));
        verify(bgpRedistributeManager, times(2)).announceHost6(eq("2a13:8740:0:7::100"), eq(100L), eq(1L));
    }
}
