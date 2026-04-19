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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.script.Script;

/**
 * Translates {@link com.cloud.hypervisor.kvm.resource.hwoffload.intent} rule specs
 * into concrete {@code tc filter add} commands on switchdev representors.
 *
 * <p><b>Chain pattern (validated 2026-04-16 on aragog, memory project_a15_tc_offload_validation):</b>
 * <pre>
 *   chain 0 prio 1 ip ip_proto tcp action ct zone N pipe action goto chain 1
 *   chain 1 prio 1 ip ct_state +new+trk dst_port P action ct commit zone N nat src/dst addr A pipe action mirred egress redirect dev OUT
 *   chain 1 prio 2 ip ct_state +est+trk action mirred egress redirect dev OUT
 * </pre>
 * Single-rule {@code action ct commit nat + mirred} pattern does NOT offload (not_in_hw).
 *
 * <p>This class generates and executes the commands but is otherwise stateless. The
 * {@code IntentReconciler} owns lifecycle (when to add/remove rules) and the
 * {@code RepresentorMapper} maps VF PCI → representor netdev.
 */
public class TcRuleProgrammer {

    private static final Logger LOGGER = LogManager.getLogger(TcRuleProgrammer.class);
    private static final AtomicInteger ZONE_SEQ = new AtomicInteger(100);

    /** Action: drop, accept (no-op pipe), or jump-to-chain (rare). */
    public enum Action { DROP, ACCEPT, REDIRECT }

    /** Direction of NAT. SNAT rewrites src; DNAT rewrites dst. */
    public enum NatDirection { SNAT, DNAT }

