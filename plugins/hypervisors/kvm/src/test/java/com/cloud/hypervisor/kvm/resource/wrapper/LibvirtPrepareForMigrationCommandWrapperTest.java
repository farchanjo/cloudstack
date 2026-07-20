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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.api.routing.ObserveVdpaMigrationAnswer;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;
import com.cloud.hypervisor.kvm.resource.MigrationIdentityFenceStore;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtPrepareForMigrationCommandWrapperTest {

    @Mock
    LibvirtComputingResource libvirtComputingResourceMock;

    @Mock
    PrepareForMigrationCommand prepareForMigrationCommandMock;

    @Mock
    VirtualMachineTO virtualMachineTOMock;

    @Spy
    LibvirtPrepareForMigrationCommandWrapper libvirtPrepareForMigrationCommandWrapperSpy = new LibvirtPrepareForMigrationCommandWrapper();

    private Path migrationFenceDirectory;
    private MockedStatic<LibvirtObserveVdpaMigrationCommandWrapper> observationMock;

    @Before
    public void installStrictMigrationFixture() throws Exception {
        migrationFenceDirectory = Files.createTempDirectory("kvm-prepare-fence-");
        System.setProperty("cloudstack.kvm.migration-fence-dir", migrationFenceDirectory.toString());
        final ObserveVdpaMigrationCommand.NicIdentity identity = migrationIdentity();
        Mockito.when(prepareForMigrationCommandMock.getMigrationWorkId()).thenReturn("work");
        Mockito.when(prepareForMigrationCommandMock.getMigrationGeneration()).thenReturn(1L);
        Mockito.when(prepareForMigrationCommandMock.getMigrationLeaseToken()).thenReturn("lease");
        Mockito.when(prepareForMigrationCommandMock.getMigrationLeaseVersion()).thenReturn(1L);
        Mockito.when(prepareForMigrationCommandMock.getMigrationLeaseExpiry()).thenReturn(Instant.now().plusSeconds(3600).getEpochSecond());
        Mockito.when(prepareForMigrationCommandMock.getMigrationIdentities()).thenReturn(List.of(identity));
        Mockito.when(virtualMachineTOMock.getName()).thenReturn("vm");
        Mockito.when(virtualMachineTOMock.getNics()).thenReturn(new NicTO[]{Mockito.mock(NicTO.class)});
        final MigrationIdentityFenceStore fences = new MigrationIdentityFenceStore(migrationFenceDirectory);
        fences.install("work", 1L, List.of(MigrationIdentityFenceStore.Fence.fromIdentity(
                "work", 1L, identity, "lease", 1L, prepareForMigrationCommandMock.getMigrationLeaseExpiry())));
        final ObserveVdpaMigrationAnswer.NicObservation observation = new ObserveVdpaMigrationAnswer.NicObservation(
                1L, "lsp-1", null, null, null, "ABSENT", null, null, null, null, null, true, true);
        observation.setNicUuid("nic-1");
        observationMock = Mockito.mockStatic(LibvirtObserveVdpaMigrationCommandWrapper.class);
        observationMock.when(() -> LibvirtObserveVdpaMigrationCommandWrapper.observeWithLocks(
                Mockito.any(ObserveVdpaMigrationCommand.class))).thenReturn(List.of(observation));
    }

    @After
    public void removeStrictMigrationFixture() throws IOException {
        if (observationMock != null) {
            observationMock.close();
        }
        System.clearProperty("cloudstack.kvm.migration-fence-dir");
        if (migrationFenceDirectory != null) {
            try (var paths = Files.walk(migrationFenceDirectory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private static ObserveVdpaMigrationCommand.NicIdentity migrationIdentity() {
        final ObserveVdpaMigrationCommand.NicIdentity identity = new ObserveVdpaMigrationCommand.NicIdentity(
                1L, "lsp-1", "VIRTIO_OVN", null, null, null,
                "aa:bb:cc:dd:ee:ff", null, "virtio", null, "br-int", "port1", "tap0", null,
                "lsp-1", "chassis1");
        identity.setExpectedNicUuid("nic-1");
        return identity;
    }

    @Test
    public void createPrepareForMigrationAnswerTestDpdkInterfaceNotEmptyShouldSetParamOnAnswer() {
        Map<String, DpdkTO> dpdkInterfaceMapping = new HashMap<>();
        dpdkInterfaceMapping.put("Interface", new DpdkTO());

        PrepareForMigrationAnswer prepareForMigrationAnswer = libvirtPrepareForMigrationCommandWrapperSpy.createPrepareForMigrationAnswer(prepareForMigrationCommandMock, dpdkInterfaceMapping, new HashMap<>(), libvirtComputingResourceMock,
                virtualMachineTOMock, virtualMachineTOMock.getNics());

        Assert.assertEquals(prepareForMigrationAnswer.getDpdkInterfaceMapping(), dpdkInterfaceMapping);
    }

    @Test
    public void createPrepareForMigrationAnswerCarriesEveryVdpaMapping() {
        Map<String, String> vdpaMapping = Map.of(
                "02:00:00:00:00:01", "/dev/vhost-vdpa-new1",
                "02:00:00:00:00:02", "/dev/vhost-vdpa-new2");

        PrepareForMigrationAnswer answer = libvirtPrepareForMigrationCommandWrapperSpy
                .createPrepareForMigrationAnswer(prepareForMigrationCommandMock, new HashMap<>(),
                        vdpaMapping, libvirtComputingResourceMock, virtualMachineTOMock,
                        virtualMachineTOMock.getNics());

        Assert.assertEquals(vdpaMapping, answer.getVdpaInterfaceMapping());
    }

    @Test
    public void createPrepareForMigrationAnswerTestVerifyThatCpuSharesIsSet() {
        int cpuShares = 1000;
        Mockito.doReturn(cpuShares).when(libvirtComputingResourceMock).calculateCpuShares(virtualMachineTOMock);
        PrepareForMigrationAnswer prepareForMigrationAnswer = libvirtPrepareForMigrationCommandWrapperSpy.createPrepareForMigrationAnswer(prepareForMigrationCommandMock,null, null,
                libvirtComputingResourceMock, virtualMachineTOMock, virtualMachineTOMock.getNics());

        Assert.assertEquals(cpuShares, prepareForMigrationAnswer.getNewVmCpuShares().intValue());
    }

    private String getTempFilepath() {
        return String.format("%s/%s.txt", System.getProperty("java.io.tmpdir"), UUID.randomUUID());
    }

    private void runTestRemoveDpdkPortForCommandInjection(String portWithCommand) {
        try {
            libvirtPrepareForMigrationCommandWrapperSpy.removeDpdkPort(portWithCommand);
            Assert.fail(String.format("Command injection working for portWithCommand: %s", portWithCommand));
        } catch (CloudRuntimeException ignored) {}
    }

    @Test
    public void testRemoveDpdkPortForCommandInjection() {
        List<String> commandVariants = List.of(
                "';touch %s'",
                ";touch %s",
                "&& touch %s",
                "|| touch %s",
                UUID.randomUUID().toString());
        for (String cmd : commandVariants) {
            String portWithCommand = String.format(cmd, getTempFilepath());
            runTestRemoveDpdkPortForCommandInjection(portWithCommand);
        }
    }
}
