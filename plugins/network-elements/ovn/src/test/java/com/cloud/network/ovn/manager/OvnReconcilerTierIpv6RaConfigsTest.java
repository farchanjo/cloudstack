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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnNetworkElement;

/**
 * Guard + apply tests for
 * {@link OvnReconcilerService#resyncTierIpv6RaConfigsForZone}. The PARSEL-V6
 * RA-config resync must:
 * <ul>
 *   <li>be a strict no-op in {@code dryRun} / with a {@code null} controller
 *       (never dereference the NB client or any DAO), and</li>
 *   <li>re-stamp {@link OvnNetworkElement#IPV6_RA_CONFIGS} (SLAAC) onto every
 *       dual-stack tier LRP while skipping IPv4-only tiers — repairing already
 *       running clusters bound under the legacy {@code dhcpv6_stateful} mode
 *       without recreating VMs.</li>
 * </ul>
 */
public class OvnReconcilerTierIpv6RaConfigsTest {

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private NetworkDao networkDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private OvnReconcilerService svc;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        networkDao = mock(NetworkDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);

        when(controller.getId()).thenReturn(2L);
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(controller);
        when(pluginManager.nbClient(anyLong())).thenReturn(nbClient);

        svc = new OvnReconcilerService();
        inject(svc, "pluginManager", pluginManager);
        inject(svc, "logicalIdMapDao", logicalIdMapDao);
        inject(svc, "networkDao", networkDao);
    }

    @Test
    public void dryRunIsNoOpAndTouchesNothing() {
        // Unwired service (null pluginManager/DAOs) would NPE if the dryRun guard
        // did not short-circuit before resolving the controller.
        final OvnReconcilerService bare = new OvnReconcilerService();
        assertEquals(0, bare.resyncTierIpv6RaConfigsForZone(1L, true));
    }

    @Test
    public void unknownControllerIsNoOp() {
        when(pluginManager.findControllerForZone(anyLong())).thenReturn(null);
        assertEquals(0, svc.resyncTierIpv6RaConfigsForZone(9L, false));
    }

    @Test
    public void reStampsSlaacOnDualStackTierAndSkipsV4Only() {
        final OvnLogicalIdMapVO dual = mock(OvnLogicalIdMapVO.class);
        when(dual.getCsId()).thenReturn(666L);
        when(dual.getOvnUuid()).thenReturn("lrp-dual");
        final OvnLogicalIdMapVO v4only = mock(OvnLogicalIdMapVO.class);
        when(v4only.getCsId()).thenReturn(777L);
        when(v4only.getOvnUuid()).thenReturn("lrp-v4");

        when(logicalIdMapDao.listByKind(eq(Kind.PUBLIC_LRP), eq(2L)))
                .thenReturn(Arrays.asList(dual, v4only));

        final NetworkVO dualNet = mock(NetworkVO.class);
        when(dualNet.getId()).thenReturn(666L);
        when(dualNet.getIp6Gateway()).thenReturn("2a13:8740:0:9::1");
        when(dualNet.getIp6Cidr()).thenReturn("2a13:8740:0:9::/64");
        final NetworkVO v4Net = mock(NetworkVO.class);
        when(v4Net.getId()).thenReturn(777L);
        when(v4Net.getIp6Gateway()).thenReturn(null);
        when(v4Net.getIp6Cidr()).thenReturn(null);
        when(networkDao.findById(666L)).thenReturn(dualNet);
        when(networkDao.findById(777L)).thenReturn(v4Net);

        final int applied = svc.resyncTierIpv6RaConfigsForZone(4L, false);

        assertEquals(1, applied);
        // Dual-stack tier LRP re-stamped with the SLAAC RA config.
        verify(nbClient, times(1)).lrpSetIpv6RaConfigs(eq("lrp-dual"), eq(OvnNetworkElement.IPV6_RA_CONFIGS));
        assertEquals("slaac", OvnNetworkElement.IPV6_RA_CONFIGS.get("address_mode"));
        // IPv4-only tier LRP left untouched.
        verify(nbClient, never()).lrpSetIpv6RaConfigs(eq("lrp-v4"), any());
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
