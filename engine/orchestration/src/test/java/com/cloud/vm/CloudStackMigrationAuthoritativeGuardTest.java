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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.outofbandmanagement.OutOfBandManagementService;
import org.junit.Test;

import com.cloud.ha.FenceBuilder;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceState;
import com.cloud.vm.dao.VMInstanceDao;

public class CloudStackMigrationAuthoritativeGuardTest {

    @Test
    public void requiresOobAndFenceBuilderForMigration() {
        final OutOfBandManagementService oob = mock(OutOfBandManagementService.class);
        final HostDao hostDao = mock(HostDao.class);
        final HostVO source = readyHost(1L);
        final Host destination = readyHost(2L);
        final VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(1L);
        when(hostDao.findById(1L)).thenReturn(source);
        when(oob.isOutOfBandManagementEnabled(source)).thenReturn(true);
        when(oob.isOutOfBandManagementEnabled(destination)).thenReturn(true);
        final CloudStackMigrationAuthoritativeGuard guard = newGuard(oob, List.of(mock(FenceBuilder.class)), hostDao);

        assertTrue(guard.fencingReady(vm, destination));
        assertFalse(newGuard(oob, List.of(), hostDao).fencingReady(vm, destination));
    }

    @Test
    public void quorumDeniesHaVmWithFewerThanThreeRunningMembers() {
        final VMInstanceDao vmDao = mock(VMInstanceDao.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final Host destination = readyHost(2L);
        when(vm.isHaEnabled()).thenReturn(true);
        when(destination.getClusterId()).thenReturn(9L);
        when(vmDao.listByClusterId(9L)).thenReturn(List.of(mock(VMInstanceVO.class), mock(VMInstanceVO.class)));
        final CloudStackMigrationAuthoritativeGuard guard = newGuard(vmDao);

        assertFalse(guard.quorumReady(vm, destination));
    }

    private CloudStackMigrationAuthoritativeGuard newGuard(final VMInstanceDao vmDao) {
        return new CloudStackMigrationAuthoritativeGuard(null, List.of(), mock(HostDao.class), vmDao,
                mock(AffinityGroupDao.class), mock(AffinityGroupVMMapDao.class));
    }

    private CloudStackMigrationAuthoritativeGuard newGuard(final OutOfBandManagementService oob,
            final List<FenceBuilder> fences, final HostDao hostDao) {
        return new CloudStackMigrationAuthoritativeGuard(oob, fences, hostDao, mock(VMInstanceDao.class),
                mock(AffinityGroupDao.class), mock(AffinityGroupVMMapDao.class));
    }

    private HostVO readyHost(final long id) {
        final HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        return host;
    }
}
