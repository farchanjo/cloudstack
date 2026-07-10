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
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.utils.net.Ip;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

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

    private static final long IP_ADDR_ID = 77L;
    private static final String PUBLIC_IP = "192.168.100.40";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private NicDao nicDao;
    private IPAddressDao ipAddressDao;
    private OvnBgpRedistributeManager bgpRedistributeManager;
    private OvnLoadBalancerService service;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        network = mock(Network.class);
        nicDao = mock(NicDao.class);
        ipAddressDao = mock(IPAddressDao.class);
        bgpRedistributeManager = mock(OvnBgpRedistributeManager.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getVpcId()).thenReturn(VPC_ID);
        // /24 with free top hosts for health-check probe source IP.
        when(network.getCidr()).thenReturn("10.0.0.0/24");
        // No NICs occupy the probe-source candidates by default.
        when(nicDao.findByIp4AddressAndNetworkId(anyString(), eq(NETWORK_ID))).thenReturn(null);

        // VPC -> LR mapping must exist in the DAO for the LB to be attached.
        final OvnLogicalIdMapVO vpcMap = mock(OvnLogicalIdMapVO.class);
        when(vpcMap.getOvnUuid()).thenReturn(LR_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(vpcMap);

        service = new OvnLoadBalancerService();
        injectField(service, "pluginManager", pluginManager);
        injectField(service, "logicalIdMapDao", logicalIdMapDao);
        injectField(service, "ipAddressDao", ipAddressDao);
        injectField(service, "bgpRedistributeManager", bgpRedistributeManager);
        injectField(service, "pendingDeletionDao", mock(OvnPendingDeletionDao.class));
        injectField(service, "nicDao", nicDao);
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
        // OVN selection_fields use tp_src / tp_dst (not tcp_*), see ovn-nb(5).
        assertTrue(selCaptor.getValue().contains("tp_src"));
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
        stubSourceIpRow(PUBLIC_IP, IP_ADDR_ID);

        final LoadBalancingRule rule = lbRule(404L, PUBLIC_IP, 80, 80,
                List.of(dest("10.0.0.5", 80, false), dest("10.0.0.6", 80, false), dest("10.0.0.7", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Active, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        verify(nbClient, never()).createLoadBalancer(anyString(), anyMap(), anyString(), anyList(), anyMap(), anyMap());
        final ArgumentCaptor<Map<String, String>> capt = mapCaptor();
        verify(nbClient, times(1)).updateLoadBalancerBackends(eq("lb-uuid-existing"), capt.capture());
        assertEquals("10.0.0.5:80,10.0.0.6:80,10.0.0.7:80", capt.getValue().get("192.168.100.40:80"));
        // Update path must self-heal the BGP /32 announce for the VIP.
        verify(bgpRedistributeManager, times(1)).announce(eq(PUBLIC_IP), eq(IP_ADDR_ID), eq(VPC_ID), eq(ZONE_ID));
    }

    @Test
    public void createPathAnnouncesLbVip() throws Exception {
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), anyList(), anyMap(), anyMap()))
                .thenReturn("lb-uuid-create");
        stubSourceIpRow("192.168.100.10", IP_ADDR_ID);

        final LoadBalancingRule rule = lbRule(401L, "192.168.100.10", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Add, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));
        verify(bgpRedistributeManager, times(1)).announce(eq("192.168.100.10"), eq(IP_ADDR_ID), eq(VPC_ID), eq(ZONE_ID));
    }

    @Test
    public void revokePathWithdrawsLbVip() throws Exception {
        final OvnLogicalIdMapVO mapping = mock(OvnLogicalIdMapVO.class);
        when(mapping.getId()).thenReturn(909L);
        when(mapping.getOvnUuid()).thenReturn("lb-uuid-revoke");
        when(logicalIdMapDao.findByCsId(eq(Kind.LOAD_BALANCER), eq(405L), eq(CONTROLLER_ID))).thenReturn(mapping);
        stubSourceIpRow("192.168.100.50", IP_ADDR_ID);

        final LoadBalancingRule rule = lbRule(405L, "192.168.100.50", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Revoke, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));
        verify(bgpRedistributeManager, times(1)).withdraw(eq("192.168.100.50"), eq(IP_ADDR_ID), eq(VPC_ID), eq(ZONE_ID));
    }

    /**
     * Chaos regression: destroy+recreate of an LB member updates {@code vips}
     * correctly but previously left {@code ip_port_mappings} stale because
     * health-check configure early-returned when an HC row already existed.
     * Every re-apply must full-replace mappings from current destinations only.
     */
    @Test
    public void existingLbWithHealthCheckFullReplacesIpPortMappingsOnMemberRecreate() throws Exception {
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getOvnUuid()).thenReturn("lb-uuid-hc");
        when(logicalIdMapDao.findByCsId(eq(Kind.LOAD_BALANCER), eq(430L), eq(CONTROLLER_ID))).thenReturn(existing);
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-hc"))).thenReturn(true);
        when(nbClient.loadBalancerHasHealthCheck(eq("lb-uuid-hc"))).thenReturn(true);

        // Old member 10.0.0.5 gone; recreated member is 10.0.0.8 with a new NIC.
        stubBackendNic("10.0.0.6", "nic-keep");
        stubBackendNic("10.0.0.8", "nic-recreated");

        final LoadBalancingRule rule = lbRule(430L, "192.168.100.40", 6443, 6443,
                List.of(dest("10.0.0.6", 6443, false), dest("10.0.0.8", 6443, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Active, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        final ArgumentCaptor<Map<String, String>> vipsCapt = mapCaptor();
        verify(nbClient).updateLoadBalancerBackends(eq("lb-uuid-hc"), vipsCapt.capture());
        assertEquals("10.0.0.6:6443,10.0.0.8:6443", vipsCapt.getValue().get("192.168.100.40:6443"));

        final ArgumentCaptor<Map<String, String>> mapCapt = mapCaptor();
        verify(nbClient, times(1)).updateLoadBalancerIpPortMappings(eq("lb-uuid-hc"), mapCapt.capture());
        // Full desired state only — stale 10.0.0.5 must not appear, new 10.0.0.8 must.
        final Map<String, String> mappings = mapCapt.getValue();
        assertEquals(2, mappings.size());
        assertFalse(mappings.containsKey("10.0.0.5"));
        assertEquals("lsp-nic-keep:10.0.0.254", mappings.get("10.0.0.6"));
        assertEquals("lsp-nic-recreated:10.0.0.254", mappings.get("10.0.0.8"));
        // Must not re-insert a second HC row when one already exists.
        verify(nbClient, never()).configureLoadBalancerHealthCheck(
                anyString(), anyString(), anyMap(), anyMap(), anyMap());
    }

    @Test
    public void existingLbWithHealthCheckDropsMappingWhenMemberRemoved() throws Exception {
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getOvnUuid()).thenReturn("lb-uuid-drop");
        when(logicalIdMapDao.findByCsId(eq(Kind.LOAD_BALANCER), eq(431L), eq(CONTROLLER_ID))).thenReturn(existing);
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-drop"))).thenReturn(true);
        when(nbClient.loadBalancerHasHealthCheck(eq("lb-uuid-drop"))).thenReturn(true);

        stubBackendNic("10.0.0.6", "nic-keep");
        // 10.0.0.5 is revoked — must leave the rebuilt map.

        final LoadBalancingRule rule = lbRule(431L, "192.168.100.40", 80, 80,
                List.of(dest("10.0.0.5", 80, true), dest("10.0.0.6", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Active, List.of());

        assertTrue(service.applyLBRules(network, List.of(rule)));

        final ArgumentCaptor<Map<String, String>> mapCapt = mapCaptor();
        verify(nbClient).updateLoadBalancerIpPortMappings(eq("lb-uuid-drop"), mapCapt.capture());
        final Map<String, String> mappings = mapCapt.getValue();
        assertEquals(1, mappings.size());
        assertNull(mappings.get("10.0.0.5"));
        assertEquals("lsp-nic-keep:10.0.0.254", mappings.get("10.0.0.6"));
    }

    @Test
    public void buildIpPortMappingsReturnsEmptyWhenAllDestinationsRevoked() {
        final LoadBalancingRule rule = lbRule(432L, "192.168.100.40", 80, 80,
                List.of(dest("10.0.0.5", 80, true), dest("10.0.0.6", 80, true)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Active, List.of());

        final Map<String, String> mappings = service.buildIpPortMappings(network, rule);
        assertNotNull(mappings);
        assertTrue(mappings.isEmpty());
    }

    @Test
    public void buildIpPortMappingsReturnsNullWhenLiveBackendNicMissing() {
        // Default nicDao returns null for all IPs — live dest cannot resolve.
        final LoadBalancingRule rule = lbRule(433L, "192.168.100.40", 80, 80,
                List.of(dest("10.0.0.5", 80, false)),
                "tcp", "tcp", "roundrobin", FirewallRule.State.Active, List.of());

        assertNull(service.buildIpPortMappings(network, rule));
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
        when(lb.getSourceIpAddressId()).thenReturn(IP_ADDR_ID);

        return new LoadBalancingRule(lb, dests, stickiness, List.of(), new Ip(vipIp), null, lbProtocol);
    }

    private static LbDestination dest(final String ip, final int port, final boolean revoked) {
        return new LbDestination(port, port, ip, revoked);
    }

    private void stubSourceIpRow(final String addr, final long ipId) {
        final IPAddressVO row = mock(IPAddressVO.class);
        when(row.getId()).thenReturn(ipId);
        when(row.getAddress()).thenReturn(new Ip(addr));
        when(ipAddressDao.findById(eq(ipId))).thenReturn(row);
    }

    private void stubBackendNic(final String ip, final String nicUuid) {
        final NicVO nic = mock(NicVO.class);
        when(nic.getUuid()).thenReturn(nicUuid);
        when(nicDao.findByIp4AddressAndNetworkId(eq(ip), eq(NETWORK_ID))).thenReturn(nic);
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
