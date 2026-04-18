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

    private final RepresentorMapper repMapper;
    private final TcRuleProgrammer programmer;
    private final Map<String, IntentSpec> currentByVr = new HashMap<>();

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

        // Public rep (Phase B/4 only): DNAT inbound + +est+rpl reply forward
        // back to guest-rep, so DNAT'd PFW packets flow through the e-switch
        // straight into the VR's guest-VF (eth1), bypassing iptables PREROUTING.
        if (publicRep != null) {
            applyToRep(publicRep, guestRep, zone, spec, false);
            LOGGER.info("Applied intent v{} for VR {} (guestRep={} guestOut={} publicRep={} zone={})",
                spec.version, spec.vrId, guestRep, guestOutDev, publicRep, zone);
        } else {
            LOGGER.info("Applied intent v{} for VR {} (guestRep={} guestOut={} zone={}, no publicRep)",
                spec.version, spec.vrId, guestRep, guestOutDev, zone);
        }

        currentByVr.put(spec.vrId, spec);
    }

    /**
     * Remove all TC rules for a VR — called when the VR is being destroyed or
     * fails over to BACKUP (BACKUP submits empty intent → reconciler invokes this).
     */
    public synchronized void removeIntent(String vrId) {
        IntentSpec prev = currentByVr.remove(vrId);
        if (prev == null) {
            return;
        }
        String guestRep = repMapper.getRepresentor(prev.guestVfPci);
        if (guestRep != null) {
            programmer.resetRepresentor(guestRep);
        }
        String publicRep = prev.publicVfPci != null ? repMapper.getRepresentor(prev.publicVfPci) : null;
        if (publicRep != null) {
            programmer.resetRepresentor(publicRep);
        }
        LOGGER.info("Removed intent for VR {} (cleared guestRep={} publicRep={})",
            vrId, guestRep, publicRep);
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
        programmer.installEstablishedForward(inRep, outRep, replyOnly);

        // NAT rules (chain 1, prio 10-99): only on the guest-side rep for SNAT,
        // public-side for DNAT. (Could be configured per-rule via direction matching.)
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
