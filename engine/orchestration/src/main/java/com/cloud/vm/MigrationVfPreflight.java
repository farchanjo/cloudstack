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

import javax.inject.Inject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.network.NetworkVO;
import com.cloud.network.ovn.OvnChassisLookup;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.router.VfPoolManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Advisory destination capacity check for vDPA migration.
 *
 * <p>The check deliberately does not reserve hardware. The destination
 * allocation is still authoritative and must fail closed if capacity changes
 * between this check and agent preparation.</p>
 */
@Component
public class MigrationVfPreflight {

    public enum MigrationMode {
        LIVE,
        COLD
    }

    private final VfPoolManager vfPoolManager;
    private final NetworkDao networkDao;
    private final NetworkOfferingDao networkOfferingDao;
    private OvnChassisLookup chassisLookup;

    @Inject
    public MigrationVfPreflight(final VfPoolManager vfPoolManager,
            final NetworkDao networkDao, final NetworkOfferingDao networkOfferingDao) {
        this.vfPoolManager = vfPoolManager;
        this.networkDao = networkDao;
        this.networkOfferingDao = networkOfferingDao;
    }

    @Autowired(required = false)
    public void setChassisLookup(final OvnChassisLookup chassisLookup) {
        this.chassisLookup = chassisLookup;
    }

    public void verify(final VirtualMachineProfile profile, final Host destination) {
        verify(profile, destination, MigrationMode.LIVE);
    }

    public boolean requiresVdpa(final VirtualMachineProfile profile) {
        return countVdpaNics(profile) > 0;
    }

    public int requiredVdpaVfs(final VirtualMachineProfile profile) {
        return countVdpaNics(profile);
    }

    public int freeVdpaVfs(final long hostId) {
        return vfPoolManager.countFreeForVdpa(hostId);
    }

    public void verify(final VirtualMachineProfile profile, final Host destination,
            final MigrationMode mode) {
        validateHostdev(profile, mode);
        final int required = countVdpaNics(profile);
        if (required == 0) {
            return;
        }
        validateRequestedChassis(profile, destination);
        validateHaAndPlacement(profile, mode);
        final int free = vfPoolManager.countFreeForVdpa(destination.getId());
        if (free < required) {
            final String message = String.format(
                    "VM %s requires %d vDPA VF(s) on host %s, but only %d are free",
                    profile.getVirtualMachine().getUuid(), required, destination.getId(), free);
            throw new CloudRuntimeException(message);
        }
    }

    private int countVdpaNics(final VirtualMachineProfile profile) {
        int required = 0;
        if (profile.getNics() == null) {
            return required;
        }
        for (final NicProfile nic : profile.getNics()) {
            final NetworkVO network = networkDao.findById(nic.getNetworkId());
            if (network == null) {
                continue;
            }
            final NetworkOfferingVO offering = networkOfferingDao.findById(network.getNetworkOfferingId());
            if (offering != null && offering.isVdpaEnabled()) {
                required++;
            }
        }
        return required;
    }

    private void validateHostdev(final VirtualMachineProfile profile, final MigrationMode mode) {
        if (mode != MigrationMode.LIVE || profile.getNics() == null) {
            return;
        }
        for (final NicProfile nic : profile.getNics()) {
            if (!nic.isUseHwOffload()) {
                continue;
            }
            final NetworkVO network = networkDao.findById(nic.getNetworkId());
            final NetworkOfferingVO offering = network == null ? null
                    : networkOfferingDao.findById(network.getNetworkOfferingId());
            if (offering == null || !offering.isVdpaEnabled()) {
                throw new CloudRuntimeException(String.format(
                        "VM %s NIC %s uses SR-IOV hostdev passthrough; live migration is not supported",
                        profile.getVirtualMachine().getUuid(), nic.getUuid()));
            }
        }
    }

    private void validateRequestedChassis(final VirtualMachineProfile profile, final Host destination) {
        final String requested = profile.getVirtualMachine().getDetails() == null ? null
                : profile.getVirtualMachine().getDetails().get("ovn.requested_chassis");
        if (requested == null || requested.isBlank()) {
            return;
        }
        if (chassisLookup == null) {
            throw new CloudRuntimeException("requested-chassis validation is unavailable; refusing vDPA migration");
        }
        final String destinationChassis = chassisLookup.findChassisUuid(destination.getId());
        if (destinationChassis == null || !requested.equals(destinationChassis)) {
            throw new CloudRuntimeException(String.format(
                    "requested OVN chassis %s does not match destination chassis %s",
                    requested, destinationChassis));
        }
    }

    private void validateHaAndPlacement(final VirtualMachineProfile profile, final MigrationMode mode) {
        final VirtualMachine vm = profile.getVirtualMachine();
        final java.util.Map<String, String> details = vm.getDetails();
        if (mode == MigrationMode.LIVE && vm.isHaEnabled()
                && !Boolean.parseBoolean(details == null ? null : details.get("fencing.configured"))) {
            throw new CloudRuntimeException("live vDPA migration requires fencing for HA restart safety");
        }
        if (details == null || !Boolean.parseBoolean(details.get("k8s.control_plane_member"))) {
            return;
        }
        if (!Boolean.parseBoolean(details.get("k8s.quorum.safe"))) {
            throw new CloudRuntimeException("vDPA migration would violate Kubernetes control-plane quorum");
        }
        if (Boolean.parseBoolean(details.get("k8s.anti_affinity_violation"))) {
            throw new CloudRuntimeException("vDPA migration would violate Kubernetes control-plane anti-affinity");
        }
    }
}
