//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.apache.cloudstack.storage.configdrive.ConfigDrive;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.collections.MapUtils;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.api.routing.ObserveVdpaMigrationAnswer;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.MigrationIdentityFenceStore;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef.GuestNetType;
import com.cloud.hypervisor.kvm.resource.OvnVdpaVifDriver;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;

@ResourceWrapper(handles =  PrepareForMigrationCommand.class)
public final class LibvirtPrepareForMigrationCommandWrapper extends CommandWrapper<PrepareForMigrationCommand, Answer, LibvirtComputingResource> {


    @Override
    public Answer execute(final PrepareForMigrationCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final VirtualMachineTO vm = command.getVirtualMachine();
        final NicTO[] nics = vm.getNics();

        if (command.isRollback()) {
            logger.info("Handling rollback for PrepareForMigration of VM {}", vm.getName());
            return handleRollback(command, libvirtComputingResource, nics);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Preparing host for migrating " + vm);
        }

        Map<String, DpdkTO> dpdkInterfaceMapping = new HashMap<>();
        Map<String, String> vdpaInterfaceMapping = new HashMap<>();

        boolean skipDisconnect = false;

        final KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        try {
            final LibvirtUtilitiesHelper libvirtUtilitiesHelper = libvirtComputingResource.getLibvirtUtilitiesHelper();

            final Connect conn = libvirtUtilitiesHelper.getConnectionByVmName(vm.getName());

            for (final NicTO nic : nics) {
                // Use selectVifDriverForNic (not the legacy getVifDriver traffic-type
                // selector) so OVN / vDPA / HW-offload NICs land on their correct
                // driver. getVifDriver only dispatches on TrafficType and falls back
                // to BridgeVifDriver for all OVN-tagged NICs — Bug 14b root cause.
                LibvirtVMDef.InterfaceDef interfaceDef = libvirtComputingResource.selectVifDriverForNic(nic).plug(nic, null, "", vm.getExtraConfig());
                if (vm.getDetails() != null) {
                    libvirtComputingResource.setInterfaceDefQueueSettings(vm.getDetails(), vm.getCpus(), interfaceDef);
                }
                if (interfaceDef != null && interfaceDef.getNetType() == GuestNetType.VHOSTUSER) {
                    DpdkTO to = new DpdkTO(interfaceDef.getDpdkOvsPath(), interfaceDef.getDpdkSourcePort(), interfaceDef.getInterfaceMode());
                    dpdkInterfaceMapping.put(nic.getMac(), to);
                    logger.debug("Configured DPDK interface for VM {}", vm.getName());
                }
                if (interfaceDef != null && interfaceDef.getNetType() == GuestNetType.VDPA) {
                    // getBrName() returns _sourceName which holds /dev/vhost-vdpa-N for vDPA interfaces.
                    final String destVhostDev = interfaceDef.getBrName();
                    if (org.apache.commons.lang3.StringUtils.isNotBlank(destVhostDev)) {
                        vdpaInterfaceMapping.put(nic.getMac().toLowerCase(java.util.Locale.ROOT), destVhostDev);
                        logger.debug("Captured destination vDPA device {} for mac {} VM {}", destVhostDev, nic.getMac(), vm.getName());
                    }
                }
            }

            /* setup disks, e.g for iso */
            final DiskTO[] volumes = vm.getDisks();
            for (final DiskTO volume : volumes) {
                final DataTO data = volume.getData();

                if (volume.getType() == Volume.Type.ISO) {
                    if (data != null && data.getPath() != null && data.getPath().startsWith(ConfigDrive.CONFIGDRIVEDIR)) {
                        libvirtComputingResource.getVolumePath(conn, volume, vm.isConfigDriveOnHostCache());
                    } else {
                        libvirtComputingResource.getVolumePath(conn, volume);
                    }
                }

                if (data instanceof VolumeObjectTO) {
                    final VolumeObjectTO volumeObjectTO = (VolumeObjectTO)data;

                    if (volumeObjectTO.requiresEncryption()) {
                        String secretConsumer = volumeObjectTO.getPath();
                        if (volume.getDetails() != null && volume.getDetails().containsKey(DiskTO.SECRET_CONSUMER_DETAIL)) {
                            secretConsumer = volume.getDetails().get(DiskTO.SECRET_CONSUMER_DETAIL);
                        }
                        String secretUuid = libvirtComputingResource.createLibvirtVolumeSecret(conn, secretConsumer, volumeObjectTO.getPassphrase());
                        logger.debug(String.format("Created libvirt secret %s for disk %s", secretUuid, volumeObjectTO.getPath()));
                        volumeObjectTO.clearPassphrase();
                    } else {
                        logger.debug(String.format("disk %s has no passphrase or encryption", volumeObjectTO));
                    }
                }
            }

            skipDisconnect = true;

            if (!storagePoolMgr.connectPhysicalDisksViaVmSpec(vm, true)) {
                skipDisconnect = false;
                RuntimeException cleanupFailure = null;
                try {
                    releaseVdpaVfsOnRollback(vm, nics, libvirtComputingResource);
                } catch (RuntimeException cleanupError) {
                    cleanupFailure = cleanupError;
                }
                final String detail = cleanupFailure == null
                        ? "failed to connect physical disks to host"
                        : "failed to connect physical disks; cleanup failed: " + cleanupFailure;
                return new PrepareForMigrationAnswer(command, detail);
            }

            logger.info("Successfully prepared destination host for migration of VM {}", vm.getName());
            return createPrepareForMigrationAnswer(command, dpdkInterfaceMapping, vdpaInterfaceMapping, libvirtComputingResource, vm, nics);
        } catch (final LibvirtException | CloudRuntimeException | InternalErrorException | URISyntaxException e) {
            RuntimeException cleanupFailure = null;
            try {
                releaseVdpaVfsOnRollback(vm, nics, libvirtComputingResource);
            } catch (RuntimeException cleanupError) {
                cleanupFailure = cleanupError;
            }
            if (MapUtils.isNotEmpty(dpdkInterfaceMapping)) {
                for (DpdkTO to : dpdkInterfaceMapping.values()) {
                    try {
                        removeDpdkPort(to.getPort());
                    } catch (RuntimeException cleanupError) {
                        if (cleanupFailure == null) {
                            cleanupFailure = cleanupError;
                        } else {
                            cleanupFailure.addSuppressed(cleanupError);
                        }
                    }
                }
            }
            if (cleanupFailure != null) {
                e.addSuppressed(cleanupFailure);
            }
            return new PrepareForMigrationAnswer(command, e.toString());
        } finally {
            if (!skipDisconnect) {
                storagePoolMgr.disconnectPhysicalDisksViaVmSpec(vm);
            }
        }
    }

