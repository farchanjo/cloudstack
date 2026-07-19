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

import org.springframework.stereotype.Component;

import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.router.MigrationNicPreflightStatus;
import com.cloud.network.router.MigrationPreflightResult;
import com.cloud.network.router.MigrationPreflightService;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class MigrationPreflightServiceImpl implements MigrationPreflightService {

    private final VMInstanceDao vmDao;
    private final HostDao hostDao;
    private final ServiceOfferingDao offeringDao;
    private final NetworkModel networkModel;
    private final MigrationVfPreflight preflight;

    @Inject
    public MigrationPreflightServiceImpl(final VMInstanceDao vmDao, final HostDao hostDao,
            final ServiceOfferingDao offeringDao, final NetworkModel networkModel,
            final MigrationVfPreflight preflight) {
        this.vmDao = vmDao;
        this.hostDao = hostDao;
        this.offeringDao = offeringDao;
        this.networkModel = networkModel;
        this.preflight = preflight;
    }

    @Override
    public MigrationPreflightResult preflight(final long vmId, final long destinationHostId) {
        final HostVO destination = hostDao.findById(destinationHostId);
        final VirtualMachine vm = vmDao.findById(vmId);
        if (destination == null || vm == null) {
            return new MigrationPreflightResult(false, vmId, destinationHostId, 0, 0,
                    "VM or destination host was not found");
        }
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, null,
                offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);
        for (final NicProfile nic : networkModel.getNicProfiles(vm)) {
            profile.addNic(nic);
        }
        final int required = preflight.requiredVdpaVfs(profile);
        final int free = preflight.freeVdpaVfs(destinationHostId);
        try {
            preflight.verify(profile, destination);
            return new MigrationPreflightResult(true, vmId, destinationHostId, required, free, null,
                    nicStatuses(profile, true, null, free));
        } catch (RuntimeException e) {
            return new MigrationPreflightResult(false, vmId, destinationHostId, required, free,
                    e.getMessage(), nicStatuses(profile, false, e.getMessage(), free));
        }
    }

    private List<MigrationNicPreflightStatus> nicStatuses(final VirtualMachineProfile profile,
            final boolean allowed, final String denialReason, final int free) {
        if (profile.getNics() == null) {
            return List.of();
        }
        return profile.getNics().stream()
                .map(nic -> new MigrationNicPreflightStatus(nic.getUuid(), allowed,
                        preflight.requiredVdpaVfs(profile), free, denialReason))
                .toList();
    }
}
