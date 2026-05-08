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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.NetworkACLItem;

/**
 * Asserts the CloudStack {@link NetworkACLItem} -> OVN ACL row translation
 * matrix and the idempotent re-apply behaviour.
 */
public class OvnFirewallServiceTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final String TIER_LS_UUID = "ls-uuid-aaa";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private OvnFirewallService service;

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

        // Tier LS already mapped (the guru would have created it).
        final OvnLogicalIdMapVO tierMap = mock(OvnLogicalIdMapVO.class);
        when(tierMap.getOvnUuid()).thenReturn(TIER_LS_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(tierMap);

        service = new OvnFirewallService();
        injectField(service, "pluginManager", pluginManager);
        injectField(service, "logicalIdMapDao", logicalIdMapDao);
    }

    @Test
    public void tcpIngressAllowProducesStatefulFromLportAcl() throws Exception {
        when(nbClient.addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyMap(), anyBoolean(), any(), anyString())).thenReturn("acl-uuid-1");

        final NetworkACLItem rule = aclRule(101L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "tcp", 80, 80, List.of("10.0.0.0/8"), null, null, 1, NetworkACLItem.State.Add);

        assertTrue(service.applyNetworkACLs(network, List.of(rule)));

        final ArgumentCaptor<String> direction = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> match = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<Integer> priority = ArgumentCaptor.forClass(Integer.class);

        verify(nbClient, times(1)).addAclToLogicalSwitch(eq(TIER_LS_UUID), direction.capture(), priority.capture(),
                match.capture(), action.capture(), anyMap(), anyBoolean(), any(), anyString());

        assertEquals(OvnNbClient.ACL_DIRECTION_FROM_LPORT, direction.getValue());
        assertEquals(OvnNbClient.ACL_ACTION_ALLOW_RELATED, action.getValue());
        final String built = match.getValue();
        assertTrue("match must include tcp predicate: " + built, built.contains("tcp"));
        assertTrue("match must include ip4.src CIDR: " + built, built.contains("ip4.src == 10.0.0.0/8"));
        assertTrue("match must include tcp.dst port equality: " + built, built.contains("tcp.dst == 80"));
        // Default priority - rule.number = 2000 - 1 = 1999.
        assertEquals(Integer.valueOf(1999), priority.getValue());

        verify(logicalIdMapDao, times(1)).persist(any(OvnLogicalIdMapVO.class));
    }

    @Test
    public void egressDenyProducesDropToLportAcl() throws Exception {
        when(nbClient.addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyMap(), anyBoolean(), any(), anyString())).thenReturn("acl-uuid-2");

        final NetworkACLItem rule = aclRule(202L, NetworkACLItem.TrafficType.Egress, NetworkACLItem.Action.Deny,
                "udp", 5060, 5061, List.of("0.0.0.0/0"), null, null, 5, NetworkACLItem.State.Active);

        assertTrue(service.applyNetworkACLs(network, List.of(rule)));

        final ArgumentCaptor<String> direction = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> match = ArgumentCaptor.forClass(String.class);

        verify(nbClient).addAclToLogicalSwitch(anyString(), direction.capture(), anyInt(),
                match.capture(), action.capture(), anyMap(), anyBoolean(), any(), anyString());

        assertEquals(OvnNbClient.ACL_DIRECTION_TO_LPORT, direction.getValue());
        assertEquals(OvnNbClient.ACL_ACTION_DROP, action.getValue());
        final String built = match.getValue();
        assertTrue("egress must use ip4.dst: " + built, built.contains("ip4.dst == 0.0.0.0/0"));
        assertTrue("range port predicate present: " + built, built.contains("udp.dst >= 5060"));
        assertTrue("range port predicate present: " + built, built.contains("udp.dst <= 5061"));
    }

    @Test
    public void icmpRuleEmitsIcmp4Predicate() {
        final NetworkACLItem rule = aclRule(303L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "icmp", null, null, List.of("10.10.0.0/16"), 8, 0, 2, NetworkACLItem.State.Add);

        final String built = OvnFirewallService.buildMatch(rule);
        assertTrue("must include icmp4 token: " + built, built.contains("icmp4"));
        assertTrue("must include icmp4.type: " + built, built.contains("icmp4.type == 8"));
        assertTrue("must include icmp4.code: " + built, built.contains("icmp4.code == 0"));
        assertTrue("must include CIDR predicate: " + built, built.contains("ip4.src == 10.10.0.0/16"));
    }

    @Test
    public void anyProtocolWithoutCidrFallsBackToWildcard() {
        final NetworkACLItem rule = aclRule(404L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "all", null, null, List.of(), null, null, 0, NetworkACLItem.State.Add);

        final String built = OvnFirewallService.buildMatch(rule);
        assertEquals("1", built);
    }

    @Test
    public void numericProtocolFallsBackToIpProto() {
        final NetworkACLItem rule = aclRule(505L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "47", null, null, List.of(), null, null, 0, NetworkACLItem.State.Add);

        final String built = OvnFirewallService.buildMatch(rule);
        assertTrue("ip.proto fallback for numeric: " + built, built.contains("ip.proto == 47"));
    }

    @Test
    public void reapplyOnExistingMappingIsNoOp() throws Exception {
        // Pretend rule 101 already mapped — addAclToLogicalSwitch must NOT fire.
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getOvnUuid()).thenReturn("acl-uuid-existing");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(101L), eq(CONTROLLER_ID))).thenReturn(existing);

        final NetworkACLItem rule = aclRule(101L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "tcp", 80, 80, List.of("10.0.0.0/8"), null, null, 1, NetworkACLItem.State.Add);

        assertTrue(service.applyNetworkACLs(network, List.of(rule)));
        verify(nbClient, never()).addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyMap(), anyBoolean(), any(), anyString());
        verify(logicalIdMapDao, never()).persist(any(OvnLogicalIdMapVO.class));
    }

    @Test
    public void revokeRemovesMappingAndCallsDetach() throws Exception {
        // mapping for rule 707 exists.
        final OvnLogicalIdMapVO mapping = mock(OvnLogicalIdMapVO.class);
        when(mapping.getId()).thenReturn(909L);
        when(mapping.getOvnUuid()).thenReturn("acl-uuid-707");
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(707L), eq(CONTROLLER_ID))).thenReturn(mapping);

        // listByKind returns one tier LS under this controller.
        final OvnLogicalIdMapVO tierLs = mock(OvnLogicalIdMapVO.class);
        when(tierLs.getOvnUuid()).thenReturn(TIER_LS_UUID);
        when(logicalIdMapDao.listByKind(eq(Kind.NETWORK), eq(CONTROLLER_ID))).thenReturn(List.of(tierLs));

        final NetworkACLItem rule = aclRule(707L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "tcp", 80, 80, List.of("10.0.0.0/8"), null, null, 1, NetworkACLItem.State.Revoke);

        assertTrue(service.applyNetworkACLs(network, List.of(rule)));

        verify(nbClient, times(1)).removeAclFromLogicalSwitch(eq(TIER_LS_UUID), eq("acl-uuid-707"));
        verify(logicalIdMapDao, times(1)).remove(eq(909L));
        verify(nbClient, never()).addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyMap(), anyBoolean(), any(), anyString());
    }

    @Test
    public void applyNetworkACLs_returnsTrue_whenControllerMissing() throws Exception {
        // Zone 999 has no OVN controller registered — cleanup must be a no-op, not a failure.
        final Network strayNetwork = mock(Network.class);
        when(strayNetwork.getDataCenterId()).thenReturn(999L);
        when(strayNetwork.getId()).thenReturn(NETWORK_ID);

        final NetworkACLItem rule = aclRule(101L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "tcp", 80, 80, List.of("10.0.0.0/8"), null, null, 1, NetworkACLItem.State.Add);

        assertTrue("no-op cleanup must return true when controller is absent",
                service.applyNetworkACLs(strayNetwork, List.of(rule)));
        verify(nbClient, never()).addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyMap(), anyBoolean(), any(), anyString());
    }

    @Test
    public void applyNetworkACLs_returnsTrue_whenTierLsMissing() throws Exception {
        // Controller is present but the tier has no OVN logical switch yet (Allocated state).
        // cleanup must succeed as a no-op so NetworkOrchestrator can proceed with deletion.
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(null);

        final NetworkACLItem rule = aclRule(202L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "tcp", 443, 443, List.of("0.0.0.0/0"), null, null, 1, NetworkACLItem.State.Add);

        assertTrue("no-op cleanup must return true when OVN logical switch is absent",
                service.applyNetworkACLs(network, List.of(rule)));
        verify(nbClient, never()).addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyMap(), anyBoolean(), any(), anyString());
    }

    @Test
    public void applyNetworkACLs_returnsFalse_whenRuleApplyThrowsOvnException() throws Exception {
        // The OVN NB client throws on the actual rule apply — must propagate as false.
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(303L), eq(CONTROLLER_ID))).thenReturn(null);
        doThrow(new OvnException("OVSDB transact failed"))
                .when(nbClient).addAclToLogicalSwitch(anyString(), anyString(), anyInt(), anyString(),
                        anyString(), anyMap(), anyBoolean(), any(), anyString());

        final NetworkACLItem rule = aclRule(303L, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow,
                "tcp", 22, 22, List.of("192.168.0.0/16"), null, null, 1, NetworkACLItem.State.Add);

        assertFalse("real apply failure must still propagate as false",
                service.applyNetworkACLs(network, List.of(rule)));
    }

    @Test
    public void capabilitiesDeclareNetworkAcl() {
        assertNotNull(service.getCapabilities());
        assertTrue(service.getCapabilities().containsKey(com.cloud.network.Network.Service.NetworkACL));
    }

    private static NetworkACLItem aclRule(final long id, final NetworkACLItem.TrafficType direction,
                                          final NetworkACLItem.Action action, final String protocol,
                                          final Integer portStart, final Integer portEnd, final List<String> cidrs,
                                          final Integer icmpType, final Integer icmpCode, final int number,
                                          final NetworkACLItem.State state) {
        final NetworkACLItem rule = mock(NetworkACLItem.class);
        when(rule.getId()).thenReturn(id);
        when(rule.getUuid()).thenReturn("uuid-" + id);
        when(rule.getTrafficType()).thenReturn(direction);
        when(rule.getAction()).thenReturn(action);
        when(rule.getProtocol()).thenReturn(protocol);
        when(rule.getSourcePortStart()).thenReturn(portStart);
        when(rule.getSourcePortEnd()).thenReturn(portEnd);
        when(rule.getSourceCidrList()).thenReturn(cidrs);
        when(rule.getIcmpType()).thenReturn(icmpType);
        when(rule.getIcmpCode()).thenReturn(icmpCode);
        when(rule.getNumber()).thenReturn(number);
        when(rule.getState()).thenReturn(state);
        return rule;
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
