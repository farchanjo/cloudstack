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

import org.junit.Before;
import org.junit.Test;

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
import com.cloud.vm.NicProfile;

/**
 * Verifies that {@link OvnQosService#removeQosForNic} falls back to
 * {@link OvnNbClient#deleteQosRowDirect(String)} (enqueued into
 * {@code ovn_pending_deletion} first) when the tier LS mapping backing the
 * QoS row is already gone — the path that used to drop only the CloudStack
 * mapping row and leave the NB row permanently orphaned.
 */
public class OvnQosServiceEnqueueTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final long NIC_ID = 55L;
    private static final String QOS_UUID = "qos-uuid-aaa";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private NicProfile nic;
    private OvnQosService service;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        network = mock(Network.class);
        nic = mock(NicProfile.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(nic.getId()).thenReturn(NIC_ID);

        service = new OvnQosService();
        inject(service, "pluginManager", pluginManager);
        inject(service, "logicalIdMapDao", logicalIdMapDao);
        inject(service, "pendingDeletionDao", pendingDeletionDao);
    }

    /** Dead-LS-mapping branch, success: enqueue then direct delete then markSucceeded. */
    @Test
    public void removeQosForNic_deadLsMapping_enqueuesThenDeletesDirect_onSuccess() {
        final OvnLogicalIdMapVO qosMapping = qosMappingFor(QOS_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.QOS), eq(NIC_ID), eq(CONTROLLER_ID))).thenReturn(qosMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(null);
        when(pendingDeletionDao.isPendingByOvnUuid(QOS_UUID, "QOS")).thenReturn(false);

        service.removeQosForNic(network, nic);

        // Enqueued before the sync delete attempt.
        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        // Direct delete used, never the LS-scoped detach+delete.
        verify(nbClient, times(1)).deleteQosRowDirect(QOS_UUID);
        verify(nbClient, never()).removeQosFromLogicalSwitch(anyString(), anyString());
        // Mapping removed + pending row marked succeeded on success.
        verify(logicalIdMapDao, times(1)).remove(qosMapping.getId());
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid(QOS_UUID, "QOS");
    }

    /** Dead-LS-mapping branch, failure: mapping + pending entry both retained for retry. */
    @Test
    public void removeQosForNic_deadLsMapping_retainsMappingAndQueue_onFailure() {
        final OvnLogicalIdMapVO qosMapping = qosMappingFor(QOS_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.QOS), eq(NIC_ID), eq(CONTROLLER_ID))).thenReturn(qosMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(null);
        when(pendingDeletionDao.isPendingByOvnUuid(QOS_UUID, "QOS")).thenReturn(false);
        doThrow(new OvnException("ovsdb timeout")).when(nbClient).deleteQosRowDirect(QOS_UUID);

        service.removeQosForNic(network, nic);

        verify(pendingDeletionDao, times(1)).persist(any(OvnPendingDeletionVO.class));
        verify(nbClient, times(1)).deleteQosRowDirect(QOS_UUID);
        verify(logicalIdMapDao, never()).remove(anyLong());
        verify(pendingDeletionDao, never()).markSucceededByOvnUuid(anyString(), anyString());
    }

    /** Live-LS-mapping branch is unaffected: still routed through the LS-scoped delete. */
    @Test
    public void removeQosForNic_liveLsMapping_usesLogicalSwitchScopedDelete() {
        final OvnLogicalIdMapVO qosMapping = qosMappingFor(QOS_UUID);
        final OvnLogicalIdMapVO lsMapping = mock(OvnLogicalIdMapVO.class);
        when(lsMapping.getOvnUuid()).thenReturn("ls-uuid-bbb");
        when(logicalIdMapDao.findByCsId(eq(Kind.QOS), eq(NIC_ID), eq(CONTROLLER_ID))).thenReturn(qosMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID))).thenReturn(lsMapping);

        service.removeQosForNic(network, nic);

        verify(nbClient, times(1)).removeQosFromLogicalSwitch("ls-uuid-bbb", QOS_UUID);
        verify(nbClient, never()).deleteQosRowDirect(anyString());
        verify(pendingDeletionDao, never()).persist(any(OvnPendingDeletionVO.class));
        verify(logicalIdMapDao, times(1)).remove(qosMapping.getId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private OvnLogicalIdMapVO qosMappingFor(final String ovnUuid) {
        final OvnLogicalIdMapVO m = mock(OvnLogicalIdMapVO.class);
        when(m.getId()).thenReturn(300L);
        when(m.getOvnUuid()).thenReturn(ovnUuid);
        when(m.getCsId()).thenReturn(NIC_ID);
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
