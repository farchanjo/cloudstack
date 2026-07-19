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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import com.cloud.host.Host;
import com.cloud.network.NetworkVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.OvnChassisLookup;
import com.cloud.network.router.VfPoolManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class MigrationVfPreflightTest {

    private static final long HOST_ID = 42L;
    private static final long NETWORK_ID = 7L;

    private VfPoolManager vfPoolManager;
    private NetworkDao networkDao;
    private NetworkOfferingDao networkOfferingDao;
    private MigrationVfPreflight preflight;

    @Before
    public void setUp() {
        vfPoolManager = mock(VfPoolManager.class);
        networkDao = mock(NetworkDao.class);
        networkOfferingDao = mock(NetworkOfferingDao.class);
        preflight = new MigrationVfPreflight(vfPoolManager, networkDao, networkOfferingDao);
    }

    @Test
    public void deniesWhenDestinationVfCapacityIsInsufficient() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final NicProfile nic = mock(NicProfile.class);
        final Host host = mock(Host.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(true);
        when(host.getId()).thenReturn(HOST_ID);
        when(vfPoolManager.countFreeForVdpa(HOST_ID)).thenReturn(0);

        assertThrows(CloudRuntimeException.class, () -> preflight.verify(profile, host));
        verify(vfPoolManager).countFreeForVdpa(HOST_ID);
    }

    @Test
    public void skipsCapacityCheckForNonVdpaProfile() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final Host host = mock(Host.class);
        when(profile.getNics()).thenReturn(java.util.List.of());

        preflight.verify(profile, host);

        verify(vfPoolManager, org.mockito.Mockito.never()).countFreeForVdpa(HOST_ID);
    }

    @Test
    public void rejectsHostdevForLiveMigration() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final NicProfile nic = mock(NicProfile.class);
        final Host host = mock(Host.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.isUseHwOffload()).thenReturn(true);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(false);

        assertThrows(CloudRuntimeException.class,
                () -> preflight.verify(profile, host, MigrationVfPreflight.MigrationMode.LIVE));
    }

    @Test
    public void rejectsUnfencedHaLiveMigration() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.isHaEnabled()).thenReturn(true);
        when(vm.getDetails()).thenReturn(java.util.Map.of());
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(true);
        when(host.getId()).thenReturn(HOST_ID);
        when(vfPoolManager.countFreeForVdpa(HOST_ID)).thenReturn(1);

        assertThrows(CloudRuntimeException.class,
                () -> preflight.verify(profile, host, MigrationVfPreflight.MigrationMode.LIVE));
    }

    @Test
    public void acceptsFencedHaColdMigrationWithoutChassisPin() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.isHaEnabled()).thenReturn(true);
        when(vm.getDetails()).thenReturn(java.util.Map.of("fencing.configured", "false"));
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(true);
        when(host.getId()).thenReturn(HOST_ID);
        when(vfPoolManager.countFreeForVdpa(HOST_ID)).thenReturn(1);

        preflight.verify(profile, host, MigrationVfPreflight.MigrationMode.COLD);
    }

    @Test
    public void rejectsRequestedChassisMismatch() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        final OvnChassisLookup lookup = mock(OvnChassisLookup.class);
        preflight.setChassisLookup(lookup);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getDetails()).thenReturn(java.util.Map.of("ovn.requested_chassis", "source-chassis"));
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(true);
        when(host.getId()).thenReturn(HOST_ID);
        when(lookup.resolveRequestedChassis(vm.getDetails())).thenReturn("source-chassis");
        when(lookup.findChassisUuid(HOST_ID)).thenReturn("destination-chassis");

        assertThrows(CloudRuntimeException.class, () -> preflight.verify(profile, host));
    }
}
