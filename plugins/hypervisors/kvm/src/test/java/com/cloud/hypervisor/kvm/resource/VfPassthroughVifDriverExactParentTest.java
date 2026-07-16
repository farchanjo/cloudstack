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
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VfPassthroughVifDriverExactParentTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exactVfParentWinsWhenTwoAdaptersExposeP1() throws Exception {
        final Path root = temporaryFolder.newFolder("sys").toPath();
        final Path pciDevices = Files.createDirectories(root.resolve("bus/pci/devices"));
        final Path netClass = Files.createDirectories(root.resolve("class/net"));
        final Path wrongPf = Files.createDirectories(pciDevices.resolve("0000:01:00.1"));
        final Path pf = Files.createDirectories(pciDevices.resolve("0000:02:00.1"));
        final Path vf = Files.createDirectories(pciDevices.resolve("0000:01:07.2"));
        Files.createDirectories(pf.resolve("net/dx6p1"));
        Files.createSymbolicLink(vf.resolve("physfn"), pf);
        Files.createSymbolicLink(pf.resolve("virtfn24"), vf);

        writeNetdev(netClass, "dx5p1", "p1", wrongPf);
        writeNetdev(netClass, "dx6p1", "p1", pf);
        writeNetdev(netClass, "aaa-wrong-pf1vf24", "pf1vf24", wrongPf);
        writeNetdev(netClass, "zzz-correct-pf1vf24", "pf1vf24", pf);

        assertEquals("dx6p1", VfPassthroughVifDriver.lookupPfFromVf(
                "0000:01:07.2", pciDevices, netClass));
        assertEquals(Integer.valueOf(24), VfPassthroughVifDriver.lookupVfIdFromPci(
                "0000:01:07.2", pciDevices));
        assertEquals("zzz-correct-pf1vf24", VfPassthroughVifDriver.lookupRepresentor(
                "0000:01:07.2", pciDevices, netClass));
    }

    private static void writeNetdev(final Path netClass, final String name, final String value,
                                    final Path parentPci) throws Exception {
        final Path iface = Files.createDirectories(netClass.resolve(name));
        Files.write(iface.resolve("phys_port_name"), value.getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(iface.resolve("device"), parentPci);
    }
}
