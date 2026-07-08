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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;

/**
 * Verifies that OvnGuestNetworkGuru.design() copies ip6Cidr/ip6Gateway from
 * the (core-auto-allocated) userSpecified network onto the designed
 * NetworkVO, mirroring VxlanGuestNetworkGuru/NsxGuestNetworkGuru behavior via
 * the inherited GuestNetworkGuru#updateNetworkDesignForIPv6IfNeeded helper.
 */
public class OvnGuestNetworkGuruDesignTest {

    private static final long OFFERING_ID = 1L;
    private static final long ZONE_ID = 2L;
    private static final long PHYSICAL_NETWORK_ID = 3L;

    private DataCenterDao dcDao;
    private PhysicalNetworkDao physicalNetworkDao;
    private NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    private OvnGuestNetworkGuru guru;

    @Before
    public void setUp() throws Exception {
        dcDao = mock(DataCenterDao.class);
        physicalNetworkDao = mock(PhysicalNetworkDao.class);
        networkOfferingServiceMapDao = mock(NetworkOfferingServiceMapDao.class);

        guru = new OvnGuestNetworkGuru();
        inject(guru, "_dcDao", dcDao);
        inject(guru, "_physicalNetworkDao", physicalNetworkDao);
        inject(guru, "networkOfferingServiceMapDao", networkOfferingServiceMapDao);

        final DataCenterVO dc = mock(DataCenterVO.class);
        when(dc.getNetworkType()).thenReturn(NetworkType.Advanced);
        when(dcDao.findById(anyLong())).thenReturn(dc);

        final PhysicalNetworkVO physnet = mock(PhysicalNetworkVO.class);
        when(physnet.getIsolationMethods()).thenReturn(Arrays.asList("VXLAN"));
        when(physicalNetworkDao.findById(anyLong())).thenReturn(physnet);

        when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(OFFERING_ID, Service.Connectivity))
                .thenReturn(true);
        when(networkOfferingServiceMapDao.isProviderForNetworkOffering(OFFERING_ID, Provider.Ovn))
                .thenReturn(true);
    }

    @Test
    public void design_copiesIp6CidrAndGateway_fromUserSpecified() {
        final NetworkOffering offering = offering();
        final DeploymentPlan plan = plan();
        final Account owner = mock(Account.class);

        final Network userSpecified = mock(Network.class);
        when(userSpecified.getCidr()).thenReturn("10.1.1.0/24");
        when(userSpecified.getGateway()).thenReturn("10.1.1.1");
        when(userSpecified.getIp6Cidr()).thenReturn("fd00:1234:5678::/64");
        when(userSpecified.getIp6Gateway()).thenReturn("fd00:1234:5678::1");

        final Network result = guru.design(offering, plan, userSpecified, "tier", 4L, owner);

        assertNotNull("design() must not return null when canHandle() succeeds", result);
        assertEquals("fd00:1234:5678::/64", result.getIp6Cidr());
        assertEquals("fd00:1234:5678::1", result.getIp6Gateway());
    }

    @Test
    public void design_leavesIp6Unset_whenUserSpecifiedHasNoIp6Cidr() {
        final NetworkOffering offering = offering();
        final DeploymentPlan plan = plan();
        final Account owner = mock(Account.class);

        final Network userSpecified = mock(Network.class);
        when(userSpecified.getCidr()).thenReturn("10.1.1.0/24");
        when(userSpecified.getGateway()).thenReturn("10.1.1.1");

        final Network result = guru.design(offering, plan, userSpecified, "tier", 4L, owner);

        assertNotNull(result);
        assertNull(result.getIp6Cidr());
        assertNull(result.getIp6Gateway());
    }

    private NetworkOffering offering() {
        final NetworkOffering offering = mock(NetworkOffering.class);
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(offering.getTrafficType()).thenReturn(TrafficType.Guest);
        when(offering.getGuestType()).thenReturn(GuestType.Isolated);
        when(offering.isRedundantRouter()).thenReturn(false);
        when(offering.isSpecifyVlan()).thenReturn(false);
        return offering;
    }

    private DeploymentPlan plan() {
        final DeploymentPlan plan = mock(DeploymentPlan.class);
        when(plan.getDataCenterId()).thenReturn(ZONE_ID);
        when(plan.getPhysicalNetworkId()).thenReturn(PHYSICAL_NETWORK_ID);
        return plan;
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null) {
            try {
                field = clazz.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        field.setAccessible(true);
        field.set(target, value);
    }
}
