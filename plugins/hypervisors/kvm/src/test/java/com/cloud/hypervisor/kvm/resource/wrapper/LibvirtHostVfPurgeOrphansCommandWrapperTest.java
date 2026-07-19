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
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtHostVfPurgeOrphansCommandWrapper.CleanupEnvironment;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtHostVfPurgeOrphansCommandWrapper.CommandResult;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtHostVfPurgeOrphansCommandWrapper.HostCommandRunner;

public class LibvirtHostVfPurgeOrphansCommandWrapperTest {

    private static final String BDF = "0000:02:07.2";
    private static final String OWNER_MAC = "02:00:00:00:00:24";
    private static final String EXPECTED_IFACE_ID = "lsp-db91cde8-e9ab-4f0a-a6f1-37f562be2536";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void emptyTargetsAreNoOpEvenWithLegacyBroadFlags() throws Exception {
        final FakeHost host = new FakeHost(temporaryFolder.newFolder("empty").toPath());
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        command.setPurgeVdpa(true);
        command.setRebindPassthroughVfs(true);
        command.setPurgeStaleOvsReps(true);

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertTrue(answer.getResult());
        assertTrue(answer.getTargetResults().isEmpty());
        assertTrue(host.commands.isEmpty());
    }

    @Test
    public void zeroMacNeverAuthorizesPresentTarget() throws Exception {
        final FakeHost host = preparedHost("zero");
        host.currentMac = "00:00:00:00:00:00";

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertFalse(answer.getResult());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("no valid owner token"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void failedIpLinkMacObservationFailsClosed() throws Exception {
        final FakeHost host = preparedHost("mac-read-error");
        host.macReadFails = true;
        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));
        assertFalse(answer.getResult());
        assertEquals("READ_ERROR", answer.getTargetResults().get(0).getMacObservation());
        assertFalse(answer.getTargetResults().get(0).isObservationComplete());
    }

