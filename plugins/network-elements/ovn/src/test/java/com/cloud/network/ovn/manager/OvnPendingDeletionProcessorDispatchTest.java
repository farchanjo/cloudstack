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
package com.cloud.network.ovn.manager;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.cloud.alert.AlertManager;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;

/**
 * Verifies that {@link OvnPendingDeletionProcessor#deleteRowFromNb} dispatches
 * the two new kinds ({@link Kind#VPC_PUBLIC_RSP}, {@link Kind#QOS}) to their
 * NB client delete method, closing the gap where both kinds had no case in
 * the retry-queue dispatcher and could never be cleaned up asynchronously.
 */
public class OvnPendingDeletionProcessorDispatchTest {

    private static final long CONTROLLER_ID = 1L;
    private static final long ZONE_ID = 7L;

    private OvnControllerDao controllerDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPluginManager pluginManager;
    private AlertManager alertManager;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnPendingDeletionProcessor processor;

    @Before
    public void setUp() throws Exception {
        controllerDao = mock(OvnControllerDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pluginManager = mock(OvnPluginManager.class);
        alertManager = mock(AlertManager.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(controller.getZoneId()).thenReturn(ZONE_ID);
        when(controllerDao.listAll()).thenReturn(List.of(controller));
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);
        when(pendingDeletionDao.findAllSentinels(anyInt())).thenReturn(List.of());

        processor = new OvnPendingDeletionProcessor();
        inject(processor, "controllerDao", controllerDao);
        inject(processor, "pendingDeletionDao", pendingDeletionDao);
        inject(processor, "logicalIdMapDao", logicalIdMapDao);
        inject(processor, "pluginManager", pluginManager);
        inject(processor, "alertManager", alertManager);
    }

    @Test
    public void tick_dispatchesVpcPublicRsp_toDeleteLogicalSwitchPort() {
        final OvnPendingDeletionVO row = rowFor(Kind.VPC_PUBLIC_RSP, "rsp-uuid-1");
        when(pendingDeletionDao.findPendingByController(eq(CONTROLLER_ID), anyInt())).thenReturn(List.of(row));

        processor.tick();

        verify(nbClient, times(1)).deleteLogicalSwitchPort("rsp-uuid-1");
        verify(pendingDeletionDao, times(1)).markSucceeded(row.getId());
    }

    @Test
    public void tick_dispatchesQos_toDeleteQosRowDirect() {
        final OvnPendingDeletionVO row = rowFor(Kind.QOS, "qos-uuid-1");
        when(pendingDeletionDao.findPendingByController(eq(CONTROLLER_ID), anyInt())).thenReturn(List.of(row));

        processor.tick();

        verify(nbClient, times(1)).deleteQosRowDirect("qos-uuid-1");
        verify(pendingDeletionDao, times(1)).markSucceeded(row.getId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private OvnPendingDeletionVO rowFor(final Kind kind, final String uuid) {
        final OvnPendingDeletionVO row = mock(OvnPendingDeletionVO.class);
        when(row.getId()).thenReturn(900L);
        when(row.getKind()).thenReturn(kind);
        when(row.getKindRaw()).thenReturn(kind.name());
        when(row.getOvnUuid()).thenReturn(uuid);
        when(row.getAttempts()).thenReturn(0);
        return row;
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