    protected PrepareForMigrationAnswer createPrepareForMigrationAnswer(PrepareForMigrationCommand command,
            Map<String, DpdkTO> dpdkInterfaceMapping, Map<String, String> vdpaInterfaceMapping,
            LibvirtComputingResource libvirtComputingResource, VirtualMachineTO vm, NicTO[] nics) {
        PrepareForMigrationAnswer answer = new PrepareForMigrationAnswer(command);

        if (MapUtils.isNotEmpty(dpdkInterfaceMapping)) {
            logger.debug(String.format("Setting DPDK interface for the migration of VM [%s].", vm));
            answer.setDpdkInterfaceMapping(dpdkInterfaceMapping);
        }

        if (MapUtils.isNotEmpty(vdpaInterfaceMapping)) {
            logger.debug("Setting vDPA interface mapping for the migration of VM [{}]: {} NIC(s)", vm, vdpaInterfaceMapping.size());
            answer.setVdpaInterfaceMapping(vdpaInterfaceMapping);
        }

        if (command.getMigrationWorkId() == null || command.getMigrationGeneration() <= 0) {
            throw new CloudRuntimeException("migration prepare is missing its persistent identity fence");
        }
        if (command.getMigrationIdentities().isEmpty()) {
            throw new CloudRuntimeException("migration prepare lacks DB NIC identity reservations");
        }
        final MigrationIdentityFenceStore fences = new MigrationIdentityFenceStore(
                MigrationIdentityFenceStore.migrationFenceDirectory());
        final java.util.concurrent.locks.ReentrantLock manifestLock = MigrationIdentityFenceStore.lockFor(
                command.getMigrationWorkId(), command.getMigrationGeneration(), fences.hostIdentity());
        manifestLock.lock();
        try {
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations =
                    LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(
                            new ObserveVdpaMigrationCommand(vm.getName(), command.getMigrationWorkId(),
                                    command.getMigrationGeneration(), command.getMigrationIdentities()));
            if (observations.size() != nics.length
                    || observations.stream().anyMatch(observation -> !observation.isExact())) {
                throw new CloudRuntimeException("destination prepare did not produce exact local NIC identities");
            }
            fences.install(command.getMigrationWorkId(), command.getMigrationGeneration(),
                    command.getMigrationIdentities().stream().map(identity -> MigrationIdentityFenceStore.Fence.fromIdentity(
                            command.getMigrationWorkId(), command.getMigrationGeneration(), identity,
                            command.getMigrationLeaseToken(), command.getMigrationLeaseVersion(),
                            command.getMigrationLeaseExpiry())).toList());
            final Map<Long, ObserveVdpaMigrationAnswer.NicObservation> byNic = new HashMap<>();
            for (final ObserveVdpaMigrationAnswer.NicObservation observation : observations) {
                if (observation.getNicId() <= 0 || observation.getNicUuid() == null
                        || byNic.putIfAbsent(observation.getNicId(), observation) != null) {
                    throw new CloudRuntimeException("destination prepare returned duplicate NIC identity");
                }
            }
            answer.setNicObservations(byNic);
        } finally {
            manifestLock.unlock();
        }

        int newCpuShares = libvirtComputingResource.calculateCpuShares(vm);
        logger.debug(String.format("Setting CPU shares to [%s] for the migration of VM [%s].", newCpuShares, vm));
        answer.setNewVmCpuShares(newCpuShares);

        return answer;
    }

