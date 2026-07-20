// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.routing.ObserveVdpaMigrationAnswer;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;
import com.cloud.hypervisor.kvm.resource.VfHostLifecycleLock;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.MigrationObservationParser;
import com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

/** Read-only exact identity observation; this wrapper never changes host state. */
@ResourceWrapper(handles = ObserveVdpaMigrationCommand.class)
public class LibvirtObserveVdpaMigrationCommandWrapper extends
        CommandWrapper<ObserveVdpaMigrationCommand, Answer, LibvirtComputingResource> {
    private static final ConcurrentHashMap<String, ReentrantLock> INTERFACE_LOCKS = new ConcurrentHashMap<>();
    @Override
    public Answer execute(final ObserveVdpaMigrationCommand command, final LibvirtComputingResource resource) {
        if (command.getWorkId() == null || command.getWorkId().isBlank() || command.getGeneration() <= 0
                || command.getNicIdentities().isEmpty()) {
            return new ObserveVdpaMigrationAnswer(command, false, "migration identity is incomplete",
                    command.getWorkId(), command.getGeneration(), false, List.of());
        }
        final List<ObserveVdpaMigrationAnswer.NicObservation> observations = observeWithLocks(command);
        final boolean available = observations.stream().allMatch(ObserveVdpaMigrationAnswer.NicObservation::isAvailable);
        return new ObserveVdpaMigrationAnswer(command, available,
                available ? "exact read-only observation" : "one or more local observations unavailable",
                command.getWorkId(), command.getGeneration(), available, observations);
    }

    /** Runs the structured observer while acquiring every canonical NIC/VF lock. */
    public static List<ObserveVdpaMigrationAnswer.NicObservation> observeWithLocks(
            final ObserveVdpaMigrationCommand command) {
        final List<ReentrantLock> locks = command.getNicIdentities().stream()
                .sorted(java.util.Comparator.comparing(LibvirtObserveVdpaMigrationCommandWrapper::lockKey))
                .map(LibvirtObserveVdpaMigrationCommandWrapper::lockFor).distinct().toList();
        locks.forEach(ReentrantLock::lock);
        try {
            return executeLocked(command);
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }

    static ReentrantLock lockFor(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        if (identity.getExpectedBdf() != null && !identity.getExpectedBdf().isBlank()) {
            return VfHostLifecycleLock.forBdf(identity.getExpectedBdf());
        }
        return INTERFACE_LOCKS.computeIfAbsent(identity.getLspId(), ignored -> new ReentrantLock());
    }

    static String lockKey(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        return identity.getExpectedBdf() == null ? "lsp:" + identity.getLspId()
                : "bdf:" + identity.getExpectedBdf().toLowerCase(java.util.Locale.ROOT);
    }

    static boolean identityContractValid(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        if (identity == null || identity.getNicId() <= 0 || identity.getLspId() == null
                || identity.getExpectedNicUuid() == null || identity.getExpectedMac() == null
                || identity.getNicKind() == null) {
            return false;
        }
        if ("VIRTIO_OVN".equals(identity.getNicKind())) {
            return true;
        }
        return ("VF_PASSTHROUGH".equals(identity.getNicKind()) || "VDPA".equals(identity.getNicKind()))
                && identity.getExpectedBdf() != null && identity.getExpectedPf() != null
                && identity.getExpectedVfId() != null && identity.getExpectedVlan() != null
                && identity.getExpectedDriver() != null && identity.getExpectedRepresentor() != null
                && identity.getExpectedRepresentorPhysPortName() != null
                && identity.getExpectedRepresentorBdf() != null
                && (!"VDPA".equals(identity.getNicKind())
                || identity.getExpectedVdpaName() != null && identity.getExpectedVdpaDevice() != null);
    }

    static boolean discoveryContractValid(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        if (identity == null || identity.getNicId() <= 0 || identity.getLspId() == null
                || identity.getExpectedNicUuid() == null || identity.getExpectedMac() == null
                || identity.getNicKind() == null) {
            return false;
        }
        if ("VIRTIO_OVN".equals(identity.getNicKind())) {
            return true;
        }
        return ("VF_PASSTHROUGH".equals(identity.getNicKind()) || "VDPA".equals(identity.getNicKind()))
                && identity.getExpectedBdf() != null && identity.getExpectedPf() != null
                && identity.getExpectedRepresentor() != null
                && (!"VDPA".equals(identity.getNicKind())
                || identity.getExpectedVdpaName() != null && identity.getExpectedVdpaDevice() != null);
    }

    /** Executes an observation while the caller owns every involved BDF lock. */
    static List<ObserveVdpaMigrationAnswer.NicObservation> executeLocked(
            final ObserveVdpaMigrationCommand command) {
        final List<ObserveVdpaMigrationAnswer.NicObservation> observations = new ArrayList<>();
        for (final ObserveVdpaMigrationCommand.NicIdentity identity : command.getNicIdentities()) {
            observations.add(observe(command, identity));
        }
        return observations;
    }

    private static ObserveVdpaMigrationAnswer.NicObservation observe(final ObserveVdpaMigrationCommand command,
            final ObserveVdpaMigrationCommand.NicIdentity identity) {
        if (identity.getNicId() <= 0 || identity.getLspId() == null) {
            return unavailable(identity);
        }
        final String bdf = identity.getExpectedBdf();
        final boolean virtio = "VIRTIO_OVN".equals(identity.getNicKind());
        final boolean vdpaKind = "VDPA".equals(identity.getNicKind());
        final boolean vfKind = "VF_PASSTHROUGH".equals(identity.getNicKind());
            if (!(command.isTopologyDiscovery() ? discoveryContractValid(identity) : identityContractValid(identity))) {
                return unavailable(identity);
            }
        final String domainXml = readOnly("virsh dumpxml " + shellQuote(command.getVmInstanceName()));
            final String domainState = readOnly("virsh domstate " + shellQuote(command.getVmInstanceName()));
            final String domainList = readOnly("virsh list --all --name");
            final String vdpa = vdpaKind ? readOnly("vdpa dev show -j") : null;
            final String pf = bdf == null ? identity.getExpectedPf() : identity.getExpectedPf() != null
                    ? identity.getExpectedPf() : VfPassthroughVifDriver.lookupPfFromVf(bdf);
            final Integer vfId = bdf == null ? identity.getExpectedVfId() : identity.getExpectedVfId() != null
                    ? identity.getExpectedVfId() : VfPassthroughVifDriver.lookupVfIdFromPci(bdf);
            final String driver = bdf == null ? null
                    : readOnly("readlink -f /sys/bus/pci/devices/" + bdf + "/driver");
            final String representor = identity.getExpectedRepresentor() != null ? identity.getExpectedRepresentor()
                    : bdf == null ? null : VfPassthroughVifDriver.lookupRepresentor(bdf);
            final String actualRepresentor = representor == null
                    ? discoverRepresentor(identity.getLspId()) : representor;
            final String repPhys = actualRepresentor == null ? null : readPhysPortName(actualRepresentor);
            final String repBdf = actualRepresentor == null ? null
                    : readOnly("readlink -f /sys/class/net/" + actualRepresentor + "/device | xargs -r basename");
            final String topology = pf == null ? null
                    : readOnly("ip -j -details link show dev " + shellQuote(pf));
            final String ovs = actualRepresentor == null ? null : ovsIdentityQuery(actualRepresentor);
            final String tc = actualRepresentor == null ? null
                    : readOnly("tc -j filter show dev " + shellQuote(actualRepresentor) + " ingress");
            final String fdb = actualRepresentor == null ? null
                    : readOnly("bridge -j fdb show dev " + shellQuote(actualRepresentor));
            final String ovn = identity.getExpectedOvnPortBinding() == null ? null
                    : readOnly("ovn-sbctl --format=json --columns=logical_port,chassis find Port_Binding "
                    + "logical_port=" + shellQuote(identity.getExpectedOvnPortBinding()));
            final MigrationObservationParser.DomainInterface domain =
                    MigrationObservationParser.domainInterface(domainXml, identity.getExpectedMac(),
                            identity.getExpectedLibvirtAlias(), identity.getExpectedLibvirtTarget());
            final MigrationObservationParser.VdpaDevice vdpaDevice = requiresVdpa(identity)
                    ? MigrationObservationParser.vdpaDevice(vdpa, identity.getExpectedVdpaName(),
                            identity.getExpectedVdpaDevice()) : null;
            final MigrationObservationParser.OvsInterface ovsInterface =
                    MigrationObservationParser.ovsInterface(ovs, identity.getExpectedOvsInterface(),
                            identity.getLspId(), identity.getExpectedOvsBridge(), identity.getExpectedOvsPort());
            final MigrationObservationParser.VfTopology vfTopology =
                    MigrationObservationParser.vfTopology(topology, pf, vfId, identity.getExpectedMac(),
                            identity.getExpectedVlan());
            final boolean domainListed = domainList != null && java.util.Arrays.stream(domainList.split("\\R"))
                    .map(String::trim).anyMatch(command.getVmInstanceName()::equals);
            final boolean domainAbsent = domainList != null && !domainListed;
            final boolean domainAvailable = domainXml != null && domain != null || domainAbsent;
            final boolean vdpaAvailable = !vdpaKind || vdpaDevice != null
                    && equals(bdf, vdpaDevice.managementBdf());
            final boolean ovsAvailable = ovs != null && ovsInterface != null;
            final boolean driverAvailable = virtio || driver != null && !driver.isBlank();
            final boolean discovery = command.isTopologyDiscovery() && !identityContractValid(identity);
            final boolean discoveredTopology = virtio || vfTopology != null && vfTopology.vfId() != null
                    && driverAvailable && actualRepresentor != null && repPhys != null && repBdf != null;
            final boolean vfAvailable = virtio || vfTopology != null
                    && identity.getExpectedBdf() != null && identity.getExpectedMac() != null
                    && identity.getExpectedVlan() != null && identity.getExpectedDriver() != null
                    && identity.getExpectedPf() != null && identity.getExpectedVfId() != null
                    && identity.getExpectedRepresentor() != null
                    && identity.getExpectedRepresentorPhysPortName() != null
                    && identity.getExpectedRepresentorBdf() != null;
            boolean complete = domainAvailable && vdpaAvailable && ovsAvailable && driverAvailable
                    && (discovery ? discoveredTopology : vfAvailable)
                    && (domainAbsent || domainMatches(domain, identity)) && ovsMatches(ovsInterface, identity)
                    && (identity.getExpectedDriver() == null
                    || driver != null && driver.endsWith(identity.getExpectedDriver()))
                    && (domainAbsent || requiresVdpa(identity)
                    ? domainAbsent || domain != null && equals(identity.getExpectedVdpaDevice(), domain.source())
                    : bdf == null || equals(bdf, domain == null ? null : domain.bdf()))
                    && (virtio || vfTopology != null && equals(identity.getExpectedPf(), vfTopology.pf()))
                    && (identity.getExpectedVfId() == null || identity.getExpectedVfId().equals(vfTopology.vfId()))
                    && (virtio || equals(identity.getExpectedRepresentor(), representor))
                    && (discovery ? identity.getExpectedRepresentorPhysPortName() == null
                    || equals(identity.getExpectedRepresentorPhysPortName(), repPhys)
                    : equals(identity.getExpectedRepresentorPhysPortName(), repPhys))
                    && (discovery ? identity.getExpectedRepresentorBdf() == null
                    || equals(identity.getExpectedRepresentorBdf(), repBdf)
                    : equals(identity.getExpectedRepresentorBdf(), repBdf))
                    && exactText(identity.getExpectedTcExpectation(), tc)
                    && exactText(identity.getExpectedFdbExpectation(), fdb)
                    && (virtio || migrationIdsMatch(ovsInterface, identity))
                    && (identity.getExpectedOvnPortBinding() == null
                    || MigrationObservationParser.ovnBindingExact(ovn, identity.getExpectedOvnPortBinding(),
                    identity.getExpectedOvnChassis()));
            complete = complete && ovsInterface != null && ovsInterface.bridgeUuid() != null
                    && ovsInterface.portUuid() != null && ovsInterface.interfaceUuid() != null
                    && (domainAbsent || domain != null && domain.alias() != null && domain.target() != null
                    && domain.type() != null && domain.model() != null
                    && (virtio || vdpaKind && vdpaDevice != null && vdpaDevice.managementBdf() != null
                    || vfKind && domain.bdf() != null));
            final Map<String, ObserveVdpaMigrationAnswer.ObservationState> states = states(identity,
                    domainAvailable, vdpaAvailable, driverAvailable, ovsAvailable);
            final String actualBdf = vdpaKind && vdpaDevice != null
                    ? vdpaDevice.managementBdf() : domain == null ? null : domain.bdf();
            final ObserveVdpaMigrationAnswer.NicObservation result = new ObserveVdpaMigrationAnswer.NicObservation(
                    identity.getNicId(), identity.getLspId(),
                    actualBdf, vdpaDevice == null ? null : vdpaDevice.name(),
                    vdpaDevice == null ? null : vdpaDevice.device(), domainState, driver, null, null,
                    actualRepresentor, ovsInterface == null || ovsInterface.externalIds() == null
                            ? null : ovsInterface.externalIds().toString(),
                    complete, complete, states);
            result.setNicUuid(identity.getExpectedNicUuid());
            result.setPf(vfTopology == null ? null : vfTopology.pf());
            result.setVfId(vfTopology == null ? null : vfTopology.vfId());
            result.setMac(vfTopology == null ? domain == null ? null : domain.mac() : vfTopology.mac());
            result.setVlan(vfTopology == null ? null : vfTopology.vlan());
            result.setRepresentorPhysPortName(repPhys);
            result.setRepresentorBdf(repBdf);
            result.setOvsBridge(ovsInterface == null ? null : ovsInterface.bridge());
            result.setOvsPort(ovsInterface == null ? null : ovsInterface.port());
            result.setOvsInterface(ovsInterface == null ? null : ovsInterface.name());
            result.setOvsBridgeUuid(ovsInterface == null ? null : ovsInterface.bridgeUuid());
            result.setOvsPortUuid(ovsInterface == null ? null : ovsInterface.portUuid());
            result.setOvsInterfaceUuid(ovsInterface == null ? null : ovsInterface.interfaceUuid());
            result.setOvnMetadata(ovn);
            if (domain != null) {
                result.setLibvirtAlias(domain.alias());
                result.setLibvirtTarget(domain.target());
                result.setLibvirtSource(domain.source());
                result.setLibvirtType(domain.type());
                result.setLibvirtModel(domain.model());
            }
            result.setTcIdentity(tc);
            result.setFdbIdentity(fdb);
            if (domainAbsent) {
                result.setDomainState("ABSENT");
            }
        return result;
    }

    private static ObserveVdpaMigrationAnswer.NicObservation unavailable(
            final ObserveVdpaMigrationCommand.NicIdentity identity) {
        return new ObserveVdpaMigrationAnswer.NicObservation(identity.getNicId(), identity.getLspId(),
                null, null, null, null, null, null, null, null, null, false, false);
    }

    private static boolean requiresVdpa(final ObserveVdpaMigrationCommand.NicIdentity identity) {
        return "VDPA".equalsIgnoreCase(identity.getNicKind());
    }

    private static boolean domainMatches(final MigrationObservationParser.DomainInterface domain,
            final ObserveVdpaMigrationCommand.NicIdentity identity) {
        return domain != null && exactExpected(identity.getExpectedMac(), domain.mac())
                && matchesIfExpected(identity.getExpectedLibvirtAlias(), domain.alias())
                && matchesIfExpected(identity.getExpectedLibvirtTarget(), domain.target())
                && matchesIfExpected(identity.getExpectedLibvirtSource(), domain.source())
                && matchesIfExpected(identity.getExpectedLibvirtType(), domain.type())
                && matchesIfExpected(identity.getExpectedLibvirtModel(), domain.model());
    }

    private static boolean ovsMatches(final MigrationObservationParser.OvsInterface ovs,
            final ObserveVdpaMigrationCommand.NicIdentity identity) {
        return ovs != null && ovs.name() != null && ovs.bridge() != null && ovs.port() != null
                && ovs.bridgeUuid() != null && ovs.portUuid() != null && ovs.interfaceUuid() != null
                && matchesIfExpected(identity.getExpectedOvsInterface(), ovs.name())
                && matchesIfExpected(identity.getExpectedOvsBridge(), ovs.bridge())
                && matchesIfExpected(identity.getExpectedOvsPort(), ovs.port())
                && matchesIfExpected(identity.getExpectedOvsBridgeUuid(), ovs.bridgeUuid())
                && matchesIfExpected(identity.getExpectedOvsPortUuid(), ovs.portUuid())
                && matchesIfExpected(identity.getExpectedOvsInterfaceUuid(), ovs.interfaceUuid())
                && (identity.getExpectedOvsExternalIds() == null
                || MigrationObservationParser.externalIdsEqual(identity.getExpectedOvsExternalIds(), ovs.externalIds()));
    }

    private static boolean exactExpected(final String expected, final String actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private static boolean matchesIfExpected(final String expected, final String actual) {
        return expected == null ? actual != null : expected.equals(actual);
    }

    private static Map<String, ObserveVdpaMigrationAnswer.ObservationState> states(
            final ObserveVdpaMigrationCommand.NicIdentity identity, final boolean domain,
            final boolean vdpa, final boolean driver, final boolean ovs) {
        final Map<String, ObserveVdpaMigrationAnswer.ObservationState> result = new java.util.HashMap<>();
        result.put("domain", domain ? ObserveVdpaMigrationAnswer.ObservationState.OBSERVED
                : ObserveVdpaMigrationAnswer.ObservationState.UNAVAILABLE);
        result.put("vdpa", requiresVdpa(identity) ? (vdpa ? ObserveVdpaMigrationAnswer.ObservationState.OBSERVED
                : ObserveVdpaMigrationAnswer.ObservationState.UNAVAILABLE)
                : ObserveVdpaMigrationAnswer.ObservationState.NOT_APPLICABLE);
        result.put("driver", identity.getExpectedBdf() == null
                ? ObserveVdpaMigrationAnswer.ObservationState.NOT_APPLICABLE
                : driver ? ObserveVdpaMigrationAnswer.ObservationState.OBSERVED
                : ObserveVdpaMigrationAnswer.ObservationState.UNAVAILABLE);
        result.put("ovs", ovs ? ObserveVdpaMigrationAnswer.ObservationState.OBSERVED
                : ObserveVdpaMigrationAnswer.ObservationState.UNAVAILABLE);
        return result;
    }

    private static boolean equals(final String expected, final String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static boolean exactText(final String expected, final String actual) {
        return expected == null ? actual != null : expected.equals(actual);
    }

    private static boolean migrationIdsMatch(final MigrationObservationParser.OvsInterface ovs,
            final ObserveVdpaMigrationCommand.NicIdentity identity) {
        if (ovs == null) {
            return false;
        }
        final String observedWork = MigrationObservationParser.externalId(ovs.externalIds(), "migration-work-id");
        final String observedGeneration = MigrationObservationParser.externalId(ovs.externalIds(), "migration-generation");
        final String observedNicUuid = MigrationObservationParser.externalId(ovs.externalIds(), "migration-nic-uuid");
        final String observedVfRow = MigrationObservationParser.externalId(ovs.externalIds(), "migration-vf-row-id");
        final String observedBdf = MigrationObservationParser.externalId(ovs.externalIds(), "migration-vf-bdf");
        if (identity.getExpectedMigrationWorkId() == null && identity.getExpectedMigrationGeneration() == null
                && identity.getExpectedNicUuid() == null && identity.getExpectedVfRowId() == null) {
            return true;
        }
        return identity.getExpectedMigrationWorkId() != null && observedWork != null
                && identity.getExpectedMigrationGeneration() != null && observedGeneration != null
                && identity.getExpectedNicUuid() != null && observedNicUuid != null
                && identity.getExpectedVfRowId() != null && observedVfRow != null
                && identity.getExpectedBdf() != null && observedBdf != null
                && identity.getExpectedMigrationWorkId().equals(observedWork)
                && String.valueOf(identity.getExpectedMigrationGeneration()).equals(observedGeneration)
                && identity.getExpectedNicUuid().equals(observedNicUuid)
                && String.valueOf(identity.getExpectedVfRowId()).equals(observedVfRow)
                && identity.getExpectedBdf().equalsIgnoreCase(observedBdf);
    }

    private static String readOnly(final String command) {
        try {
            return Script.runSimpleBashScriptWithFullResult(command, 30);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ovsIdentityQuery(final String representor) {
        if (!representor.matches("[A-Za-z0-9_.:-]+")) {
            return null;
        }
        return readOnly("ovsdb-client transact unix:/var/run/openvswitch/db.sock '[\"Open_vSwitch\","
                + "{\"op\":\"select\",\"table\":\"Interface\",\"where\":[[\"name\",\"==\",\""
                + representor + "\"]],\"columns\":[\"_uuid\",\"name\",\"external_ids\"]},"
                + "{\"op\":\"select\",\"table\":\"Port\",\"where\":[[\"name\",\"==\",\""
                + representor + "\"]],\"columns\":[\"_uuid\",\"name\",\"interfaces\"]},"
                + "{\"op\":\"select\",\"table\":\"Bridge\",\"where\":[],\"columns\":[\"_uuid\",\"name\",\"ports\"]}]'");
    }

    private static String discoverRepresentor(final String lspId) {
        if (lspId == null || !lspId.matches("lsp-[A-Za-z0-9_.:-]+")) {
            return null;
        }
        final String result = readOnly("ovs-vsctl --if-exists --bare --columns=name find Interface "
                + "external_ids:iface-id=" + shellQuote(lspId));
        return result == null ? null : java.util.Arrays.stream(result.split("\\R"))
                .map(String::trim).filter(value -> !value.isBlank()).findFirst().orElse(null);
    }

    private static String readPhysPortName(final String iface) {
        final String value = readOnly("cat /sys/class/net/" + shellQuote(iface) + "/phys_port_name");
        return value == null ? null : value.trim();
    }

    private static String shellQuote(final String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("invalid domain name");
        }
        return "'" + value + "'";
    }

}
