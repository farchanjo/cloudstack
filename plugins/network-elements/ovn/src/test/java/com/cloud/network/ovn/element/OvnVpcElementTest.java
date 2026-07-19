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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.Vpc;

public class OvnVpcElementTest {

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnVpcElement element;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        when(controller.getId()).thenReturn(1L);
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(controller);
        when(pluginManager.nbClient(anyLong())).thenReturn(nbClient);

        element = new OvnVpcElement();
        injectField(element, "pluginManager", pluginManager);
        injectField(element, "logicalIdMapDao", logicalIdMapDao);
        injectField(element, "pendingDeletionDao", pendingDeletionDao);
    }

    @Test
    public void createsLogicalRouterAndPersistsMapping() {
        when(nbClient.createLogicalRouter(anyString(), anyMap())).thenReturn("lr-uuid-123");
        when(logicalIdMapDao.findByCsId(any(Kind.class), anyLong(), anyLong())).thenReturn(null);

        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(42L);
        when(vpc.getZoneId()).thenReturn(7L);
        when(vpc.getUuid()).thenReturn("vpc-uuid-1");
        when(vpc.getName()).thenReturn("test-vpc");

        final String uuid = element.createLogicalRouterFor(vpc);
        assertEquals("lr-uuid-123", uuid);
        verify(nbClient, times(1)).createLogicalRouter(anyString(), anyMap());
        verify(logicalIdMapDao, times(1)).persist(any(OvnLogicalIdMapVO.class));
    }

    @Test
    public void reusesMappingWhenAlreadyPresent() {
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getOvnUuid()).thenReturn("existing-uuid");
        when(logicalIdMapDao.findByCsId(any(Kind.class), anyLong(), anyLong())).thenReturn(existing);
        when(nbClient.rowExistsByUuid("Logical_Router", "existing-uuid")).thenReturn(true);

        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(42L);
        when(vpc.getZoneId()).thenReturn(7L);
        when(vpc.getUuid()).thenReturn("vpc-uuid-1");
        when(vpc.getName()).thenReturn("test-vpc");

        final String uuid = element.createLogicalRouterFor(vpc);
        assertEquals("existing-uuid", uuid);
        // No new persist + no NB call when the mapping was cached.
        verify(nbClient, times(0)).createLogicalRouter(anyString(), anyMap());
    }

    @Test
    public void deletesNbAndMappingTogether() {
        final OvnLogicalIdMapVO existing = mock(OvnLogicalIdMapVO.class);
        when(existing.getId()).thenReturn(99L);
        when(existing.getOvnUuid()).thenReturn("delete-me-uuid");
        when(logicalIdMapDao.findByCsId(Kind.VPC, 42L, 1L)).thenReturn(existing);

        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(42L);
        when(vpc.getZoneId()).thenReturn(7L);

        element.deleteLogicalRouterFor(vpc);

        verify(nbClient, times(1)).deleteLogicalRouter("delete-me-uuid");
        verify(logicalIdMapDao, times(1)).remove(99L);
    }

    @Test
    public void bindTierEmitsBindRequestAgainstNbClient() {
        when(nbClient.createLogicalRouter(anyString(), anyMap())).thenReturn("lr-uuid");
        when(nbClient.bindLrToLs(any())).thenReturn(new OvnNbClient.BindResult("lrp-1", "lsp-1"));
        when(logicalIdMapDao.findByCsId(any(Kind.class), anyLong(), anyLong())).thenReturn(null);

        final Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(42L);
        when(vpc.getZoneId()).thenReturn(7L);
        when(vpc.getUuid()).thenReturn("vpc-uuid-1");
        when(vpc.getName()).thenReturn("test-vpc");

        final OvnNbClient.BindResult result = element.bindTierToVpc(
                vpc, "ls-uuid", "tier-A", "02:00:00:01:01:01",
                List.of("10.101.0.1/24"));

        assertNotNull(result);
        assertEquals("lrp-1", result.lrpUuid);
        verify(nbClient, times(1)).bindLrToLs(any(OvnNbClient.BindRequest.class));
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
