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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Agent-side periodic sweep over {@code vdpa dev show -j} output. Phase H.1.
 *
 * <p>On every tick (driven by the {@code vdpa.sf.reconcile.interval.seconds}
 * mgmt setting, default 60 s):
 * <ol>
 *   <li>Run {@code vdpa dev show -j} and parse the SF list.
 *   <li>Cross-reference each SF against the {@link IntentReconciler}'s live
 *       VR set <em>and</em> the host's libvirt domain XML (via the
 *       {@link DomainOwnerProbe} hook so tests can stub it).
 *   <li>If the SF has no live owner: add it to {@code pendingDeletion} for
 *       observability only. Destructive deletion requires an explicit PCI
 *       target from management and is never inferred from local-domain absence.
 *   <li>If a domain references an SF that is missing on the host: log an
 *       error (data-plane drift; admin alert path).
 * </ol>
 *
 * <p>Sweep history is appended to
 * {@code /var/lib/cloudstack-agent/vdpa-reconciler/log.json} (rotated weekly)
 * so a forensic post-mortem can answer "who deleted my SF?".
 *
 * <p>This class is intentionally side-effect-injectable for unit testing: every
 * external call (running {@code vdpa}, listing live VRs, listing domain XML,
 * persisting log entries) is wrapped in a small interface or method that
 * tests can override.
 */
public class VdpaPoolReconciler {

    private static final Logger LOGGER = LogManager.getLogger(VdpaPoolReconciler.class);

    /** Default grace period before deleting an unclaimed SF. */
    public static final long DEFAULT_GRACE_MILLIS = 5L * 60L * 1000L;

    /** Default location for the rotating sweep log. */
    public static final String DEFAULT_LOG_DIR = "/var/lib/cloudstack-agent/vdpa-reconciler";

    private final IntentReconciler intentReconciler;
    private final DomainOwnerProbe domainOwnerProbe;
    private final long graceMillis;
    private final Path logDir;

    /** SF name → first-seen-as-orphan instant (millis). */
    private final Map<String, Long> pendingDeletion = new LinkedHashMap<>();

    public VdpaPoolReconciler(IntentReconciler intentReconciler) {
        this(intentReconciler, new LibvirtDomainOwnerProbe(),
                DEFAULT_GRACE_MILLIS, Paths.get(DEFAULT_LOG_DIR));
    }

    public VdpaPoolReconciler(IntentReconciler intentReconciler,
            DomainOwnerProbe domainOwnerProbe, long graceMillis, Path logDir) {
        this.intentReconciler = intentReconciler;
        this.domainOwnerProbe = domainOwnerProbe;
        this.graceMillis = graceMillis;
        this.logDir = logDir;
    }

    /** Read-only snapshot of pending deletions. Used by tests for assertions. */
    public Map<String, Long> pendingDeletionSnapshot() {
        synchronized (pendingDeletion) {
            return new LinkedHashMap<>(pendingDeletion);
        }
    }

    /**
     * Run one reconciliation pass. Idempotent and safe to call from the
     * agent's scheduled executor.
     *
     * @return per-action counters wrapped in {@link SweepResult}.
     */
    public synchronized SweepResult sweep() {
        Map<String, VdpaSf> hostSfs = parseHostSfs(runVdpaDevShow());
        Set<String> liveOwners = collectLiveOwners();
        Set<String> domainOwners = domainOwnerProbe.collectDomainSfNames();

        long now = System.currentTimeMillis();
        int deleted = 0;
        int markedPending = 0;
        int driftAlerts = 0;
        int preserved = 0;

        for (VdpaSf sf : hostSfs.values()) {
            if (isOwned(sf, liveOwners, domainOwners)) {
                pendingDeletion.remove(sf.name);
                preserved++;
                continue;
            }
            Long firstSeen = pendingDeletion.get(sf.name);
            if (firstSeen == null) {
                pendingDeletion.put(sf.name, now);
                markedPending++;
                LOGGER.warn("VdpaPoolReconciler: SF {} (mgmtdev={} mac={}) has no live owner — preserving and rechecking in {} ms",
                        sf.name, sf.mgmtdevPci, sf.mac, graceMillis);
                continue;
            }
            if (now - firstSeen >= graceMillis) {
                driftAlerts++;
                appendLogEntry(now, "suspect", sf, "grace-expired-targeted-cleanup-required");
                LOGGER.error("VdpaPoolReconciler: SF {} remains unowned after {} ms; preserving it until explicit targeted cleanup",
                        sf.name, graceMillis);
            }
        }

        // Data-plane drift: a domain references an SF that is missing on the host.
        for (String domainSf : domainOwners) {
            if (domainSf == null || domainSf.isEmpty()) {
                continue;
            }
            if (!hostSfs.containsKey(domainSf)) {
                driftAlerts++;
                LOGGER.error("VdpaPoolReconciler: data-plane drift — domain references SF {} but host inventory is missing it",
                        domainSf);
                appendLogEntry(now, "drift", new VdpaSf(domainSf, null, null, null, null), "domain-references-missing-sf");
            }
        }

        // Drop pending entries that no longer correspond to any host SF (e.g.
        // operator removed them manually between sweeps).
        pendingDeletion.keySet().removeIf(name -> !hostSfs.containsKey(name));

        return new SweepResult(hostSfs.size(), preserved, markedPending, deleted, driftAlerts);
    }

