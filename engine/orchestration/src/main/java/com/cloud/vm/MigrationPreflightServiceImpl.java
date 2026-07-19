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
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.NicVO;

@Component
public class MigrationPreflightServiceImpl implements MigrationPreflightService {

    private final VMInstanceDao vmDao;
    private final HostDao hostDao;
    private final ServiceOfferingDao offeringDao;
    private final NetworkModel networkModel;
    private final MigrationVfPreflight preflight;
    private final NicDao nicDao;

    @Inject
    public MigrationPreflightServiceImpl(final VMInstanceDao vmDao, final HostDao hostDao,
            final ServiceOfferingDao offeringDao, final NetworkModel networkModel,
            final MigrationVfPreflight preflight, final NicDao nicDao) {
        this.vmDao = vmDao;
        this.hostDao = hostDao;
        this.offeringDao = offeringDao;
        this.networkModel = networkModel;
        this.preflight = preflight;
        this.nicDao = nicDao;
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
        final List<NicVO> inventory = nicDao.listByVmId(vmId);
        if (inventory.size() != profile.getNics().size()
                || inventory.stream().map(NicVO::getId).collect(java.util.stream.Collectors.toSet()).size() != inventory.size()
                || profile.getNics().stream().map(NicProfile::getId).collect(java.util.stream.Collectors.toSet()).size() != profile.getNics().size()
                || !inventory.stream().map(NicVO::getId).collect(java.util.stream.Collectors.toSet()).equals(
                        profile.getNics().stream().map(NicProfile::getId).collect(java.util.stream.Collectors.toSet()))) {
            return new MigrationPreflightResult(false, vmId, destinationHostId, 0, 0,
                    "VM NIC inventory/profile bijection failed", List.of(), true, false);
        }
        final int required = preflight.requiredVdpaVfs(profile);
        final int free = preflight.freeVdpaVfs(destinationHostId);
        try {
            preflight.verify(profile, destination);
            return new MigrationPreflightResult(true, vmId, destinationHostId, required, free, null,
                    nicStatuses(profile, destinationHostId, true, null));
        } catch (RuntimeException e) {
            final String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new MigrationPreflightResult(false, vmId, destinationHostId, required, free,
                    reason, nicStatuses(profile, destinationHostId, false, reason),
                    !reason.contains("requested OVN chassis"),
                    reason.contains("SR-IOV hostdev"));
        }
    }

    private List<MigrationNicPreflightStatus> nicStatuses(final VirtualMachineProfile profile,
            final long destinationHostId, final boolean globallyAllowed, final String denialReason) {
        if (profile.getNics() == null) {
            return List.of();
        }
        final int totalRequired = profile.getNics().stream().mapToInt(preflight::requiredVdpaVfs).sum();
        final int totalFree = totalRequired == 0 ? 0 : preflight.freeVdpaVfs(destinationHostId);
        final boolean capacityAdmissionFailure = totalRequired > totalFree;
        final boolean perNicDenial = profile.getNics().stream()
                .map(nic -> preflight.evaluateNic(profile, nic, hostDao.findById(destinationHostId)))
                .anyMatch(decision -> decision != null && !decision.allowed());
        return profile.getNics().stream()
                .map(nic -> {
                    final int required = preflight.requiredVdpaVfs(nic);
                    final int free = required == 0 ? 0 : preflight.freeVdpaVfs(destinationHostId);
                    final int consumed = profile.getNics().stream()
                            .filter(candidate -> candidate.getId() < nic.getId())
                            .mapToInt(preflight::requiredVdpaVfs).sum();
                    final int availableForNic = required == 0 ? 0 : Math.max(0, free - consumed);
                    final boolean capacityDenied = required > availableForNic;
                    final boolean hostdevDenied = nic.isUseHwOffload() && required == 0
                            && denialReason != null && denialReason.contains("SR-IOV hostdev");
                    final MigrationVfPreflight.NicPreflightDecision decision = preflight.evaluateNic(profile, nic,
                            hostDao.findById(destinationHostId));
                    final String nicReason = capacityDenied ? String.format("NIC %s requires %d vDPA VF(s), but only %d are free",
                            nic.getUuid(), required, availableForNic) : hostdevDenied ? denialReason
                            : decision != null && !decision.allowed() ? decision.denialReason() : null;
                    final boolean decisionAllowed = decision == null || decision.allowed();
                    return new MigrationNicPreflightStatus(nic.getUuid(), decisionAllowed
                            && nicReason == null && (globallyAllowed || perNicDenial || capacityAdmissionFailure),
                            required, availableForNic, nicReason, decision.macAddress(), decision.ifaceId(),
                            decision.requestedChassis(), decision.sourceChassis(), decision.destinationChassis());
                })
                .toList();
    }
}
