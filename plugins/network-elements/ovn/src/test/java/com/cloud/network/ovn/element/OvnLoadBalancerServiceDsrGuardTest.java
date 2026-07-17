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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.utils.net.Ip;
import com.cloud.vm.dao.NicDao;

/**
 * Guards: CT_LB service must never create OVN Load_Balancer for DSR_SOFTWARE.
 */
@RunWith(MockitoJUnitRunner.class)
public class OvnLoadBalancerServiceDsrGuardTest {

    @Mock
    private OvnPluginManager pluginManager;
    @Mock
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private IPAddressDao ipAddressDao;
    @Mock
    private OvnBgpRedistributeManager bgpRedistributeManager;
    @Mock
    private OvnPendingDeletionDao pendingDeletionDao;
    @Mock
    private NetworkDetailsDao networkDetailsDao;
    @Mock
    private NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Mock
    private NicDao nicDao;
    @Mock
    private Network network;
    @Mock
    private OvnNbClient nb;
    @Mock
    private OvnControllerVO controller;

    @InjectMocks
    private OvnLoadBalancerService service;

    @Before
    public void setUp() {
        lenient().when(network.getDataCenterId()).thenReturn(1L);
        lenient().when(network.getId()).thenReturn(10L);
        lenient().when(network.getVpcId()).thenReturn(100L);
        lenient().when(pluginManager.findControllerForZone(1L)).thenReturn(controller);
        lenient().when(controller.getId()).thenReturn(1L);
        lenient().when(pluginManager.nbClient(1L)).thenReturn(nb);
        OvnLogicalIdMapVO lrMap = org.mockito.Mockito.mock(OvnLogicalIdMapVO.class);
        lenient().when(lrMap.getOvnUuid()).thenReturn("lr-uuid");
        lenient().when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(100L), eq(1L))).thenReturn(lrMap);
    }

    @Test
    public void validateRejectsDsr() {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"));
        assertFalse(service.validateLBRule(network, rule));
    }

    @Test
    public void applyRefusesDsrWithoutCreateLoadBalancer() throws Exception {
        LoadBalancerVO lb = new LoadBalancerVO("x", "dsr", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"));

        assertFalse(service.applyLBRules(network, List.of(rule)));
        verify(nb, never()).createLoadBalancer(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    public void validateAcceptsCtLbRoundRobin() {
        LoadBalancerVO lb = new LoadBalancerVO("x", "ct", "d", 1L, 80, 8080, "roundrobin", 10L, 1L, 1L, "tcp", null);
        lb.setLbKind(LbKind.CT_LB);
        LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(), new Ip("1.2.3.4"));
        assertTrue(service.validateLBRule(network, rule));
    }
}
