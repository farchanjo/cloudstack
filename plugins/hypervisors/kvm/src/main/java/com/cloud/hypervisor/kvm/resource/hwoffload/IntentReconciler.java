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

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.AclRule;
import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.IntentSpec;
import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.NatRule;
import com.cloud.hypervisor.kvm.resource.hwoffload.TcRuleProgrammer.Action;
import com.cloud.hypervisor.kvm.resource.hwoffload.TcRuleProgrammer.NatDirection;

/**
 * Owns the state machine for HW offload intents. When a VR submits a fresh
 * IntentSpec, this class:
 *
 * <ol>
 *   <li>Resolves VF PCI → representor netdev via {@link RepresentorMapper}
 *   <li>Compares the new spec against the previously-applied spec for the same VR
 *   <li>If the version is newer (or no previous), applies a full reset+reinstall
 *       (atomic: clsact qdisc del+add wipes all rules, then we re-add the new set)
 *   <li>Records the new spec as "current" for {@code GET /state} queries and for
 *       the periodic GC sweep
 * </ol>
 *
 * <p>Strategy is "wipe and reinstall" rather than incremental diff, because:
 *   <ul>
 *     <li>per-VR rule count is small (typically &lt;100) — wipe is fast (&lt;100ms)
 *     <li>incremental diff is fragile when chain assignments change
 *     <li>reset is more resilient to drift (e.g. someone manually deleted a rule)
 *   </ul>
 *
 * <p>Periodic GC sweep ({@link #gcOrphans()}) runs every 60s and removes TC rules
 * on representors that have no current intent — guards against leaks when a VR
 * is undefined while the host agent was disconnected.
 */
public class IntentReconciler {

    private static final Logger LOGGER = LogManager.getLogger(IntentReconciler.class);

    /** Where SNAT'd traffic gets redirected on egress. */
    public enum UplinkKind {
        /** Auto-resolve PF parent of the guest VF from sysfs (no LAG). */
        AUTO,
        /** Physical Function netdev (e.g. dx6p0). */
        PF,
        /** Virtual Function netdev (e.g. dx6p0vf3). */
        VF,
        /** Sub-Function netdev (e.g. en3f0pf0sf42). */
        SF
    }

    /**
     * Disk-backed state for the per-VR IntentSpec map. Without persistence the
     * map is empty after every agent restart; subsequent SetNetworkACLCommand
     * etc. find currentIntent==null and silently skip HW programming until the
     * next SetSourceNatCommand re-establishes the spec. With persistence the
     * agent rehydrates currentByVr from disk on startup, so HW programming
     * stays consistent with TC rules already installed on representors.
     */
    private static final java.nio.file.Path STATE_DIR =
            java.nio.file.Paths.get("/var/lib/cloudstack-agent/hwoffload");
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private final RepresentorMapper repMapper;
    private final TcRuleProgrammer programmer;
    private final Map<String, IntentSpec> currentByVr = new HashMap<>();

    /**
     * Cached ingress block id of the host uplink (e.g. 37 for bond1 on aragog).
     * Populated lazily on first PFW apply; reused for both add and remove.
     * -1 means "unknown / no block"; 0+ is a valid block id.
     */
    private int cachedUplinkBlockId = -1;

    private final UplinkKind uplinkKind;
    private final boolean uplinkLag;
    private final String uplinkNetdev;

    public IntentReconciler(RepresentorMapper repMapper, TcRuleProgrammer programmer) {
        this(repMapper, programmer, UplinkKind.AUTO, false, null);
    }

    public IntentReconciler(RepresentorMapper repMapper, TcRuleProgrammer programmer,
                            UplinkKind uplinkKind, boolean uplinkLag, String uplinkNetdev) {
        this.repMapper = repMapper;
        this.programmer = programmer;
        this.uplinkKind = uplinkKind != null ? uplinkKind : UplinkKind.AUTO;
        this.uplinkLag = uplinkLag;
        this.uplinkNetdev = (uplinkNetdev != null && !uplinkNetdev.isBlank()) ? uplinkNetdev.trim() : null;
        LOGGER.info("HwOffload uplink config: kind={} lag={} netdev={}",
                this.uplinkKind, this.uplinkLag, this.uplinkNetdev != null ? this.uplinkNetdev : "<auto>");
        loadPersistedSpecs();
    }

