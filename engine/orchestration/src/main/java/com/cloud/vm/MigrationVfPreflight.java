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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.network.ovn.OvnChassisLookup;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.VfPoolManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.vm.dao.NicDao;
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

    public static final class NicPreflightDecision {
        private final boolean allowed;
        private final String denialReason;
        private final String macAddress;
        private final String ifaceId;
        private final String requestedChassis;
        private final String sourceChassis;
        private final String destinationChassis;

        public NicPreflightDecision(final boolean allowed, final String denialReason, final String macAddress,
                final String ifaceId, final String requestedChassis, final String sourceChassis,
                final String destinationChassis) {
            this.allowed = allowed;
            this.denialReason = denialReason;
            this.macAddress = macAddress;
            this.ifaceId = ifaceId;
            this.requestedChassis = requestedChassis;
            this.sourceChassis = sourceChassis;
            this.destinationChassis = destinationChassis;
        }

        public boolean allowed() { return allowed; }
        public String denialReason() { return denialReason; }
        public String macAddress() { return macAddress; }
        public String ifaceId() { return ifaceId; }
        public String requestedChassis() { return requestedChassis; }
        public String sourceChassis() { return sourceChassis; }
        public String destinationChassis() { return destinationChassis; }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NicPreflightDecision)) {
                return false;
            }
            final NicPreflightDecision that = (NicPreflightDecision) other;
            return allowed == that.allowed && Objects.equals(denialReason, that.denialReason)
                    && Objects.equals(macAddress, that.macAddress) && Objects.equals(ifaceId, that.ifaceId)
                    && Objects.equals(requestedChassis, that.requestedChassis)
                    && Objects.equals(sourceChassis, that.sourceChassis)
                    && Objects.equals(destinationChassis, that.destinationChassis);
        }

        @Override
        public int hashCode() {
            return Objects.hash(allowed, denialReason, macAddress, ifaceId, requestedChassis,
                    sourceChassis, destinationChassis);
        }

        @Override
        public String toString() {
            return "NicPreflightDecision[allowed=" + allowed + ", denialReason=" + denialReason
                    + ", macAddress=" + macAddress + ", ifaceId=" + ifaceId + ", requestedChassis="
                    + requestedChassis + ", sourceChassis=" + sourceChassis + ", destinationChassis="
                    + destinationChassis + "]";
        }
    }

    public enum MigrationMode {
        LIVE,
        COLD
    }

    private final VfPoolManager vfPoolManager;
    private final NetworkDao networkDao;
    private final NetworkOfferingDao networkOfferingDao;
    private final NicDao nicDao;
    private final ConcurrentMap<String, ReentrantLock> admissionLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ReentrantLock> clusterLocks = new ConcurrentHashMap<>();
    private OvnChassisLookup chassisLookup;
    private MigrationAuthoritativeGuard authoritativeGuard;

    @Inject
    public MigrationVfPreflight(final VfPoolManager vfPoolManager,
            final NetworkDao networkDao, final NetworkOfferingDao networkOfferingDao,
            final NicDao nicDao) {
        this.vfPoolManager = vfPoolManager;
        this.networkDao = networkDao;
        this.networkOfferingDao = networkOfferingDao;
        this.nicDao = nicDao;
    }

    public MigrationVfPreflight(final VfPoolManager vfPoolManager,
            final NetworkDao networkDao, final NetworkOfferingDao networkOfferingDao) {
        this(vfPoolManager, networkDao, networkOfferingDao, null);
    }

    @Autowired(required = false)
    public void setChassisLookup(final OvnChassisLookup chassisLookup) {
        this.chassisLookup = chassisLookup;
    }

    @Autowired(required = false)
    public void setAuthoritativeGuard(final MigrationAuthoritativeGuard authoritativeGuard) {
        this.authoritativeGuard = authoritativeGuard;
    }

    public void verify(final VirtualMachineProfile profile, final Host destination) {
        verify(profile, destination, MigrationMode.LIVE);
    }

    public boolean requiresVdpa(final VirtualMachineProfile profile) {
        return countVdpaNics(profile) > 0;
    }

    public boolean requiresColdVfMigration(final VirtualMachineProfile profile) {
        return requiresVdpa(profile) || (profile.getNics() != null
                && profile.getNics().stream().anyMatch(NicProfile::isUseHwOffload));
    }

    public int requiredVdpaVfs(final VirtualMachineProfile profile) {
        return countVdpaNics(profile);
    }

    public int freeVdpaVfs(final long hostId) {
        if (vfPoolManager == null) {
            throw new CloudRuntimeException("vDPA VF pool manager is unavailable");
        }
        return vfPoolManager.countFreeForVdpa(hostId);
    }

    public String expectedChassis(final Host destination) {
        return chassisLookup == null ? null : chassisLookup.findChassisUuid(destination.getId());
    }

    public void verify(final VirtualMachineProfile profile, final Host destination,
            final MigrationMode mode) {
        final String key = profile.getVirtualMachine().getUuid() == null
                ? "identity-" + System.identityHashCode(profile.getVirtualMachine())
                : profile.getVirtualMachine().getUuid();
        final ReentrantLock vmLock = admissionLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        if (!vmLock.tryLock()) {
            throw new CloudRuntimeException("another migration admission is already in progress for VM " + key);
        }
        if (destination.getClusterId() == null) {
            vmLock.unlock();
            throw new CloudRuntimeException("destination cluster is unresolved; refusing vDPA migration");
        }
        final ReentrantLock clusterLock = clusterLocks.computeIfAbsent(destination.getClusterId(),
                ignored -> new ReentrantLock());
        if (!clusterLock.tryLock()) {
            vmLock.unlock();
            throw new CloudRuntimeException("another vDPA migration admission is active in destination cluster "
                    + destination.getClusterId());
        }
        try {
            verifyInternal(profile, destination, mode);
        } finally {
            clusterLock.unlock();
            vmLock.unlock();
            admissionLocks.remove(key, vmLock);
            clusterLocks.remove(destination.getClusterId(), clusterLock);
        }
    }

    private void verifyInternal(final VirtualMachineProfile profile, final Host destination,
            final MigrationMode mode) {
        validateNicInventory(profile);
        validateHostdev(profile, mode);
        final int required = countVdpaNics(profile);
        final int requiredHostdev = mode == MigrationMode.COLD ? countColdHostdevNics(profile) : 0;
        if (required == 0 && requiredHostdev == 0) {
            return;
        }
        if (required > 0) {
            validateRequestedChassis(profile, destination);
            validateGlobalClaims(profile, destination);
        }
        validateHaAndPlacement(profile, destination);
        final int free = required == 0 ? Integer.MAX_VALUE : vfPoolManager.countFreeForVdpa(destination.getId());
        if (free < required) {
            final String message = String.format(
                    "VM %s requires %d vDPA VF(s) on host %s, but only %d are free",
                    profile.getVirtualMachine().getUuid(), required, destination.getId(), free);
            throw new CloudRuntimeException(message);
        }
        if (vfPoolManager.countFree(destination.getId()) < requiredHostdev) {
            throw new CloudRuntimeException(String.format(
                    "VM requires %d cold SR-IOV hostdev VF(s) on host %s, but capacity is unavailable",
                    requiredHostdev, destination.getId()));
        }
    }

    private void validateNicInventory(final VirtualMachineProfile profile) {
        if (nicDao == null) {
            return;
        }
        final java.util.List<NicVO> inventory = nicDao.listByVmId(profile.getId());
        final java.util.List<NicProfile> profiles = profile.getNics();
        if (inventory == null || profiles == null || inventory.size() != profiles.size()) {
            throw new CloudRuntimeException("VM NIC inventory/profile count mismatch; refusing migration");
        }
        final java.util.Set<Long> inventoryIds = inventory.stream().map(NicVO::getId).collect(java.util.stream.Collectors.toSet());
        final java.util.Set<Long> profileIds = profiles.stream().map(NicProfile::getId).collect(java.util.stream.Collectors.toSet());
        if (inventoryIds.size() != inventory.size() || profileIds.size() != profiles.size()
                || !inventoryIds.equals(profileIds)) {
            throw new CloudRuntimeException("VM NIC inventory/profile identity mismatch; refusing migration");
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

    private int countColdHostdevNics(final VirtualMachineProfile profile) {
        if (profile.getNics() == null) {
            return 0;
        }
        return (int) profile.getNics().stream()
                .filter(NicProfile::isUseHwOffload)
                .filter(nic -> {
                    final NetworkVO network = networkDao.findById(nic.getNetworkId());
                    final NetworkOfferingVO offering = network == null ? null
                            : networkOfferingDao.findById(network.getNetworkOfferingId());
                    return offering == null || !offering.isVdpaEnabled();
                })
                .count();
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
        final String requested = chassisLookup == null
                ? (profile.getVirtualMachine().getDetails() == null ? null
                : profile.getVirtualMachine().getDetails().get("ovn.requested_chassis"))
                : chassisLookup.resolveRequestedChassis(profile.getVirtualMachine().getDetails());
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

    private void validateGlobalClaims(final VirtualMachineProfile profile, final Host destination) {
        if (chassisLookup == null) {
            throw new CloudRuntimeException("OVN global claim validation is unavailable; refusing vDPA migration");
        }
        final String destinationChassis = chassisLookup.findChassisUuid(destination.getId());
        if (destinationChassis == null || destinationChassis.isBlank()) {
            throw new CloudRuntimeException("destination OVN chassis identity is unresolved; refusing vDPA migration");
        }
        final Long sourceHostId = profile.getVirtualMachine().getHostId();
        final String sourceChassis = sourceHostId == null ? null : chassisLookup.findChassisUuid(sourceHostId);
        if (sourceChassis == null || sourceChassis.isBlank()) {
            throw new CloudRuntimeException("source OVN chassis identity is unresolved; refusing vDPA migration");
        }
        for (final NicProfile nic : profile.getNics()) {
            if (!isVdpaNic(nic)) {
                continue;
            }
            final int claims = chassisLookup.countActiveClaims(destination.getDataCenterId(), "lsp-" + nic.getUuid());
            final String lsp = "lsp-" + nic.getUuid();
            if (claims != 1 || !chassisLookup.hasExactActiveClaim(destination.getDataCenterId(), lsp, sourceChassis)) {
                throw new CloudRuntimeException(String.format(
                        "expected exactly one pre-cutover source Port_Binding claim for NIC %s, found %d",
                        nic.getUuid(), claims));
            }
        }
    }

    public boolean isVdpaNic(final NicProfile nic) {
        final NetworkVO network = networkDao.findById(nic.getNetworkId());
        final NetworkOfferingVO offering = network == null ? null
                : networkOfferingDao.findById(network.getNetworkOfferingId());
        return offering != null && offering.isVdpaEnabled();
    }

    public int requiredVdpaVfs(final NicProfile nic) {
        return isVdpaNic(nic) ? 1 : 0;
    }

    public NicPreflightDecision evaluateNic(final VirtualMachineProfile profile, final NicProfile nic,
            final Host destination) {
        final String mac = nic.getMacAddress();
        final String ifaceId = "lsp-" + nic.getUuid();
        if (!isVdpaNic(nic)) {
            return new NicPreflightDecision(true, null, mac, ifaceId, null, null, null);
        }
        if (chassisLookup == null) {
            return new NicPreflightDecision(false,
                    "OVN chassis lookup is unavailable for NIC " + nic.getUuid(), mac, ifaceId,
                    null, null, null);
        }
        final String requested = chassisLookup.resolveRequestedChassis(profile.getVirtualMachine().getDetails());
        final String destinationChassis = chassisLookup.findChassisUuid(destination.getId());
        final Long sourceHostId = profile.getVirtualMachine().getHostId();
        final String sourceChassis = sourceHostId == null ? null : chassisLookup.findChassisUuid(sourceHostId);
        if (requested != null && !requested.isBlank() && !requested.equals(destinationChassis)) {
            return new NicPreflightDecision(false,
                    "requested OVN chassis mismatch for NIC " + nic.getUuid(), mac, ifaceId,
                    requested, sourceChassis, destinationChassis);
        }
        final int claims = chassisLookup.countActiveClaims(destination.getDataCenterId(), ifaceId);
        if (claims != 1 || !chassisLookup.hasExactActiveClaim(destination.getDataCenterId(), ifaceId, sourceChassis)) {
            return new NicPreflightDecision(false,
                    String.format("expected exactly one source Port_Binding claim for NIC %s, found %d",
                            nic.getUuid(), claims), mac, ifaceId, requested, sourceChassis, destinationChassis);
        }
        return new NicPreflightDecision(true, null, mac, ifaceId, requested, sourceChassis, destinationChassis);
    }

    private void validateHaAndPlacement(final VirtualMachineProfile profile, final Host destination) {
        if (destination.getStatus() != Status.Up) {
            throw new CloudRuntimeException("destination host is not Up; refusing vDPA migration");
        }
        if (authoritativeGuard == null
                || !authoritativeGuard.fencingReady(profile.getVirtualMachine(), destination)
                || !authoritativeGuard.placementReady(profile.getVirtualMachine(), destination)
                || !authoritativeGuard.quorumReady(profile.getVirtualMachine(), destination)
                || !authoritativeGuard.antiAffinityReady(profile.getVirtualMachine(), destination)) {
            throw new CloudRuntimeException(
                    "authoritative fencing/placement guard is unavailable or denied vDPA migration");
        }
    }
}