    private boolean isOwned(VdpaSf sf, Set<String> liveOwners, Set<String> domainOwners) {
        if (sf.name == null) {
            return true; // anonymous entry — never delete
        }
        if (domainOwners.contains(sf.name)) {
            return true;
        }
        // The SF naming convention `vdpa-<vrId>` lets us probe IntentReconciler
        // ownership by suffix.
        for (String vrId : liveOwners) {
            if (sf.name.endsWith("-" + vrId) || sf.name.equals("vdpa-" + vrId)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> collectLiveOwners() {
        if (intentReconciler == null) {
            return Collections.emptySet();
        }
        try {
            return intentReconciler.currentVrIds();
        } catch (RuntimeException e) {
            LOGGER.warn("VdpaPoolReconciler: failed to read currentVrIds: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /** Run {@code vdpa dev show -j}. Overridable for tests. */
    protected String runVdpaDevShow() {
        return Script.runSimpleBashScript("vdpa dev show -j 2>/dev/null", 5000);
    }

    /**
     * Parse the JSON shape that iproute2 emits:
     * <pre>{"dev":{"vdpa-vmA2":{"mgmtdev":"pci/0000:01:00.3","mac":"...","max_vqs":33}}}</pre>
     * Returns map keyed by SF name. Tolerant of an empty / missing payload.
     */
    static Map<String, VdpaSf> parseHostSfs(String json) {
        Map<String, VdpaSf> out = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonObject dev = root.getAsJsonObject().getAsJsonObject("dev");
            if (dev == null) {
                return out;
            }
            for (String name : dev.keySet()) {
                JsonObject entry = dev.getAsJsonObject(name);
                if (entry == null) {
                    continue;
                }
                String mgmtdev = entry.has("mgmtdev") ? entry.get("mgmtdev").getAsString() : null;
                String mac = entry.has("mac") ? entry.get("mac").getAsString() : null;
                Integer maxVqs = entry.has("max_vqs") ? entry.get("max_vqs").getAsInt() : null;
                String mgmtdevPci = mgmtdev != null && mgmtdev.startsWith("pci/")
                        ? mgmtdev.substring("pci/".length()) : mgmtdev;
                out.put(name, new VdpaSf(name, mgmtdevPci, mac, maxVqs, null));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("VdpaPoolReconciler: failed to parse vdpa dev show -j output: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Append an entry to the rotating sweep log. Best-effort; failures are
     * logged but do not abort the sweep.
     */
    private void appendLogEntry(long nowMillis, String event, VdpaSf sf, String reason) {
        try {
            ensureLogDirExists();
            Path file = logDir.resolve("log.json");
            rotateIfNeeded(file);
            Map<String, Object> entry = new HashMap<>();
            entry.put("ts", Instant.ofEpochMilli(nowMillis).toString());
            entry.put("event", event);
            entry.put("sf", sf == null ? null : sf.name);
            entry.put("mgmtdev", sf == null ? null : sf.mgmtdevPci);
            entry.put("mac", sf == null ? null : sf.mac);
            entry.put("reason", reason);
            String line = new Gson().toJson(entry) + "\n";
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("VdpaPoolReconciler: failed to append log entry: {}", e.getMessage());
        }
    }

    private void ensureLogDirExists() throws IOException {
        if (!Files.isDirectory(logDir)) {
            Files.createDirectories(logDir);
        }
    }

    /**
     * Rotate the log file weekly. Opens fresh on Mondays; otherwise appends
     * to the existing file.
     */
    private void rotateIfNeeded(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
        if (ageMillis >= 7L * 24L * 60L * 60L * 1000L) {
            Path rotated = file.resolveSibling("log-" + Instant.now().toEpochMilli() + ".json");
            Files.move(file, rotated, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** Lightweight DTO representing one host-side SF. */
    public static final class VdpaSf {
        final String name;
        final String mgmtdevPci;
        final String mac;
        final Integer maxVqs;
        final String devicePath;

        public VdpaSf(String name, String mgmtdevPci, String mac, Integer maxVqs, String devicePath) {
            this.name = name;
            this.mgmtdevPci = mgmtdevPci;
            this.mac = mac;
            this.maxVqs = maxVqs;
            this.devicePath = devicePath;
        }

        public String getName() {
            return name;
        }

        public String getMgmtdevPci() {
            return mgmtdevPci;
        }

        public String getMac() {
            return mac;
        }

        public Integer getMaxVqs() {
            return maxVqs;
        }

        public String getDevicePath() {
            return devicePath;
        }
    }

    /**
     * Probe the live libvirt domains for SF references. Default
     * implementation parses every {@code <interface type='vdpa'>} entry's
     * source dev path. Tests inject a stub.
     */
    public interface DomainOwnerProbe {
        Set<String> collectDomainSfNames();
    }

    /**
     * Default probe: walk {@code virsh list --name --state-running} domains,
     * dump XML, extract SF names. Falls back to an empty set on any error.
     */
    public static class LibvirtDomainOwnerProbe implements DomainOwnerProbe {
        @Override
        public Set<String> collectDomainSfNames() {
            Set<String> out = new HashSet<>();
            String list = Script.runSimpleBashScript("virsh list --name --state-running 2>/dev/null", 5000);
            if (list == null || list.isEmpty()) {
                return out;
            }
            for (String dom : list.split("\\s+")) {
                if (dom == null || dom.isEmpty()) {
                    continue;
                }
                String xml = Script.runSimpleBashScript(
                        String.format("virsh dumpxml %s 2>/dev/null", dom), 5000);
                if (xml == null) {
                    continue;
                }
                int idx = 0;
                while (true) {
                    int start = xml.indexOf("/dev/vhost-vdpa", idx);
                    if (start < 0) {
                        break;
                    }
                    int end = start;
                    while (end < xml.length()
                            && (Character.isLetterOrDigit(xml.charAt(end)) || xml.charAt(end) == '-' || xml.charAt(end) == '/')) {
                        end++;
                    }
                    String dev = xml.substring(start, end);
                    String name = sfNameFromVhostDev(dev);
                    if (name != null) {
                        out.add(name);
                    }
                    idx = end;
                }
            }
            return out;
        }

        /**
         * Derive the SF name from a {@code /dev/vhost-vdpaN} path by reading
         * the matching sysfs entry under {@code /sys/bus/vdpa/devices}. Returns
         * {@code null} when no SF is bound to the path.
         */
        static String sfNameFromVhostDev(String vhostDev) {
            String want = vhostDev.startsWith("/dev/") ? vhostDev.substring("/dev/".length()) : vhostDev;
            File base = new File("/sys/bus/vdpa/devices");
            String[] devs = base.list();
            if (devs == null) {
                return null;
            }
            for (String dev : devs) {
                String[] children = new File(base, dev).list();
                if (children == null) {
                    continue;
                }
                for (String child : children) {
                    if (child.equals(want)) {
                        return dev;
                    }
                }
            }
            return null;
        }
    }

    /** Counters returned per sweep call. */
    public static final class SweepResult {
        private final int totalSfs;
        private final int preserved;
        private final int markedPending;
        private final int deleted;
        private final int driftAlerts;

        public SweepResult(int totalSfs, int preserved, int markedPending, int deleted, int driftAlerts) {
            this.totalSfs = totalSfs;
            this.preserved = preserved;
            this.markedPending = markedPending;
            this.deleted = deleted;
            this.driftAlerts = driftAlerts;
        }

        public int getTotalSfs() {
            return totalSfs;
        }

        public int getPreserved() {
            return preserved;
        }

        public int getMarkedPending() {
            return markedPending;
        }

        public int getDeleted() {
            return deleted;
        }

        public int getDriftAlerts() {
            return driftAlerts;
        }

        @Override
        public String toString() {
            return String.format(
                    "VdpaPoolReconciler.SweepResult{total=%d preserved=%d markedPending=%d deleted=%d driftAlerts=%d}",
                    totalSfs, preserved, markedPending, deleted, driftAlerts);
        }
    }

    /** Test-only: bridge for collections that prefer immutable views. */
    @SuppressWarnings("unused")
    private static <T> Set<T> immutableCopy(Collection<T> in) {
        return Collections.unmodifiableSet(new HashSet<>(in));
    }
}