    /**
     * Load all per-VR IntentSpec JSON files from STATE_DIR into currentByVr.
     * Called once from the constructor (i.e. on agent startup). Best-effort:
     * malformed files are logged and skipped, the directory is created if
     * missing.
     */
    private synchronized void loadPersistedSpecs() {
        try {
            java.nio.file.Files.createDirectories(STATE_DIR);
        } catch (java.io.IOException e) {
            LOGGER.warn("Cannot create HwOffload state dir {}: {}", STATE_DIR, e.getMessage());
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(STATE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    String json = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                    IntentSpec spec = GSON.fromJson(json, IntentSpec.class);
                    if (spec != null && spec.vrId != null) {
                        currentByVr.put(spec.vrId, spec);
                        LOGGER.info("Rehydrated HwOffload IntentSpec for VR {} from {} (version={})",
                                spec.vrId, p.getFileName(), spec.version);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed HwOffload state file {}: {}", p.getFileName(), e.getMessage());
                }
            });
        } catch (java.io.IOException e) {
            LOGGER.warn("Cannot enumerate HwOffload state dir {}: {}", STATE_DIR, e.getMessage());
        }
    }

    /** Atomically write spec to STATE_DIR/&lt;vrId&gt;.json. Best-effort. */
    private void persistSpec(IntentSpec spec) {
        if (spec == null || spec.vrId == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(STATE_DIR);
            java.nio.file.Path target = STATE_DIR.resolve(sanitizeFilename(spec.vrId) + ".json");
            java.nio.file.Path tmp = STATE_DIR.resolve(sanitizeFilename(spec.vrId) + ".json.tmp");
            java.nio.file.Files.write(tmp, GSON.toJson(spec).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            LOGGER.warn("Failed to persist HwOffload state for VR {}: {}", spec.vrId, e.getMessage());
        }
    }

    /** Delete STATE_DIR/&lt;vrId&gt;.json. Idempotent. */
    private void deletePersistedSpec(String vrId) {
        if (vrId == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(STATE_DIR.resolve(sanitizeFilename(vrId) + ".json"));
        } catch (java.io.IOException e) {
            LOGGER.warn("Failed to delete HwOffload state file for VR {}: {}", vrId, e.getMessage());
        }
    }

    /** Strip path separators / dots so a VR id can't escape the state dir. */
    private static String sanitizeFilename(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public synchronized void applyIntent(IntentSpec spec) {
        if (spec == null || spec.vrId == null) {
            LOGGER.warn("applyIntent called with null spec or vrId");
            return;
        }
        IntentSpec previous = currentByVr.get(spec.vrId);
        if (previous != null && previous.version >= spec.version) {
            LOGGER.debug("Skipping stale intent for VR {} (got version {}, current {})",
                spec.vrId, spec.version, previous.version);
            return;
        }

        String guestRep = repMapper.getRepresentor(spec.guestVfPci);
        if (guestRep == null) {
            LOGGER.error("Cannot apply intent for VR {}: no rep for guest VF {}", spec.vrId, spec.guestVfPci);
            return;
        }

        // Phase B/4: VR can have its public NIC promoted to a VF (hostdev) too.
        // When that happens, we install a *full* HW pipeline:
        //   - guest-rep: SNAT outbound + +est forward to uplink (bond1/PF)
        //   - public-rep: DNAT inbound + +est+rpl forward back to guest-rep
        // When publicVfPci is null (legacy: VR's public NIC is a TAP), we fall
        // back to half-pipeline (guest-rep only); kernel iptables on the VR
        // handles all public-side processing.
        String publicRep = spec.publicVfPci != null ? repMapper.getRepresentor(spec.publicVfPci) : null;

        // Egress for the guest-side outbound flow (post-SNAT) → host uplink.
        // Uplink resolution honors agent config (kind/lag/netdev); falls back to
        // auto-discovery from the guest VF's PF parent in sysfs.
        String guestOutDev = resolveUplink(spec.guestVfPci);
        if (guestOutDev == null && publicRep != null) {
            // No PF uplink resolvable but we have a public rep — use it as the
            // egress for SNAT'd packets (HW path: guest-rep → public-rep → wire).
            guestOutDev = publicRep;
        }
        if (guestOutDev == null) {
            LOGGER.error("Cannot apply intent for VR {}: no uplink resolved for guest-side redirect", spec.vrId);
            return;
        }

        // Zone stability: reuse the previous spec's ctZone across re-applies of
        // the same VR (SetSourceNat → SetNetworkACL → SetPortForwarding all fire
        // applyIntent and a fresh nextZone() per call would orphan the existing
        // HW conntrack entries → established connections drop). Spec-supplied
        // ctZone wins, then previous spec's zone, then a fresh allocation.
        int zone;
        if (spec.ctZone != null) {
            zone = spec.ctZone;
        } else if (previous != null && previous.ctZone != null) {
            zone = previous.ctZone;
        } else {
            zone = TcRuleProgrammer.nextZone();
        }
        spec.ctZone = zone;  // pin so subsequent re-applies see it via currentByVr

        // Guest rep: outbound SNAT + +est forward to uplink (bond1/PF).
        applyToRep(guestRep, guestOutDev, zone, spec, true);

        // Multi-tier: mirror chain-0 ct + chain-1 bypass/SNAT to each additional guest VF rep.
        // Same SNAT rule (src_cidr = VPC supernet) applies to all tiers since they share /16.
        if (spec.additionalGuestVfPcis != null && !spec.additionalGuestVfPcis.isEmpty()) {
            for (String extraVfPci : spec.additionalGuestVfPcis) {
                String extraRep = repMapper.getRepresentor(extraVfPci);
                if (extraRep == null) {
                    LOGGER.warn("Multi-tier: no rep for additional guest VF {} — skipping TC mirror", extraVfPci);
                    continue;
                }
                String extraOut = resolveUplink(extraVfPci);
                if (extraOut == null) { extraOut = guestOutDev; }
                applyToRep(extraRep, extraOut, zone, spec, true);
                LOGGER.info("Multi-tier: applied TC mirror on extra guest rep {} (VF {})", extraRep, extraVfPci);
            }
        }

        // Public rep (Phase B/4 only): TC ingress on the public-rep matches
        // packets going FROM the VR's public VF OUT TO THE WIRE — that is
        // the *reply* path on the public side (e.g. SYN-ACK from VR back to
        // an external client after kernel-iptables PFW, or BGP/FRR replies).
        // outRep MUST be the uplink (bond1), not the guest-rep — the reply
        // goes to the wire, not back into the VR via guest-VF (which would
        // create a forwarding loop and break PFW SSH).
        if (publicRep != null) {
            applyToRep(publicRep, guestOutDev, zone, spec, false);
            LOGGER.info("Applied intent v{} for VR {} (guestRep={} guestOut={} publicRep={} zone={})",
                spec.version, spec.vrId, guestRep, guestOutDev, publicRep, zone);
        } else {
            LOGGER.info("Applied intent v{} for VR {} (guestRep={} guestOut={} zone={}, no publicRep)",
                spec.version, spec.vrId, guestRep, guestOutDev, zone);
        }

        // Phase B/2: apply PFW DNAT rules to the host uplink ingress block.
        applyPfwRules(spec, publicRep, guestOutDev, zone);

        // Phase B/3+: apply StaticNat inbound DNAT rules to the host uplink
        // ingress block. Mirrors PFW but without ip_proto/port match (1:1 for
        // all protocols). Separate pref window (20-29) so the two features
        // don't clobber each other and can coexist on the same block.
        applyStaticNatDnatRules(spec, publicRep, guestOutDev, zone);

        currentByVr.put(spec.vrId, spec);
        persistSpec(spec);
    }

    /**
     * Remove all TC rules for a VR — called when the VR is being destroyed or
     * fails over to BACKUP (BACKUP submits empty intent → reconciler invokes this).
     */
    public synchronized void removeIntent(String vrId) {
        IntentSpec prev = currentByVr.remove(vrId);
        deletePersistedSpec(vrId);
        if (prev == null) {
            return;
        }
        // Use clearChain1 (per-pref del) on each rep instead of resetRepresentor
        // (which does `tc qdisc del clsact` and fails silently when OVS owns
        // the clsact qdisc — leaving stale chain-1 filters behind). clearChain1
        // works regardless of OVS ownership because it deletes filters by
        // chain+pref, which don't conflict with OVS's own chain-0 redirects.
        String guestRep = repMapper.getRepresentor(prev.guestVfPci);
        // Multi-tier: also clean extra guest reps
        if (prev.additionalGuestVfPcis != null) {
            for (String extraPci : prev.additionalGuestVfPcis) {
                String extraRep = repMapper.getRepresentor(extraPci);
                if (extraRep != null) {
                    try { programmer.clearChain1(extraRep); } catch (Exception e) { LOGGER.warn("clearChain1 failed on {}: {}", extraRep, e.toString()); }
                }
            }
        }
        if (guestRep != null) {
            programmer.clearChain1(guestRep);
            // Also drop chain-0 dispatch rules (ct lookup) we installed —
            // OVS owns the qdisc but its chain-0 entries live at different prefs.
            // Best-effort delete by the specific prios we used (1, 2 for tcp/udp).
            for (int p : new int[]{1, 2}) {
                com.cloud.utils.script.Script.runSimpleBashScript(String.format(
                    "tc filter del dev %s ingress chain 0 pref %d 2>/dev/null || true", guestRep, p));
            }
        }
        String publicRep = prev.publicVfPci != null ? repMapper.getRepresentor(prev.publicVfPci) : null;
        if (publicRep != null) {
            programmer.clearChain1(publicRep);
            for (int p : new int[]{1, 2}) {
                com.cloud.utils.script.Script.runSimpleBashScript(String.format(
                    "tc filter del dev %s ingress chain 0 pref %d 2>/dev/null || true", publicRep, p));
            }
        }
        if (cachedUplinkBlockId >= 0 && prev.pfwRules != null && !prev.pfwRules.isEmpty()) {
            programmer.clearPfwBlock(cachedUplinkBlockId);
            LOGGER.info("Cleared PFW HW DNAT rules from block {} for removed VR {}", cachedUplinkBlockId, vrId);
        }
        if (cachedUplinkBlockId >= 0 && hasStaticNatDnat(prev)) {
            programmer.clearStaticNatBlock(cachedUplinkBlockId);
            LOGGER.info("Cleared StaticNat HW DNAT rules from block {} for removed VR {}", cachedUplinkBlockId, vrId);
        }
        // VR-destroy leak fix: strip VF representor ports from OVS br-bond.
        // Background: VfPassthroughVifDriver.unplug() does `ovs-vsctl --if-exists del-port`
        // for each hostdev iface libvirt reports at StopCommand time. But when the VR is
        // already stopped before expunge (e.g. advanceStop followed by ExpungeOperation
        // issued while domain is gone), getInterfaces() returns an empty list and the
        // driver-side cleanup is skipped. The reps stay on br-bond tagged with the old
        // VLAN of the destroyed tier, and later VRs landing on the same VFs inherit
        // stale tag state. Since removeIntent has authoritative knowledge of every VF
        // PCI this VR ever used (persisted in the intent JSON), mirror the del-port
        // here so cleanup is tied to VR lifecycle rather than libvirt domain state.
        detachRepresentorFromBridge(guestRep);
        if (prev.additionalGuestVfPcis != null) {
            for (String extraPci : prev.additionalGuestVfPcis) {
                detachRepresentorFromBridge(repMapper.getRepresentor(extraPci));
            }
        }
        detachRepresentorFromBridge(publicRep);
        LOGGER.info("Removed intent for VR {} (cleared guestRep={} publicRep={})",
            vrId, guestRep, publicRep);
    }

    /**
     * Remove a VF representor from OVS br-bond. Idempotent and best-effort:
     * if the rep isn't on the bridge (already removed by VifDriver.unplug or
     * never added), ovs-vsctl returns 0 and we move on.
     */
    private void detachRepresentorFromBridge(String repName) {
        if (repName == null || repName.isEmpty()) {
            return;
        }
        try {
            com.cloud.utils.script.Script.runSimpleBashScript(String.format(
                "ovs-vsctl --if-exists del-port br-bond %s", repName));
            LOGGER.debug("Detached VF representor {} from br-bond", repName);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to detach rep {} from br-bond: {}", repName, e.getMessage());
        }
    }

    public synchronized IntentSpec currentIntent(String vrId) {
        return currentByVr.get(vrId);
    }

    /**
     * Periodic sweep: any rep not associated with a current intent has its TC
     * rules cleared. Call from a scheduled executor every 30-60s.
     */
    public synchronized void gcOrphans() {
        // Build set of "owned" reps from current intents.
        java.util.Set<String> owned = new java.util.HashSet<>();
        for (IntentSpec spec : currentByVr.values()) {
            String g = repMapper.getRepresentor(spec.guestVfPci);
            String p = repMapper.getRepresentor(spec.publicVfPci);
            if (g != null) owned.add(g);
            if (p != null) owned.add(p);
        }
        // Walk all reps; if not owned and has rules, reset.
        java.io.File netDir = new java.io.File("/sys/class/net");
        String[] ifaces = netDir.list();
        if (ifaces == null) {
            return;
        }
        for (String iface : ifaces) {
            java.io.File ppn = new java.io.File("/sys/class/net/" + iface + "/phys_port_name");
            if (!ppn.isFile()) {
                continue;
            }
            try {
                String n = new String(java.nio.file.Files.readAllBytes(ppn.toPath())).trim();
                if (!n.matches("pf[01]vf\\d+")) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }
            if (owned.contains(iface)) {
                continue;
            }
            String snap = programmer.snapshot(iface);
            if (snap != null && !snap.trim().isEmpty()) {
                LOGGER.warn("GC: clearing orphan TC rules on {} (no current intent owns it)", iface);
                programmer.resetRepresentor(iface);
            }
        }
    }


    /**
     * Apply Phase B/2 PFW DNAT rules in HW. Inbound external→public IP
     * traffic on the uplink VLAN is matched on the shared ingress block,
     * then HW does pop_vlan + ct nat dst + mirred to the VR's public VF rep.
     *
     * <p>Idempotent: clears the PFW pref window (60-79) on the block before
     * re-installing every rule. Cheap because at most ~10 PFW rules per VR
     * and the block is per-host (shared across all VRs but pref windows are
     * sliced per-VR — currently all VRs share 60-79 because we only use this
     * for the single VR's PFW set per applyIntent invocation).
     */
    private void applyPfwRules(IntentSpec spec, String publicRep, String guestOutDev, int zone) {
        if (spec.pfwRules == null || spec.pfwRules.isEmpty()) {
            // Still clear the block in case we're going from "had rules" to "no rules"
            if (cachedUplinkBlockId >= 0) {
                programmer.clearPfwBlock(cachedUplinkBlockId);
            }
            return;
        }
        if (publicRep == null) {
            LOGGER.warn("PFW rules requested for VR {} but no publicRep — skipping HW DNAT (kernel iptables fallback)", spec.vrId);
            return;
        }
        if (spec.publicVlanId == null || spec.publicVlanId <= 0) {
            LOGGER.warn("PFW rules requested for VR {} but spec.publicVlanId is unset — skipping HW DNAT", spec.vrId);
            return;
        }
        // Resolve and cache block id from the uplink netdev (e.g. bond1).
        if (cachedUplinkBlockId < 0) {
            cachedUplinkBlockId = programmer.resolveIngressBlock(guestOutDev);
            if (cachedUplinkBlockId < 0) {
                LOGGER.warn("Cannot resolve ingress block on uplink {} — PFW HW DNAT disabled for VR {}",
                        guestOutDev, spec.vrId);
                return;
            }
            LOGGER.info("Resolved ingress block {} on uplink {} for PFW HW DNAT", cachedUplinkBlockId, guestOutDev);
        }
        programmer.clearPfwBlock(cachedUplinkBlockId);
        int autoPrio = 60;
        for (com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.PfwRule r : spec.pfwRules) {
            if (r == null || r.publicIp == null || r.publicPort == null
                    || r.internalIp == null || r.internalPort == null) {
                continue;
            }
            String proto = (r.ipProto != null && !r.ipProto.isBlank()) ? r.ipProto.toLowerCase() : "tcp";
            int prio = (r.prio != null && r.prio >= 60 && r.prio <= 79) ? r.prio : autoPrio++;
            programmer.installPfwInboundDnat(cachedUplinkBlockId, spec.publicVlanId, proto,
                    r.publicIp, r.publicPort, zone, r.internalIp, r.internalPort, publicRep, prio);
            LOGGER.info("Installed PFW HW DNAT for VR {}: {}:{} -> {}:{} ({}) on block {} pref {}",
                    spec.vrId, r.publicIp, r.publicPort, r.internalIp, r.internalPort,
                    proto, cachedUplinkBlockId, prio);
            if (autoPrio > 79) {
                LOGGER.warn("PFW pref window 60-79 exhausted for VR {}; remaining rules skipped", spec.vrId);
                break;
            }
        }
    }

    /**
     * Return {@code true} iff the spec carries at least one DNAT NatRule
     * (i.e. an inbound StaticNat entry that {@link #applyStaticNatDnatRules}
     * would have programmed into the block's 20-29 pref window).
     */
    private static boolean hasStaticNatDnat(IntentSpec spec) {
        if (spec == null || spec.natRules == null) {
            return false;
        }
        for (NatRule r : spec.natRules) {
            if (r != null && "DNAT".equalsIgnoreCase(r.dir)
                    && r.matchAddr != null && r.translateAddr != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Phase B/3+: apply StaticNat inbound DNAT rules in HW. Mirrors
     * {@link #applyPfwRules} but uses a distinct pref window (20-29) and
     * OMITS ip_proto/port (StaticNat is 1:1, all protocols).
     *
     * <p>Rule pattern (empirically offloads on mlx5 CX6-Dx, validated 2026-04-19):
     * <pre>
     *   tc filter add block &lt;N&gt; ingress pref 20+i protocol 802.1Q flower \
     *     vlan_id &lt;publicVlanId&gt; vlan_ethtype 0x0800 \
     *     dst_ip &lt;publicIp&gt; ct_state -trk \
     *     action vlan pop pipe \
     *     action ct commit zone &lt;Z&gt; nat dst addr &lt;vmIp&gt; pipe \
     *     action mirred egress redirect dev &lt;publicVfRep&gt;
     * </pre>
     *
     * <p>The DNAT entries are stored in the same {@code spec.natRules} list
     * as the existing SNAT entries but with {@code dir = "DNAT"} and
     * {@code ipProto = null/""} (distinguishes from PFW-style DNAT which
     * always has a proto+port and goes via {@code applyToRep} on the public
     * rep). StaticNat DNAT is rejected inside {@code applyToRep} because it
     * targets the BLOCK, not per-rep. Keeping it in {@code natRules} lets
     * the merge-by-key in the mgmt-side handler operate uniformly.
     */
    private void applyStaticNatDnatRules(IntentSpec spec, String publicRep, String guestOutDev, int zone) {
        if (!hasStaticNatDnat(spec)) {
            // Still clear the block in case we're going from "had rules" to "no rules".
            if (cachedUplinkBlockId >= 0) {
                programmer.clearStaticNatBlock(cachedUplinkBlockId);
            }
            return;
        }
        if (publicRep == null) {
            LOGGER.warn("StaticNat DNAT rules requested for VR {} but no publicRep — skipping HW DNAT (kernel iptables fallback)", spec.vrId);
            return;
        }
        if (spec.publicVlanId == null || spec.publicVlanId <= 0) {
            LOGGER.warn("StaticNat DNAT rules requested for VR {} but spec.publicVlanId is unset — skipping HW DNAT", spec.vrId);
            return;
        }
        if (cachedUplinkBlockId < 0) {
            cachedUplinkBlockId = programmer.resolveIngressBlock(guestOutDev);
            if (cachedUplinkBlockId < 0) {
                LOGGER.warn("Cannot resolve ingress block on uplink {} — StaticNat HW DNAT disabled for VR {}",
                        guestOutDev, spec.vrId);
                return;
            }
            LOGGER.info("Resolved ingress block {} on uplink {} for StaticNat HW DNAT", cachedUplinkBlockId, guestOutDev);
        }
        // Dedup by publicIp: multiple NatRule DNAT entries can land here (the
        // mgmt handler may emit per-protocol variants for future extensions).
        // A single HW rule per public IP is both necessary (mlx5 rejects
        // duplicates on the same match) and sufficient (StaticNat is 1:1).
        java.util.LinkedHashMap<String, NatRule> byPub = new java.util.LinkedHashMap<>();
        for (NatRule r : spec.natRules) {
            if (r == null || !"DNAT".equalsIgnoreCase(r.dir)
                    || r.matchAddr == null || r.translateAddr == null) {
                continue;
            }
            byPub.putIfAbsent(r.matchAddr, r);
        }
        programmer.clearStaticNatBlock(cachedUplinkBlockId);
        int autoPrio = 20;
        for (NatRule r : byPub.values()) {
            int prio = (r.prio != null && r.prio >= 20 && r.prio <= 29) ? r.prio : autoPrio++;
            programmer.installStaticNatInboundDnat(cachedUplinkBlockId, spec.publicVlanId,
                    r.matchAddr, zone, r.translateAddr, publicRep, prio);
            LOGGER.info("Installed StaticNat HW DNAT for VR {}: {} -> {} on block {} pref {}",
                    spec.vrId, r.matchAddr, r.translateAddr, cachedUplinkBlockId, prio);
            if (autoPrio > 29) {
                LOGGER.warn("StaticNat pref window 20-29 exhausted for VR {}; remaining rules skipped", spec.vrId);
                break;
            }
        }
    }

    /**
     * Resolve the PF uplink netdev for a VF PCI address.
     * VF at 0000:01:00.2 → parent PF at 0000:01:00.0 → netdev "dx6p0".
     */
    /**
     * Resolve the egress netdev for SNAT'd traffic, based on agent.properties:
     * <ul>
     *   <li>{@code hwoffload.uplink.netdev}: explicit netdev (any kind, with or without LAG).
     *       Validated against /sys/class/net/. Wins over auto-discovery.
     *   <li>{@code hwoffload.uplink.kind=auto} (default) or {@code kind=pf,lag=false}:
     *       walk the guest VF's physfn → pick the netdev under PF/net whose
     *       phys_port_name is "p0"/"p1" (the PF uplink, not a representor).
     *   <li>Any other (kind=pf,lag=true | kind=vf | kind=sf) without explicit netdev:
     *       error — the caller must specify the netdev (no safe default).
     * </ul>
     */
    private String resolveUplink(String vfPciAddress) {
        if (uplinkNetdev != null) {
            java.io.File net = new java.io.File("/sys/class/net/" + uplinkNetdev);
            if (!net.isDirectory()) {
                LOGGER.error("Configured uplink netdev '{}' does not exist under /sys/class/net", uplinkNetdev);
                return null;
            }
            return uplinkNetdev;
        }
        if (uplinkKind == UplinkKind.AUTO || (uplinkKind == UplinkKind.PF && !uplinkLag)) {
            return resolvePfUplink(vfPciAddress);
        }
        LOGGER.error("Uplink kind={} lag={} requires explicit hwoffload.uplink.netdev", uplinkKind, uplinkLag);
        return null;
    }

    private String resolvePfUplink(String vfPciAddress) {
        if (vfPciAddress == null) {
            return null;
        }
        try {
            java.io.File physfn = new java.io.File("/sys/bus/pci/devices/" + vfPciAddress + "/physfn");
            if (!physfn.exists()) {
                return null;
            }
            String pfPci = physfn.getCanonicalFile().getName();
            java.io.File netDir = new java.io.File("/sys/bus/pci/devices/" + pfPci + "/net");
            String[] names = netDir.list();
            if (names == null) {
                return null;
            }
            // In switchdev mode, /sys/bus/pci/devices/PF/net/ contains both the PF
            // uplink (phys_port_name = "p0"/"p1") and the VF representors
            // (phys_port_name = "pf0vf0", "pf0vf1", ...). We must pick the PF
            // uplink, not a representor — names[0] is non-deterministic.
            for (String name : names) {
                java.io.File ppn = new java.io.File("/sys/class/net/" + name + "/phys_port_name");
                if (!ppn.isFile()) {
                    return name;
                }
                String n = new String(java.nio.file.Files.readAllBytes(ppn.toPath())).trim();
                if (n.matches("p[01]")) {
                    LOGGER.debug("Resolved PF uplink for VF {}: {} -> {} (phys_port_name={})", vfPciAddress, pfPci, name, n);
                    return name;
                }
            }
            LOGGER.warn("No PF uplink netdev found under {}/net (entries: {})", pfPci, java.util.Arrays.toString(names));
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve PF uplink for VF {}: {}", vfPciAddress, e.getMessage());
        }
        return null;
    }

    private void applyToRep(String inRep, String outRep, int zone, IntentSpec spec, boolean isGuestSide) {
        // Try a full clsact reset (works on raw reps); fails silently if OVS
        // owns the qdisc, in which case clearChain1 below scrubs stale rules.
        programmer.initRepresentor(inRep);
        programmer.clearChain1(inRep);

        // Chain 0 dispatch: ct lookup for tcp+udp.
        programmer.installChain0Dispatch(inRep, zone);

        // Chain 1, prio 100: established → forward (the workhorse rule for sustained traffic).
        // BGP-safe: on the public-side rep we install +est+rpl (reply direction only)
        // so VR-originated flows (BGP TCP 179 to upstream, SSH outbound, conntrackd sync,
        // FRR ↔ peer keepalives) don't get wrongly redirected to the guest VF.
        // On the guest-side rep we install plain +est (tenant→external is the only
        // expected direction; VR's eth0 doesn't originate non-control traffic).
        boolean replyOnly = !isGuestSide;
        // On the public-side rep we MUST push the public VLAN tag before
        // mirreding to bond1 — the VR's Public VF outputs un-tagged packets
        // (it's hostdev VFIO), and bond1 is a trunk port. Without push_vlan,
        // the upstream switch (or peer) sees an untagged frame and drops it
        // (or floods on the wrong VLAN), breaking PFW reverse-path. On the
        // guest-side rep we don't push (guest tier VLAN often >4094, OVS
        // handles it via NORMAL switching once packet is back in the kernel).
        Integer pushVlanId = (!isGuestSide && spec.publicVlanId != null) ? spec.publicVlanId : null;
        programmer.installEstablishedForward(inRep, outRep, replyOnly, pushVlanId);

        // Intra-LAN bypass (chain 1 prio 40-42): MUST be installed BEFORE the
        // SNAT pref 50, otherwise VR-originated traffic destined to other VMs in
        // the SAME tier (e.g. dnsmasq DHCPOFFER, intra-tier ssh from VR) gets
        // wrongly SNATed and mirreded to bond1, never reaching the dst VM. Only
        // applies on the guest-side rep — public-side never has this issue
        // because the SNAT rule there is direction-asymmetric (public-rep is the
        // egress side of SNAT, not the ingress).
        if (isGuestSide) {
            String tierCidr = null;
            if (spec.natRules != null) {
                for (NatRule r : spec.natRules) {
                    if (r != null && "SNAT".equalsIgnoreCase(r.dir) && r.matchAddr != null && !r.matchAddr.isBlank()) {
                        tierCidr = r.matchAddr;
                        break;
                    }
                }
            }
            programmer.installIntraLanBypass(inRep, tierCidr);
        }

        // NAT rules (chain 1, prio 10-99): only on the guest-side rep for SNAT,
        // public-side for DNAT. (Could be configured per-rule via direction matching.)
        // Note: StaticNat inbound DNAT (dir=DNAT, ipProto empty/null) is a
        // 1:1 all-proto rule programmed in {@link #applyStaticNatDnatRules}
        // on the shared uplink block (20-29), NOT per-rep here. It's filtered
        // out explicitly below so the per-rep installNatRule (which requires
        // ip_proto+port) is never called with incomplete inputs.
        if (spec.natRules != null) {
            for (NatRule r : spec.natRules) {
                if (r == null || r.dir == null || r.translateAddr == null) {
                    continue;
                }
                NatDirection dir;
                try {
                    dir = NatDirection.valueOf(r.dir.toUpperCase());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Unknown NAT direction in spec: {}", r.dir);
                    continue;
                }
                boolean appliesHere = (dir == NatDirection.SNAT && isGuestSide)
                                   || (dir == NatDirection.DNAT && !isGuestSide);
                if (!appliesHere) {
                    continue;
                }
                // Skip StaticNat DNAT entries — they target the shared uplink
                // block (20-29), not the per-rep chain-1. Convention: StaticNat
                // DNAT rules have ipProto null/blank; PFW-style DNAT (if ever
                // routed through natRules instead of pfwRules) would carry
                // an explicit proto+port and land here.
                if (dir == NatDirection.DNAT && (r.ipProto == null || r.ipProto.isBlank())) {
                    continue;
                }
                int prio = r.prio != null ? r.prio : 50;
                int port = r.matchPort != null ? r.matchPort : 0;
                String proto = r.ipProto != null ? r.ipProto : "tcp";
                programmer.installNatRule(inRep, outRep, zone, dir,
                    r.matchAddr, port, r.translateAddr, proto, prio);
            }
        }

        // Catch-all chain 1 rule (prio 200): plain `action pass`. Required because
        // chain-1 fall-through drops the packet when no rule matches.
        //
        // Guest-side rationale: DNAT'd PFW traffic (src=external client, not tier
        // CIDR) doesn't match the tier-only SNAT rule (pref 50). Without this
        // catch-all, kernel-DNAT'd packets would be dropped before reaching the
        // wire. With `pass`, mlx5 HW lets the packet continue through OVS
        // forwarding which sends it via bond1 to the VM, preserving the original
        // client src IP so the VR's kernel ct can reverse-NAT replies.
        //
        // Public-side rationale (Phase B/4): VR-originated +new traffic on the
        // public VF (BGP TCP 179, FRR keepalives, conntrackd UDP, outbound SSH
        // for cmk push, etc.) doesn't match any chain-1 rule and would otherwise
        // be silently dropped on the rep ingress, breaking the control plane.
        // Empirical fix verified 2026-04-17: PFW SSH succeeds end-to-end with
        // this rule + tier-only SNAT (pref 50, src_ip=guestCidr).
        programmer.installCatchAllPass(inRep, 200);

        // ACL rules apply on whichever side they target. For simplicity, install on
        // the guest-side rep (most common for tenant-defined ACLs).
        if (isGuestSide && spec.aclRules != null) {
            for (AclRule a : spec.aclRules) {
                if (a == null || a.action == null) {
                    continue;
                }
                Action action;
                try {
                    action = Action.valueOf(a.action.toUpperCase());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Unknown ACL action in spec: {}", a.action);
                    continue;
                }
                int prio = a.prio != null ? a.prio : 80;
                int port = a.matchPort != null ? a.matchPort : 0;
                String proto = a.ipProto != null ? a.ipProto : "tcp";
                if (Boolean.TRUE.equals(a.stateful)) {
                    programmer.installStatefulAclRule(inRep, zone,
                        a.matchSrcIp, a.matchDstIp, port, proto, action, prio);
                } else {
                    programmer.installAclRule(inRep,
                        a.matchSrcIp, a.matchDstIp, port, proto, action, prio);
                }
            }
        }

        // LB rules (L4 select group) are deferred — needs OVS group programming
        // which is out of scope for the foundational reconciler. Logged for now.
        if (isGuestSide && spec.lbRules != null && !spec.lbRules.isEmpty()) {
            LOGGER.info("LB rules in spec (count={}) NOT yet applied — OVS select group integration TODO",
                spec.lbRules.size());
        }
    }
}
