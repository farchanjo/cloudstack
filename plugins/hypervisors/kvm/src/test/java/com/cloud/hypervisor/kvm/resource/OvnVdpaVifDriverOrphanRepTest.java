/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.hypervisor.kvm.resource;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.script.Script;

/**
 * Unit tests for the orphan-representor free path in
 * {@link OvnVdpaVifDriver#unplug} (FIX D + Chaos B).
 *
 * <p>libvirt zeroes the VF MAC during managed hostdev detach / domain destroy
 * BEFORE {@code unplug} runs, so the VF-PCI reverse lookup by guest MAC
 * ({@code lookupVfPciByMac}) routinely comes back null on the VM-expunge path.
 * Without a fallback the representor stays on the integration bridge with
 * stale {@code external_ids} ({@code iface-id=lsp-...},
 * {@code iface-status=active}).
 *
 * <p>Chaos B additionally requires that free always clears
 * {@code external_ids} and uses a bridge-agnostic {@code del-port &lt;rep&gt;}
 * so a wrong configured bridge name cannot leave the OVN binding live.
 *
 * <p>All tests intercept {@link Script#runSimpleBashScript} via Mockito
 * {@link MockedStatic} so no actual OVS, vdpa or shell process is invoked.
 * The test environment has no real sysfs PF/VF topology, so both
 * {@code VdpaVifDriver.lookupVdpaNameByVhostDev} and the reflective
 * {@code VfPassthroughVifDriver.lookupVfPciByMac} naturally resolve to null
 * without any additional stubbing — exercising the exact failure branch
 * this fix addresses.
 */
public class OvnVdpaVifDriverOrphanRepTest {

    /**
     * The stop-path fan-out (getAllVifDrivers) hands every InterfaceDef to
     * every driver; a non-vDPA iface (e.g. an OVN kernel tap or a hostdev
     * VF) must be ignored entirely — zero Script activity (Bug 30 type gate).
     */
    @Test
    public void unplug_foreignNetType_isNoOp() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defHostdevNet("0000:01:00.6", "fa:16:3e:00:02:99", 0);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            driver.unplug(iface, true);
            scriptMock.verify(() -> Script.runSimpleBashScript(org.mockito.ArgumentMatchers.anyString()), never());
        }
    }

    /**
     * With the PCI reverse lookup failing, unplug() must fall back to the
     * OVSDB {@code attached-mac} lookup and free every representor it finds:
     * clear {@code external_ids} then bridge-agnostic {@code del-port}.
     */
    @Test
    public void unplug_failedPciLookup_attachedMacHit_removesOrphanRep() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defVdpaNet("/dev/vhost-vdpa-3", "fa:16:3e:00:02:10", 4);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // FullResult: multi-rep attached-mac find must not use OneLineParser.
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("dx6p1vf6");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("clear Interface")))
                    .thenReturn("");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("del-port")))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("clear Interface dx6p1vf6 external_ids")),
                    times(1));
            // Bridge-agnostic: "del-port <rep>" with no bridge name argument.
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port dx6p1vf6")), times(1));
        }
    }

    /**
     * When the attached-mac fallback also finds nothing (no orphan exists),
     * unplug() must be a clean no-op with respect to representor teardown:
     * no del-port / clear call is issued.
     */
    @Test
    public void unplug_failedPciLookup_noAttachedMacHit_isNoOp() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defVdpaNet("/dev/vhost-vdpa-4", "fa:16:3e:00:02:11", 4);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("clear Interface")), never());
        }
    }

    /**
     * Chaos B helper contract: freeRepresentorOnOvs always clears OVN
     * external_ids before bridge-agnostic del-port, so a FREE VF never keeps
     * iface-id=lsp-... / iface-status=active after deallocate.
     */
    @Test
    public void freeRepresentorOnOvs_clearsExternalIdsThenDelPort() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn("");

            OvnVifDriver.freeRepresentorOnOvs(
                    org.apache.logging.log4j.LogManager.getLogger(OvnVdpaVifDriverOrphanRepTest.class),
                    "test", "dx6p0vf9");

            scriptMock.verify(() -> Script.runSimpleBashScript(
                    contains("clear Interface dx6p0vf9 external_ids")), times(1));
            scriptMock.verify(() -> Script.runSimpleBashScript(
                    contains("del-port dx6p0vf9")), times(1));
            // Must NOT require a bridge name (Chaos B: wrong bridge silent no-op).
            scriptMock.verify(() -> Script.runSimpleBashScript(
                    contains("del-port br-")), never());
        }
    }
}
