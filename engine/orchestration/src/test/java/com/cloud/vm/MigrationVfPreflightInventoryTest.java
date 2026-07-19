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

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Test;

import com.cloud.host.Host;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.router.VfPoolManager;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;

public class MigrationVfPreflightInventoryTest {

    @Test
    public void rejectsMissingLiveProfileBeforeAdmission() {
        final NicDao nicDao = mock(NicDao.class);
        final MigrationVfPreflight preflight = new MigrationVfPreflight(mock(VfPoolManager.class),
                mock(NetworkDao.class), mock(NetworkOfferingDao.class), nicDao);
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final NicProfile profileNic = mock(NicProfile.class);
        final Host destination = readyHost();
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(profile.getId()).thenReturn(99L);
        when(profile.getNics()).thenReturn(List.of(profileNic));
        when(profileNic.getId()).thenReturn(7L);
        when(nicDao.listByVmId(99L)).thenReturn(List.of());

        assertThrows(CloudRuntimeException.class, () -> preflight.verify(profile, destination));
    }

    @Test
    public void rejectsStorageProfileWithDuplicateIdentity() {
        final NicDao nicDao = mock(NicDao.class);
        final MigrationVfPreflight preflight = new MigrationVfPreflight(mock(VfPoolManager.class),
                mock(NetworkDao.class), mock(NetworkOfferingDao.class), nicDao);
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final NicProfile first = mock(NicProfile.class);
        final NicProfile second = mock(NicProfile.class);
        final NicVO inventoryNic = mock(NicVO.class);
        final Host destination = readyHost();
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(profile.getId()).thenReturn(99L);
        when(profile.getNics()).thenReturn(List.of(first, second));
        when(first.getId()).thenReturn(7L);
        when(second.getId()).thenReturn(7L);
        when(inventoryNic.getId()).thenReturn(7L);
        when(nicDao.listByVmId(99L)).thenReturn(List.of(inventoryNic, mock(NicVO.class)));

        assertThrows(CloudRuntimeException.class,
                () -> preflight.verify(profile, destination, MigrationVfPreflight.MigrationMode.COLD));
    }

    @Test
    public void rejectsProfileThatOmitsOneAuthoritativeNic() {
        final NicDao nicDao = mock(NicDao.class);
        final MigrationVfPreflight preflight = new MigrationVfPreflight(mock(VfPoolManager.class),
                mock(NetworkDao.class), mock(NetworkOfferingDao.class), nicDao);
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final NicProfile one = mock(NicProfile.class);
        final NicProfile two = mock(NicProfile.class);
        final NicVO inventoryOne = mock(NicVO.class);
        final NicVO inventoryTwo = mock(NicVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(profile.getId()).thenReturn(99L);
        when(profile.getNics()).thenReturn(List.of(one));
        when(one.getId()).thenReturn(7L);
        when(inventoryOne.getId()).thenReturn(7L);
        when(inventoryTwo.getId()).thenReturn(8L);
        when(nicDao.listByVmId(99L)).thenReturn(List.of(inventoryOne, inventoryTwo));

        assertThrows(CloudRuntimeException.class, () -> preflight.verify(profile, readyHost()));
    }

    private Host readyHost() {
        final Host host = mock(Host.class);
        when(host.getState()).thenReturn(Host.State.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        return host;
    }
}