    @Test
    public void missingIpLinkMacObservationFailsClosed() throws Exception {
        final FakeHost host = preparedHost("mac-missing");
        host.macOutputMissing = true;
        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));
        assertFalse(answer.getResult());
        assertEquals("READ_ERROR", answer.getTargetResults().get(0).getMacObservation());
        assertFalse(answer.getTargetResults().get(0).isObservationComplete());
    }

    @Test
    public void explicitZeroMacIsDistinguishedFromReadFailure() throws Exception {
        final FakeHost host = preparedHost("mac-zero");
        host.currentMac = "00:00:00:00:00:00";
        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));
        assertEquals("UNASSIGNED_ZERO", answer.getTargetResults().get(0).getMacObservation());
        assertFalse(answer.getResult());
    }

    @Test
    public void exactNonzeroMacIsReportedExplicitly() throws Exception {
        final HostVfPurgeOrphansAnswer answer = execute(preparedHost("mac-nonzero"), command(true));
        assertEquals("NONZERO", answer.getTargetResults().get(0).getMacObservation());
        assertEquals(OWNER_MAC, answer.getTargetResults().get(0).getCurrentMac());
    }

    @Test
    public void exactLifecycleTokenCanAuthorizeAnExplicitlyUnassignedZeroMac() throws Exception {
        final FakeHost host = preparedHost("zero-authorized");
        host.currentMac = "00:00:00:00:00:00";
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "approved-plan-hash", "RECONCILE");

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertTrue(answer.getTargetResults().get(0).getDetails(), answer.getResult());
        assertTrue(answer.getTargetResults().get(0).isLifecycleAuthorizationUsed());
        assertTrue(host.hasMutation());
    }

    @Test
    public void pausedDomainReferenceBlocksCleanupEvenWithValidToken() throws Exception {
        final FakeHost host = preparedHost("paused");
        host.domainName = "vm-paused";
        host.domainState = "paused";
        host.domainXml = hostdevXml(BDF);
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "op-paused", "STAGE_ROLLBACK");

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertFalse(answer.getResult());
        assertTrue(answer.getTargetResults().get(0).isDomainReferenced());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("vm-paused"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void runningDomainReferenceBlocksCleanup() throws Exception {
        final FakeHost host = preparedHost("running");
        host.domainName = "vm-running";
        host.domainState = "running";
        host.domainXml = hostdevXml(BDF);

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertFalse(answer.getResult());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("vm-running"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void migratingDomainReferencePreservesStateAndBlocksCleanup() throws Exception {
        final FakeHost host = preparedHost("migrating");
        host.domainName = "vm-migrating";
        host.domainState = "paused (migration)";
        host.domainXml = hostdevXml(BDF);

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertFalse(answer.getResult());
        assertTrue(answer.getTargetResults().get(0).isDomainReferenced());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("paused (migration)"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void malformedDomainXmlMakesInventoryIncompleteAndFailsClosed() throws Exception {
        final FakeHost host = preparedHost("malformed-domain");
        host.domainName = "vm-malformed";
        host.domainState = "running";
        host.domainXml = "<domain><devices><hostdev type='pci'><source>";

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertFalse(answer.getResult());
        assertFalse(answer.getTargetResults().get(0).isObservationComplete());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("securely parse"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void externalEntityDomainXmlMakesInventoryIncompleteAndFailsClosed() throws Exception {
        final FakeHost host = preparedHost("xxe-domain");
        host.domainName = "vm-xxe";
        host.domainState = "paused";
        host.domainXml = "<!DOCTYPE domain [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<domain><name>&xxe;</name><devices/></domain>";

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertFalse(answer.getResult());
        assertFalse(answer.getTargetResults().get(0).isObservationComplete());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("securely parse"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void stagedPersistentDestinationRequiresExplicitRollbackAuthorization() throws Exception {
        final FakeHost host = preparedHost("staged-denied");
        host.domainName = "vm-stage";
        host.domainState = "shut off";
        host.domainXml = hostdevXml(BDF);

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertFalse(answer.getResult());
        assertTrue(answer.getTargetResults().get(0).getDetails().contains("STAGE_ROLLBACK"));
        assertFalse(host.hasMutation());
    }

    @Test
    public void realCleanupUsesExactParentRepresentorWhenDuplicateNameIsOrderedFirst() throws Exception {
        final FakeHost host = preparedHost("exact-parent");
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "work-77", "STAGE_ROLLBACK");

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertTrue(answer.getTargetResults().get(0).getDetails(), answer.getResult());
        assertTrue(answer.getTargetResults().get(0).isRepresentorRemoved());
        assertTrue(host.commands.stream().anyMatch(value -> value.contains("del-port br-bond zzz-correct-pf1vf24")));
        assertFalse(host.commands.stream().anyMatch(value -> value.contains("del-port br-bond aaa-wrong-pf1vf24")));
        assertEquals("00:00:00:00:00:00", host.currentMac);
    }

    @Test
    public void realCleanupDeletesOnlyExactVdpaTargetAndVerifiesPostcondition() throws Exception {
        final FakeHost host = preparedHost("vdpa-real");
        host.vdpaPresent = true;

        final HostVfPurgeOrphansAnswer answer = execute(host, command(false));

        assertTrue(answer.getResult());
        assertTrue(answer.getTargetResults().get(0).isVdpaRemoved());
        assertTrue(host.commands.stream().anyMatch(value -> value.endsWith("vdpa dev del vdpa-target")));
        assertFalse(host.vdpaPresent);
    }

    @Test
    public void successfulEmptyVdpaInventoryDiffersFromFailureAndPartialResultsArePreserved() throws Exception {
        final FakeHost host = preparedHost("vdpa-failure");
        host.vdpaInventoryFails = true;
        final HostVfPurgeOrphansCommand command = command(false);
        command.setTargetPciBdfs(new LinkedHashSet<>(java.util.Arrays.asList(BDF, "0000:03:07.2")));
        command.setExpectedRepresentorsByPciBdf(Map.of(BDF, "zzz-correct-pf1vf24", "0000:03:07.2", "rep-second"));
        command.setExpectedInterfaceIdsByPciBdf(Map.of(BDF, EXPECTED_IFACE_ID,
                "0000:03:07.2", EXPECTED_IFACE_ID));
        command.setExpectedRepresentorsByPciBdf(Map.of(BDF, "zzz-correct-pf1vf24", "0000:03:07.2", "rep-second"));
        command.setExpectedInterfaceIdsByPciBdf(Map.of(BDF, EXPECTED_IFACE_ID,
                "0000:03:07.2", EXPECTED_IFACE_ID));

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertFalse(answer.getResult());
        assertEquals(2, answer.getTargetResults().size());
        assertTrue(answer.getTargetResults().stream().allMatch(result -> !result.isSuccess()
                && result.getDetails().contains("vDPA inventory unavailable")));
    }

    @Test
    public void targetMutationFailurePreservesIndependentLaterTargetResult() throws Exception {
        final FakeHost host = preparedHost("partial-targets");
        host.identityClearFails = true;
        host.removedRepresentors.add("rep-second");
        final HostVfPurgeOrphansCommand command = command(false);
        command.setTargetPciBdfs(new LinkedHashSet<>(java.util.Arrays.asList(BDF, "0000:03:07.2")));

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertFalse(answer.getResult());
        assertEquals(2, answer.getTargetResults().size());
        assertTrue(answer.getTargetResults().stream().anyMatch(result -> !result.isSuccess()));
        assertTrue("target details=" + answer.getTargetResults().stream()
                        .map(HostVfPurgeOrphansAnswer.TargetResult::getDetails).toList(),
                answer.getTargetResults().stream().anyMatch(HostVfPurgeOrphansAnswer.TargetResult::isSuccess));
    }

    @Test
    public void staleRepresentorIsRemovedOnlyWhenExactIfaceIdMatches() throws Exception {
        final FakeHost host = preparedHost("stale-representor");
        host.ovsIfaceId = EXPECTED_IFACE_ID;
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "op-stale", "LIFECYCLE_RELEASE");
        command.setExpectedRepresentorsByPciBdf(Collections.singletonMap(BDF, "dx6p1vf24"));
        command.setExpectedInterfaceIdsByPciBdf(Collections.singletonMap(BDF, EXPECTED_IFACE_ID));

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertTrue(answer.getResult());
        assertTrue(host.removedRepresentors.contains("dx6p1vf24"));
    }

    @Test
    public void sharedRepresentorIsNotRemovedWhenIfaceIdDoesNotMatch() throws Exception {
        final FakeHost host = preparedHost("shared-representor");
        host.ovsIfaceId = "lsp-other-nic";
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "op-shared", "LIFECYCLE_RELEASE");
        command.setExpectedRepresentorsByPciBdf(Collections.singletonMap(BDF, "dx6p1vf24"));
        command.setExpectedInterfaceIdsByPciBdf(Collections.singletonMap(BDF, EXPECTED_IFACE_ID));

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertFalse(answer.getResult());
        assertTrue(host.removedRepresentors.isEmpty());
    }

    @Test
    public void absentPciWithExactStaleRepresentorIsRemoved() throws Exception {
        final FakeHost host = preparedHost("absent-pci");
        final Path target = host.pciDevices.resolve(BDF);
        Files.delete(target.resolve("physfn"));
        Files.delete(target);
        host.ovsIfaceId = EXPECTED_IFACE_ID;
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "op-absent", "LIFECYCLE_RELEASE");
        command.setExpectedRepresentorsByPciBdf(Collections.singletonMap(BDF, "dx6p1vf24"));
        command.setExpectedInterfaceIdsByPciBdf(Collections.singletonMap(BDF, EXPECTED_IFACE_ID));

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertTrue(answer.getResult());
        assertTrue(host.removedRepresentors.contains("dx6p1vf24"));
    }

    @Test
    public void representorIfaceIdRaceFailsClosedBeforeDeletion() throws Exception {
        final FakeHost host = preparedHost("iface-race");
        host.ovsIfaceId = EXPECTED_IFACE_ID;
        host.changeIfaceBeforeDelete = true;
        final HostVfPurgeOrphansCommand command = command(false);
        authorize(command, "op-race", "LIFECYCLE_RELEASE");
        command.setExpectedRepresentorsByPciBdf(Collections.singletonMap(BDF, "dx6p1vf24"));
        command.setExpectedInterfaceIdsByPciBdf(Collections.singletonMap(BDF, EXPECTED_IFACE_ID));

        final HostVfPurgeOrphansAnswer answer = execute(host, command);

        assertFalse(answer.getResult());
        assertTrue(host.removedRepresentors.isEmpty());
    }

    @Test
    public void parsersSelectExactVfAndHostdev() {
        final String output = "vf 5 link/ether 02:00:00:00:00:05 spoof checking off\n"
                + "vf 24 link/ether 02:00:00:00:00:24 spoof checking off\n";

        assertEquals(OWNER_MAC, LibvirtHostVfPurgeOrphansCommandWrapper.parseVfMac(output, 24));
        assertEquals(Collections.singleton(BDF),
                LibvirtHostVfPurgeOrphansCommandWrapper.parseHostdevBdfs(hostdevXml(BDF)));
        assertFalse(LibvirtHostVfPurgeOrphansCommandWrapper.parseHostdevBdfs(hostdevXml(BDF))
                .contains("0000:00:04.0"));
        assertEquals(Collections.singleton("000a:0b:1c.7"),
                LibvirtHostVfPurgeOrphansCommandWrapper.parseHostdevBdfs(hostdevXml("000A:0B:1C.7")));
        assertFalse(LibvirtHostVfPurgeOrphansCommandWrapper.matchesExpectedMac(OWNER_MAC, null));
        assertFalse(LibvirtHostVfPurgeOrphansCommandWrapper.matchesExpectedMac(
                OWNER_MAC, "00:00:00:00:00:00"));
        assertTrue(LibvirtHostVfPurgeOrphansCommandWrapper.matchesExpectedMac(OWNER_MAC, OWNER_MAC));
    }

    private FakeHost preparedHost(final String name) throws Exception {
        final FakeHost host = new FakeHost(temporaryFolder.newFolder(name).toPath());
        host.prepareExactParentTopology();
        return host;
    }

    private static HostVfPurgeOrphansCommand command(final boolean dryRun) {
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        command.setTargetPciBdfs(Collections.singleton(BDF));
        command.setExpectedMacsByPciBdf(Collections.singletonMap(BDF, OWNER_MAC));
        command.setExpectedRepresentorsByPciBdf(Collections.singletonMap(BDF, "zzz-correct-pf1vf24"));
        command.setExpectedInterfaceIdsByPciBdf(Collections.singletonMap(BDF, EXPECTED_IFACE_ID));
        command.setDryRun(dryRun);
        return command;
    }

    private static void authorize(final HostVfPurgeOrphansCommand command, final String operation,
                                  final String purpose) {
        command.setOwnerOperationIdsByPciBdf(Collections.singletonMap(BDF, operation));
        command.setOwnerPurposesByPciBdf(Collections.singletonMap(BDF, purpose));
        command.setOwnerTokensByPciBdf(Collections.singletonMap(BDF,
                HostVfPurgeOrphansCommand.createOwnerToken(BDF, OWNER_MAC, operation, purpose)));
    }

    private static HostVfPurgeOrphansAnswer execute(final FakeHost host,
                                                    final HostVfPurgeOrphansCommand command) {
        final CleanupEnvironment environment = new CleanupEnvironment(host.pciDevices, host.netClass,
                host.pciDrivers, host.vdpaDevices, host, (path, value) -> {
                    throw new AssertionError("unexpected sysfs mutation " + path);
                });
        return (HostVfPurgeOrphansAnswer) new LibvirtHostVfPurgeOrphansCommandWrapper(environment)
                .execute(command, mock(LibvirtComputingResource.class));
    }

    private static String hostdevXml(final String bdf) {
        final String[] pieces = bdf.replace(".", ":").split(":");
        return String.format("<domain type='kvm' id='42'><name>vf-domain</name><devices>"
                        + "<hostdev mode='subsystem' type='pci' managed='yes'><driver name='vfio'/>"
                        + "<source><address domain='0x%s' bus='0x%s' slot='0x%s' function='0x%s'/></source>"
                        + "<alias name='hostdev0'/><address type='pci' domain='0x0000' bus='0x00' "
                        + "slot='0x04' function='0x0'/></hostdev></devices></domain>",
                pieces[0], pieces[1], pieces[2], pieces[3]);
    }

    private static final class FakeHost implements HostCommandRunner {
        private final Path pciDevices;
        private final Path netClass;
        private final Path pciDrivers;
        private final Path vdpaDevices;
        private final List<String> commands = new ArrayList<>();
        private final Set<String> removedRepresentors = new HashSet<>();
        private String currentMac = OWNER_MAC;
        private String domainName;
        private String domainState;
        private String domainXml;
        private boolean vdpaInventoryFails;
        private boolean vdpaPresent;
        private boolean identityClearFails;
        private boolean macReadFails;
        private boolean macOutputMissing;
        private String ovsIfaceId = EXPECTED_IFACE_ID;
        private boolean changeIfaceBeforeDelete;

        private FakeHost(final Path root) throws Exception {
            pciDevices = Files.createDirectories(root.resolve("bus/pci/devices"));
            netClass = Files.createDirectories(root.resolve("class/net"));
            pciDrivers = Files.createDirectories(root.resolve("bus/pci/drivers"));
            vdpaDevices = Files.createDirectories(root.resolve("bus/vdpa/devices"));
        }

        private void prepareExactParentTopology() throws Exception {
            final Path wrongPf = Files.createDirectories(pciDevices.resolve("0000:01:00.1"));
            final Path parentPf = Files.createDirectories(pciDevices.resolve("0000:02:00.1"));
            final Path vf = Files.createDirectories(pciDevices.resolve(BDF));
            Files.createDirectories(parentPf.resolve("net/dx6p1"));
            Files.createSymbolicLink(vf.resolve("physfn"), parentPf);
            Files.createSymbolicLink(parentPf.resolve("virtfn24"), vf);
            writeNetdev("dx5p1", "p1", wrongPf);
            writeNetdev("dx6p1", "p1", parentPf);
            writeNetdev("aaa-wrong-pf1vf24", "pf1vf24", wrongPf);
            writeNetdev("zzz-correct-pf1vf24", "pf1vf24", parentPf);
        }

        private void writeNetdev(final String name, final String port, final Path device) throws Exception {
            final Path netdev = Files.createDirectories(netClass.resolve(name));
            Files.write(netdev.resolve("phys_port_name"), port.getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(netdev.resolve("device"), device);
        }

        private boolean hasMutation() {
            return commands.stream().anyMatch(value -> value.contains(" del ")
                    || value.contains(" del-port ") || value.contains(" link set "));
        }

        @Override
        public CommandResult run(final String... command) {
            final String value = String.join(" ", command);
            commands.add(value);
            if (value.endsWith("vdpa dev show")) {
                if (vdpaInventoryFails) {
                    return CommandResult.failure("vdpa unavailable");
                }
                return CommandResult.success(vdpaPresent
                        ? "vdpa-target: type network mgmtdev pci/" + BDF : "");
            }
            if (value.endsWith("vdpa dev del vdpa-target")) {
                vdpaPresent = false;
                return CommandResult.success("");
            }
            if (value.endsWith("virsh list --all --name")) {
                return CommandResult.success(domainName == null ? "" : domainName + "\n");
            }
            if (domainName != null && value.endsWith("virsh domstate " + domainName)) {
                return CommandResult.success(domainState);
            }
            if (domainName != null && value.endsWith("virsh dumpxml " + domainName)) {
                return CommandResult.success(domainXml);
            }
            if (value.contains("/sbin/ip link show dev dx6p1")) {
                if (macReadFails) {
                    return CommandResult.failure("ip link failed");
                }
                if (macOutputMissing) {
                    return CommandResult.success("");
                }
                return CommandResult.success("vf 24 link/ether " + currentMac + " spoof checking off");
            }
            if (value.contains("/sbin/ip link set dx6p1 vf 24 mac 00:00:00:00:00:00")) {
                if (identityClearFails) {
                    return CommandResult.failure("ip link failed");
                }
                currentMac = "00:00:00:00:00:00";
                return CommandResult.success("");
            }
            if (value.contains("ovs-vsctl")) {
                if (value.contains(" get Port ")) {
                    final String representor = command[command.length - 2];
                    return CommandResult.success(removedRepresentors.contains(representor) ? "" : representor);
                }
                if (value.contains(" get Interface ") && value.contains("external_ids:iface-id")) {
                    return CommandResult.success(ovsIfaceId == null ? "" : "\"" + ovsIfaceId + "\"");
                }
                if (value.contains(" port-to-br ")) {
                    if (changeIfaceBeforeDelete) {
                        ovsIfaceId = "lsp-other-owner";
                    }
                    return CommandResult.success("br-bond");
                }
                if (value.contains(" del-port ")) {
                    final String representor = command[command.length - 1];
                    removedRepresentors.add(representor);
                    ovsIfaceId = null;
                    return CommandResult.success("");
                }
                return CommandResult.success(value.contains(" get Interface ") ? "{}" : "");
            }
            return CommandResult.success("");
        }
    }
}
