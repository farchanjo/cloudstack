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
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Asserts the public-localnet VLAN resolution chain: caller override wins,
 * then ConfigKey override, then auto-detect from CloudStack Public network
 * broadcastUri, otherwise null (untagged).
 *
 * <p>Avoids touching ConfigKey internals — every test path supplies an
 * explicit caller override or asserts the auto-detect-from-DAO surface, so
 * the tests are stable across `value()` cache rules.
 */
public class OvnPublicNetworkManagerVlanTest {

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnChassisMapDao chassisMapDao;
    private NetworkDao networkDao;
    private VlanDao vlanDao;
    private OvnPublicNetworkManager manager;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        chassisMapDao = mock(OvnChassisMapDao.class);
        networkDao = mock(NetworkDao.class);
        vlanDao = mock(VlanDao.class);

        manager = new OvnPublicNetworkManager();
        injectField(manager, "pluginManager", pluginManager);
        injectField(manager, "logicalIdMapDao", logicalIdMapDao);
        injectField(manager, "chassisMapDao", chassisMapDao);
        injectField(manager, "networkDao", networkDao);
        injectField(manager, "vlanDao", vlanDao);
    }

    @Test
    public void explicitCallerOverrideShortCircuitsResolution() {
        // Even with auto-detect populated, the explicit override wins.
        seedPublicNetwork(2988L);
        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, 4001);
        assertEquals(Integer.valueOf(4001), resolved);
    }

    @Test
    public void autoDetectFromBroadcastUriPicksFirstVlan() {
        seedPublicNetwork(2988L);
        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertEquals(Integer.valueOf(2988), resolved);
    }

    @Test
    public void noPublicNetworkResolvesToNull() {
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of());
        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertNull(resolved);
    }

    @Test
    public void untaggedBroadcastUriResolvesToNull() {
        final NetworkVO net = mock(NetworkVO.class);
        when(net.getBroadcastUri()).thenReturn(null);
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(net));
        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertNull(resolved);
    }

    @Test
    public void multipleNetworksFirstVlanWins() {
        // First entry has a non-VLAN broadcastUri — the resolver must skip it
        // and pick the next entry whose scheme is "vlan". The exact non-VLAN
        // scheme does not matter: any URI whose scheme is not "vlan" exercises
        // the same skip branch in autoDetectPublicVlan().
        final NetworkVO untagged = mock(NetworkVO.class);
        when(untagged.getBroadcastUri()).thenReturn(URI.create("lswitch://placeholder"));
        final NetworkVO tagged = mock(NetworkVO.class);
        when(tagged.getId()).thenReturn(99L);
        when(tagged.getBroadcastUri()).thenReturn(URI.create("vlan://2988"));
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(untagged, tagged));
        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertEquals(Integer.valueOf(2988), resolved);
    }

    private void seedPublicNetwork(final long vlan) {
        final NetworkVO net = mock(NetworkVO.class);
        when(net.getId()).thenReturn(101L);
        when(net.getBroadcastUri()).thenReturn(URI.create("vlan://" + vlan));
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(net));
    }

    @Test
    public void autoDetectFallsBackToVlanDaoWhenBroadcastUriNull() {
        // Network row has no broadcast_uri (the canonical CloudStack shape
        // when public IPs are managed through the vlan table directly).
        // OvnPublicNetworkManager must walk vlanDao.listVlansByNetworkId()
        // and pick the first numeric tag.
        final NetworkVO net = mock(NetworkVO.class);
        when(net.getId()).thenReturn(204L);
        when(net.getBroadcastUri()).thenReturn(null);
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(net));

        final VlanVO untagged = mock(VlanVO.class);
        when(untagged.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(untagged.getVlanTag()).thenReturn("vlan://untagged");
        final VlanVO tagged = mock(VlanVO.class);
        when(tagged.getId()).thenReturn(12L);
        when(tagged.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(tagged.getVlanTag()).thenReturn("vlan://2988");
        when(vlanDao.listVlansByNetworkId(204L)).thenReturn(List.of(untagged, tagged));

        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertEquals(Integer.valueOf(2988), resolved);
    }

    @Test
    public void autoDetectVlanDaoSkipsNonVirtualNetworkRows() {
        final NetworkVO net = mock(NetworkVO.class);
        when(net.getId()).thenReturn(204L);
        when(net.getBroadcastUri()).thenReturn(null);
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(net));

        final VlanVO direct = mock(VlanVO.class);
        when(direct.getVlanType()).thenReturn(Vlan.VlanType.DirectAttached);
        when(direct.getVlanTag()).thenReturn("vlan://4001");
        final VlanVO tagged = mock(VlanVO.class);
        when(tagged.getId()).thenReturn(15L);
        when(tagged.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(tagged.getVlanTag()).thenReturn("vlan://2988");
        when(vlanDao.listVlansByNetworkId(204L)).thenReturn(List.of(direct, tagged));

        // DirectAttached row carries vlan://4001 but must be skipped — the
        // resolver returns 2988 from the VirtualNetwork row.
        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertEquals(Integer.valueOf(2988), resolved);
    }

    @Test
    public void autoDetectVlanDaoOnlyUntaggedReturnsNull() {
        final NetworkVO net = mock(NetworkVO.class);
        when(net.getId()).thenReturn(204L);
        when(net.getBroadcastUri()).thenReturn(null);
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(net));

        final VlanVO untagged = mock(VlanVO.class);
        when(untagged.getVlanType()).thenReturn(Vlan.VlanType.VirtualNetwork);
        when(untagged.getVlanTag()).thenReturn("vlan://untagged");
        when(vlanDao.listVlansByNetworkId(204L)).thenReturn(List.of(untagged));

        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertNull(resolved);
    }

    @Test
    public void broadcastUriWinsOverVlanDao() {
        // When the network row already carries a broadcast_uri, the vlan
        // table is never queried — the broadcast URI takes precedence.
        final NetworkVO net = mock(NetworkVO.class);
        when(net.getId()).thenReturn(101L);
        when(net.getBroadcastUri()).thenReturn(URI.create("vlan://2988"));
        when(networkDao.listByZoneAndTrafficType(7L, TrafficType.Public))
                .thenReturn(List.of(net));

        final Integer resolved = manager.resolvePublicLocalnetVlan(7L, null);
        assertEquals(Integer.valueOf(2988), resolved);
        // Implementation contract: vlanDao must not be touched on this path.
        org.mockito.Mockito.verifyNoInteractions(vlanDao);
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
