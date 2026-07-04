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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;

import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Covers the VPC public peer LSP (rsp-public-vpc&lt;id&gt;) lifecycle fixed
 * alongside {@link OvnLogicalIdMapVO.Kind#VPC_PUBLIC_RSP}:
 * {@code bindVpcToPublic} must persist the RSP mapping (not just the LRP
 * one), and {@code unbindVpcFromPublic} must delete both the RSP LSP and the
 * LRP — RSP first, since {@code deleteLogicalRouterPort} does not cascade to
 * the peer port.
 */
public class OvnPublicNetworkManagerRspTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long VPC_ID = 42L;
    private static final String LR_UUID = "lr-uuid";
    private static final String LRP_UUID = "lrp-uuid";
    private static final String RSP_UUID = "rsp-uuid";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnPendingDeletionDao pendingDeletionDao;
    private OvnChassisMapDao chassisMapDao;
    private NetworkDao networkDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnPublicNetworkManager manager;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        pendingDeletionDao = mock(OvnPendingDeletionDao.class);
        chassisMapDao = mock(OvnChassisMapDao.class);
        networkDao = mock(NetworkDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);
        // Auto-detect VLAN chain (ovn.public.vlan.auto defaults to true) must
        // not NPE — no Public networks in this zone -> auto-detect no-ops.
        when(networkDao.listByZoneAndTrafficType(ZONE_ID, TrafficType.Public)).thenReturn(List.of());

        manager = new OvnPublicNetworkManager();
        inject(manager, "pluginManager", pluginManager);
        inject(manager, "logicalIdMapDao", logicalIdMapDao);
        inject(manager, "pendingDeletionDao", pendingDeletionDao);
        inject(manager, "chassisMapDao", chassisMapDao);
        inject(manager, "networkDao", networkDao);
    }

    /** bindVpcToPublic must persist a VPC_PUBLIC_RSP mapping row, not just VPC_PUBLIC_LRP. */
    @Test
    public void bindVpcToPublic_persistsVpcPublicRspMapping() {
        seedExistingPublicLsAndHag();
        final OvnNbClient.BindResult bound = new OvnNbClient.BindResult(LRP_UUID, RSP_UUID);
        when(nbClient.bindLrToLs(any(OvnNbClient.BindRequest.class), any())).thenReturn(bound);
        when(nbClient.addLogicalRouterStaticRoute(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("route-uuid");

        manager.bindVpcToPublic(ZONE_ID, LR_UUID, "aa:bb:cc:dd:ee:ff", List.of("10.0.0.2/24"), VPC_ID);

        final ArgumentCaptor<OvnLogicalIdMapVO> captor = ArgumentCaptor.forClass(OvnLogicalIdMapVO.class);
        verify(logicalIdMapDao, times(3)).persist(captor.capture());
        final boolean rspPersisted = captor.getAllValues().stream()
                .anyMatch(v -> Kind.VPC_PUBLIC_RSP.name().equals(v.getCsKind())
                        && v.getCsId() == VPC_ID
                        && RSP_UUID.equals(v.getOvnUuid()));
        org.junit.Assert.assertTrue("VPC_PUBLIC_RSP mapping must be persisted with the bound LSP uuid", rspPersisted);
    }

    /** unbindVpcFromPublic deletes both the RSP LSP and the LRP, RSP first, and removes both mappings. */
    @Test
    public void unbindVpcFromPublic_deletesRspBeforeLrp_andRemovesBothMappings() {
        final OvnLogicalIdMapVO lrpMapping = mappingFor(LRP_UUID, 500L);
        final OvnLogicalIdMapVO rspMapping = mappingFor(RSP_UUID, 501L);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC_PUBLIC_LRP), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(lrpMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC_PUBLIC_RSP), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(rspMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.STATIC_ROUTE), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(null);
        when(pendingDeletionDao.isPendingByOvnUuid(anyString(), anyString())).thenReturn(false);

        manager.unbindVpcFromPublic(ZONE_ID, VPC_ID, LR_UUID);

        final InOrder order = inOrder(nbClient);
        order.verify(nbClient).deleteLogicalSwitchPort(RSP_UUID);
        order.verify(nbClient).deleteLogicalRouterPort(LRP_UUID);

        // Both rows enqueued into ovn_pending_deletion before their sync delete.
        verify(pendingDeletionDao, times(2)).persist(any(OvnPendingDeletionVO.class));
        verify(logicalIdMapDao, times(1)).remove(rspMapping.getId());
        verify(logicalIdMapDao, times(1)).remove(lrpMapping.getId());
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid(RSP_UUID, "VPC_PUBLIC_RSP");
        verify(pendingDeletionDao, times(1)).markSucceededByOvnUuid(LRP_UUID, "VPC_PUBLIC_LRP");
    }

    /** RSP delete failure must not remove either mapping and must not stop the LRP delete attempt. */
    @Test
    public void unbindVpcFromPublic_rspDeleteFails_retainsRspMapping_stillAttemptsLrp() {
        final OvnLogicalIdMapVO lrpMapping = mappingFor(LRP_UUID, 500L);
        final OvnLogicalIdMapVO rspMapping = mappingFor(RSP_UUID, 501L);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC_PUBLIC_LRP), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(lrpMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.VPC_PUBLIC_RSP), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(rspMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.STATIC_ROUTE), eq(VPC_ID), eq(CONTROLLER_ID))).thenReturn(null);
        when(pendingDeletionDao.isPendingByOvnUuid(anyString(), anyString())).thenReturn(false);
        doThrow(new OvnException("ovsdb timeout")).when(nbClient).deleteLogicalSwitchPort(RSP_UUID);

        manager.unbindVpcFromPublic(ZONE_ID, VPC_ID, LR_UUID);

        verify(logicalIdMapDao, never()).remove(rspMapping.getId());
        verify(pendingDeletionDao, never()).markSucceededByOvnUuid(eq(RSP_UUID), anyString());
        // LRP path still runs and succeeds independently.
        verify(nbClient, times(1)).deleteLogicalRouterPort(LRP_UUID);
        verify(logicalIdMapDao, times(1)).remove(lrpMapping.getId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Seeds an existing PUBLIC_LS + HA_CHASSIS_GROUP mapping so bindVpcToPublic's
     *  ensure* helpers hit the idempotent already-exists path without needing to
     *  mock the full create flow (networkDao / vlanDao are irrelevant here). */
    private void seedExistingPublicLsAndHag() {
        final OvnLogicalIdMapVO lsMapping = mock(OvnLogicalIdMapVO.class);
        when(lsMapping.getOvnUuid()).thenReturn("public-ls-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.PUBLIC_LS), eq(ZONE_ID), eq(CONTROLLER_ID))).thenReturn(lsMapping);
        when(nbClient.rowExistsByUuid("Logical_Switch", "public-ls-uuid")).thenReturn(true);

        final OvnLogicalIdMapVO hagMapping = mock(OvnLogicalIdMapVO.class);
        when(hagMapping.getOvnUuid()).thenReturn("hag-uuid");
        when(logicalIdMapDao.findByCsId(eq(Kind.HA_CHASSIS_GROUP), eq(ZONE_ID), eq(CONTROLLER_ID))).thenReturn(hagMapping);
        when(nbClient.rowExistsByUuid("HA_Chassis_Group", "hag-uuid")).thenReturn(true);
    }

    private OvnLogicalIdMapVO mappingFor(final String ovnUuid, final long id) {
        final OvnLogicalIdMapVO m = mock(OvnLogicalIdMapVO.class);
        when(m.getId()).thenReturn(id);
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
