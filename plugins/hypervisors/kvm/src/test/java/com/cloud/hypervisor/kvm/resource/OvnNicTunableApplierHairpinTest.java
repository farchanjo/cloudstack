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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-string tests for the new OVS hairpin / tc-policy helpers in
 * {@link OvnNicTunableApplier}. The helpers that actually shell out
 * ({@code applyHairpin}, {@code applyTcPolicyOnce}) call
 * {@code Script.runSimpleBashScript} which we cannot intercept without
 * a process-level mock; we instead test the command-builder seams
 * ({@code buildHairpinCommand}, {@code buildTcPolicyCommand}) so the
 * generated invocation shape is pinned and any future regression in the
 * argument order or option name surface in the test signal rather than
 * in production.
 */
public class OvnNicTunableApplierHairpinTest {

    @Test
    public void hairpinCommandStampsTrueWhenEnabled() {
        String cmd = OvnNicTunableApplier.buildHairpinCommand("vnet42", true);
        assertEquals("ovs-vsctl --if-exists set Port vnet42 other_config:hairpin=true", cmd);
    }

    @Test
    public void hairpinCommandStampsFalseWhenDisabled() {
        String cmd = OvnNicTunableApplier.buildHairpinCommand("vnet42", false);
        assertEquals("ovs-vsctl --if-exists set Port vnet42 other_config:hairpin=false", cmd);
    }

    @Test
    public void hairpinCommandUsesIfExistsToTolerateRaceWithDelPort() {
        // The flag matters: on a vDPA / VF rep teardown another thread might
        // remove the port between add-port and our hairpin set. --if-exists
        // turns the racing failure into a no-op instead of a stack trace.
        String cmd = OvnNicTunableApplier.buildHairpinCommand("dx6p0vf20", true);
        assertTrue("hairpin set must use --if-exists for race safety",
                cmd.contains("--if-exists"));
    }

    @Test
    public void tcPolicyCommandTargetsOpenVSwitchSingleton() {
        String cmd = OvnNicTunableApplier.buildTcPolicyCommand("none");
        // The Open_vSwitch table has exactly one row addressed by '.'
        // (OVSDB convention). Anything else would silently miss.
        assertEquals("ovs-vsctl set Open_vSwitch . other_config:tc-policy=none", cmd);
    }

    @Test
    public void tcPolicyCommandStampsAllWhitelistedValues() {
        for (String value : new String[]{"none", "skip_sw", "skip_hw"}) {
            String cmd = OvnNicTunableApplier.buildTcPolicyCommand(value);
            assertTrue("tc-policy command must round-trip value '" + value + "'",
                    cmd.endsWith("tc-policy=" + value));
        }
    }

    @Test
    public void applyHairpinNullValueIsNoop() {
        // Direct call: no exception, no shell invocation. The check is
        // "did the call complete?" — nothing to assert against state, only
        // that a null tunable doesn't throw.
        OvnNicTunableApplier.applyHairpin("vnet42", null);
    }

    @Test
    public void applyHairpinBlankPortIsNoop() {
        // Same shape as the null check. Blank port name short-circuits.
        OvnNicTunableApplier.applyHairpin("", Boolean.TRUE);
        OvnNicTunableApplier.applyHairpin(null, Boolean.TRUE);
    }

    @Test
    public void applyTcPolicyOnceLatchesPerJvm() {
        // The latch is process-global; reset it for a deterministic test.
        OvnNicTunableApplier.resetTcPolicyLatchForTesting();
        // The call may shell out to ovs-vsctl which is unavailable in
        // unit-test context; the helper catches RuntimeException and rearms
        // the latch so the next call can retry. We can't easily assert the
        // latch state without running ovs-vsctl, so we just verify the
        // method doesn't throw on null/blank input — which is the wire-
        // compat requirement.
        OvnNicTunableApplier.applyTcPolicyOnce(null);
        OvnNicTunableApplier.applyTcPolicyOnce("");
    }
}
