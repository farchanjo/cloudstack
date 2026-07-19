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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Test;

import com.cloud.host.HostVO;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.ovn.OvnChassisLookup;
import com.cloud.network.router.MigrationPreflightResult;
import com.cloud.network.router.VfPoolManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
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
        when(fixture.preflight.evaluateNic(any(VirtualMachineProfile.class), any(NicProfile.class), any(Host.class)))
                .thenReturn(new MigrationVfPreflight.NicPreflightDecision(true, null,
                        "02:aa:00:00:00:01", "lsp-nic-1", null, "source", "destination"));
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
        when(fixture.preflight.evaluateNic(any(VirtualMachineProfile.class), any(NicProfile.class), any(Host.class)))
                .thenReturn(new MigrationVfPreflight.NicPreflightDecision(true, null,
                        "02:aa:00:00:00:01", "lsp-nic-1", null, "source", "destination"));
        when(fixture.preflight.evaluateNic(any(VirtualMachineProfile.class), org.mockito.Mockito.eq(fixture.second), any(Host.class)))
                .thenReturn(new MigrationVfPreflight.NicPreflightDecision(false,
                        "expected exactly one source Port_Binding claim for NIC nic-2, found 0",
                        "02:bb:00:00:00:02", "lsp-nic-2", null, "source", "destination"));
        doThrow(new RuntimeException("requested OVN chassis mismatch for NIC nic-2"))
                .when(fixture.preflight).verify(any(VirtualMachineProfile.class), any(Host.class));

        final MigrationPreflightResult result = fixture.service.preflight(99L, 44L);

        assertTrue(result.nicStatuses().get(0).allowed());
        assertFalse(result.nicStatuses().get(1).allowed());
        assertNull(result.nicStatuses().get(0).denialReason());
        assertTrue(result.nicStatuses().get(1).denialReason().contains("nic-2"));
    }

    @Test
    public void realPreflightOutputDeniesOnlyNicWithWrongPortBinding() {
        final VMInstanceDao vmDao = mock(VMInstanceDao.class);
        final HostDao hostDao = mock(HostDao.class);
        final ServiceOfferingDao offeringDao = mock(ServiceOfferingDao.class);
        final NetworkOrchestrationService networkOrchestrationService = mock(NetworkOrchestrationService.class);
        final NicDao nicDao = mock(NicDao.class);
        final NetworkDao networkDao = mock(NetworkDao.class);
        final NetworkOfferingDao networkOfferingDao = mock(NetworkOfferingDao.class);
        final VfPoolManager pool = mock(VfPoolManager.class);
        final OvnChassisLookup lookup = mock(OvnChassisLookup.class);
        final MigrationAuthoritativeGuard guard = mock(MigrationAuthoritativeGuard.class);
        final MigrationVfPreflight preflight = new MigrationVfPreflight(pool, networkDao,
                networkOfferingDao, nicDao);
        final VMInstanceVO vm = mock(VMInstanceVO.class);
        final HostVO host = mock(HostVO.class);
        final NicProfile first = mock(NicProfile.class);
        final NicProfile second = mock(NicProfile.class);
        final NicVO firstInventory = mock(NicVO.class);
        final NicVO secondInventory = mock(NicVO.class);
        final NetworkVO network = mock(NetworkVO.class);
        final NetworkOfferingVO networkOffering = mock(NetworkOfferingVO.class);
        when(vm.getId()).thenReturn(99L);
        when(vm.getServiceOfferingId()).thenReturn(1L);
        when(vm.getHostId()).thenReturn(10L);
        when(host.getId()).thenReturn(44L);
        when(hostDao.findById(44L)).thenReturn(host);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getClusterId()).thenReturn(1L);
        when(host.getDataCenterId()).thenReturn(1L);
        when(networkOrchestrationService.getNicProfiles(vm)).thenReturn(List.of(first, second));
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);
        when(first.getUuid()).thenReturn("nic-1");
        when(second.getUuid()).thenReturn("nic-2");
        when(first.getMacAddress()).thenReturn("02:aa:00:00:00:01");
        when(second.getMacAddress()).thenReturn("02:bb:00:00:00:02");
        when(first.getNetworkId()).thenReturn(7L);
        when(second.getNetworkId()).thenReturn(7L);
        when(nicDao.listByVmId(99L)).thenReturn(List.of(firstInventory, secondInventory));
        when(firstInventory.getId()).thenReturn(1L);
        when(secondInventory.getId()).thenReturn(2L);
        when(networkDao.findById(7L)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(11L);
        when(networkOfferingDao.findById(11L)).thenReturn(networkOffering);
        when(networkOffering.isVdpaEnabled()).thenReturn(true);
        when(pool.countFreeForVdpa(44L)).thenReturn(2);
        when(lookup.findChassisUuid(10L)).thenReturn("source-chassis");
        when(lookup.findChassisUuid(44L)).thenReturn("destination-chassis");
        when(lookup.countActiveClaims(1L, "lsp-nic-1")).thenReturn(1);
        when(lookup.countActiveClaims(1L, "lsp-nic-2")).thenReturn(1);
        when(lookup.hasExactActiveClaim(1L, "lsp-nic-1", "source-chassis")).thenReturn(true);
        when(lookup.hasExactActiveClaim(1L, "lsp-nic-2", "source-chassis")).thenReturn(false);
        when(guard.fencingReady(vm, host)).thenReturn(true);
        when(guard.placementReady(vm, host)).thenReturn(true);
        when(guard.quorumReady(vm, host)).thenReturn(true);
        when(guard.antiAffinityReady(vm, host)).thenReturn(true);
        preflight.setChassisLookup(lookup);
        preflight.setAuthoritativeGuard(guard);

        final MigrationPreflightResult result = new MigrationPreflightServiceImpl(vmDao, hostDao,
                offeringDao, networkOrchestrationService, preflight, nicDao).preflight(99L, 44L);

        assertTrue(result.nicStatuses().get(0).allowed());
        assertFalse(result.nicStatuses().get(1).allowed());
        assertEquals("lsp-nic-2", result.nicStatuses().get(1).ifaceId());
        assertEquals("02:bb:00:00:00:02", result.nicStatuses().get(1).macAddress());
        assertTrue(result.nicStatuses().get(1).denialReason().contains("nic-2"));
    }

    private Fixture fixture() {
        final VMInstanceDao vmDao = mock(VMInstanceDao.class);
        final HostDao hostDao = mock(HostDao.class);
        final ServiceOfferingDao offeringDao = mock(ServiceOfferingDao.class);
        final NetworkOrchestrationService networkOrchestrationService = mock(NetworkOrchestrationService.class);
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
        when(networkOrchestrationService.getNicProfiles(vm)).thenReturn(List.of(first, second));
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);
        when(first.getUuid()).thenReturn("nic-1");
        when(second.getUuid()).thenReturn("nic-2");
        when(nicDao.listByVmId(99L)).thenReturn(List.of(firstInventory, secondInventory));
        when(firstInventory.getId()).thenReturn(1L);
        when(secondInventory.getId()).thenReturn(2L);
        return new Fixture(new MigrationPreflightServiceImpl(vmDao, hostDao, offeringDao,
                networkOrchestrationService, preflight, nicDao), preflight, first, second);
    }

    private static final class Fixture {
        private final MigrationPreflightServiceImpl service;
        private final MigrationVfPreflight preflight;
        private final NicProfile first;
        private final NicProfile second;

        private Fixture(final MigrationPreflightServiceImpl service, final MigrationVfPreflight preflight,
                final NicProfile first, final NicProfile second) {
            this.service = service;
            this.preflight = preflight;
            this.first = first;
            this.second = second;
        }
    }
}
