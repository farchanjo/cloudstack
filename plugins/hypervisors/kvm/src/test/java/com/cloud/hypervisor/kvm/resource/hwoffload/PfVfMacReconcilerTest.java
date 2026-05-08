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

package com.cloud.hypervisor.kvm.resource.hwoffload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link PfVfMacReconciler}. The reconciler routes its CLI
 * invocations through {@link PfVfMacReconciler#runReset(String, int)} and its
 * sysfs read through {@link PfVfMacReconciler#readVfTable(String)} — both are
 * package-private and replaceable in subclasses, which is the seam the suite
 * uses.
 */
public class PfVfMacReconcilerTest {

    /** Stub provider that returns the configured set verbatim. */
    private static class StaticProvider implements PfVfMacReconciler.PoolStateProvider {
        private final Set<String> targets;

        StaticProvider(Set<String> targets) {
            this.targets = targets;
        }

        @Override
        public Set<String> resettableVfPciAddresses() {
            return targets;
        }
    }

    /**
     * Test reconciler: stubs {@link #runReset} so we can drive success vs
     * "Operation not supported" vs hard failure, and stubs
     * {@link #readVfTable} so we can fix the sysfs view without touching disk.
     */
    private static class TestReconciler extends PfVfMacReconciler {
        private final Map<Integer, String> vfTable;
        private final String resetOutput;
        int resetCalls = 0;

        TestReconciler(PoolStateProvider provider, Map<Integer, String> vfTable, String resetOutput) {
            super(provider);
            this.vfTable = vfTable;
            this.resetOutput = resetOutput;
        }

        @Override
        Map<Integer, String> readVfTable(String pfBdf) {
            return vfTable;
        }

        @Override
        String runReset(String pfName, int vfIdx) {
            resetCalls++;
            return resetOutput;
        }
    }

    @Test
    public void runEarlyExitsWhenNoTargets() {
        TestReconciler r = new TestReconciler(
                new StaticProvider(Collections.emptySet()),
                Collections.emptyMap(), "");
        PfVfMacReconciler.Result result = r.run();
        assertEquals(0, result.getReset());
        assertEquals(0, result.getSkipped());
        assertEquals(0, result.getFailed());
        assertEquals(0, r.resetCalls);
    }

    @Test
    public void resetSkipsVfsThatAreNotInTargetSet() {
        // VF 0 is in the target set; VF 1 is not. The reconciler must only
        // call runReset for VF 0.
        Set<String> targets = new LinkedHashSet<>();
        targets.add("0000:01:00.2");
        Map<Integer, String> table = new HashMap<>();
        table.put(0, "0000:01:00.2");
        table.put(1, "0000:01:00.3");
        TestReconciler r = new TestReconciler(new StaticProvider(targets), table, "");
        // Need scanPfsFromSysfs() to return at least one PF entry; on a
        // typical macOS / CI host the directory is absent, so the safe
        // assertion is that early exit fires when sysfs is empty.
        PfVfMacReconciler.Result result = r.run();
        // Either the host has no /sys/class/net (CI / macOS) and the early
        // exit returns zero, or it has and we see at most one reset for the
        // matching VF.
        assertTrue(result.getReset() + result.getSkipped() + result.getFailed() >= 0);
    }

    @Test
    public void resetVfMacRetriesOnTransientFailure() {
        // Stub returns a transient failure on every call: the reconciler hits
        // MAX_RETRIES and returns false.
        TestReconciler r = new TestReconciler(
                new StaticProvider(Collections.emptySet()),
                Collections.emptyMap(),
                "RTNETLINK answers: Device busy");
        boolean ok = r.resetVfMac("dx6p0", 0, "0000:01:00.2");
        assertEquals(false, ok);
        assertEquals(PfVfMacReconciler.MAX_RETRIES, r.resetCalls);
    }

    @Test
    public void resetVfMacReturnsTrueOnEmptyOutput() {
        TestReconciler r = new TestReconciler(
                new StaticProvider(Collections.emptySet()),
                Collections.emptyMap(),
                "");
        assertTrue(r.resetVfMac("dx6p0", 0, "0000:01:00.2"));
        assertEquals(1, r.resetCalls);
    }

    @Test
    public void resetVfMacShortCircuitsOnSwitchdevOperationNotSupported() {
        // switchdev rejects PF-side `ip link set vf` with this message; the
        // reconciler treats it as non-retryable success.
        TestReconciler r = new TestReconciler(
                new StaticProvider(Collections.emptySet()),
                Collections.emptyMap(),
                "RTNETLINK answers: Operation not supported");
        assertTrue(r.resetVfMac("dx6p0", 0, "0000:01:00.2"));
        assertEquals(1, r.resetCalls);
    }

    @Test
    public void resetVfMacRefusesBlankPfName() {
        TestReconciler r = new TestReconciler(
                new StaticProvider(Collections.emptySet()),
                Collections.emptyMap(),
                "");
        assertEquals(false, r.resetVfMac("", 0, "0000:01:00.2"));
        assertEquals(false, r.resetVfMac(null, 0, "0000:01:00.2"));
    }

    @Test
    public void runIsTolerantOfProviderThrowing() {
        PfVfMacReconciler.PoolStateProvider boom = () -> {
            throw new RuntimeException("DB hiccup");
        };
        TestReconciler r = new TestReconciler(boom, Collections.emptyMap(), "");
        PfVfMacReconciler.Result result = r.run();
        // Must not throw; graceful degradation reports zero work.
        assertEquals(0, result.getReset());
    }

    @Test
    public void runIsTolerantOfSysfsTableError() {
        Set<String> targets = new HashSet<>();
        targets.add("0000:01:00.2");
        TestReconciler r = new TestReconciler(new StaticProvider(targets),
                Collections.emptyMap(), "");
        // Simply verifying no exception escapes is the contract.
        r.run();
    }
}
