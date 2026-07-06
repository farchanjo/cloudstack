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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.net.Ip;

/**
 * Asserts the CloudStack {@link PortForwardingRule} -> OVN
 * {@code Load_Balancer} translation (VIP {@code ext_ip:ext_port -> priv_ip:
 * priv_port}), plus the legacy {@code dnat_and_snat}-with-port self-heal on
 * both apply (migration) and revoke (match-based cleanup).
 */
public class OvnPortForwardingServiceTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final long VPC_ID = 9L;
    private static final long IP_ADDRESS_ID = 42L;
    private static final String LR_UUID = "lr-uuid-vpc-9";
    private static final String PUBLIC_IP = "217.179.89.42";
    private static final String PRIVATE_IP = "10.99.10.137";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private VpcDao vpcDao;
    private OvnVpcElement vpcElement;
    private IPAddressDao ipAddressDao;
    private OvnBgpRedistributeManager bgpRedistributeManager;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnPortForwardingService service;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        network = mock(Network.class);
        vpcDao = mock(VpcDao.class);
        vpcElement = mock(OvnVpcElement.class);
        ipAddressDao = mock(IPAddressDao.class);
        bgpRedistributeManager = mock(OvnBgpRedistributeManager.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getVpcId()).thenReturn(VPC_ID);

        // VPC -> LR mapping must exist in the DAO so the PF lands on the right LR.
        final VpcVO vpc = mock(VpcVO.class);
        when(vpc.getId()).thenReturn(VPC_ID);
        when(vpcDao.findById(VPC_ID)).thenReturn(vpc);

        final OvnLogicalIdMapVO vpcMap = mock(OvnLogicalIdMapVO.class);
        when(vpcMap.getOvnUuid()).thenReturn(LR_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(vpcMap);

        // Public IP row.
        final IPAddressVO publicIpRow = mock(IPAddressVO.class);
        when(publicIpRow.getAddress()).thenReturn(new Ip(PUBLIC_IP));
        when(ipAddressDao.findById(IP_ADDRESS_ID)).thenReturn(publicIpRow);

        service = new OvnPortForwardingService();
        injectField(service, "pluginManager", pluginManager);
        injectField(service, "logicalIdMapDao", logicalIdMapDao);
        injectField(service, "vpcDao", vpcDao);
        injectField(service, "vpcElement", vpcElement);
        injectField(service, "ipAddressDao", ipAddressDao);
        injectField(service, "bgpRedistributeManager", bgpRedistributeManager);
        injectField(service, "pendingDeletionDao", pendingDeletionDao);
    }

    @Test
    public void freshPfRuleEmitsLoadBalancerAndPersistsMapping() throws Exception {
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), any(), anyMap(), isNull()))
                .thenReturn("lb-uuid-1");

        final PortForwardingRule rule = pfRule(526L, 2222, 2222, 22, 22, FirewallRule.State.Add);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        final ArgumentCaptor<Map<String, String>> vipsCaptor = mapCaptor();
        final ArgumentCaptor<Map<String, String>> extCaptor = mapCaptor();
        verify(nbClient, times(1)).createLoadBalancer(eq(OvnPortForwardingService.PF_LB_NAME_PREFIX + "526"),
                vipsCaptor.capture(), eq(OvnNbClient.LB_PROTOCOL_TCP), any(), extCaptor.capture(), isNull());
        verify(nbClient, times(1)).attachLoadBalancerToLogicalRouter(eq(LR_UUID), eq("lb-uuid-1"));
        verify(nbClient, never()).addNatRule(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), anyMap());
        verify(logicalIdMapDao, times(1)).persist(any(OvnLogicalIdMapVO.class));

        final Map<String, String> vips = vipsCaptor.getValue();
        assertEquals(1, vips.size());
        assertEquals(PRIVATE_IP + ":22", vips.get(PUBLIC_IP + ":2222"));

        final Map<String, String> ext = extCaptor.getValue();
        assertNotNull(ext);
        assertEquals(Kind.PORT_FORWARDING.name(), ext.get(OvnConstants.EXT_ID_KIND));
        assertEquals("526", ext.get(OvnConstants.EXT_ID_ID));
        assertEquals(String.valueOf(ZONE_ID), ext.get(OvnConstants.EXT_ID_ZONE));
    }

    @Test
    public void portRangeExpandsToPerPortVips() throws Exception {
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), any(), anyMap(), isNull()))
                .thenReturn("lb-uuid-range");

        final PortForwardingRule rule = pfRule(530L, 8080, 8082, 80, 82, FirewallRule.State.Add);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        final ArgumentCaptor<Map<String, String>> vipsCaptor = mapCaptor();
        verify(nbClient, times(1)).createLoadBalancer(anyString(), vipsCaptor.capture(), anyString(),
                any(), anyMap(), isNull());

        final Map<String, String> vips = vipsCaptor.getValue();
        assertEquals(3, vips.size());
        assertEquals(PRIVATE_IP + ":80", vips.get(PUBLIC_IP + ":8080"));
        assertEquals(PRIVATE_IP + ":81", vips.get(PUBLIC_IP + ":8081"));
        assertEquals(PRIVATE_IP + ":82", vips.get(PUBLIC_IP + ":8082"));
    }

    @Test
    public void oversizePortRangeIsRejected() throws Exception {
        final PortForwardingRule rule = pfRule(531L, 1, 100, 22, 22, FirewallRule.State.Add);

        // A single failing rule collapses the batch result to false and emits no LB.
        assertFalse(service.applyPFRules(network, List.of(rule)));
        verify(nbClient, never()).createLoadBalancer(anyString(), anyMap(), anyString(),
                any(), anyMap(), isNull());
    }

    @Test
    public void revokeDeletesLbAndDropsMapping() throws Exception {
        final OvnLogicalIdMapVO mapping = mock(OvnLogicalIdMapVO.class);
        when(mapping.getId()).thenReturn(909L);
        when(mapping.getOvnUuid()).thenReturn("lb-uuid-revoke");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(527L), eq(CONTROLLER_ID))).thenReturn(mapping);
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-revoke"))).thenReturn(true);

        final PortForwardingRule rule = pfRule(527L, 80, 80, 8080, 8080, FirewallRule.State.Revoke);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        verify(nbClient, times(1)).detachLoadBalancerFromLogicalRouter(eq(LR_UUID), eq("lb-uuid-revoke"));
        verify(nbClient, times(1)).deleteLoadBalancer(eq("lb-uuid-revoke"));
        verify(nbClient, never()).deleteNatRule(anyString());
        verify(logicalIdMapDao, times(1)).remove(eq(909L));
        // Match-based legacy sweep always runs (belt-and-suspenders self-heal).
        verify(nbClient, times(1)).deleteNatByMatch(eq(OvnPortForwardingService.LEGACY_NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq("80"), eq(PRIVATE_IP));
    }

    @Test
    public void legacyNatPfMigratesToLbOnApply() throws Exception {
        // Pre-existing mapping points at a legacy dnat_and_snat NAT row.
        final OvnLogicalIdMapVO legacy = mock(OvnLogicalIdMapVO.class);
        when(legacy.getId()).thenReturn(810L);
        when(legacy.getOvnUuid()).thenReturn("nat-uuid-legacy");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(528L), eq(CONTROLLER_ID))).thenReturn(legacy);

        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("nat-uuid-legacy"))).thenReturn(false);
        when(nbClient.rowExistsByUuid(eq("NAT"), eq("nat-uuid-legacy"))).thenReturn(true);
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), any(), anyMap(), isNull()))
                .thenReturn("lb-uuid-new");

        final PortForwardingRule rule = pfRule(528L, 2222, 2222, 22, 22, FirewallRule.State.Active);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        // Legacy NAT row dropped, mapping removed, fresh LB created + persisted.
        verify(nbClient, times(1)).deleteNatRule(eq("nat-uuid-legacy"));
        verify(logicalIdMapDao, times(1)).remove(eq(810L));
        verify(nbClient, times(1)).createLoadBalancer(eq(OvnPortForwardingService.PF_LB_NAME_PREFIX + "528"),
                anyMap(), eq(OvnNbClient.LB_PROTOCOL_TCP), any(), anyMap(), isNull());
        verify(nbClient, times(1)).attachLoadBalancerToLogicalRouter(eq(LR_UUID), eq("lb-uuid-new"));
        verify(logicalIdMapDao, times(1)).persist(any(OvnLogicalIdMapVO.class));
    }

    @Test
    public void existingLbMappingResyncsBackendsOnApply() throws Exception {
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getId()).thenReturn(901L);
        when(existing.getOvnUuid()).thenReturn("lb-uuid-existing");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(529L), eq(CONTROLLER_ID))).thenReturn(existing);
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-existing"))).thenReturn(true);

        final PortForwardingRule rule = pfRule(529L, 2222, 2222, 22, 22, FirewallRule.State.Active);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        final ArgumentCaptor<Map<String, String>> vipsCaptor = mapCaptor();
        verify(nbClient, times(1)).updateLoadBalancerBackends(eq("lb-uuid-existing"), vipsCaptor.capture());
        assertEquals(PRIVATE_IP + ":22", vipsCaptor.getValue().get(PUBLIC_IP + ":2222"));
        verify(nbClient, never()).createLoadBalancer(anyString(), anyMap(), anyString(), any(), anyMap(), isNull());
        verify(logicalIdMapDao, never()).persist(any(OvnLogicalIdMapVO.class));
        verify(logicalIdMapDao, never()).remove(eq(901L));
    }

    @Test
    public void freshPfRuleAlsoAttachesToTierLs() throws Exception {
        final OvnLogicalIdMapVO lsMap = mock(OvnLogicalIdMapVO.class);
        when(lsMap.getOvnUuid()).thenReturn("ls-uuid-tier");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(lsMap);
        when(nbClient.createLoadBalancer(anyString(), anyMap(), anyString(), any(), anyMap(), isNull()))
                .thenReturn("lb-uuid-ls");

        final PortForwardingRule rule = pfRule(541L, 2222, 2222, 22, 22, FirewallRule.State.Add);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        verify(nbClient, times(1)).attachLoadBalancerToLogicalRouter(eq(LR_UUID), eq("lb-uuid-ls"));
        verify(nbClient, times(1)).attachLoadBalancerToLogicalSwitch(eq("ls-uuid-tier"), eq("lb-uuid-ls"));
    }

    @Test
    public void revokeDetachesFromTierLsToo() throws Exception {
        final OvnLogicalIdMapVO lsMap = mock(OvnLogicalIdMapVO.class);
        when(lsMap.getOvnUuid()).thenReturn("ls-uuid-tier");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(lsMap);
        final OvnLogicalIdMapVO mapping = mock(OvnLogicalIdMapVO.class);
        when(mapping.getId()).thenReturn(910L);
        when(mapping.getOvnUuid()).thenReturn("lb-uuid-rls");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(542L), eq(CONTROLLER_ID))).thenReturn(mapping);
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-rls"))).thenReturn(true);

        final PortForwardingRule rule = pfRule(542L, 80, 80, 8080, 8080, FirewallRule.State.Revoke);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        verify(nbClient, times(1)).detachLoadBalancerFromLogicalRouter(eq(LR_UUID), eq("lb-uuid-rls"));
        verify(nbClient, times(1)).detachLoadBalancerFromLogicalSwitch(eq("ls-uuid-tier"), eq("lb-uuid-rls"));
        verify(nbClient, times(1)).deleteLoadBalancer(eq("lb-uuid-rls"));
    }

    @Test
    public void revokeCleansUntrackedLegacyNatWhenMappingAbsent() throws Exception {
        // No mapping row for the rule — only the match-based legacy sweep runs.
        final PortForwardingRule rule = pfRule(540L, 2222, 2222, 22, 22, FirewallRule.State.Revoke);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        verify(nbClient, times(1)).deleteNatByMatch(eq(OvnPortForwardingService.LEGACY_NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq("2222"), eq(PRIVATE_IP));
        verify(nbClient, never()).deleteLoadBalancer(anyString());
        verify(nbClient, never()).deleteNatRule(anyString());
    }

    private PortForwardingRule pfRule(final long id, final int extStart, final int extEnd,
                                      final int privStart, final int privEnd, final FirewallRule.State state) {
        final PortForwardingRule rule = mock(PortForwardingRule.class);
        when(rule.getId()).thenReturn(id);
        when(rule.getState()).thenReturn(state);
        when(rule.getProtocol()).thenReturn("tcp");
        when(rule.getSourceIpAddressId()).thenReturn(IP_ADDRESS_ID);
        when(rule.getSourcePortStart()).thenReturn(extStart);
        when(rule.getSourcePortEnd()).thenReturn(extEnd);
        when(rule.getDestinationPortStart()).thenReturn(privStart);
        when(rule.getDestinationPortEnd()).thenReturn(privEnd);
        when(rule.getDestinationIpAddress()).thenReturn(new Ip(PRIVATE_IP));
        return rule;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
