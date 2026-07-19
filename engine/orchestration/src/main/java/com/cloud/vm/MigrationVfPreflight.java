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

import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.network.NetworkVO;
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

    private final VfPoolManager vfPoolManager;
    private final NetworkDao networkDao;
    private final NetworkOfferingDao networkOfferingDao;

    @Inject
    public MigrationVfPreflight(final VfPoolManager vfPoolManager,
            final NetworkDao networkDao, final NetworkOfferingDao networkOfferingDao) {
        this.vfPoolManager = vfPoolManager;
        this.networkDao = networkDao;
        this.networkOfferingDao = networkOfferingDao;
    }

    public void verify(final VirtualMachineProfile profile, final Host destination) {
        final int required = countVdpaNics(profile);
        if (required == 0) {
            return;
        }
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
}
