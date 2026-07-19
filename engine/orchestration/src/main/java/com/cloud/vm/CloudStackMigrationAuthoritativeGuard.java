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

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.affinity.AffinityGroupVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.outofbandmanagement.OutOfBandManagementService;
import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceState;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.ha.FenceBuilder;

/** Production authoritative adapter for the migration policy port. */
@Component
public class CloudStackMigrationAuthoritativeGuard implements MigrationAuthoritativeGuard {

    private static final String HOST_ANTI_AFFINITY = "host anti-affinity";
    private final OutOfBandManagementService outOfBandManagementService;
    private final List<FenceBuilder> fenceBuilders;
    private final HostDao hostDao;
    private final VMInstanceDao vmDao;
    private final AffinityGroupDao affinityGroupDao;
    private final AffinityGroupVMMapDao affinityGroupVMMapDao;

    @Inject
    public CloudStackMigrationAuthoritativeGuard(final OutOfBandManagementService outOfBandManagementService,
            final List<FenceBuilder> fenceBuilders, final HostDao hostDao, final VMInstanceDao vmDao,
            final AffinityGroupDao affinityGroupDao, final AffinityGroupVMMapDao affinityGroupVMMapDao) {
        this.outOfBandManagementService = outOfBandManagementService;
        this.fenceBuilders = fenceBuilders;
        this.hostDao = hostDao;
        this.vmDao = vmDao;
        this.affinityGroupDao = affinityGroupDao;
        this.affinityGroupVMMapDao = affinityGroupVMMapDao;
    }

    @Override
    public boolean fencingReady(final VirtualMachine vm, final Host destination) {
        final Host source = vm.getHostId() == null ? null : hostDao.findById(vm.getHostId());
        return source != null && destination != null && fenceBuilders != null && !fenceBuilders.isEmpty()
                && outOfBandManagementService != null
                && outOfBandManagementService.isOutOfBandManagementEnabled(source)
                && outOfBandManagementService.isOutOfBandManagementEnabled(destination);
    }

    @Override
    public boolean placementReady(final VirtualMachine vm, final Host destination) {
        if (destination == null || destination.getStatus() != Status.Up
                || destination.getResourceState() != ResourceState.Enabled) {
            return false;
        }
        for (final var mapping : affinityGroupVMMapDao.listByInstanceId(vm.getId())) {
            final AffinityGroupVO group = affinityGroupDao.findById(mapping.getAffinityGroupId());
            if (group == null || group.getType() == null) {
                return false;
            }
            if (HOST_ANTI_AFFINITY.equalsIgnoreCase(group.getType())) {
                for (final Long memberId : affinityGroupVMMapDao.listVmIdsByAffinityGroup(group.getId())) {
                    if (memberId.equals(vm.getId())) {
                        continue;
                    }
                    final VMInstanceVO member = vmDao.findById(memberId);
                    if (member == null || java.util.Objects.equals(member.getHostId(), destination.getId())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean quorumReady(final VirtualMachine vm, final Host destination) {
        if (!vm.isHaEnabled()) {
            return true;
        }
        final List<VMInstanceVO> running = vmDao.listByClusterId(destination.getClusterId()).stream()
                .filter(instance -> instance.getState() == VirtualMachine.State.Running)
                .toList();
        return running.size() >= 3;
    }

    @Override
    public boolean antiAffinityReady(final VirtualMachine vm, final Host destination) {
        return placementReady(vm, destination);
    }
}
