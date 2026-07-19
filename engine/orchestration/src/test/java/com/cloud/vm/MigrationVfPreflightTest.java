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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;

import org.junit.Before;
import org.junit.Test;

import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
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
    private MigrationAuthoritativeGuard authoritativeGuard;
    private OvnChassisLookup chassisLookup;

    @Before
    public void setUp() {
        vfPoolManager = mock(VfPoolManager.class);
        networkDao = mock(NetworkDao.class);
        networkOfferingDao = mock(NetworkOfferingDao.class);
        preflight = new MigrationVfPreflight(vfPoolManager, networkDao, networkOfferingDao);
        chassisLookup = mock(OvnChassisLookup.class);
        when(chassisLookup.countActiveClaims(anyLong(), anyString())).thenReturn(1);
        when(chassisLookup.findChassisUuid(anyLong())).thenReturn("destination-chassis");
        preflight.setChassisLookup(chassisLookup);
        authoritativeGuard = mock(MigrationAuthoritativeGuard.class);
        when(authoritativeGuard.fencingReady(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(authoritativeGuard.placementReady(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(authoritativeGuard.quorumReady(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(authoritativeGuard.antiAffinityReady(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        preflight.setAuthoritativeGuard(authoritativeGuard);
    }

    @Test
    public void deniesWhenDestinationVfCapacityIsInsufficient() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final NicProfile nic = mock(NicProfile.class);
        final Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(19L);
        when(vm.getUuid()).thenReturn("vm-1");
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getUuid()).thenReturn("nic-1");
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(true);
        when(host.getId()).thenReturn(HOST_ID);
        when(vfPoolManager.countFreeForVdpa(HOST_ID)).thenReturn(0);
        when(chassisLookup.findChassisUuid(19L)).thenReturn("source-chassis");
        when(chassisLookup.hasExactActiveClaim(1L, "lsp-nic-1", "source-chassis")).thenReturn(true);

        assertThrows(CloudRuntimeException.class, () -> preflight.verify(profile, host));
        verify(vfPoolManager).countFreeForVdpa(HOST_ID);
    }

    @Test
    public void skipsCapacityCheckForNonVdpaProfile() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getUuid()).thenReturn("vm-1");
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
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(19L);
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
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(19L);
        when(vm.getUuid()).thenReturn("vm-1");
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
        when(authoritativeGuard.fencingReady(vm, host)).thenReturn(false);

        assertThrows(CloudRuntimeException.class,
                () -> preflight.verify(profile, host, MigrationVfPreflight.MigrationMode.LIVE));
    }

    @Test
    public void acceptsFencedHaColdMigrationWithoutChassisPin() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(19L);
        when(vm.isHaEnabled()).thenReturn(true);
        when(vm.getDetails()).thenReturn(java.util.Map.of("fencing.configured", "false"));
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getUuid()).thenReturn("nic-1");
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(true);
        when(host.getId()).thenReturn(HOST_ID);
        when(vfPoolManager.countFreeForVdpa(HOST_ID)).thenReturn(1);
        when(chassisLookup.findChassisUuid(19L)).thenReturn("source-chassis");
        when(chassisLookup.findChassisUuid(HOST_ID)).thenReturn("destination-chassis");
        when(chassisLookup.hasExactActiveClaim(1L, "lsp-nic-1", "source-chassis")).thenReturn(true);

        preflight.verify(profile, host, MigrationVfPreflight.MigrationMode.COLD);
    }

    @Test
    public void rejectsRequestedChassisMismatch() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        final OvnChassisLookup lookup = mock(OvnChassisLookup.class);
        preflight.setChassisLookup(lookup);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(19L);
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

    @Test
    public void coldHostdevRequiresDestinationVfCapacity() {
        final VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        final NicProfile nic = mock(NicProfile.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(19L);
        when(profile.getNics()).thenReturn(java.util.List.of(nic));
        when(nic.isUseHwOffload()).thenReturn(true);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(offering);
        when(offering.isVdpaEnabled()).thenReturn(false);
        when(host.getId()).thenReturn(HOST_ID);
        when(vfPoolManager.countFree(HOST_ID)).thenReturn(0);

        assertTrue(preflight.requiresColdVfMigration(profile));
        assertThrows(CloudRuntimeException.class,
                () -> preflight.verify(profile, host, MigrationVfPreflight.MigrationMode.COLD));
    }
}
