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

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.script.Script;

/**
 * Unit tests for the orphan-representor attached-mac fallback introduced in
 * {@link OvnVdpaVifDriver#unplug} (FIX D). libvirt zeroes the VF MAC during
 * managed hostdev detach / domain destroy BEFORE {@code unplug} runs, so the
 * VF-PCI reverse lookup by guest MAC ({@code lookupVfPciByMac}) routinely
 * comes back null on the VM-expunge path, leaving the representor attached
 * to the integration bridge with its stale {@code external_ids}.
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
     * With the PCI reverse lookup failing, unplug() must fall back to the
     * OVSDB {@code attached-mac} lookup and remove every representor it
     * finds from the integration bridge. The vdpa-dev deletion branch
     * (section 1 of unplug) must still run first and unconditionally, ahead
     * of the rep fallback — it is untouched by this fix.
     */
    @Test
    public void unplug_failedPciLookup_attachedMacHit_removesOrphanRep() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defVdpaNet("/dev/vhost-vdpa-3", "fa:16:3e:00:02:10", 4);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface external_ids:attached-mac")))
                    .thenReturn("dx6p1vf6");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("del-port")))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), times(1));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("dx6p1vf6")), times(1));
        }
    }

    /**
     * When the attached-mac fallback also finds nothing (no orphan exists),
     * unplug() must be a clean no-op with respect to representor teardown:
     * no del-port call is issued.
     */
    @Test
    public void unplug_failedPciLookup_noAttachedMacHit_isNoOp() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defVdpaNet("/dev/vhost-vdpa-4", "fa:16:3e:00:02:11", 4);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface external_ids:attached-mac")))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
        }
    }
}
