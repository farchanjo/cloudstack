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

import com.cloud.utils.script.Script;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * OVS-DPDK + rte_flow backend for the HW offload pipeline.
 *
 * <p>Translates {@link IntentReconciler} primitives into {@code ovs-ofctl add-flow}
 * commands on userspace bridges ({@code datapath_type=netdev}). OVS-DPDK compiles
 * matching OF flows to {@code rte_flow} rules in mlx5 firmware.
 *
 * <p><b>Stage 1 (this commit): SKELETON.</b> All methods throw
 * {@link UnsupportedOperationException}; switching {@code hwoffload.programmer=of}
 * at runtime will fail loudly. The interface and wiring are in place so the
 * Stage 2 commit can fill in the OF translation per-method without touching
 * {@link IntentReconciler} or {@link LibvirtComputingResource}.
 *
 * <p>Pre-deploy requirements (Stage 3):
 * <ul>
 *   <li>{@code openvswitch-switch-dpdk} package + {@code dpdk-init=true}</li>
 *   <li>Bridges {@code br-bond} and {@code cloud0} recreated with
 *       {@code datapath_type=netdev}</li>
 *   <li>{@code pmd-cpu-mask} pinned to isolated cores (cluster default 24-29)</li>
 *   <li>VF representors exposed via DPDK port
 *       {@code dpdk-devargs="0000:01:00.0,representor=[0-31]"}</li>
 * </ul>
 */
public class OfFlowProgrammer implements RuleProgrammer {

    private static final Logger LOGGER = LogManager.getLogger(OfFlowProgrammer.class);

    /** Bridge that receives uplink + VF reps for VPC data plane. */
    public static final String BRIDGE = "br-bond";

    public OfFlowProgrammer() {
        LOGGER.info("OfFlowProgrammer initialised — Stage 1 skeleton (methods unimplemented).");
    }

    private static UnsupportedOperationException notYet(String method) {
        return new UnsupportedOperationException(
                "OfFlowProgrammer." + method + " not implemented yet (Stage 2). " +
                "Set hwoffload.programmer=tc to fall back to the kernel TC backend.");
    }

