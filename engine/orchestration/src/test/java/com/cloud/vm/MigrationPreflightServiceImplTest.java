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
package com.cloud.vm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Test;

import com.cloud.host.HostVO;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.router.MigrationPreflightResult;
import com.cloud.network.router.MigrationVfPreflight;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

public class MigrationPreflightServiceImplTest {

    @Test
    public void reportsCapacityOnlyForTheNicThatExhaustsRemainingCapacity() {
        final Fixture fixture = fixture();
        when(fixture.preflight.requiredVdpaVfs(any(VirtualMachineProfile.class))).thenReturn(2);
        when(fixture.preflight.requiredVdpaVfs(fixture.first)).thenReturn(1);
        when(fixture.preflight.requiredVdpaVfs(fixture.second)).thenReturn(1);
        when(fixture.preflight.freeVdpaVfs(44L)).thenReturn(1);
        doThrow(new RuntimeException("VM requires 2 vDPA VF(s), but only 1 are free"))
                .when(fixture.preflight).verify(any(VirtualMachineProfile.class), any(Host.class));

        final MigrationPreflightResult result = fixture.service.preflight(99L, 44L);

        assertTrue(result.nicStatuses().get(0).allowed());
        assertFalse(result.nicStatuses().get(1).allowed());
        assertNull(result.nicStatuses().get(0).denialReason());
        assertTrue(result.nicStatuses().get(1).denialReason().contains("nic-2"));
    }

    @Test
    public void reportsWrongChassisOnlyForTheClaimedNic() {
        final Fixture fixture = fixture();
        when(fixture.preflight.requiredVdpaVfs(any(VirtualMachineProfile.class))).thenReturn(2);
        when(fixture.preflight.requiredVdpaVfs(fixture.first)).thenReturn(1);
        when(fixture.preflight.requiredVdpaVfs(fixture.second)).thenReturn(1);
        when(fixture.preflight.freeVdpaVfs(44L)).thenReturn(2);
        doThrow(new RuntimeException("requested OVN chassis mismatch for NIC nic-2"))
                .when(fixture.preflight).verify(any(VirtualMachineProfile.class), any(Host.class));

        final MigrationPreflightResult result = fixture.service.preflight(99L, 44L);

        assertTrue(result.nicStatuses().get(0).allowed());
        assertFalse(result.nicStatuses().get(1).allowed());
        assertNull(result.nicStatuses().get(0).denialReason());
        assertTrue(result.nicStatuses().get(1).denialReason().contains("nic-2"));
    }

    private Fixture fixture() {
        final VMInstanceDao vmDao = mock(VMInstanceDao.class);
        final HostDao hostDao = mock(HostDao.class);
        final ServiceOfferingDao offeringDao = mock(ServiceOfferingDao.class);
        final NetworkModel networkModel = mock(NetworkModel.class);
        final MigrationVfPreflight preflight = mock(MigrationVfPreflight.class);
        final NicDao nicDao = mock(NicDao.class);
        final VMInstanceVO vm = mock(VMInstanceVO.class);
        final HostVO host = mock(HostVO.class);
        final NicProfile first = mock(NicProfile.class);
        final NicProfile second = mock(NicProfile.class);
        final NicVO firstInventory = mock(NicVO.class);
        final NicVO secondInventory = mock(NicVO.class);
        when(vm.getId()).thenReturn(99L);
        when(vm.getServiceOfferingId()).thenReturn(1L);
        when(hostDao.findById(44L)).thenReturn(host);
        when(vmDao.findById(99L)).thenReturn(vm);
        when(networkModel.getNicProfiles(vm)).thenReturn(List.of(first, second));
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);
        when(first.getUuid()).thenReturn("nic-1");
        when(second.getUuid()).thenReturn("nic-2");
        when(nicDao.listByVmId(99L)).thenReturn(List.of(firstInventory, secondInventory));
        when(firstInventory.getId()).thenReturn(1L);
        when(secondInventory.getId()).thenReturn(2L);
        return new Fixture(new MigrationPreflightServiceImpl(vmDao, hostDao, offeringDao,
                networkModel, preflight, nicDao), preflight, first, second);
    }

    private record Fixture(MigrationPreflightServiceImpl service, MigrationVfPreflight preflight,
            NicProfile first, NicProfile second) {
    }
}
