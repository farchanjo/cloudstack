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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
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
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.utils.net.Ip;

/**
 * Asserts the CloudStack {@link LoadBalancingRule} -> OVN load_balancer
 * translation matrix and the lifecycle behaviour (create / update /
 * revoke).
 */
public class OvnLoadBalancerServiceTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final long VPC_ID = 9L;
    private static final String LR_UUID = "lr-uuid-vpc-9";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private OvnLoadBalancerService service;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        network = mock(Network.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getVpcId()).thenReturn(VPC_ID);

        // VPC -> LR mapping must exist in the DAO for the LB to be attached.
        final OvnLogicalIdMapVO vpcMap = mock(OvnLogicalIdMapVO.class);
        when(vpcMap.getOvnUuid()).thenReturn(LR_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(vpcMap);

        service = new OvnLoadBalancerService();
        injectField(service, "pluginManager", pluginManager);
        injectField(service, "logicalIdMapDao", logicalIdMapDao);
        injectField(service, "ipAddressDao", mock(IPAddressDao.class));
        injectField(service, "bgpRedistributeManager", mock(OvnBgpRedistributeManager.class));
    }

    @Test
    public void roundRobinTcpRuleProducesLbAndAttach() throws Exception {
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), anyList(), anyMap(), anyMap()))
                .thenReturn("lb-uuid-1");

        final LoadBalancingRule rule = lbRule(401L, "192.168.100.10", 80, 80,
                List.of(dest("10.0.0.5", 80, false), dest("10.0.0.6", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Add, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        final ArgumentCaptor<Map<String, String>> vipCaptor = mapCaptor();
        final ArgumentCaptor<List<String>> selCaptor = listCaptor();
        final ArgumentCaptor<String> protoCaptor = ArgumentCaptor.forClass(String.class);

        final ArgumentCaptor<Map<String, String>> optsCaptor = mapCaptor();
        verify(nbClient, times(1)).createLoadBalancer(anyString(), vipCaptor.capture(),
                protoCaptor.capture(), selCaptor.capture(), anyMap(), optsCaptor.capture());
        verify(nbClient, times(1)).attachLoadBalancerToLogicalRouter(eq(LR_UUID), eq("lb-uuid-1"));
        // Hairpin SNAT IP must equal the LB VIP so backends hitting their
        // own VIP get reflected back through the LR.
        assertEquals("192.168.100.10", optsCaptor.getValue().get("hairpin_snat_ip"));
        verify(logicalIdMapDao, times(1)).persist(any(OvnLogicalIdMapVO.class));

        final Map<String, String> vips = vipCaptor.getValue();
        assertEquals(1, vips.size());
        assertTrue(vips.containsKey("192.168.100.10:80"));
        assertEquals("10.0.0.5:80,10.0.0.6:80", vips.get("192.168.100.10:80"));
        assertTrue(selCaptor.getValue().isEmpty());
        assertEquals("tcp", protoCaptor.getValue());
    }

    @Test
    public void sourceHashAlgorithmProducesSelectionFields() throws Exception {
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), anyList(), anyMap(), anyMap()))
                .thenReturn("lb-uuid-2");

        final LoadBalancingRule rule = lbRule(402L, "192.168.100.20", 443, 443,
                List.of(dest("10.0.0.50", 443, false)),
                "ssl", "tcp", "source-hash", FirewallRule.State.Add, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        final ArgumentCaptor<List<String>> selCaptor = listCaptor();
        final ArgumentCaptor<String> protoCaptor = ArgumentCaptor.forClass(String.class);
        verify(nbClient).createLoadBalancer(anyString(), anyMap(), protoCaptor.capture(), selCaptor.capture(), anyMap(), anyMap());
        // ssl maps to tcp on the wire.
        assertEquals("tcp", protoCaptor.getValue());
        assertFalse("selection_fields must be present", selCaptor.getValue().isEmpty());
        assertTrue(selCaptor.getValue().contains("ip_src"));
        assertTrue(selCaptor.getValue().contains("tcp_src"));
    }

    @Test
    public void leastConnAlgorithmIsRejectedByValidation() {
        final LoadBalancingRule rule = lbRule(403L, "192.168.100.30", 22, 22,
                List.of(dest("10.0.0.7", 22, false)),
                "tcp", "tcp", "leastconn", FirewallRule.State.Add, List.of());
        assertFalse(service.validateLBRule(network, rule));
    }

    @Test
    public void backendUpdateOnExistingMappingCallsUpdateBackends() throws Exception {
        // Mapping already exists.
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getOvnUuid()).thenReturn("lb-uuid-existing");
        when(logicalIdMapDao.findByCsId(eq(Kind.LOAD_BALANCER), eq(404L), eq(CONTROLLER_ID))).thenReturn(existing);
        // The OVN row must still exist so applyOne takes the update-backends
        // fast path instead of falling through to recreate.
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-existing"))).thenReturn(true);

        final LoadBalancingRule rule = lbRule(404L, "192.168.100.40", 80, 80,
                List.of(dest("10.0.0.5", 80, false), dest("10.0.0.6", 80, false), dest("10.0.0.7", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Active, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        verify(nbClient, never()).createLoadBalancer(anyString(), anyMap(), anyString(), anyList(), anyMap(), anyMap());
        final ArgumentCaptor<Map<String, String>> capt = mapCaptor();
        verify(nbClient, times(1)).updateLoadBalancerBackends(eq("lb-uuid-existing"), capt.capture());
        assertEquals("10.0.0.5:80,10.0.0.6:80,10.0.0.7:80", capt.getValue().get("192.168.100.40:80"));
    }

    @Test
    public void revokeRemovesMappingAndDeletesLb() throws Exception {
        final OvnLogicalIdMapVO mapping = mock(OvnLogicalIdMapVO.class);
        when(mapping.getId()).thenReturn(909L);
        when(mapping.getOvnUuid()).thenReturn("lb-uuid-revoke");
        when(logicalIdMapDao.findByCsId(eq(Kind.LOAD_BALANCER), eq(405L), eq(CONTROLLER_ID))).thenReturn(mapping);

        final LoadBalancingRule rule = lbRule(405L, "192.168.100.50", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Revoke, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        verify(nbClient, times(1)).detachLoadBalancerFromLogicalRouter(eq(LR_UUID), eq("lb-uuid-revoke"));
        verify(nbClient, times(1)).deleteLoadBalancer(eq("lb-uuid-revoke"));
        verify(logicalIdMapDao, times(1)).remove(eq(909L));
    }

    @Test
    public void revokedDestinationsAreFilteredFromVipsMap() {
        final LoadBalancingRule rule = lbRule(406L, "192.168.100.60", 80, 80,
                List.of(dest("10.0.0.5", 80, false), dest("10.0.0.99", 80, true), dest("10.0.0.6", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Add, List.of());

        final Map<String, String> vips = OvnLoadBalancerService.buildVipsMap(rule);
        assertEquals("10.0.0.5:80,10.0.0.6:80", vips.get("192.168.100.60:80"));
    }

    @Test
    public void udpAndSctpProtocolsMapStraightThrough() {
        final LoadBalancingRule udp = lbRule(407L, "192.168.100.70", 53, 53,
                List.of(dest("10.0.0.5", 53, false)),
                "udp", "udp", "roundrobin", FirewallRule.State.Add, List.of());
        assertEquals("udp", OvnLoadBalancerService.protocolFor(udp));

        final LoadBalancingRule sctp = lbRule(408L, "192.168.100.80", 22, 22,
                List.of(dest("10.0.0.5", 22, false)),
                "sctp", "sctp", "roundrobin", FirewallRule.State.Add, List.of());
        assertEquals("sctp", OvnLoadBalancerService.protocolFor(sctp));
    }

    @Test
    public void unknownProtocolFallsBackToNullProtocol() {
        final LoadBalancingRule rule = lbRule(409L, "192.168.100.90", 9, 9,
                List.of(dest("10.0.0.5", 9, false)),
                "esp", "esp", "roundrobin", FirewallRule.State.Add, List.of());
        assertNull(OvnLoadBalancerService.protocolFor(rule));
    }

    @Test
    public void missingVpcLrShortCircuits() throws Exception {
        when(network.getVpcId()).thenReturn(null);

        final LoadBalancingRule rule = lbRule(410L, "192.168.100.100", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Add, List.of());

        assertFalse(service.applyLBRules(network, List.of(rule)));
        verify(nbClient, never()).createLoadBalancer(anyString(), anyMap(), anyString(), anyList(), anyMap(), anyMap());
    }

    @Test
    public void buildLbOptionsEmitsHairpinSnatIp() {
        final LoadBalancingRule rule = lbRule(420L, "203.0.113.42", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Add, List.of());
        final Map<String, String> opts = service.buildLbOptions(rule, network);
        assertEquals("203.0.113.42", opts.get("hairpin_snat_ip"));
    }

    @Test
    public void selectionFieldsForRoundRobinIsEmpty() {
        final LoadBalancingRule rule = lbRule(411L, "1.1.1.1", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Add, List.of());
        assertTrue(OvnLoadBalancerService.selectionFieldsFor(rule).isEmpty());
    }

    @Test
    public void capabilitiesDeclareLb() {
        assertNotNull(service.getCapabilities());
        assertTrue(service.getCapabilities().containsKey(com.cloud.network.Network.Service.Lb));
    }

    private static LoadBalancingRule lbRule(final long id, final String vipIp, final int srcPortStart,
                                            final int srcPortEnd, final List<LbDestination> dests,
                                            final String protocol, final String lbProtocol, final String algorithm,
                                            final FirewallRule.State state,
                                            final List<LoadBalancingRule.LbStickinessPolicy> stickiness) {
        final LoadBalancer lb = mock(LoadBalancer.class);
        when(lb.getId()).thenReturn(id);
        when(lb.getUuid()).thenReturn("uuid-" + id);
        when(lb.getName()).thenReturn("lb-" + id);
        when(lb.getSourcePortStart()).thenReturn(srcPortStart);
        when(lb.getSourcePortEnd()).thenReturn(srcPortEnd);
        when(lb.getDefaultPortStart()).thenReturn(srcPortStart);
        when(lb.getDefaultPortEnd()).thenReturn(srcPortEnd);
        when(lb.getProtocol()).thenReturn(protocol);
        when(lb.getAlgorithm()).thenReturn(algorithm);
        when(lb.getState()).thenReturn(state);
        when(lb.getNetworkId()).thenReturn(NETWORK_ID);

        return new LoadBalancingRule(lb, dests, stickiness, List.of(), new Ip(vipIp), null, lbProtocol);
    }

    private static LbDestination dest(final String ip, final int port, final boolean revoked) {
        return new LbDestination(port, port, ip, revoked);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<String>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
