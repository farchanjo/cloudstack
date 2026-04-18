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
        runTc(String.format(
            "tc filter add dev %s ingress chain 0 prio 1 protocol ip flower ip_proto tcp " +
            "action ct zone %d pipe action goto chain 1",
            repName, zone));
        runTc(String.format(
            "tc filter add dev %s ingress chain 0 prio 2 protocol ip flower ip_proto udp " +
            "action ct zone %d pipe action goto chain 1",
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
        String state = replyOnly ? "+trk+est+rpl" : "+trk+est";
        runTc(String.format(
            "tc filter add dev %s ingress chain 1 prio 100 protocol ip flower ct_state %s " +
            "action mirred egress redirect dev %s",
            repName, state, outRep));
    }

    /** Backwards-compatible: defaults to non-reply (matches all established). */
    public void installEstablishedForward(String repName, String outRep) {
        installEstablishedForward(repName, outRep, false);
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
        for (int prio : new int[]{50, 60, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 200}) {
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
