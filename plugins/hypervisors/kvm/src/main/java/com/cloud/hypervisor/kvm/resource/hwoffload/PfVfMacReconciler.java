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

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver;
import com.cloud.utils.script.Script;

/**
 * One-shot agent-startup reconciler that resets the PF-pinned MAC + VLAN of
 * every VF currently in {@code FREE}, {@code SUSPECT} or {@code ORPHAN_MANUAL}
 * state. Phase H.1.
 *
 * <p>Why this exists: when a VR / user-VM is force-killed or the agent crashes
 * mid-plug, the PF-side VF entry can keep the previous MAC pinned. The next
 * VM that picks the same VF inherits a stale MAC (or, worse, a MAC owned by
 * another tenant). Resetting MACs on every FREE-equivalent VF at boot
 * guarantees a clean slate.
 *
 * <p>Best-effort: failure to reset a single VF logs at WARN and moves on.
 * Bounded retries (up to {@link #MAX_RETRIES}) per VF defend against
 * transient {@code Operation not supported} errors that surface immediately
 * after switchdev mode flips.
 */
public class PfVfMacReconciler {

    private static final Logger LOGGER = LogManager.getLogger(PfVfMacReconciler.class);

    /** Max attempts per VF before giving up and logging at ERROR. */
    public static final int MAX_RETRIES = 3;

    /** Retry back-off (ms) between attempts. */
    public static final long RETRY_BACKOFF_MILLIS = 250L;

    /** Provider for the agent's pool view. Tests inject a stub. */
    public interface PoolStateProvider {
        /**
         * Return the set of VF PCI BDFs that the mgmt server currently flags as
         * FREE / SUSPECT / ORPHAN_MANUAL — i.e. eligible for MAC reset.
         */
        Set<String> resettableVfPciAddresses();
    }

    private final PoolStateProvider poolStateProvider;

    public PfVfMacReconciler(PoolStateProvider poolStateProvider) {
        this.poolStateProvider = poolStateProvider;
    }

    /**
     * Run the one-shot reconcile. Walks every PF in sysfs (via
     * {@link VfPassthroughVifDriver#scanPfsFromSysfs()}), enumerates each PF's
     * VF table (via {@code ip link show <PF>}), and resets the MAC on every
     * VF whose PCI BDF is in {@link PoolStateProvider#resettableVfPciAddresses()}.
     *
     * @return per-action counters wrapped in {@link Result}.
     */
    public Result run() {
        Set<String> targets = safeTargets();
        if (targets.isEmpty()) {
            LOGGER.info("PfVfMacReconciler: no VFs eligible for MAC reset; skipping");
            return new Result(0, 0, 0);
        }
        Map<String, String> pfs = VfPassthroughVifDriver.scanPfsFromSysfs();
        if (pfs.isEmpty()) {
            LOGGER.info("PfVfMacReconciler: no PFs detected in sysfs; nothing to reset");
            return new Result(0, 0, 0);
        }
        int reset = 0;
        int skipped = 0;
        int failed = 0;

        for (Map.Entry<String, String> pfEntry : pfs.entrySet()) {
            String pfName = pfEntry.getKey();
            String pfBdf = pfEntry.getValue();
            Map<Integer, String> vfMap = readVfTable(pfBdf);
            for (Map.Entry<Integer, String> vfEntry : vfMap.entrySet()) {
                int vfIdx = vfEntry.getKey();
                String vfBdf = vfEntry.getValue();
                if (!targets.contains(vfBdf)) {
                    skipped++;
                    continue;
                }
                if (resetVfMac(pfName, vfIdx, vfBdf)) {
                    reset++;
                } else {
                    failed++;
                }
            }
        }
        LOGGER.info("PfVfMacReconciler: completed — reset={} skipped={} failed={}", reset, skipped, failed);
        return new Result(reset, skipped, failed);
    }

    private Set<String> safeTargets() {
        try {
            Set<String> raw = poolStateProvider.resettableVfPciAddresses();
            return raw == null ? new LinkedHashSet<>() : new LinkedHashSet<>(raw);
        } catch (RuntimeException e) {
            LOGGER.warn("PfVfMacReconciler: PoolStateProvider failed: {}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    /**
     * Run {@code ip link set <pf> vf <idx> mac 00:00:00:00:00:00 vlan 0} with
     * bounded retries. Returns true on success; logs at WARN and returns false
     * after {@link #MAX_RETRIES} consecutive failures.
     */
    boolean resetVfMac(String pfName, int vfIdx, String vfBdf) {
        if (StringUtils.isBlank(pfName)) {
            return false;
        }
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String out = runReset(pfName, vfIdx);
            if (out == null || out.isEmpty()) {
                LOGGER.debug("PfVfMacReconciler: reset OK pf={} vf={} ({})", pfName, vfIdx, vfBdf);
                return true;
            }
            if (out.contains("Operation not supported")) {
                LOGGER.debug("PfVfMacReconciler: pf={} vf={} ({}) refused MAC reset (switchdev / kernel rejection); skipping",
                        pfName, vfIdx, vfBdf);
                return true; // not retryable; counted as success
            }
            LOGGER.debug("PfVfMacReconciler: attempt {}/{} pf={} vf={} ({}) failed: {}",
                    attempt, MAX_RETRIES, pfName, vfIdx, vfBdf, out.trim());
            try {
                Thread.sleep(RETRY_BACKOFF_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        LOGGER.warn("PfVfMacReconciler: gave up resetting MAC on pf={} vf={} ({}) after {} attempts",
                pfName, vfIdx, vfBdf, MAX_RETRIES);
        return false;
    }

    /** Side-effect entrypoint extracted so unit tests can stub. */
    String runReset(String pfName, int vfIdx) {
        return Script.runSimpleBashScript(String.format(
                "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0 2>&1", pfName, vfIdx));
    }

    /**
     * Read a PF's VF table by reading {@code virtfnN} symlinks under
     * {@code /sys/bus/pci/devices/<pfBdf>/}. Returns a map keyed by VF index.
     * Tolerant of missing PF / partial sysfs.
     */
    Map<Integer, String> readVfTable(String pfBdf) {
        Map<Integer, String> out = new HashMap<>();
        if (StringUtils.isBlank(pfBdf)) {
            return out;
        }
        File pfDir = new File("/sys/bus/pci/devices/" + pfBdf);
        if (!pfDir.isDirectory()) {
            return out;
        }
        String[] entries = pfDir.list();
        if (entries == null) {
            return out;
        }
        for (String entry : entries) {
            if (!entry.startsWith("virtfn")) {
                continue;
            }
            try {
                int idx = Integer.parseInt(entry.substring("virtfn".length()));
                File vfLink = new File(pfDir, entry);
                String target = vfLink.getCanonicalPath();
                String name = target.substring(target.lastIndexOf('/') + 1);
                out.put(idx, name.startsWith("0000:") ? name : ("0000:" + name));
            } catch (Exception ignore) {
                // skip malformed entry
            }
        }
        return out;
    }

    /** Per-call counters. */
    public static final class Result {
        private final int reset;
        private final int skipped;
        private final int failed;

        public Result(int reset, int skipped, int failed) {
            this.reset = reset;
            this.skipped = skipped;
            this.failed = failed;
        }

        public int getReset() {
            return reset;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getFailed() {
            return failed;
        }

        @Override
        public String toString() {
            return String.format("PfVfMacReconciler.Result{reset=%d skipped=%d failed=%d}",
                    reset, skipped, failed);
        }
    }
}
