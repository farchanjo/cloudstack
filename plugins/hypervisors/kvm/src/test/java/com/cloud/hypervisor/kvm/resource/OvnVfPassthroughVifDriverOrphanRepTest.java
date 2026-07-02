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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.script.Script;

/**
 * Unit tests for the orphan-iface-id cleanup path introduced in
 * {@link OvnVfPassthroughVifDriver} to prevent the
 * {@code ovn-controller} duplicate-binding WARN:
 *
 * <pre>
 *   binding|WARN|Invalid configuration: iface-id is configured on
 *   interfaces: [dx6p1vf6] and [dx6p0vf4]. Ignoring the configuration
 *   on interface [dx6p0vf4]
 * </pre>
 *
 * <p>All tests intercept {@link Script#runSimpleBashScript} via Mockito
 * {@link MockedStatic} so no actual OVS or shell process is invoked.
 */
public class OvnVfPassthroughVifDriverOrphanRepTest {

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Reflectively invoke the private clearOrphanRepsForLspName helper. */
    private static void invokeClearOrphans(
            final OvnVfPassthroughVifDriver driver,
            final String lspName,
            final String keepRepName) throws Exception {
        final Method m = OvnVfPassthroughVifDriver.class
                .getDeclaredMethod("clearOrphanRepsForLspName", String.class, String.class);
        m.setAccessible(true);
        m.invoke(driver, lspName, keepRepName);
    }

    // -----------------------------------------------------------------------
    // clearOrphanRepsForLspName tests
    // -----------------------------------------------------------------------