    @Override
    public void initRepresentor(String repName) {
        // OVS auto-manages the rep when it's added as a port to the bridge.
        // Verify the rep exists as an OVS port; log a warning if missing.
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("initRepresentor({}): not found as OVS port on {}; expecting VifDriver to add", repName, BRIDGE);
        } else {
            LOGGER.debug("initRepresentor({}): OVS port={} on {}", repName, port, BRIDGE);
        }
    }

    @Override
    public void installChain0Dispatch(String repName, int zone) {
        // Mirror of TC chain-0 dispatch: ct(table=1, zone=<z>, nat) for tcp/udp on in_port=<rep>.
        // Two flows (proto 6/17) installed at table=0; non-matching traffic falls to NORMAL.
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("installChain0Dispatch({}, zone={}): rep not found as OVS port, skip", repName, zone);
            return;
        }
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'table=0,priority=100,in_port=%d,ip,ip_proto=6 actions=ct(table=1,zone=%d,nat)'",
                BRIDGE, port, zone));
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'table=0,priority=100,in_port=%d,ip,ip_proto=17 actions=ct(table=1,zone=%d,nat)'",
                BRIDGE, port, zone));
    }

    @Override
    public void installEstablishedForward(String repName, String outRep, boolean replyOnly) {
        installEstablishedForward(repName, outRep, replyOnly, null);
    }

    @Override
    public void installEstablishedForward(String repName, String outRep, boolean replyOnly, Integer pushVlanId) {
        // Mirror of TC chain-1 prio 100 +trk+est[+rpl]: forward established traffic
        // to outRep; optional VLAN push for public-side reply path.
        int inPort = repToOfPort(repName);
        int outPort = repToOfPort(outRep);
        if (inPort <= 0 || outPort <= 0) {
            LOGGER.warn("installEstablishedForward({}->{}): port lookup failed (in={}, out={}), skip",
                    repName, outRep, inPort, outPort);
            return;
        }
        String ctState = replyOnly ? "+trk+est+rpl" : "+trk+est";
        StringBuilder actions = new StringBuilder();
        if (pushVlanId != null && pushVlanId > 0 && pushVlanId <= 4094) {
            actions.append(String.format("push_vlan:0x8100,mod_vlan_vid:%d,", pushVlanId));
        }
        actions.append(String.format("output:%d", outPort));
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'table=1,priority=100,in_port=%d,ip,ct_state=%s actions=%s'",
                BRIDGE, inPort, ctState, actions.toString()));
    }

    @Override
    public void installEstablishedForward(String repName, String outRep) {
        installEstablishedForward(repName, outRep, false, null);
    }

    @Override
    public void installCatchAllPass(String repName, int prio) {
        // TC "action pass" = let kernel handle. In OVS, action=NORMAL re-enters L2
        // forwarding (MAC learning + flood) which is the equivalent fallback path.
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("installCatchAllPass({}, prio={}): rep not found as OVS port, skip", repName, prio);
            return;
        }
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'table=1,priority=%d,in_port=%d,ip actions=NORMAL'",
                BRIDGE, prio, port));
    }

    @Override
    public void installNatRule(String repName, String outRep, int zone, TcRuleProgrammer.NatDirection dir,
                               String matchAddr, int matchPort, String translateAddr, String ipProto, int prio) {
        // Mirror of TC chain-1 +trk+new SNAT/DNAT: ct commit + nat + mirred outRep.
        int inPort = repToOfPort(repName);
        int outPort = repToOfPort(outRep);
        if (inPort <= 0 || outPort <= 0) {
            LOGGER.warn("installNatRule: port lookup failed (in={}, out={}), skip", inPort, outPort);
            return;
        }
        StringBuilder match = new StringBuilder();
        match.append(String.format("table=1,priority=%d,in_port=%d,ip,ct_state=+trk+new,ip_proto=%d",
                prio, inPort, "tcp".equalsIgnoreCase(ipProto) ? 6 : 17));
        if (matchAddr != null && !matchAddr.isEmpty()) {
            match.append(dir == TcRuleProgrammer.NatDirection.SNAT ? ",nw_src=" : ",nw_dst=").append(matchAddr);
        }
        if (matchPort > 0) {
            match.append(",tp_dst=").append(matchPort);
        }
        String natSpec = dir == TcRuleProgrammer.NatDirection.SNAT
                ? String.format("nat(src=%s)", translateAddr)
                : String.format("nat(dst=%s)", translateAddr);
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s '%s actions=ct(commit,zone=%d,%s),output:%d'",
                BRIDGE, match, zone, natSpec, outPort));
    }

    @Override
    public void installAclRule(String repName, String matchSrcIp, String matchDstIp,
                               int matchPort, String ipProto, TcRuleProgrammer.Action action, int prio) {
        // Stateless ACL: per-rep flow at priority=P, no ct_state. Action drop or NORMAL (pass).
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("installAclRule({}, prio={}): rep not on OVS, skip", repName, prio);
            return;
        }
        StringBuilder match = new StringBuilder();
        match.append(String.format("table=0,priority=%d,in_port=%d,ip,ip_proto=%d",
                prio, port, "tcp".equalsIgnoreCase(ipProto) ? 6 : 17));
        if (matchSrcIp != null && !matchSrcIp.isEmpty()) {
            match.append(",nw_src=").append(matchSrcIp);
        }
        if (matchDstIp != null && !matchDstIp.isEmpty()) {
            match.append(",nw_dst=").append(matchDstIp);
        }
        if (matchPort > 0) {
            match.append(",tp_dst=").append(matchPort);
        }
        String act = action == TcRuleProgrammer.Action.DROP ? "drop" : "NORMAL";
        runOvs(String.format("ovs-ofctl -O OpenFlow13 add-flow %s '%s actions=%s'", BRIDGE, match, act));
    }

    @Override
    public void installStatefulAclRule(String repName, int zone, String matchSrcIp, String matchDstIp,
                                       int matchPort, String ipProto, TcRuleProgrammer.Action action, int prio) {
        // Stateful ACL: chain-1 ct_state=+trk+new evaluation. ACCEPT commits ct entry
        // (so subsequent established traffic shortcuts via the +est forward rule).
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("installStatefulAclRule({}, prio={}): rep not on OVS, skip", repName, prio);
            return;
        }
        StringBuilder match = new StringBuilder();
        match.append(String.format("table=1,priority=%d,in_port=%d,ip,ct_state=+trk+new,ip_proto=%d",
                prio, port, "tcp".equalsIgnoreCase(ipProto) ? 6 : 17));
        if (matchSrcIp != null && !matchSrcIp.isEmpty()) {
            match.append(",nw_src=").append(matchSrcIp);
        }
        if (matchDstIp != null && !matchDstIp.isEmpty()) {
            match.append(",nw_dst=").append(matchDstIp);
        }
        if (matchPort > 0) {
            match.append(",tp_dst=").append(matchPort);
        }
        String actions = action == TcRuleProgrammer.Action.DROP
                ? "drop"
                : String.format("ct(commit,zone=%d),NORMAL", zone);
        runOvs(String.format("ovs-ofctl -O OpenFlow13 add-flow %s '%s actions=%s'", BRIDGE, match, actions));
    }

    @Override
    public void resetRepresentor(String repName) {
        // TC equivalent: 'tc qdisc del clsact' wipes all chains/filters at once.
        // OF equivalent: del-flows by in_port matches all tables.
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.debug("resetRepresentor({}): rep not on OVS, nothing to clear", repName);
            return;
        }
        runOvs(String.format("ovs-ofctl -O OpenFlow13 del-flows %s 'in_port=%d'", BRIDGE, port));
        LOGGER.debug("resetRepresentor({}): cleared all flows for ofport={}", repName, port);
    }

    @Override
    public void installIntraLanBypass(String repName, String tierCidr) {
        // Three pref slots ABOVE SNAT pref 50, allow VR-originated intra-tier
        // and DHCPOFFER/broadcast traffic to bypass NAT.
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("installIntraLanBypass({}): rep not on OVS, skip", repName);
            return;
        }
        if (tierCidr != null && !tierCidr.isBlank()) {
            runOvs(String.format(
                    "ovs-ofctl -O OpenFlow13 add-flow %s 'table=1,priority=40,in_port=%d,ip,nw_dst=%s actions=NORMAL'",
                    BRIDGE, port, tierCidr));
        }
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'table=1,priority=41,in_port=%d,udp,tp_src=67,tp_dst=68 actions=NORMAL'",
                BRIDGE, port));
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'table=1,priority=42,in_port=%d,ip,nw_dst=255.255.255.255 actions=NORMAL'",
                BRIDGE, port));
    }

    @Override
    public void clearChain1(String repName) {
        // Remove all flows on table=1 (chain-1) matching in_port=<rep>.
        int port = repToOfPort(repName);
        if (port <= 0) {
            LOGGER.warn("clearChain1({}): rep not found as OVS port on {}, skip", repName, BRIDGE);
            return;
        }
        runOvs(String.format("ovs-ofctl -O OpenFlow13 del-flows %s 'table=1,in_port=%d'", BRIDGE, port));
    }

    @Override
    public String snapshot(String repName) {
        // Mirror of TcRuleProgrammer.snapshot: return raw flow dump for diff.
        int port = repToOfPort(repName);
        if (port <= 0) {
            return "";
        }
        String out = runOvs(String.format("ovs-ofctl -O OpenFlow13 dump-flows %s 'in_port=%d'", BRIDGE, port));
        return out == null ? "" : out;
    }

    @Override
    public void installPfwInboundDnat(int blockId, int vlanId, String ipProto,
                                      String publicIp, int publicPort,
                                      int ctZone, String internalIp, int internalPort,
                                      String publicVfRep, int prio) {
        // Mirror of TC PFW inbound DNAT on shared ingress block.
        // OVS has no "block"; use bond1 ofport as the uplink ingress and tag the
        // flow with a cookie scoped to (PFW, blockId) so {@link #clearPfwBlock} can
        // remove every PFW flow installed against the same block in one shot.
        int uplinkPort = repToOfPort(UPLINK_PORT);
        int targetPort = repToOfPort(publicVfRep);
        if (uplinkPort <= 0 || targetPort <= 0) {
            LOGGER.warn("installPfwInboundDnat: uplink({})={} or publicVfRep({})={} not on OVS, skip",
                    UPLINK_PORT, uplinkPort, publicVfRep, targetPort);
            return;
        }
        long cookie = pfwCookieFor(blockId);
        int proto = "tcp".equalsIgnoreCase(ipProto) ? 6 : 17;
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'cookie=0x%x,table=0,priority=%d,in_port=%d,dl_vlan=%d,ip,ip_proto=%d,"
                        + "nw_dst=%s,tp_dst=%d,ct_state=-trk "
                        + "actions=strip_vlan,ct(commit,zone=%d,nat(dst=%s:%d)),output:%d'",
                BRIDGE, cookie, prio, uplinkPort, vlanId, proto, publicIp, publicPort,
                ctZone, internalIp, internalPort, targetPort));
    }

    @Override
    public void clearPfwBlock(int blockId) {
        // Delete every flow tagged with the PFW cookie for this block.
        long cookie = pfwCookieFor(blockId);
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 del-flows %s 'cookie=0x%x/-1'",
                BRIDGE, cookie));
    }

    @Override
    public void installStaticNatInboundDnat(int blockId, int vlanId,
                                            String publicIp, int ctZone,
                                            String internalIp,
                                            String publicVfRep, int prio) {
        // Mirror of TC StaticNat inbound DNAT: 1:1 IP, no L4 match, single flow.
        int uplinkPort = repToOfPort(UPLINK_PORT);
        int targetPort = repToOfPort(publicVfRep);
        if (uplinkPort <= 0 || targetPort <= 0) {
            LOGGER.warn("installStaticNatInboundDnat: uplink({})={} or publicVfRep({})={} not on OVS, skip",
                    UPLINK_PORT, uplinkPort, publicVfRep, targetPort);
            return;
        }
        long cookie = staticNatCookieFor(blockId);
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 add-flow %s 'cookie=0x%x,table=0,priority=%d,in_port=%d,dl_vlan=%d,ip,"
                        + "nw_dst=%s,ct_state=-trk "
                        + "actions=strip_vlan,ct(commit,zone=%d,nat(dst=%s)),output:%d'",
                BRIDGE, cookie, prio, uplinkPort, vlanId, publicIp,
                ctZone, internalIp, targetPort));
    }

    @Override
    public void clearStaticNatBlock(int blockId) {
        long cookie = staticNatCookieFor(blockId);
        runOvs(String.format(
                "ovs-ofctl -O OpenFlow13 del-flows %s 'cookie=0x%x/-1'",
                BRIDGE, cookie));
    }

    @Override
    public int resolveIngressBlock(String netdev) {
        // OVS-DPDK / OF backend does not use TC ingress blocks. Block-scoped
        // matching is replaced by per-port flow installation (in_port=<uplink>).
        // Returning -1 signals "no block" — TC-block-based callsites must be
        // adapted to use uplink port resolution instead in Stage 2c.
        LOGGER.debug("resolveIngressBlock({}): OF backend has no ingress_block concept, returning -1", netdev);
        return -1;
    }

    @Override
    public boolean isOffloaded(String repName) {
        // In OVS, a flow is offloaded if dpctl/dump-flows shows offloaded:yes for
        // megaflows hitting the rep's in_port. This matches TC semantics where
        // 'in_hw in_hw_count' indicates HW-installed filters.
        int port = repToOfPort(repName);
        if (port <= 0) {
            return false;
        }
        String out = runOvs(String.format(
                "ovs-appctl dpctl/dump-flows ovs-system 2>/dev/null | grep 'in_port(%d)' | head -10", port));
        return out != null && out.contains("offloaded:yes");
    }

    /** Uplink interface name used by the PFW/StaticNat OF flows. */
    public static final String UPLINK_PORT = "bond1";

    /** Cookie base for PFW DNAT flows: {@code 0x504657 ("PFW") << 24 | blockId}. */
    private static long pfwCookieFor(int blockId) {
        return 0x504657_00000000L | (((long) blockId) & 0xFFFFFFFFL);
    }

    /** Cookie base for StaticNat DNAT flows: {@code 0x534e54 ("SNT") << 24 | blockId}. */
    private static long staticNatCookieFor(int blockId) {
        return 0x534e54_00000000L | (((long) blockId) & 0xFFFFFFFFL);
    }

    /** Resolve an OVS interface name to its OpenFlow port number on {@link #BRIDGE}. */
    private static int repToOfPort(String repName) {
        if (repName == null || repName.isBlank()) {
            return -1;
        }
        String out = Script.runSimpleBashScript(
                String.format("ovs-vsctl --if-exists get Interface %s ofport 2>/dev/null", repName));
        if (out == null || out.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(out.trim());
        } catch (NumberFormatException e) {
            LOGGER.debug("repToOfPort({}): unparseable ofport '{}'", repName, out.trim());
            return -1;
        }
    }

    /** Run an ovs-ofctl/ovs-appctl command, log on failure, return stdout. */
    private static String runOvs(String cmd) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("OVS: {}", cmd);
        }
        String result = Script.runSimpleBashScript(cmd + " 2>&1");
        if (result != null && (result.contains("ovs-vsctl:") || result.toLowerCase().contains("error"))) {
            LOGGER.warn("OVS command failed: {} -> {}", cmd, result);
        }
        return result;
    }
}