    /**
     * Initialize the representor's TC subsystem: ensure clsact qdisc exists.
     * Idempotent.
     */
    public void initRepresentor(String repName) {
        // Best-effort delete any pre-existing clsact (no-op if absent), then add.
        Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null; tc qdisc add dev %s clsact",
                repName, repName));
        LOGGER.debug("Initialized clsact qdisc on {}", repName);
    }

    /**
     * Install the chain-0 dispatch rule on the representor: every TCP/UDP packet
     * goes through ct lookup then to chain 1 (where the actual NAT/forward decisions live).
     * Called once per representor when it's first used.
     */
    public void installChain0Dispatch(String repName, int zone) {
        // ct + nat in chain 0 dispatch:
        // - For INBOUND (DNAT'd by HW elsewhere, e.g. PFW HW DNAT in block 37):
        //   `nat` here is a no-op on the request — the packet already has dst translated.
        //   But the ct entry is created with NAT info, so reply traffic gets reverse-NAT.
        // - For OUTBOUND (SNAT in chain 1 prio 50): `nat` here applies reverse-NAT
        //   on reply traffic too, so server-to-client return packets restore the
        //   original tier IP→public IP rewrite.
        // Without `nat` in chain 0, ct lookup happens but no NAT info is attached
        // to the metadata, so chain-1 mirred sends the packet out un-NATed —
        // breaking reverse-path of all HW NAT (PFW DNAT and SNAT).
        runTc(String.format(
            "tc filter add dev %s ingress chain 0 prio 1 protocol ip flower ip_proto tcp " +
            "action ct zone %d nat pipe action goto chain 1",
            repName, zone));
        runTc(String.format(
            "tc filter add dev %s ingress chain 0 prio 2 protocol ip flower ip_proto udp " +
            "action ct zone %d nat pipe action goto chain 1",
            repName, zone));
    }

    /**
     * Install the established-flow forward rule on the representor (chain 1, prio 100).
     * Once a CT entry is established, subsequent packets match here and are forwarded
     * with NAT applied (free in HW).
     *
     * <p><b>BGP-safe design:</b> {@code replyOnly=true} adds the {@code +rpl}
     * (reply direction) flag so the rule only matches packets traveling in the
     * REVERSE direction of the original SYN. Used on the "return-path" rep (typically
     * public-rep for SNAT) so traffic ORIGINATED from the VR itself (BGP, SSH,
     * conntrackd, FRR keepalives) on the same rep does NOT get redirected back to
     * the guest-rep. BGP/SSH originated by the VR are {@code +new} → no match.
     *
     * <p>For the "outbound-path" rep (typically guest-rep for SNAT, where tenant
     * traffic enters), use {@code replyOnly=false} so all established tenant flows
     * are forwarded fast-path.
     */
    public void installEstablishedForward(String repName, String outRep, boolean replyOnly) {
        installEstablishedForward(repName, outRep, replyOnly, null);
    }

    /**
     * Install +est forward rule. When {@code pushVlanId} is non-null, the
     * outgoing packet is tagged with that VLAN before being mirreded to
     * {@code outRep} (typically bond1). REQUIRED on the public-side rep:
     * when reverse-NAT happens for a PFW DNAT reply, the packet leaves the
     * VR's Public VF un-tagged. mirred bond1 without push_vlan sends an
     * untagged frame onto a trunk port; the upstream switch (or peer
     * untag-aware code) drops or floods it on the wrong VLAN. With
     * push_vlan, the reply hits the wire in the correct VLAN and reaches
     * the original client cleanly.
     */
    public void installEstablishedForward(String repName, String outRep, boolean replyOnly, Integer pushVlanId) {
        String state = replyOnly ? "+trk+est+rpl" : "+trk+est";
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format(
            "tc filter add dev %s ingress chain 1 prio 100 protocol ip flower ct_state %s ",
            repName, state));
        if (pushVlanId != null && pushVlanId > 0 && pushVlanId <= 4094) {
            cmd.append(String.format(
                "action vlan push id %d protocol 802.1q pipe ",
                pushVlanId));
        }
        cmd.append(String.format("action mirred egress redirect dev %s", outRep));
        runTc(cmd.toString());
    }

    /** Backwards-compatible: defaults to non-reply (matches all established). */
    public void installEstablishedForward(String repName, String outRep) {
        installEstablishedForward(repName, outRep, false, null);
    }

    /**
     * Catch-all rule at the END of chain 1 that simply passes the packet
     * through (no NAT, no mirred). Required because chain-1 fall-through
     * (no matching filter) drops the packet, and we need DNAT'd PFW traffic
     * (which has src=external client and therefore doesn't match the tier-only
     * SNAT rule at pref 50) to flow back to the kernel's normal forwarding
     * path so the kernel iptables conntrack can reverse-NAT replies correctly.
     *
     * <p>Uses {@code flower} so the rule is HW-offloaded by mlx5. The
     * {@code action pass} is the cheapest possible HW operation — no NAT,
     * no commit, no mirred.
     */
    public void installCatchAllPass(String repName, int prio) {
        runTc(String.format(
            "tc filter add dev %s ingress chain 1 prio %d protocol ip flower action pass",
            repName, prio));
    }

    /**
     * Install a NAT rule on the representor (chain 1, +new+trk).
     * On first packet of a flow: commits a CT entry with NAT, then forwards.
     * Subsequent packets are caught by the established-forward rule (cheaper).
     *
     * @param repName       inbound rep (where the VR's "guest-facing" VF lives)
     * @param outRep        outbound rep (other side, e.g. "public-facing" VF)
     * @param zone          conntrack zone to commit into (must match chain-0 dispatch)
     * @param dir           SNAT or DNAT
     * @param matchAddr     packet match: src_ip (for SNAT) or dst_ip (for DNAT). Null = match all.
     * @param matchPort     L4 dst port to match (0 = any)
     * @param translateAddr the address to translate to (NAT target)
     * @param ipProto       "tcp" or "udp"
     * @param prio          rule priority within chain 1 (smaller = matched first; spec rules at prio 10-99, established at 100)
     */
    public void installNatRule(String repName, String outRep, int zone, NatDirection dir,
                               String matchAddr, int matchPort, String translateAddr, String ipProto, int prio) {
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format(
            "tc filter add dev %s ingress chain 1 prio %d protocol ip flower ct_state +trk+new ip_proto %s",
            repName, prio, ipProto));
        if (matchAddr != null && !matchAddr.isEmpty()) {
            cmd.append(dir == NatDirection.SNAT ? " src_ip " : " dst_ip ").append(matchAddr);
        }
        if (matchPort > 0) {
            cmd.append(" dst_port ").append(matchPort);
        }
        cmd.append(String.format(" action ct commit zone %d nat %s addr %s pipe action mirred egress redirect dev %s",
            zone,
            dir == NatDirection.SNAT ? "src" : "dst",
            translateAddr,
            outRep));
        runTc(cmd.toString());
    }

    /**
     * Install a stateless ACL rule (drop or accept). For stateful ACLs, use
     * {@link #installStatefulAclRule} which goes through the chain-1 ct-state pattern.
     */
    public void installAclRule(String repName, String matchSrcIp, String matchDstIp,
                               int matchPort, String ipProto, Action action, int prio) {
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format("tc filter add dev %s ingress prio %d protocol ip flower ip_proto %s",
            repName, prio, ipProto));
        if (matchSrcIp != null) {
            cmd.append(" src_ip ").append(matchSrcIp);
        }
        if (matchDstIp != null) {
            cmd.append(" dst_ip ").append(matchDstIp);
        }
        if (matchPort > 0) {
            cmd.append(" dst_port ").append(matchPort);
        }
        if (action == Action.DROP) {
            cmd.append(" action drop");
        } else {
            cmd.append(" action pass");
        }
        runTc(cmd.toString());
    }

    /**
     * Install a stateful ACL rule (only NEW connections evaluated; established
     * traffic is forwarded by the chain-1 +est rule installed once per rep).
     */
    public void installStatefulAclRule(String repName, int zone, String matchSrcIp, String matchDstIp,
                                       int matchPort, String ipProto, Action action, int prio) {
        StringBuilder cmd = new StringBuilder();
        cmd.append(String.format(
            "tc filter add dev %s ingress chain 1 prio %d protocol ip flower ct_state +trk+new ip_proto %s",
            repName, prio, ipProto));
        if (matchSrcIp != null) {
            cmd.append(" src_ip ").append(matchSrcIp);
        }
        if (matchDstIp != null) {
            cmd.append(" dst_ip ").append(matchDstIp);
        }
        if (matchPort > 0) {
            cmd.append(" dst_port ").append(matchPort);
        }
        if (action == Action.DROP) {
            cmd.append(" action drop");
        } else {
            cmd.append(String.format(" action ct commit zone %d pipe action pass", zone));
        }
        runTc(cmd.toString());
    }

    /** Remove every TC rule on this representor (used when the VR is being undefined). */
    public void resetRepresentor(String repName) {
        Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null", repName));
        LOGGER.debug("Reset clsact qdisc on {}", repName);
    }

    /**
     * Install intra-LAN bypass rules in chain 1 ABOVE the SNAT pref 50.
     * Without these, the SNAT rule (which matches src_ip=tier_cidr) erroneously
     * captures VR-originated traffic destined to other VMs in the SAME tier
     * (e.g. dnsmasq DHCPOFFER, internal DNS responses, intra-tier ssh from VR
     * to a tenant VM) and SNATs them to the public IP + mirreds to bond1 — the
     * intended dst VM never receives them.
     *
     * <p>Three pref slots:
     * <ul>
     *   <li>{@code pref 40}: {@code dst_ip <tier_cidr> action pass} — intra-tier unicast
     *   <li>{@code pref 41}: {@code udp src_port 67 dst_port 68 action pass} —
     *       DHCPOFFER from VR's dnsmasq (server→client; client may not yet have
     *       its assigned IP so dst_ip filter alone wouldn't match the broadcast form)
     *   <li>{@code pref 42}: {@code dst_ip 255.255.255.255 action pass} — generic broadcast
     * </ul>
     *
     * <p>All match BEFORE the SNAT pref 50, so SNAT is only applied to traffic
     * actually destined to external networks. Empirically validated 2026-04-18:
     * vm-fr received DHCP lease only after these rules were installed.
     */
    public void installIntraLanBypass(String repName, String tierCidr) {
        if (tierCidr != null && !tierCidr.isBlank()) {
            runTc(String.format(
                "tc filter add dev %s ingress chain 1 prio 40 protocol ip flower " +
                "dst_ip %s action pass", repName, tierCidr));
        }
        runTc(String.format(
            "tc filter add dev %s ingress chain 1 prio 41 protocol ip flower " +
            "ip_proto udp src_port 67 dst_port 68 action pass", repName));
        runTc(String.format(
            "tc filter add dev %s ingress chain 1 prio 42 protocol ip flower " +
            "dst_ip 255.255.255.255 action pass", repName));
    }

    /**
     * Delete all TC filters in chain 1 (the policy/NAT/ACL chain). Leaves chain 0
     * dispatch intact. Called by the reconciler at the start of {@code applyToRep}
     * so re-applying an intent reinstalls a clean rule set instead of accumulating
     * stale handles across applications. Does NOT touch the OVS-managed clsact
     * qdisc (which would fail with "Exclusivity flag on" anyway since OVS owns it).
     */
    public void clearChain1(String repName) {
        // `tc filter del dev X ingress chain 1` removes all filters in chain 1 in one shot
        // when supported; some iproute2 versions require per-pref deletion. Use both for
        // safety: bulk first, then per-pref for known ranges (50, 60, 80-99, 100, 200).
        Script.runSimpleBashScript(String.format(
            "tc filter del dev %s ingress chain 1 2>/dev/null || true", repName));
        for (int prio : new int[]{40, 41, 42, 50, 60, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 200}) {
            Script.runSimpleBashScript(String.format(
                "tc filter del dev %s ingress chain 1 pref %d 2>/dev/null || true", repName, prio));
        }
    }

    /**
     * Snapshot the current TC filters on a rep, returning the raw output. Used by
     * the reconciler to compute add/remove diffs.
     */
    public String snapshot(String repName) {
        return Script.runSimpleBashScript(String.format("tc filter show dev %s ingress", repName));
    }

    /**
     * Phase B/2: install one PFW DNAT rule on the shared ingress block.
     *
     * <p>The block (typically {@code 37}) covers the kernel bond device
     * ({@code bond1}) AND the underlying mlx5 PFs ({@code dx6p0/dx6p1}).
     * bond1 itself does NOT support {@code hw-tc-offload}, but the PFs do —
     * so a flower rule installed via the shared block lands in HW via the
     * PFs while still matching wire-side traffic that arrived through the
     * bond. Empirically validated 2026-04-18 with {@code in_hw in_hw_count 2}.
     *
     * <p>Match: VLAN-tagged inbound (the public VLAN, e.g. 2988) + L4 dst.
     * <p>Action: pop_vlan → ct commit nat dst → mirred to the VR's public VF rep.
     *
     * @param blockId      ingress block ID (resolved from the uplink, e.g. 37)
     * @param vlanId       VLAN tag of the public network (e.g. 2988)
     * @param ipProto      "tcp" / "udp"
     * @param publicIp     external-facing public IP (the wire dst)
     * @param publicPort   external-facing port (the wire dst port)
     * @param ctZone       conntrack zone (must match the VR's zone for reverse-NAT to work via VR kernel ct)
     * @param internalIp   tenant VM IP to translate to
     * @param internalPort tenant VM port to translate to
     * @param publicVfRep  representor of the VR's public VF (e.g. dx6p0vf0)
     * @param prio         pref window; recommended 60-79 (between source NAT 50 and ACL 80)
     */
    public void installPfwInboundDnat(int blockId, int vlanId, String ipProto,
                                      String publicIp, int publicPort,
                                      int ctZone, String internalIp, int internalPort,
                                      String publicVfRep, int prio) {
        String cmd = String.format(
            "tc filter add block %d ingress pref %d protocol 802.1Q flower " +
                "vlan_id %d vlan_ethtype 0x0800 ip_proto %s dst_ip %s dst_port %d ct_state -trk " +
                "action vlan pop pipe " +
                "action ct commit zone %d nat dst addr %s port %d pipe " +
                "action mirred egress redirect dev %s",
            blockId, prio, vlanId, ipProto, publicIp, publicPort,
            ctZone, internalIp, internalPort, publicVfRep);
        runTc(cmd);
    }

    /**
     * Idempotent: clear all PFW pref slots on the shared block before re-installing.
     * Pref window is 60-79 (20 slots — generous for the typical 1-5 PFW rules per VR).
     */
    public void clearPfwBlock(int blockId) {
        for (int prio = 60; prio <= 79; prio++) {
            Script.runSimpleBashScript(String.format(
                "tc filter del block %d ingress pref %d 2>/dev/null || true", blockId, prio));
        }
    }

    /**
     * Phase B/3+: install one Static NAT inbound DNAT rule on the shared
     * ingress block (mirrors {@link #installPfwInboundDnat} but WITHOUT
     * ip_proto / dst_port matching — StaticNat is 1:1 for ALL protocols
     * and ports on the public IP).
     *
     * <p>Empirically validated 2026-04-19 on aragog block 44:
     * {@code vlan_ethtype 0x0800} + {@code dst_ip} + {@code ct_state -trk}
     * (no ip_proto, no dst_port) → {@code in_hw in_hw_count 2} (bond1 + 2
     * mlx5 PFs). Single-rule per StaticNat entry; no need to split into
     * tcp/udp/icmp.
     *
     * <p>Pref window 20-29 (BELOW PFW 60-79 and source NAT 30-50 to satisfy
     * ordering: StaticNat is more specific than source NAT for the same
     * public IP, and any overlap with PFW on the same public IP must resolve
     * to PFW for defined (protocol, port) tuples).
     *
     * @param blockId     ingress block ID (resolved from the uplink, e.g. 44)
     * @param vlanId      VLAN tag of the public network (e.g. 2988)
     * @param publicIp    external-facing public IP (the wire dst) dedicated to one VM
     * @param ctZone      conntrack zone (shared with VR zone for reverse-NAT via kernel ct)
     * @param internalIp  tenant VM IP to translate the packet dst to
     * @param publicVfRep representor of the VR's public VF (e.g. dx6p0vf0)
     * @param prio        pref window; recommended 20-29 (below source NAT pref 30)
     */
    public void installStaticNatInboundDnat(int blockId, int vlanId,
                                            String publicIp, int ctZone,
                                            String internalIp,
                                            String publicVfRep, int prio) {
        String cmd = String.format(
            "tc filter add block %d ingress pref %d protocol 802.1Q flower " +
                "vlan_id %d vlan_ethtype 0x0800 dst_ip %s ct_state -trk " +
                "action vlan pop pipe " +
                "action ct commit zone %d nat dst addr %s pipe " +
                "action mirred egress redirect dev %s",
            blockId, prio, vlanId, publicIp,
            ctZone, internalIp, publicVfRep);
        runTc(cmd);
    }

    /**
     * Idempotent: clear all Static NAT pref slots on the shared block before
     * re-installing. Pref window 20-29 (10 slots — generous for the typical
     * 1-3 StaticNat per VR; legal range is 8 per-tenant VPC IP quota anyway).
     */
    public void clearStaticNatBlock(int blockId) {
        for (int prio = 20; prio <= 29; prio++) {
            Script.runSimpleBashScript(String.format(
                "tc filter del block %d ingress pref %d 2>/dev/null || true", blockId, prio));
        }
    }

    /**
     * Resolve the ingress block id of a given netdev (typically the uplink, e.g. bond1).
     * Returns {@code -1} if the netdev has no ingress qdisc with a block.
     *
     * <p>Parses output of {@code tc qdisc show dev <netdev>}:
     * <pre>
     *   qdisc ingress ffff: parent ffff:fff1 ingress_block 37 ----------------
     * </pre>
     */
    public int resolveIngressBlock(String netdev) {
        // Use JSON output (single-line) — Script.runSimpleBashScript truncates
        // multi-line tc output (likely a stdout buffering race when reading
        // from a non-TTY child process), so the second qdisc line was lost.
        // tc -j emits everything in one write.
        String out = Script.runSimpleBashScript(String.format("tc -j qdisc show dev %s", netdev));
        if (out == null || out.isBlank()) {
            LOGGER.warn("resolveIngressBlock: tc -j qdisc returned null/empty for dev {}", netdev);
            return -1;
        }
        // Look for {"...","ingress_block":<N>,"..."} via simple substring match.
        int idx = out.indexOf("\"ingress_block\":");
        if (idx < 0) {
            LOGGER.warn("resolveIngressBlock: no ingress_block in tc -j output for dev {}: {}", netdev, out);
            return -1;
        }
        String tail = out.substring(idx + "\"ingress_block\":".length());
        // Number is followed by `,` or `}`.
        int end = 0;
        while (end < tail.length() && (Character.isDigit(tail.charAt(end)) || tail.charAt(end) == '-')) {
            end++;
        }
        if (end == 0) {
            LOGGER.warn("resolveIngressBlock: failed to parse number for dev {}: {}", netdev, tail);
            return -1;
        }
        try {
            int b = Integer.parseInt(tail.substring(0, end));
            LOGGER.debug("resolveIngressBlock({}) = {}", netdev, b);
            return b;
        } catch (NumberFormatException e) {
            LOGGER.warn("resolveIngressBlock: parse failed for dev {}: {}", netdev, tail.substring(0, end));
            return -1;
        }
    }

    /** Allocate a fresh conntrack zone id for a new VR. Zones isolate flow state per-VR. */
    public static int nextZone() {
        return ZONE_SEQ.incrementAndGet();
    }

    /**
     * Verify a tc rule made it into HW by checking {@code in_hw} flag in
     * {@code tc filter show}. Returns true if at least one rule on the rep is in_hw.
     */
    public boolean isOffloaded(String repName) {
        String out = snapshot(repName);
        return out != null && out.contains("in_hw in_hw_count");
    }

    private void runTc(String cmd) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("TC: {}", cmd);
        }
        String result = Script.runSimpleBashScript(cmd + " 2>&1");
        if (result != null && (result.contains("Error") || result.contains("error"))) {
            LOGGER.warn("TC command failed: {} -> {}", cmd, result);
        }
    }

    // Builder helper for constructing batches of commands without immediate execution.
    public static List<String> emptyBatch() {
        return new ArrayList<>();
    }
}
