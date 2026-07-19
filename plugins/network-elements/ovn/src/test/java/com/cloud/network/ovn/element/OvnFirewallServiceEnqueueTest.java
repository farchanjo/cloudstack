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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.NetworkACLItem;

/**
 * Verifies that OvnFirewallService.revokeOne enqueues the ACL UUID into
 * ovn_pending_deletion BEFORE the synchronous NB delete attempt, and that
 * the queue row is marked succeeded only on sync success.
 */
public class OvnFirewallServiceEnqueueTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final long RULE_ID = 42L;
    private static final String TIER_LS_UUID = "ls-uuid-aaa";
    private static final String ACL_UUID = "acl-uuid-001";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private OvnFirewallService service;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        network = mock(Network.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(controller.getZoneId()).thenReturn(ZONE_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getId()).thenReturn(NETWORK_ID);

        // Tier LS mapping.
        final OvnLogicalIdMapVO lsMapping = mock(OvnLogicalIdMapVO.class);
        when(lsMapping.getOvnUuid()).thenReturn(TIER_LS_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID)))
                .thenReturn(lsMapping);

        service = new OvnFirewallService();
        inject(service, "pluginManager", pluginManager);
        inject(service, "logicalIdMapDao", logicalIdMapDao);
        inject(service, "pendingDeletionDao", pendingDeletionDao);
    }

    /**
     * Success path: enqueue fires, NB delete succeeds, row is marked succeeded.
     */
    @Test
    public void revokeOne_enqueuesAclUuid_thenMarksSucceeded_onSyncSuccess() throws ResourceUnavailableException {
        final OvnLogicalIdMapVO aclMapping = aclMappingFor(ACL_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(RULE_ID), eq(CONTROLLER_ID), eq(NETWORK_ID)))
                .thenReturn(aclMapping);
        when(pendingDeletionDao.isPendingByOvnUuid(ACL_UUID, "NETWORK_ACL")).thenReturn(false);

        service.applyNetworkACLs(network, List.of(revokeRule(RULE_ID)));

        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, times(1)).removeAclFromLogicalSwitch(TIER_LS_UUID, ACL_UUID);
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid(ACL_UUID, "NETWORK_ACL");
    }

    /**
     * Failure path: enqueue fires, NB delete throws, mark-succeeded NOT called.
     */
    @Test
    public void revokeOne_leavesQueueRow_onSyncFailure() throws ResourceUnavailableException {
        final OvnLogicalIdMapVO aclMapping = aclMappingFor(ACL_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(RULE_ID), eq(CONTROLLER_ID), eq(NETWORK_ID)))
                .thenReturn(aclMapping);
        when(pendingDeletionDao.isPendingByOvnUuid(ACL_UUID, "NETWORK_ACL")).thenReturn(false);
        doThrow(new OvnException("ovsdb timeout"))
                .when(nbClient).removeAclFromLogicalSwitch(TIER_LS_UUID, ACL_UUID);

        // applyNetworkACLs swallows OvnException from revokeOne and returns false.
        service.applyNetworkACLs(network, List.of(revokeRule(RULE_ID)));

        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, times(1)).removeAclFromLogicalSwitch(TIER_LS_UUID, ACL_UUID);
        verify(pendingDeletionDao, never()).markSucceededByOvnUuid(anyString(), anyString());
        // Mapping row must NOT be removed when sync delete fails.
        verify(logicalIdMapDao, never()).remove(anyLong());
    }

    /**
     * Idempotency: if already pending, no second enqueue; sync delete still runs.
     */
    @Test
    public void revokeOne_skipsEnqueue_whenAlreadyPending() throws ResourceUnavailableException {
        final OvnLogicalIdMapVO aclMapping = aclMappingFor(ACL_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(RULE_ID), eq(CONTROLLER_ID), eq(NETWORK_ID)))
                .thenReturn(aclMapping);
        when(pendingDeletionDao.isPendingByOvnUuid(ACL_UUID, "NETWORK_ACL")).thenReturn(true);

        service.applyNetworkACLs(network, List.of(revokeRule(RULE_ID)));

        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, times(1)).removeAclFromLogicalSwitch(TIER_LS_UUID, ACL_UUID);
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid(ACL_UUID, "NETWORK_ACL");
    }

    /**
     * No ACL mapping: revoke is a no-op — no enqueue, no NB call.
     */
    @Test
    public void revokeOne_isNoOp_whenNoMapping() throws ResourceUnavailableException {
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK_ACL), eq(RULE_ID), eq(CONTROLLER_ID), eq(NETWORK_ID)))
                .thenReturn(null);

        service.applyNetworkACLs(network, List.of(revokeRule(RULE_ID)));

        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, never()).removeAclFromLogicalSwitch(anyString(), anyString());
    }

    /**
     * Empty rule list: no enqueue, no NB call.
     */
    @Test
    public void applyNetworkACLs_emptyRuleList_isNoOp() throws ResourceUnavailableException {
        service.applyNetworkACLs(network, List.of());

        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, never()).removeAclFromLogicalSwitch(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private NetworkACLItem revokeRule(final long id) {
        final NetworkACLItem rule = mock(NetworkACLItem.class);
        when(rule.getId()).thenReturn(id);
        when(rule.getState()).thenReturn(NetworkACLItem.State.Revoke);
        return rule;
    }

    private OvnLogicalIdMapVO aclMappingFor(final String ovnUuid) {
        final OvnLogicalIdMapVO m = mock(OvnLogicalIdMapVO.class);
        when(m.getId()).thenReturn(200L);
        when(m.getOvnUuid()).thenReturn(ovnUuid);
        return m;
    }

    private static void inject(final Object target, final String fieldName, final Object value) throws Exception {
        Field f;
        try {
            f = target.getClass().getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            f = target.getClass().getSuperclass().getDeclaredField(fieldName);
        }
        f.setAccessible(true);
        f.set(target, value);
    }
}