    /**
     * When the OVS find command returns two representors for the same
     * iface-id and keepRepName is one of them, only the other rep must be
     * removed with del-port. The target rep must never be passed to del-port.
     */
    @Test
    public void clearOrphanRepsForLspName_clearsOtherRep_keepsTarget() throws Exception {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // Simulate ovs-vsctl find returning two reps on separate lines.
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface")))
                    .thenReturn("dx6p1vf6\ndx6p0vf4");

            // All other Script calls (del-port) should succeed with empty output.
            scriptMock.when(() -> Script.runSimpleBashScript(contains("del-port")))
                    .thenReturn("");

            invokeClearOrphans(driver, "lsp-99640d2f", "dx6p1vf6");

            // del-port must have been called exactly once — for the orphan, not the keeper.
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), times(1));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("dx6p0vf4")), times(1));
            scriptMock.verify(() -> Script.runSimpleBashScript(
                    contains("del-port") + anyString() + contains("dx6p1vf6")), never());
        }
    }

    /**
     * A blank or null lspName must short-circuit before any Script call,
     * including the OVS find query. Stamping an empty iface-id on the OVS
     * Interface table would corrupt the row.
     */
    @Test
    public void clearOrphanRepsForLspName_skipsWhenLspBlank() throws Exception {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            invokeClearOrphans(driver, "", "dx6p1vf6");
            invokeClearOrphans(driver, null, "dx6p1vf6");
            invokeClearOrphans(driver, "   ", "dx6p1vf6");

            // No Script calls expected for blank lspName.
            scriptMock.verify(() -> Script.runSimpleBashScript(anyString()), never());
        }
    }

    /**
     * When the OVS find query returns empty output (no orphan exists) no
     * del-port command must be issued.
     */
    @Test
    public void clearOrphanRepsForLspName_skipsWhenNoMatch() throws Exception {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface")))
                    .thenReturn("");

            invokeClearOrphans(driver, "lsp-c44b8d7e", "dx6p0vf2");

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
        }
    }

    // -----------------------------------------------------------------------
    // cleanupStaleRepsByLspName tests (public API, keepRepName=null)
    // -----------------------------------------------------------------------

    /**
     * When keepRepName is null (the public cleanup API path), all reps
     * carrying the target iface-id must be removed, including the one that
     * would have been the "keep" target.
     */
    @Test
    public void cleanupStaleRepsByLspName_clearsAll() throws Exception {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface")))
                    .thenReturn("dx6p1vf6\ndx6p0vf4");

            scriptMock.when(() -> Script.runSimpleBashScript(contains("del-port")))
                    .thenReturn("");

            driver.cleanupStaleRepsByLspName("lsp-b3f77bd2");

            // Both reps must have been removed — keepRepName=null means keep nothing.
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), times(2));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("dx6p1vf6")), times(1));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("dx6p0vf4")), times(1));
        }
    }

    // -----------------------------------------------------------------------
    // attachRepresentorToBrInt ordering test
    // -----------------------------------------------------------------------

    /**
     * attachRepresentorToBrInt must invoke clearOrphanRepsForLspName
     * (the find query) BEFORE the add-port stamp. The ordering guarantee is
     * verified by capturing Script calls in sequence via Mockito InOrder
     * applied over the collected invocation log.
     *
     * <p>This test reaches the private 4-arg overload reflectively because
     * the 3-arg public-facing plug() has too many external dependencies to
     * drive in isolation.
     */
    @Test
    public void attachRepresentorToBrInt_callsClearOrphansBeforeAddPort() throws Exception {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();
        final List<String> callLog = new ArrayList<>();

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // Record every Script invocation in call order.
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenAnswer(inv -> {
                        callLog.add((String) inv.getArgument(0));
                        return "";
                    });

            // Override find to return no orphans so no del-port side effects.
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface")))
                    .thenAnswer(inv -> {
                        callLog.add((String) inv.getArgument(0));
                        return "";
                    });

            // Invoke the private 4-arg overload.
            final Method attach = OvnVfPassthroughVifDriver.class.getDeclaredMethod(
                    "attachRepresentorToBrInt", String.class, String.class, String.class, Boolean.class);
            attach.setAccessible(true);
            attach.invoke(driver, "dx6p0vf3", "lsp-42ee9124", "fa:16:3e:00:01:02", Boolean.TRUE);

            // The find (orphan check) must appear before add-port in the log.
            int findIndex = -1;
            int addPortIndex = -1;
            for (int i = 0; i < callLog.size(); i++) {
                final String cmd = callLog.get(i);
                if (cmd.contains("find Interface") && findIndex == -1) {
                    findIndex = i;
                }
                if (cmd.contains("add-port") && addPortIndex == -1) {
                    addPortIndex = i;
                }
            }
            org.junit.Assert.assertTrue(
                    "find Interface must precede add-port; callLog=" + callLog,
                    findIndex >= 0 && addPortIndex >= 0 && findIndex < addPortIndex);
        }
    }

    // -----------------------------------------------------------------------
    // unplug() attached-mac fallback tests (FIX D — orphan rep after unplug)
    // -----------------------------------------------------------------------

    /**
     * libvirt zeroes the VF MAC during managed hostdev detach / domain
     * destroy BEFORE unplug() runs, so the VF-PCI reverse lookup by guest MAC
     * comes back null in this environment (no real sysfs PF/VF topology).
     * unplug() must then fall back to the OVSDB {@code attached-mac} lookup
     * and remove every representor it finds from the integration bridge.
     */
    @Test
    public void unplug_failedPciLookup_attachedMacHit_removesOrphanRep() {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defHostdevNet(null, "fa:16:3e:00:01:02", 0);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface external_ids:attached-mac")))
                    .thenReturn("dx6p0vf4");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("del-port")))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), times(1));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("dx6p0vf4")), times(1));
        }
    }

    /**
     * When the attached-mac fallback finds no matching representor either
     * (no orphan exists), unplug() must be a clean no-op: no del-port call
     * is issued.
     */
    @Test
    public void unplug_failedPciLookup_noAttachedMacHit_isNoOp() {
        final OvnVfPassthroughVifDriver driver = new OvnVfPassthroughVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defHostdevNet(null, "fa:16:3e:00:01:03", 0);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface external_ids:attached-mac")))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
        }
    }
}