    private Answer handleRollback(PrepareForMigrationCommand command, LibvirtComputingResource libvirtComputingResource,
            NicTO[] nics) {
        KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        VirtualMachineTO vmTO = command.getVirtualMachine();

        RuntimeException cleanupFailure = null;
        try {
            releaseVdpaVfsOnRollback(vmTO, nics, libvirtComputingResource);
        } catch (RuntimeException e) {
            cleanupFailure = e;
        }

        logger.info("Rolling back PrepareForMigration for VM {}: disconnecting physical disks", vmTO.getName());
        if (!storagePoolMgr.disconnectPhysicalDisksViaVmSpec(vmTO)) {
            return new PrepareForMigrationAnswer(command, "failed to disconnect physical disks from host");
        }

        if (cleanupFailure != null) {
            return new PrepareForMigrationAnswer(command, cleanupFailure.toString());
        }

        return new PrepareForMigrationAnswer(command);
    }

    /**
     * For each vDPA NIC in the VM spec, release the destination VF that was
     * allocated by {@link OvnVdpaVifDriver#plug} during the forward prepare.
     * This prevents VF pool leaks and orphaned {@code vdpa dev} instances on
     * the destination after a migration abort.
     *
     * <p>Called before physical-disk disconnect so that VF cleanup always runs
     * even if the disk path throws.
     */
    private void releaseVdpaVfsOnRollback(VirtualMachineTO vm, NicTO[] nics,
            LibvirtComputingResource libvirtComputingResource) {
        if (nics == null || nics.length == 0) {
            return;
        }
        final OvnVdpaVifDriver vdpaDriver = libvirtComputingResource.getOvnVdpaVifDriver();
        if (vdpaDriver == null) {
            return;
        }
        RuntimeException cleanupFailure = null;
        for (final NicTO nic : nics) {
            if (!nic.isUseVdpa()) {
                continue;
            }
            logger.info("PrepareForMigration rollback: releasing vDPA VF for mac={} vm={}", nic.getMac(), vm.getName());
            try {
                vdpaDriver.releaseVdpaOnRollback(nic);
            } catch (RuntimeException cleanupError) {
                if (cleanupFailure == null) {
                    cleanupFailure = cleanupError;
                } else {
                    cleanupFailure.addSuppressed(cleanupError);
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }
}
