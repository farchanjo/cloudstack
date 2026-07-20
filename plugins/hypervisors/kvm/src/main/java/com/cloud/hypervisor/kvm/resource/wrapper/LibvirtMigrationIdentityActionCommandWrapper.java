// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.routing.MigrationIdentityActionAnswer;
import com.cloud.agent.api.routing.MigrationIdentityActionCommand;
import com.cloud.agent.api.routing.ObserveVdpaMigrationAnswer;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.MigrationIdentityFenceStore;
import com.cloud.hypervisor.kvm.resource.MigrationObservationParser;
import com.cloud.hypervisor.kvm.resource.OvnVdpaVifDriver;
import com.cloud.hypervisor.kvm.resource.OvnVfPassthroughVifDriver;
import com.cloud.hypervisor.kvm.resource.OvsRepresentorCas;
import com.cloud.hypervisor.kvm.resource.VdpaVifDriver;
import com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

/** Generation-fenced, exact-identity host actions for accelerated migration recovery. */
@ResourceWrapper(handles = MigrationIdentityActionCommand.class)
public class LibvirtMigrationIdentityActionCommandWrapper extends
        CommandWrapper<MigrationIdentityActionCommand, Answer, LibvirtComputingResource> {
    private static final String OVS_SOCKET = "unix:/var/run/openvswitch/db.sock";
    @Override
    public Answer execute(final MigrationIdentityActionCommand command, final LibvirtComputingResource resource) {
        if (!valid(command)) {
            return answer(command, false, "complete migration identity is required",
                    MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, List.of());
        }
        if (command.getRecoveryLeaseExpiresAt() <= System.currentTimeMillis() / 1000L) {
            return answer(command, false, "migration lease is expired",
                    MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, List.of());
        }
        final MigrationIdentityFenceStore fences = new MigrationIdentityFenceStore(
                MigrationIdentityFenceStore.migrationFenceDirectory());
        final ReentrantLock manifestLock = MigrationIdentityFenceStore.lockFor(command.getWorkId(),
                command.getGeneration(), fences.hostIdentity());
        final List<ReentrantLock> locks = command.getNicIdentities().stream()
                .sorted(java.util.Comparator.comparing(LibvirtObserveVdpaMigrationCommandWrapper::lockKey))
                .map(LibvirtObserveVdpaMigrationCommandWrapper::lockFor).distinct()
                .toList();
        manifestLock.lock();
        locks.forEach(ReentrantLock::lock);
        try {
            if (command.getAction() == MigrationIdentityActionCommand.Action.INSTALL_DESTINATION_FENCE) {
                return installDestinationFence(command, fences);
            }
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations = observe(command);
            final MigrationIdentityFenceStore.Lookup lookup = fences.lookup(command.getWorkId(), command.getGeneration());
            if (command.getAction() == MigrationIdentityActionCommand.Action.CLEAN_RECOVERY_FENCE) {
                return cleanRecoveryFence(command, fences, lookup, observations);
            }
            final MigrationIdentityFenceStore.Manifest manifest = lookup.manifest();
            if (lookup.status() != MigrationIdentityFenceStore.Status.PRESENT) {
                return manual(command, lookup.status() == MigrationIdentityFenceStore.Status.ABSENT
                        ? "migration fence manifest is absent" : "migration fence manifest is corrupt or legacy");
            }
            if (!manifestMatches(command, manifest, observations)) {
                return manual(command, "migration fence manifest or fresh observation does not match");
            }
            if (command.getAction() == MigrationIdentityActionCommand.Action.CLEAR_FENCE_ONLY) {
                return clearFenceOnly(command, fences, observations);
            }
            if (command.getAction() == MigrationIdentityActionCommand.Action.ADOPT_RECOVERY_FENCE) {
                return adoptRecoveryFence(command, fences, manifest, observations);
            }
            final Answer result = executeLocked(command, resource);
            if (result instanceof MigrationIdentityActionAnswer
                    && ((MigrationIdentityActionAnswer) result).getResult()
                    && (((MigrationIdentityActionAnswer) result).getStatus() == MigrationIdentityActionAnswer.Status.SUCCESS
                    || ((MigrationIdentityActionAnswer) result).getStatus() == MigrationIdentityActionAnswer.Status.ALREADY_SATISFIED)
                    && command.getAction() == MigrationIdentityActionCommand.Action.CLEAN_SOURCE_AFTER_COMMIT) {
                fences.clear(command.getWorkId(), command.getGeneration(), requestedFences(command));
            }
            return result;
        } catch (MigrationIdentityFenceStore.ManualFenceException e) {
            return manual(command, e.getMessage());
        } catch (RuntimeException e) {
            return answer(command, false, "identity action failed: " + e.getMessage(),
                    MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, List.of());
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
            manifestLock.unlock();
        }
    }

    private static Answer cleanRecoveryFence(final MigrationIdentityActionCommand command,
            final MigrationIdentityFenceStore fences, final MigrationIdentityFenceStore.Lookup lookup,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        if (lookup.status() == MigrationIdentityFenceStore.Status.LEGACY_OR_CORRUPT) {
            return manual(command, "migration fence manifest is corrupt or legacy");
        }
        if (lookup.status() == MigrationIdentityFenceStore.Status.ABSENT) {
            if (observationsUnavailable(command, observations)) {
                return answer(command, false, "cleanup retry observation is unavailable",
                        MigrationIdentityActionAnswer.Status.OBSERVATION_UNAVAILABLE, false, false, observations);
            }
            if (cleanPostcondition(command, observations)) {
                return answer(command, true, "migration fence cleanup already satisfied",
                        MigrationIdentityActionAnswer.Status.ALREADY_SATISFIED, true, true, observations);
            }
            return manual(command, "migration resources remain after absent migration fence");
        }
        if (!manifestCommandMatches(command, lookup.manifest())) {
            return manual(command, "migration fence manifest or fresh observation does not match");
        }
        if (observationsUnavailable(command, observations)) {
            return answer(command, false, "cleanup retry observation is unavailable",
                    MigrationIdentityActionAnswer.Status.OBSERVATION_UNAVAILABLE, false, false, observations);
        }
        if (observations.stream().anyMatch(observation -> !observation.isExact())
                && !cleanPostcondition(command, observations)) {
            return manual(command, "migration resources are neither exact nor absent");
        }
        fences.clear(command.getWorkId(), command.getGeneration(), requestedFences(command));
        return answer(command, true, "migration recovery fence cleared",
                MigrationIdentityActionAnswer.Status.SUCCESS, true, true, observations);
    }

    private static boolean observationsUnavailable(final MigrationIdentityActionCommand command,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        return observations.size() != command.getNicIdentities().size()
                || observations.stream().anyMatch(observation -> !observation.isAvailable());
    }

    private static boolean manifestCommandMatches(final MigrationIdentityActionCommand command,
            final MigrationIdentityFenceStore.Manifest manifest) {
        return command.getWorkId().equals(manifest.workId()) && command.getGeneration() == manifest.generation()
                && manifest.hostIdentity().equals(new MigrationIdentityFenceStore(
                        MigrationIdentityFenceStore.migrationFenceDirectory()).hostIdentity())
                && command.getNicIdentities().stream().allMatch(identity -> manifest.entries().stream()
                .filter(entry -> entry.key().equals(identity.getExpectedBdf() == null
                        ? identity.getLspId() : identity.getExpectedBdf()))
                .anyMatch(entry -> entry.leaseToken().equals(command.getRecoveryLeaseToken())
                        && entry.leaseVersion() == command.getRecoveryLeaseVersion()
                        && entry.leaseExpiry() == command.getRecoveryLeaseExpiresAt()
                        && entry.sameIdentity(MigrationIdentityFenceStore.Fence.fromIdentity(command.getWorkId(),
                        command.getGeneration(), identity, command.getRecoveryLeaseToken(),
                        command.getRecoveryLeaseVersion(), command.getRecoveryLeaseExpiresAt()))));
    }

    private static List<ObserveVdpaMigrationAnswer.NicObservation> observe(
            final MigrationIdentityActionCommand command) {
        return LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(new ObserveVdpaMigrationCommand(
                command.getVmInstanceName(), command.getWorkId(), command.getGeneration(), command.getNicIdentities()));
    }

    private static boolean manifestMatches(final MigrationIdentityActionCommand command,
            final MigrationIdentityFenceStore.Manifest manifest,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        if (!command.getWorkId().equals(manifest.workId()) || command.getGeneration() != manifest.generation()
                || !manifest.hostIdentity().equals(new MigrationIdentityFenceStore(
                        MigrationIdentityFenceStore.migrationFenceDirectory()).hostIdentity())
                || !command.getNicIdentities().stream().allMatch(identity -> {
                    final MigrationIdentityFenceStore.Fence entry = manifest.entries().stream()
                            .filter(candidate -> candidate.key().equals(identity.getExpectedBdf() == null
                                    ? identity.getLspId() : identity.getExpectedBdf())).findFirst().orElse(null);
                    final ObserveVdpaMigrationAnswer.NicObservation observation = observations.stream()
                            .filter(candidate -> candidate.getNicId() == identity.getNicId()).findFirst().orElse(null);
                    final boolean currentLease = entry != null && entry.leaseToken().equals(command.getRecoveryLeaseToken())
                            && entry.leaseVersion() == command.getRecoveryLeaseVersion()
                            && entry.leaseExpiry() == command.getRecoveryLeaseExpiresAt();
                    final boolean oldLease = entry != null && command.getAction()
                            == MigrationIdentityActionCommand.Action.ADOPT_RECOVERY_FENCE
                            && entry.leaseToken().equals(command.getOldFenceToken())
                            && entry.leaseVersion() == command.getOldFenceVersion();
                    return entry != null && (currentLease || oldLease)
                            && entry.matches(identity, observation);
                })) {
            return false;
        }
        return observations.size() == command.getNicIdentities().size();
    }

    private static Answer installDestinationFence(final MigrationIdentityActionCommand command,
            final MigrationIdentityFenceStore fences) {
        final ObserveVdpaMigrationCommand observationCommand = new ObserveVdpaMigrationCommand(
                command.getVmInstanceName(), command.getWorkId(), command.getGeneration(), command.getNicIdentities());
        final List<ObserveVdpaMigrationAnswer.NicObservation> observations =
                LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(observationCommand);
        if (observations.size() != command.getNicIdentities().size()
                || observations.stream().anyMatch(observation -> !observation.isExact())) {
            return answer(command, false, "destination fence requires exact observation",
                    MigrationIdentityActionAnswer.Status.OBSERVATION_UNAVAILABLE, false, false, observations);
        }
        fences.install(command.getWorkId(), command.getGeneration(), requestedFences(command));
        return answer(command, true, "durable destination fence installed",
                MigrationIdentityActionAnswer.Status.SUCCESS, true, true, observations);
    }

    private static Answer clearFenceOnly(final MigrationIdentityActionCommand command,
            final MigrationIdentityFenceStore fences,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        if (observations.size() != command.getNicIdentities().size()
                || observations.stream().anyMatch(observation -> !observation.isExact())) {
            return answer(command, false, "fence cleanup requires exact observation",
                    MigrationIdentityActionAnswer.Status.OBSERVATION_UNAVAILABLE, false, false, observations);
        }
        fences.clear(command.getWorkId(), command.getGeneration(), requestedFences(command));
        return answer(command, true, "migration fences cleared", MigrationIdentityActionAnswer.Status.SUCCESS,
                true, true, observations);
    }

    private static Answer adoptRecoveryFence(final MigrationIdentityActionCommand command,
            final MigrationIdentityFenceStore fences,
            final MigrationIdentityFenceStore.Manifest manifest,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        if (command.getOldFenceToken() == null || command.getOldFenceVersion() < 0
                || observations.stream().anyMatch(observation -> !observation.isExact())) {
            return answer(command, false, "recovery fence adoption precondition failed",
                    MigrationIdentityActionAnswer.Status.MANUAL_REQUIRED, false, false, List.of());
        }
        if (!command.getOldFenceToken().equals(manifest.leaseToken())
                || command.getOldFenceVersion() != manifest.leaseVersion()) {
            return manual(command, "previous recovery fence is not represented");
        }
        fences.adopt(command.getWorkId(), command.getGeneration(), command.getOldFenceToken(),
                command.getOldFenceVersion(), requestedFences(command));
        return answer(command, true, "recovery fence adopted without host mutation",
                MigrationIdentityActionAnswer.Status.SUCCESS, true, true, observations);
    }

    private static List<MigrationIdentityFenceStore.Fence> requestedFences(
            final MigrationIdentityActionCommand command) {
        return command.getNicIdentities().stream().map(identity -> MigrationIdentityFenceStore.Fence.fromIdentity(
                command.getWorkId(), command.getGeneration(), identity, command.getRecoveryLeaseToken(),
                command.getRecoveryLeaseVersion(), command.getRecoveryLeaseExpiresAt())).toList();
    }

    private static MigrationIdentityActionAnswer manual(final MigrationIdentityActionCommand command,
            final String details) {
        return answer(command, false, details, MigrationIdentityActionAnswer.Status.MANUAL_REQUIRED,
                false, false, List.of());
    }

    private Answer executeLocked(final MigrationIdentityActionCommand command,
            final LibvirtComputingResource resource) {
        final ObserveVdpaMigrationCommand identityCopy = new ObserveVdpaMigrationCommand(
                command.getVmInstanceName(), command.getWorkId(), command.getGeneration(),
                command.getNicIdentities());
        final List<ObserveVdpaMigrationCommand.NicIdentity> preIdentities =
                new ArrayList<>(identityCopy.getNicIdentities());
        if (command.getAction() == MigrationIdentityActionCommand.Action.VERIFY_AND_RESTAMP) {
            preIdentities.forEach(identity -> {
                identity.setExpectedMigrationWorkId(null);
                identity.setExpectedMigrationGeneration(null);
                identity.setExpectedNicUuid(null);
                identity.setExpectedVfRowId(null);
            });
        }
        final ObserveVdpaMigrationCommand observationCommand = new ObserveVdpaMigrationCommand(
                command.getVmInstanceName(), command.getWorkId(), command.getGeneration(),
                preIdentities);
        final List<ObserveVdpaMigrationAnswer.NicObservation> before =
                LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(observationCommand);
        final boolean exactAvailable = matchesActiveAttachment(before);
        final boolean absenceAvailable = cleanupPreconditionAvailable(command, before);
        if (!exactAvailable && !absenceAvailable) {
            return answer(command, false, "exact pre-observation unavailable",
                    MigrationIdentityActionAnswer.Status.OBSERVATION_UNAVAILABLE, false, false, before);
        }
        if (command.getAction() == MigrationIdentityActionCommand.Action.VERIFY_AND_RESTAMP) {
            if (!exactAvailable) {
                return answer(command, false, "exact restamp observation unavailable",
                        MigrationIdentityActionAnswer.Status.OBSERVATION_UNAVAILABLE, false, false, before);
            }
            return restamp(command, observationCommand, before);
        }
        if (command.getAction() == MigrationIdentityActionCommand.Action.CLEAN_DESTINATION_PREP) {
            if (!"PREPARING_DESTINATION".equals(command.getExpectedPhase())
                    && !"DESTINATION_ALLOCATED".equals(command.getExpectedPhase())) {
                return answer(command, false, "destination cleanup phase fence failed",
                        MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, before);
            }
            return clean(command, observationCommand, before, false);
        }
        if (command.getAction() == MigrationIdentityActionCommand.Action.CLEAN_SOURCE_AFTER_COMMIT) {
            if (!"OWNERSHIP_COMMITTED".equals(command.getExpectedPhase())
                    && !"SOURCE_CLEANUP".equals(command.getExpectedPhase())) {
                return answer(command, false, "source cleanup requires committed migration phase",
                        MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, before);
            }
            return clean(command, observationCommand, before, true);
        }
        if (!"ROLLING_BACK".equals(command.getExpectedPhase())) {
            return answer(command, false, "source restore phase fence failed",
                    MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, before);
        }
        return restore(command, observationCommand, before);
    }

    private Answer restamp(final MigrationIdentityActionCommand command,
            final ObserveVdpaMigrationCommand observationCommand,
            final List<ObserveVdpaMigrationAnswer.NicObservation> before) {
        for (ObserveVdpaMigrationCommand.NicIdentity identity : command.getNicIdentities()) {
            if (!"VIRTIO_OVN".equals(identity.getNicKind()) && !exactAcceleratedIdentity(identity)) {
                return answer(command, false, "restamp requires complete VF identity",
                        MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, before);
            }
            if (!OvsRepresentorCas.restamp(LibvirtMigrationIdentityActionCommandWrapper::runOvsdb,
                    OVS_SOCKET, identity.getExpectedRepresentor(), identity.getLspId(), command.getWorkId(),
                    command.getGeneration(), "VIRTIO_OVN".equals(identity.getNicKind()) ? identity.getExpectedNicUuid()
                            : identity.getExpectedNicUuid(), "VIRTIO_OVN".equals(identity.getNicKind()) ? null
                            : identity.getExpectedVfRowId(), "VIRTIO_OVN".equals(identity.getNicKind()) ? null
                            : identity.getExpectedBdf())) {
                return answer(command, false, "exact OVS CAS restamp failed",
                        MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, before);
            }
        }
        final ObserveVdpaMigrationCommand postObservationCommand = new ObserveVdpaMigrationCommand(
                command.getVmInstanceName(), command.getWorkId(), command.getGeneration(),
                command.getNicIdentities());
        final List<ObserveVdpaMigrationAnswer.NicObservation> after =
                LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(postObservationCommand);
        if (!after.stream().allMatch(ObserveVdpaMigrationAnswer.NicObservation::isExact)) {
            return answer(command, false, "restamp postcondition failed",
                    MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, after);
        }
        return answer(command, true, "exact identity restamped", MigrationIdentityActionAnswer.Status.SUCCESS,
                true, true, after);
    }

    private Answer clean(final MigrationIdentityActionCommand command,
            final ObserveVdpaMigrationCommand observationCommand,
            final List<ObserveVdpaMigrationAnswer.NicObservation> before, final boolean source) {
        if (before.stream().anyMatch(observation -> !observation.isExact()
                && !"ABSENT".equals(observation.getDomainState()))) {
            return answer(command, false, "cleanup observation is unavailable",
                    MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, before);
        }
        if (matchesAbsentPostcondition(command, before)) {
            return answer(command, true, "exact cleanup already satisfied",
                    MigrationIdentityActionAnswer.Status.ALREADY_SATISFIED, true, true, before);
        }
        try {
            for (ObserveVdpaMigrationCommand.NicIdentity identity : command.getNicIdentities()) {
                cleanupNic(identity);
            }
        } catch (RuntimeException e) {
            return answer(command, false, "cleanup failed: " + e.getMessage(),
                    MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, before);
        }
        final List<ObserveVdpaMigrationAnswer.NicObservation> after =
                LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(observationCommand);
        if (!matchesAbsentPostcondition(command, after)) {
            return answer(command, false, "cleanup postcondition failed",
                    MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, after);
        }
        return answer(command, true, source ? "source cleanup complete" : "destination cleanup complete",
                MigrationIdentityActionAnswer.Status.SUCCESS, true, true, after);
    }

    private Answer restore(final MigrationIdentityActionCommand command,
            final ObserveVdpaMigrationCommand observationCommand,
            final List<ObserveVdpaMigrationAnswer.NicObservation> before) {
        if (before.stream().anyMatch(observation -> !"ABSENT".equals(observation.getDomainState()))) {
            return answer(command, false, "restore requires exact local domain absence",
                    MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, false, false, before);
        }
        try {
            for (ObserveVdpaMigrationCommand.NicIdentity identity : command.getNicIdentities()) {
                restoreNic(identity, command.getWorkId(), command.getGeneration());
            }
        } catch (RuntimeException e) {
            return answer(command, false, "source dataplane restore failed: " + e.getMessage(),
                    MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, before);
        }
        final List<ObserveVdpaMigrationAnswer.NicObservation> after =
                LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(observationCommand);
        if (!matchesRestoredDataplane(after)) {
            return answer(command, false, "source dataplane restore postcondition failed",
                    MigrationIdentityActionAnswer.Status.POSTCONDITION_FAILED, true, false, after);
        }
        return answer(command, true, "dataplane restored; management must start domain",
                MigrationIdentityActionAnswer.Status.DATAPLANE_RESTORED_DOMAIN_START_REQUIRED,
                true, true, after);
    }

    private static void cleanupNic(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        if ("VIRTIO_OVN".equals(identity.getNicKind())) {
            cleanupVirtioIdentity(identity);
            return;
        }
        final String bdf = required(identity.getExpectedBdf(), "BDF");
        final String rep = required(identity.getExpectedRepresentor(), "representor");
        if (identity.getExpectedTcExpectation() == null || identity.getExpectedFdbExpectation() == null) {
            throw new CloudRuntimeException("exact TC/FDB ownership is not persisted; manual intervention required");
        }
        if (!identity.getExpectedTcExpectation().equals(read("tc -j filter show dev " + token(rep) + " ingress"))
                || !identity.getExpectedFdbExpectation().equals(read("bridge -j fdb show dev " + token(rep)))) {
            throw new CloudRuntimeException("TC/FDB ownership changed");
        }
        final String observedIfaceId = OvsRepresentorCas.readIfaceId(OvsRepresentorCasExecutor.INSTANCE,
                OVS_SOCKET, rep);
        if (!OvsRepresentorCas.isAbsentExact(OvsRepresentorCasExecutor.INSTANCE, OVS_SOCKET, rep)
                && !identity.getLspId().equals(observedIfaceId)) {
            throw new CloudRuntimeException("representor owner changed");
        }
        if ("VDPA".equalsIgnoreCase(identity.getNicKind())) {
            final String vdpa = required(identity.getExpectedVdpaName(), "vDPA name");
            final String device = required(identity.getExpectedVdpaDevice(), "vDPA device");
            final String inventory = read("vdpa dev show -j 2>/dev/null");
            final MigrationObservationParser.VdpaDevice present =
                    MigrationObservationParser.vdpaDevice(inventory, vdpa, device);
            if (present != null) {
                Script.runSimpleBashScript("vdpa dev del " + token(vdpa));
            } else if (!vdpaAbsent(inventory, vdpa, bdf)) {
                throw new CloudRuntimeException("vDPA identity is not exact");
            }
        } else if (!"VF_PASSTHROUGH".equalsIgnoreCase(identity.getNicKind())) {
            throw new CloudRuntimeException("unknown NIC kind");
        }
        final String tc = read("tc -j filter show dev " + token(rep) + " ingress");
        final String fdb = read("bridge -j fdb show dev " + token(rep));
        if (!identity.getExpectedTcExpectation().equals(tc)
                || !identity.getExpectedFdbExpectation().equals(fdb)) {
            throw new CloudRuntimeException("TC/FDB ownership changed");
        }
        final List<MigrationObservationParser.TcHandle> handles =
                MigrationObservationParser.exactTcHandles(tc);
        if (handles.isEmpty()) {
            throw new CloudRuntimeException("TC identity has no exact handles");
        }
        final String current = OvsRepresentorCas.readIfaceId(OvsRepresentorCasExecutor.INSTANCE,
                OVS_SOCKET, rep);
        final boolean absent = OvsRepresentorCas.isAbsentExact(OvsRepresentorCasExecutor.INSTANCE,
                OVS_SOCKET, rep);
        if (!absent && !identity.getLspId().equals(current)) {
            throw new CloudRuntimeException("representor owner changed");
        }
        if (!absent && !OvsRepresentorCas.remove(OvsRepresentorCasExecutor.INSTANCE,
                OVS_SOCKET, rep, identity.getLspId())) {
            throw new CloudRuntimeException("representor CAS cleanup failed");
        }
        for (final MigrationObservationParser.TcHandle handle : handles) {
            Script.runSimpleBashScript("tc filter del dev " + token(rep)
                    + " ingress pref " + handle.pref() + " handle " + handle.handle() + " flower");
        }
        Script.runSimpleBashScript("bridge fdb del lladdr " + token(identity.getExpectedMac())
                + " dev " + token(rep) + " vlan " + token(identity.getExpectedVlan()));
        if (!MigrationObservationParser.jsonArrayEmpty(read("tc -j filter show dev " + token(rep) + " ingress"))
                || !MigrationObservationParser.jsonArrayEmpty(read("bridge -j fdb show dev " + token(rep)))) {
            throw new CloudRuntimeException("TC/FDB cleanup postcondition failed");
        }
        if (!OvsRepresentorCas.isAbsentExact(OvsRepresentorCasExecutor.INSTANCE, OVS_SOCKET, rep)) {
            throw new CloudRuntimeException("OVS representor cleanup postcondition failed");
        }
        VfPassthroughVifDriver.clearVfIdentityLocked(bdf);
    }

    private static void restoreNic(final ObserveVdpaMigrationCommand.NicIdentity identity,
            final String workId, final long generation) {
        if ("VIRTIO_OVN".equals(identity.getNicKind())) {
            if (!OvsRepresentorCas.restamp(OvsRepresentorCasExecutor.INSTANCE, OVS_SOCKET,
                    required(identity.getExpectedRepresentor(), "representor"), identity.getLspId(), workId,
                    generation, identity.getExpectedNicUuid(), null, null)) {
                throw new CloudRuntimeException("virtio OVS identity restore failed");
            }
            return;
        }
        final NicTO nic = new NicTO();
        nic.setUuid(identity.getExpectedNicUuid());
        nic.setMac(identity.getExpectedMac());
        nic.setVfPciAddress(required(identity.getExpectedBdf(), "BDF"));
        nic.setVfPfName(identity.getExpectedPf());
        nic.setVfRepName(identity.getExpectedRepresentor());
        nic.setOvnLspName(identity.getLspId());
        nic.setUseOvn(identity.getExpectedOvsBridge() != null
                && identity.getExpectedOvsBridge().equals("br-int"));
        nic.setUseVdpa("VDPA".equalsIgnoreCase(identity.getNicKind()));
        nic.setVdpaDevice(identity.getExpectedVdpaDevice());
        try {
            if (nic.isUseVdpa() && nic.isUseOvn()) {
                new OvnVdpaVifDriver().plug(nic, null, null, java.util.Map.of());
            } else if (nic.isUseVdpa()) {
                new VdpaVifDriver().plug(nic, null, null, java.util.Map.of());
            } else if (nic.isUseOvn()) {
                new OvnVfPassthroughVifDriver().plug(nic, null, null, java.util.Map.of());
            } else {
                new VfPassthroughVifDriver().plug(nic, null, null, java.util.Map.of());
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("driver restore failed", e);
        }
        if (!OvsRepresentorCas.restamp(OvsRepresentorCasExecutor.INSTANCE, OVS_SOCKET,
                required(identity.getExpectedRepresentor(), "representor"), identity.getLspId(), workId,
                generation, identity.getExpectedNicUuid(), identity.getExpectedVfRowId(),
                identity.getExpectedBdf())) {
            throw new CloudRuntimeException("restored representor metadata postcondition failed");
        }
    }

    private static void cleanupVirtioIdentity(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        final String representor = required(identity.getExpectedRepresentor(), "representor");
        final String current = OvsRepresentorCas.readIfaceId(OvsRepresentorCasExecutor.INSTANCE,
                OVS_SOCKET, representor);
        if (current != null && !identity.getLspId().equals(current)) {
            throw new CloudRuntimeException("virtio representor owner changed");
        }
        if (current != null && !OvsRepresentorCas.remove(OvsRepresentorCasExecutor.INSTANCE,
                OVS_SOCKET, representor, identity.getLspId())) {
            throw new CloudRuntimeException("virtio OVS cleanup failed");
        }
    }

    private static boolean cleanPostcondition(final MigrationIdentityActionCommand command,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        if (observations.size() != command.getNicIdentities().size()
                || observations.stream().anyMatch(observation -> !"ABSENT".equals(observation.getDomainState()))) {
            return false;
        }
        for (ObserveVdpaMigrationCommand.NicIdentity identity : command.getNicIdentities()) {
            final ObserveVdpaMigrationAnswer.NicObservation observation = observations.stream()
                    .filter(candidate -> candidate.getNicId() == identity.getNicId()).findFirst().orElse(null);
            if (observation == null || observation.getOvsBridge() != null || observation.getOvsPort() != null
                    || observation.getOvsInterface() != null || observation.getOvsExternalIds() != null
                    || observation.getTcIdentity() != null || observation.getFdbIdentity() != null) {
                return false;
            }
            if (identity.getExpectedRepresentor() != null
                    && !OvsRepresentorCas.isAbsentExact(OvsRepresentorCasExecutor.INSTANCE, OVS_SOCKET,
                    identity.getExpectedRepresentor())) {
                return false;
            }
            if ("VDPA".equalsIgnoreCase(identity.getNicKind())
                    && !vdpaAbsent(read("vdpa dev show -j 2>/dev/null"), identity.getExpectedVdpaName(),
                    identity.getExpectedBdf())) {
                return false;
            }
            if (!"VIRTIO_OVN".equals(identity.getNicKind()) && !vfCleared(identity)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesActiveAttachment(
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        return observations.stream().allMatch(ObserveVdpaMigrationAnswer.NicObservation::isAvailable);
    }

    private static boolean matchesPreparedResourcesWithoutDomain(
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        return observations.stream().allMatch(observation -> "ABSENT".equals(observation.getDomainState())
                && (observation.isExact() || observation.isAvailable()));
    }

    private static boolean matchesAbsentPostcondition(final MigrationIdentityActionCommand command,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        return cleanPostcondition(command, observations);
    }

    private static boolean matchesRestoredDataplane(
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        return observations.stream().allMatch(LibvirtMigrationIdentityActionCommandWrapper::dataplaneExact);
    }

    private static boolean vfIdentitySafeForCleanup(final ObserveVdpaMigrationCommand.NicIdentity identity,
            final ObserveVdpaMigrationAnswer.NicObservation observation) {
        if (observation == null || identity.getExpectedMac() == null) {
            return false;
        }
        return identity.getExpectedMac().equalsIgnoreCase(observation.getMac())
                || "00:00:00:00:00:00".equals(observation.getMac());
    }

    private static boolean vfCleared(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        final String pf = VfPassthroughVifDriver.lookupPfFromVf(identity.getExpectedBdf());
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(identity.getExpectedBdf());
        if (pf == null || vfId == null) {
            return false;
        }
        final String topology = read("ip -j -details link show dev " + token(pf));
        return MigrationObservationParser.vfTopology(topology, pf, vfId,
                "00:00:00:00:00:00", "0") != null;
    }

    private static boolean cleanupPreconditionAvailable(final MigrationIdentityActionCommand command,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        if (observations.size() != command.getNicIdentities().size()
                || observations.stream().anyMatch(observation -> !"ABSENT".equals(observation.getDomainState()))) {
            return false;
        }
        for (ObserveVdpaMigrationCommand.NicIdentity identity : command.getNicIdentities()) {
            if (!vfIdentitySafeForCleanup(identity,
                    observations.get(command.getNicIdentities().indexOf(identity)))) {
                return false;
            }
            if (!OvsRepresentorCas.isAbsentExact(OvsRepresentorCasExecutor.INSTANCE, OVS_SOCKET,
                    required(identity.getExpectedRepresentor(), "representor"))) {
                return false;
            }
            if ("VDPA".equalsIgnoreCase(identity.getNicKind())
                    && !vdpaAbsent(read("vdpa dev show -j 2>/dev/null"), identity.getExpectedVdpaName(),
                    identity.getExpectedBdf())) {
                return false;
            }
            if (!"VIRTIO_OVN".equals(identity.getNicKind()) && !vfCleared(identity)) {
                return false;
            }
        }
        return true;
    }

    private static boolean dataplaneExact(final ObserveVdpaMigrationAnswer.NicObservation observation) {
        return observation.isExact();
    }

    private static boolean vdpaAbsent(final String inventory, final String name, final String bdf) {
        if (inventory == null) {
            return false;
        }
        return MigrationObservationParser.vdpaInventoryAbsent(inventory, name, bdf);
    }

    private static boolean exactAcceleratedIdentity(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        return identity.getExpectedBdf() != null && identity.getExpectedRepresentor() != null
                && identity.getExpectedNicUuid() != null && identity.getExpectedVfRowId() != null
                && identity.getExpectedMigrationWorkId() != null
                && identity.getExpectedMigrationGeneration() != null
                && identity.getExpectedMac() != null && identity.getExpectedVlan() != null
                && identity.getExpectedPf() != null && identity.getExpectedVfId() != null;
    }

    private static boolean valid(final MigrationIdentityActionCommand command) {
        return command != null && command.getVmInstanceName() != null && command.getVmUuid() != null
                && command.getWorkId() != null && !command.getWorkId().isBlank() && command.getGeneration() > 0
                && command.getRecoveryLeaseToken() != null && !command.getRecoveryLeaseToken().isBlank()
                && command.getExpectedPhase() != null && command.getAction() != null
                && !command.getNicIdentities().isEmpty()
                && command.getNicIdentities().stream().allMatch(identity -> identity.getNicKind() != null
                && ("VIRTIO_OVN".equals(identity.getNicKind())
                || "VF_PASSTHROUGH".equals(identity.getNicKind())
                || "VDPA".equals(identity.getNicKind())));
    }

    private static MigrationIdentityActionAnswer answer(final MigrationIdentityActionCommand command,
            final boolean success, final String details, final MigrationIdentityActionAnswer.Status status,
            final boolean precondition, final boolean postcondition,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        return new MigrationIdentityActionAnswer(command, success, details, status, precondition,
                postcondition, observations);
    }

    private static String required(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw new CloudRuntimeException("missing exact " + label);
        }
        return value;
    }

    private static String token(final String value) {
        if (!value.matches("[A-Za-z0-9_.:/-]+")) {
            throw new CloudRuntimeException("invalid host token");
        }
        return value;
    }

    private static String read(final String command) {
        return Script.runSimpleBashScriptWithFullResult(command, 30);
    }

    private static OvsRepresentorCas.Result runOvsdb(final String... argv) {
        try {
            return new OvsRepresentorCas.Result(true, Script.executeCommand(argv), "");
        } catch (RuntimeException e) {
            return new OvsRepresentorCas.Result(false, "", e.getMessage());
        }
    }

    private enum OvsRepresentorCasExecutor implements OvsRepresentorCas.Executor {
        INSTANCE;
        @Override
        public OvsRepresentorCas.Result run(final String... argv) {
            return runOvsdb(argv);
        }
    }
}
