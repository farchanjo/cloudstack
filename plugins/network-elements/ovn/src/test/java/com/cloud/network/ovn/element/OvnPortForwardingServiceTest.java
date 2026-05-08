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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
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
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.net.Ip;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

/**
 * Asserts the CloudStack {@link PortForwardingRule} -> OVN {@code NAT}
 * (type {@code dnat_and_snat}) translation, including hot upgrade migration
 * from the previous {@code Load_Balancer}-based PF shape.
 */
public class OvnPortForwardingServiceTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final long VPC_ID = 9L;
    private static final long NIC_ID = 555L;
    private static final long IP_ADDRESS_ID = 42L;
    private static final long VM_ID = 77L;
    private static final String LR_UUID = "lr-uuid-vpc-9";
    private static final String NIC_UUID = "nic-uuid-aa39e102";
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
    private NicDao nicDao;
    private OvnBgpRedistributeManager bgpRedistributeManager;
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
        nicDao = mock(NicDao.class);
        bgpRedistributeManager = mock(OvnBgpRedistributeManager.class);

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

        // VM NIC on the network.
        final NicVO nic = mock(NicVO.class);
        when(nic.getId()).thenReturn(NIC_ID);
        when(nic.getUuid()).thenReturn(NIC_UUID);
        when(nicDao.findNonReleasedByInstanceIdAndNetworkId(NETWORK_ID, VM_ID)).thenReturn(nic);

        service = new OvnPortForwardingService();
        injectField(service, "pluginManager", pluginManager);
        injectField(service, "logicalIdMapDao", logicalIdMapDao);
        injectField(service, "vpcDao", vpcDao);
        injectField(service, "vpcElement", vpcElement);
        injectField(service, "ipAddressDao", ipAddressDao);
        injectField(service, "nicDao", nicDao);
        injectField(service, "bgpRedistributeManager", bgpRedistributeManager);
    }

    @Test
    public void freshPfRuleEmitsDnatAndSnatNatRowAndPersistsMapping() throws Exception {
        when(nbClient.addNatRule(eq(LR_UUID), eq(OvnPortForwardingService.NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq(PRIVATE_IP), anyString(), anyString(), isNull(), anyMap()))
                .thenReturn("nat-uuid-1");

        final PortForwardingRule rule = pfRule(526L, 22, 22, FirewallRule.State.Add);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        final ArgumentCaptor<String> portCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> lspCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<Map<String, String>> extCaptor = mapCaptor();

        verify(nbClient, times(1)).addNatRule(eq(LR_UUID), eq(OvnPortForwardingService.NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq(PRIVATE_IP), lspCaptor.capture(), portCaptor.capture(), isNull(), extCaptor.capture());
        verify(nbClient, never()).createLoadBalancer(anyString(), anyMap(), anyString(), any(), anyMap());
        verify(logicalIdMapDao, times(1)).persist(any(OvnLogicalIdMapVO.class));

        assertEquals("22", portCaptor.getValue());
        assertEquals("lsp-" + NIC_UUID, lspCaptor.getValue());

        final Map<String, String> ext = extCaptor.getValue();
        assertNotNull(ext);
        assertEquals(Kind.PORT_FORWARDING.name(), ext.get(OvnConstants.EXT_ID_KIND));
        assertEquals("526", ext.get(OvnConstants.EXT_ID_ID));
        assertEquals(String.valueOf(ZONE_ID), ext.get(OvnConstants.EXT_ID_ZONE));
    }

    @Test
    public void revokeDeletesNatRowAndDropsMapping() throws Exception {
        final OvnLogicalIdMapVO mapping = mock(OvnLogicalIdMapVO.class);
        when(mapping.getId()).thenReturn(909L);
        when(mapping.getOvnUuid()).thenReturn("nat-uuid-revoke");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(527L), eq(CONTROLLER_ID))).thenReturn(mapping);
        when(nbClient.rowExistsByUuid(eq("NAT"), eq("nat-uuid-revoke"))).thenReturn(true);

        final PortForwardingRule rule = pfRule(527L, 80, 80, FirewallRule.State.Revoke);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        verify(nbClient, times(1)).deleteNatRule(eq("nat-uuid-revoke"));
        verify(nbClient, never()).deleteLoadBalancer(anyString());
        verify(logicalIdMapDao, times(1)).remove(eq(909L));
    }

    @Test
    public void legacyLbPfMigratesToNatOnApply() throws Exception {
        // Pre-existing mapping points at a Load_Balancer row from a pre-NAT plugin version.
        final OvnLogicalIdMapVO legacy = mock(OvnLogicalIdMapVO.class);
        when(legacy.getId()).thenReturn(810L);
        when(legacy.getOvnUuid()).thenReturn("lb-uuid-legacy");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(528L), eq(CONTROLLER_ID))).thenReturn(legacy);

        // OVSDB still hosts the LB row.
        when(nbClient.rowExistsByUuid(eq("Load_Balancer"), eq("lb-uuid-legacy"))).thenReturn(true);
        when(nbClient.rowExistsByUuid(eq("NAT"), eq("lb-uuid-legacy"))).thenReturn(false);

        when(nbClient.addNatRule(eq(LR_UUID), eq(OvnPortForwardingService.NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq(PRIVATE_IP), anyString(), anyString(), isNull(), anyMap()))
                .thenReturn("nat-uuid-new");

        final PortForwardingRule rule = pfRule(528L, 22, 22, FirewallRule.State.Active);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        // Legacy LB row + LR.load_balancer reference dropped.
        verify(nbClient, times(1)).detachLoadBalancerFromLogicalRouter(eq(LR_UUID), eq("lb-uuid-legacy"));
        verify(nbClient, times(1)).deleteLoadBalancer(eq("lb-uuid-legacy"));
        verify(logicalIdMapDao, atLeastOnce()).remove(eq(810L));

        // Fresh NAT row created with the right tuple + new mapping persisted.
        verify(nbClient, times(1)).addNatRule(eq(LR_UUID), eq(OvnPortForwardingService.NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq(PRIVATE_IP), anyString(), anyString(), isNull(), anyMap());
        verify(logicalIdMapDao, atLeastOnce()).persist(any(OvnLogicalIdMapVO.class));
    }

    @Test
    public void existingNatRowMappingIsNoOpOnApply() throws Exception {
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getId()).thenReturn(901L);
        when(existing.getOvnUuid()).thenReturn("nat-uuid-existing");
        when(logicalIdMapDao.findByCsId(eq(Kind.PORT_FORWARDING), eq(529L), eq(CONTROLLER_ID))).thenReturn(existing);
        when(nbClient.rowExistsByUuid(eq("NAT"), eq("nat-uuid-existing"))).thenReturn(true);

        final PortForwardingRule rule = pfRule(529L, 22, 22, FirewallRule.State.Active);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        verify(nbClient, never()).addNatRule(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), anyMap());
        verify(logicalIdMapDao, never()).persist(any(OvnLogicalIdMapVO.class));
        verify(logicalIdMapDao, never()).remove(eq(901L));
    }

    @Test
    public void portRangeBuildsHyphenatedExternalPortRange() throws Exception {
        when(nbClient.addNatRule(eq(LR_UUID), eq(OvnPortForwardingService.NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq(PRIVATE_IP), anyString(), anyString(), isNull(), anyMap()))
                .thenReturn("nat-uuid-range");

        final PortForwardingRule rule = pfRule(530L, 8080, 8090, FirewallRule.State.Add);

        assertTrue(service.applyPFRules(network, List.of(rule)));

        final ArgumentCaptor<String> portCaptor = ArgumentCaptor.forClass(String.class);
        verify(nbClient, times(1)).addNatRule(eq(LR_UUID), eq(OvnPortForwardingService.NAT_TYPE_DNAT_AND_SNAT),
                eq(PUBLIC_IP), eq(PRIVATE_IP), anyString(), portCaptor.capture(), isNull(), anyMap());
        assertEquals("8080-8090", portCaptor.getValue());
    }

    private PortForwardingRule pfRule(final long id, final int srcPortStart, final int srcPortEnd,
                                      final FirewallRule.State state) {
        final PortForwardingRule rule = mock(PortForwardingRule.class);
        when(rule.getId()).thenReturn(id);
        when(rule.getState()).thenReturn(state);
        when(rule.getSourceIpAddressId()).thenReturn(IP_ADDRESS_ID);
        when(rule.getSourcePortStart()).thenReturn(srcPortStart);
        when(rule.getSourcePortEnd()).thenReturn(srcPortEnd);
        when(rule.getDestinationPortStart()).thenReturn(srcPortStart);
        when(rule.getDestinationIpAddress()).thenReturn(new Ip(PRIVATE_IP));
        when(rule.getVirtualMachineId()).thenReturn(VM_ID);
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
