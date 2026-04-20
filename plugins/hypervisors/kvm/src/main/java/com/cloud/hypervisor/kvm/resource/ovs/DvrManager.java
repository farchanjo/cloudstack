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
package com.cloud.hypervisor.kvm.resource.ovs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.script.Script;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Distributed Virtual Router (DVR) — MVP, intra-host cross-tier only.
 *
 * <p><b>Goal</b>: eliminate the centralized VR kernel as a bottleneck for
 * <b>intra-host cross-tier L3 routing</b> within a VPC. When two tenant VMs
 * sit on the same hypervisor in different tiers of the same VPC, traffic
 * between them is forwarded directly by OVS (hit in the software FDB, same
 * host), skipping the VR round-trip entirely.
 *
 * <h2>Scope (MVP)</h2>
 * <ul>
 *   <li>IPv4 only.</li>
 *   <li>Single VPC per (vpcId) on the host; multi-VPC untested.</li>
 *   <li>Intra-host only — cross-host cross-tier still goes via the VR
 *       (standard CloudStack behavior). That is a useful, conservative
 *       subset: it proves the concept, eliminates load for the common
 *       "tenant tier-to-tier on the same host" pattern, and leaves the VR
 *       as a safe fallback for anything else.</li>
 *   <li>No per-rule ACL enforcement. Cross-tier traffic is permitted when
 *       both tiers are registered under the same VPC. Ingress ACL remains
 *       in the VR (kernel iptables).</li>
 *   <li>No conntrack sync — not needed since cross-tier routing is pure
 *       L3 (no NAT). SNAT to internet stays on the VR.</li>
 *   <li>No live-migration handoff — if a VM migrates, the destination
 *       host's {@code OvsVifDriver} will re-register it on plug; the
 *       source host's unplug hook removes its local flows. There is a
 *       brief transient during which only the VR path works (existing
 *       behavior pre-DVR), which is acceptable for an MVP.</li>
 * </ul>
 *
 * <h2>What the VR keeps doing</h2>
 * <ul>
 *   <li>Source NAT to the internet.</li>
 *   <li>Port forwarding / static NAT (already HW-offloaded — block 20).</li>
 *   <li>DHCP / DNS / metadata (dnsmasq, Apache).</li>
 *   <li>BGP (FRR).</li>
 *   <li>Ingress ACL (kernel iptables).</li>
 * </ul>
 *
 * <h2>How DVR bypasses the VR for intra-host cross-tier</h2>
 * <ol>
 *   <li><b>Virtual gateway MAC</b> — {@value #DVR_GATEWAY_MAC}. All tiers on
 *       every host answer ARP for their {@code .1} gateway IP with this
 *       synthetic MAC. Chosen to be globally unique and deterministic so we
 *       never collide with the VR's real eth2/eth3/eth4 MAC. The VR still
 *       answers ARP too; whichever reply arrives first wins on the VM.
 *       In practice, the DVR ARP responder is one OpenFlow hop away and
 *       consistently wins over the VR (which is several hops and a kernel
 *       traversal away).</li>
 *   <li><b>ARP responder flow per tier</b> — at {@code priority=500}, a
 *       classic OVS "NXM move+load+in_port" ARP-reply recipe. Fires when an
 *       ARP request for the tier gateway enters the bridge on the tier's
 *       access tag. Nothing is forwarded further; the reply is crafted
 *       in-place and sent back out the incoming port.</li>
 *   <li><b>L3 routing flow per (src_tier, dst_vm)</b> — at
 *       {@code priority=300}, matches an IP packet whose:
 *       <ul>
 *         <li>{@code dl_vlan} = source tier's folded tag,</li>
 *         <li>{@code dl_dst} = {@value #DVR_GATEWAY_MAC} (the VM sent it to
 *             its default gateway),</li>
 *         <li>{@code nw_dst} = the peer VM's IP,</li>
 *       </ul>
 *       and rewrites {@code dl_dst} to the peer VM's MAC, swaps the VLAN
 *       tag to the destination tier, then hands off to {@code NORMAL}.
 *       OVS's NORMAL path already knows which local port delivers that MAC
 *       on that tag (learned from the VM's own announcements on
 *       {@code vnetN}), so the packet exits directly to the other VM's tap.
 *       Total in-kernel path: 2 flow lookups + NORMAL. No VR.</li>
 * </ol>
 *
 * <h2>State model</h2>
 * <p>Persisted at {@code /var/lib/cloudstack-agent/dvr/state.json}. Keyed by
 * {@code vpcId} (opaque string supplied by the management server via a NIC
 * detail; falls back to the synthetic {@code "*"} bucket when absent, which
 * effectively groups all tiers on the host into one VPC — fine for the
 * single-VPC MVP).
 *
 * <pre>
 *   vpcId -> {
 *     tiers: {
 *        vni -> { cidr, gatewayIp, foldedTag, arpInstalled }
 *     },
 *     vms: {
 *        vmName -> { vni, ip, mac }
 *     }
 *   }
 * </pre>
 *
 * <p>Flows are keyed by a cookie family ({@value #DVR_COOKIE_ARP} for ARP
 * responders, {@value #DVR_COOKIE_ROUTE} for L3 routes) so a targeted
 * {@code del-flows cookie=...} cleanly wipes every DVR flow without
 * touching split-horizon, HW-offload or any other tenant flow.
 */
public class DvrManager {

    private static final Logger LOGGER = LogManager.getLogger(DvrManager.class);

    private static final String DEFAULT_BRIDGE = "br-bond";
    private static final int OVS_TIMEOUT_MS = 15_000;
    private static final String OF_PROTOCOL = "OpenFlow13";

    /**
     * Synthetic gateway MAC answered on every host for every tier's
     * {@code .1} IP. Chosen from the locally-administered 02: range and
     * carved to not collide with the VR's libvirt-assigned MACs.
     */
    public static final String DVR_GATEWAY_MAC = "02:dc:00:00:00:01";

    /** Cookie stamped on ARP responder flows. */
    static final String DVR_COOKIE_ARP = "0x0dc70ac7e";
    /** Cookie stamped on L3 routing flows. */
    static final String DVR_COOKIE_ROUTE = "0x0dc7e007e";
    /** Combined mask covering both cookies for bulk del-flows. */
    static final String DVR_COOKIE_MASK = "0x0dc7ffffe";

    private static final int PRIORITY_ARP_RESPONDER = 500;
    private static final int PRIORITY_L3_ROUTE = 300;

    private static final Path STATE_DIR = Paths.get("/var/lib/cloudstack-agent/dvr");
    private static final Path STATE_FILE = STATE_DIR.resolve("state.json");

    private final ObjectMapper objectMapper = buildObjectMapper();
    private final String bridgeName;

    /** vpcId -> VpcState. "*" bucket used when management doesn't tag. */
    private final Map<String, VpcState> vpcs = new TreeMap<>();

    /**
     * Build a manager reading the bridge name from agent properties. Safe
     * default when the props are null / missing.
     */
    public DvrManager(java.util.Properties agentProperties) {
        this.bridgeName = resolveBridge(agentProperties);
        loadState();
        LOGGER.info("DvrManager initialized: bridge={} vpcs={}", bridgeName, vpcs.keySet());
    }

    /**
     * Register a tier the first time a local VM plugs into it. Installs the
     * ARP responder at {@code priority=500} when the gateway IP is known.
     *
     * @param vpcId     opaque VPC identifier; null/blank folds to {@code "*"}.
     * @param vni       raw 24-bit VXLAN network identifier
     * @param cidr      tier CIDR, e.g. {@code 10.254.1.0/24}
     * @param gatewayIp tier gateway IP (the .1 of the CIDR), e.g. {@code 10.254.1.1}
     */
    public synchronized void registerTier(String vpcId, int vni, String cidr, String gatewayIp) {
        if (vni <= 0 || StringUtils.isBlank(cidr) || StringUtils.isBlank(gatewayIp)) {
            return;
        }
        String vKey = vpcKey(vpcId);
        VpcState vpc = vpcs.computeIfAbsent(vKey, k -> new VpcState());
        TierState tier = vpc.tiers.get(vni);
        int folded = toOvsAccessTag(vni);
        if (tier == null) {
            tier = new TierState();
            tier.cidr = cidr.trim();
            tier.gatewayIp = gatewayIp.trim();
            tier.foldedTag = folded;
            vpc.tiers.put(vni, tier);
            LOGGER.info("DVR registerTier: vpc={} vni={} tag={} cidr={} gw={}",
                    vKey, vni, folded, tier.cidr, tier.gatewayIp);
        } else {
            // Idempotent update — CIDR/gw shouldn't change but tolerate.
            tier.cidr = cidr.trim();
            tier.gatewayIp = gatewayIp.trim();
            tier.foldedTag = folded;
        }
        if (!tier.arpInstalled) {
            if (installArpResponder(folded, tier.gatewayIp)) {
                tier.arpInstalled = true;
            }
        }
        persistState();
    }

    /**
     * Register a local VM that just plugged into a tier. Re-programs the L3
     * routing flows across all (other) tiers known for the same VPC so that
     * any VM in a peer tier can route traffic to this new VM via OVS alone.
     *
     * @param vpcId   opaque VPC identifier
     * @param vmName  libvirt instance name (for state ownership)
     * @param vni     raw VXLAN VNI of the tier this VM is on
     * @param vmIp    VM IP address
     * @param vmMac   VM MAC address
     */
    public synchronized void registerVmInTier(String vpcId, String vmName, int vni, String vmIp, String vmMac) {
        if (vni <= 0 || StringUtils.isBlank(vmName) || StringUtils.isBlank(vmIp) || StringUtils.isBlank(vmMac)) {
            return;
        }
        String vKey = vpcKey(vpcId);
        VpcState vpc = vpcs.computeIfAbsent(vKey, k -> new VpcState());
        VmEntry entry = new VmEntry();
        entry.vni = vni;
        entry.ip = vmIp.trim();
        entry.mac = vmMac.trim().toLowerCase();
        vpc.vms.put(vmName.trim(), entry);

        // For every OTHER tier in this VPC, install an L3 route flow so a
        // packet coming from that tier with dst_ip=thisVm.ip gets rewritten
        // and dispatched on this tier's access tag.
        TierState thisTier = vpc.tiers.get(vni);
        if (thisTier == null) {
            LOGGER.warn("DVR registerVmInTier: tier vni={} not registered for vpc={}; skipping route flows",
                    vni, vKey);
            persistState();
            return;
        }
        int installed = 0;
        for (Map.Entry<Integer, TierState> te : vpc.tiers.entrySet()) {
            int srcVni = te.getKey();
            if (srcVni == vni) {
                continue;
            }
            int srcTag = te.getValue().foldedTag;
            if (installL3Route(srcTag, entry.ip, entry.mac, thisTier.foldedTag)) {
                installed++;
            }
        }
        persistState();
        LOGGER.info("DVR registerVmInTier: vpc={} vm={} vni={} ip={} mac={} routeFlowsInstalled={}",
                vKey, vmName, vni, entry.ip, entry.mac, installed);
    }

    /**
     * Unplug hook. Removes routes pointing to {@code vmName} and, if the
     * tier has no remaining local VMs, removes the ARP responder too.
     *
     * @param vmName libvirt instance name
     */
    public synchronized void unregisterVm(String vmName) {
        if (StringUtils.isBlank(vmName)) {
            return;
        }
        String key = vmName.trim();
        for (Map.Entry<String, VpcState> ve : vpcs.entrySet()) {
            VpcState vpc = ve.getValue();
            VmEntry e = vpc.vms.remove(key);
            if (e == null) {
                continue;
            }
            // Remove all L3 routes pointing to this VM, across every src tier.
            removeL3RoutesForDstIp(e.ip);
            // If no more local VMs are on tier e.vni, tear down the ARP
            // responder too — keeps the flow-table tidy.
            boolean stillHasLocal = false;
            for (VmEntry other : vpc.vms.values()) {
                if (other.vni == e.vni) {
                    stillHasLocal = true;
                    break;
                }
            }
            if (!stillHasLocal) {
                TierState tier = vpc.tiers.get(e.vni);
                if (tier != null && tier.arpInstalled) {
                    removeArpResponder(tier.foldedTag, tier.gatewayIp);
                    tier.arpInstalled = false;
                }
            }
            LOGGER.info("DVR unregisterVm: vpc={} vm={} ip={} (remaining tier={} localVms={})",
                    ve.getKey(), key, e.ip, e.vni, stillHasLocal);
        }
        persistState();
    }

    /** Diagnostic: return an unmodifiable snapshot of current VPC state. */
    public synchronized Map<String, VpcState> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(vpcs));
    }

    /** Effective bridge. Exposed for tests. */
    public String getBridgeName() {
        return bridgeName;
    }

    // -------------------------------------------------------------------
    // OpenFlow programming
    // -------------------------------------------------------------------

    /**
     * Install the ARP responder for a tier. Classic NXM recipe: when an ARP
     * request for the gateway IP hits the bridge on the tier's access tag,
     * craft a reply in-place and send back {@code in_port}.
     */
    private boolean installArpResponder(int foldedTag, String gatewayIp) {
        long gwInt = ipv4ToLong(gatewayIp);
        if (gwInt < 0) {
            LOGGER.warn("DVR installArpResponder: bad gw IP '{}'", gatewayIp);
            return false;
        }
        String flow = String.format(
                "cookie=%s,table=0,priority=%d,arp,dl_vlan=%d,arp_tpa=%s,arp_op=1,"
                        + "actions=move:NXM_OF_ETH_SRC[]->NXM_OF_ETH_DST[],"
                        + "mod_dl_src:%s,"
                        + "load:0x2->NXM_OF_ARP_OP[],"
                        + "move:NXM_NX_ARP_SHA[]->NXM_NX_ARP_THA[],"
                        + "move:NXM_OF_ARP_SPA[]->NXM_OF_ARP_TPA[],"
                        + "load:0x%s->NXM_NX_ARP_SHA[],"
                        + "load:0x%x->NXM_OF_ARP_SPA[],"
                        + "in_port",
                DVR_COOKIE_ARP, PRIORITY_ARP_RESPONDER, foldedTag, gatewayIp,
                DVR_GATEWAY_MAC,
                macToHex(DVR_GATEWAY_MAC),
                gwInt);
        return addFlow(flow, "arp-responder tag=" + foldedTag + " gw=" + gatewayIp);
    }

    private void removeArpResponder(int foldedTag, String gatewayIp) {
        String spec = String.format("cookie=%s/-1,table=0,arp,dl_vlan=%d,arp_tpa=%s",
                DVR_COOKIE_ARP, foldedTag, gatewayIp);
        delFlows(spec, "arp-responder tag=" + foldedTag);
    }

    /**
     * Install a cross-tier L3 route for a specific destination VM.
     *
     * @param srcFoldedTag the tag packets arrive on (source tier)
     * @param dstVmIp      destination VM's IP
     * @param dstVmMac     destination VM's MAC (rewritten into dl_dst)
     * @param dstFoldedTag the tag packets exit on (destination tier)
     */
    private boolean installL3Route(int srcFoldedTag, String dstVmIp, String dstVmMac, int dstFoldedTag) {
        String flow = String.format(
                "cookie=%s,table=0,priority=%d,ip,dl_vlan=%d,dl_dst=%s,nw_dst=%s,"
                        + "actions=mod_dl_dst:%s,"
                        + "mod_vlan_vid:%d,"
                        + "NORMAL",
                DVR_COOKIE_ROUTE, PRIORITY_L3_ROUTE, srcFoldedTag, DVR_GATEWAY_MAC, dstVmIp,
                dstVmMac, dstFoldedTag);
        return addFlow(flow, "l3-route src_tag=" + srcFoldedTag + " -> " + dstVmIp + "/" + dstVmMac
                + " tag=" + dstFoldedTag);
    }

    private void removeL3RoutesForDstIp(String dstVmIp) {
        String spec = String.format("cookie=%s/-1,table=0,ip,nw_dst=%s",
                DVR_COOKIE_ROUTE, dstVmIp);
        delFlows(spec, "l3-route dst=" + dstVmIp);
    }

    private boolean addFlow(String flow, String label) {
        String cmd = String.format("ovs-ofctl -O %s add-flow %s \"%s\"", OF_PROTOCOL, bridgeName, flow);
        try {
            Script.runSimpleBashScript(cmd, OVS_TIMEOUT_MS);
            LOGGER.debug("DVR add-flow: {}", label);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("DVR add-flow failed ({}): {}", label, e.getMessage());
            return false;
        }
    }

    private void delFlows(String spec, String label) {
        String cmd = String.format("ovs-ofctl -O %s del-flows %s \"%s\"",
                OF_PROTOCOL, bridgeName, spec);
        try {
            Script.runSimpleBashScript(cmd, OVS_TIMEOUT_MS);
            LOGGER.debug("DVR del-flows: {}", label);
        } catch (RuntimeException e) {
            LOGGER.warn("DVR del-flows failed ({}): {}", label, e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    static int toOvsAccessTag(int vni) {
        if (vni >= 1 && vni <= 4094) {
            return vni;
        }
        return ((vni - 1) % 4094) + 1;
    }

    static long ipv4ToLong(String ip) {
        if (StringUtils.isBlank(ip)) {
            return -1;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long out = 0;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) {
                    return -1;
                }
                out = (out << 8) | v;
            } catch (NumberFormatException nfe) {
                return -1;
            }
        }
        return out;
    }

    /** Convert {@code 02:dc:00:00:00:01} → {@code 02dc00000001}. */
    static String macToHex(String mac) {
        return StringUtils.isBlank(mac) ? "0" : mac.replace(":", "").toLowerCase();
    }

    private static String vpcKey(String vpcId) {
        return StringUtils.isBlank(vpcId) ? "*" : vpcId.trim();
    }

    private String resolveBridge(java.util.Properties props) {
        if (props == null) {
            return DEFAULT_BRIDGE;
        }
        String b = props.getProperty("network.bridge.name");
        if (StringUtils.isBlank(b)) {
            b = props.getProperty("guest.bridge.name");
        }
        if (StringUtils.isBlank(b)) {
            b = props.getProperty("guest.network.device");
        }
        return StringUtils.isBlank(b) ? DEFAULT_BRIDGE : b.trim();
    }

    // -------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------

    /** Per-VPC in-memory state. */
    public static final class VpcState {
        public Map<Integer, TierState> tiers = new TreeMap<>();
        public Map<String, VmEntry> vms = new LinkedHashMap<>();
    }

    public static final class TierState {
        public String cidr;
        public String gatewayIp;
        public int foldedTag;
        public boolean arpInstalled;
    }

    public static final class VmEntry {
        public int vni;
        public String ip;
        public String mac;
    }

    /**
     * Persistent layout: we serialize {@link #vpcs} as-is but flatten the
     * {@code Integer}-keyed tier map to {@code String} keys so Jackson
     * round-trips cleanly without custom serializers.
     */
    static final class StateFile {
        public Map<String, PersistedVpc> vpcs = new HashMap<>();
    }

    static final class PersistedVpc {
        public Map<String, TierState> tiers = new HashMap<>();
        public Map<String, VmEntry> vms = new HashMap<>();
    }

    private void loadState() {
        if (!Files.exists(STATE_FILE)) {
            return;
        }
        try {
            StateFile sf = objectMapper.readValue(STATE_FILE.toFile(), StateFile.class);
            if (sf == null || sf.vpcs == null) {
                return;
            }
            for (Map.Entry<String, PersistedVpc> ve : sf.vpcs.entrySet()) {
                VpcState vpc = new VpcState();
                PersistedVpc pv = ve.getValue();
                if (pv == null) {
                    continue;
                }
                if (pv.tiers != null) {
                    for (Map.Entry<String, TierState> te : pv.tiers.entrySet()) {
                        try {
                            int vni = Integer.parseInt(te.getKey());
                            // arpInstalled resets on reload — we'll re-apply.
                            TierState t = te.getValue();
                            if (t != null) {
                                t.arpInstalled = false;
                            }
                            vpc.tiers.put(vni, t);
                        } catch (NumberFormatException ignore) {
                            // skip corrupt key
                        }
                    }
                }
                if (pv.vms != null) {
                    vpc.vms.putAll(pv.vms);
                }
                vpcs.put(ve.getKey(), vpc);
            }
            LOGGER.info("DvrManager loaded state: vpcs={}", vpcs.keySet());
        } catch (IOException e) {
            LOGGER.warn("DvrManager loadState failed: {}", e.getMessage());
        }
    }

    private void persistState() {
        try {
            if (!Files.exists(STATE_DIR)) {
                Files.createDirectories(STATE_DIR);
            }
            StateFile sf = new StateFile();
            for (Map.Entry<String, VpcState> ve : vpcs.entrySet()) {
                PersistedVpc pv = new PersistedVpc();
                for (Map.Entry<Integer, TierState> te : ve.getValue().tiers.entrySet()) {
                    pv.tiers.put(String.valueOf(te.getKey()), te.getValue());
                }
                pv.vms.putAll(ve.getValue().vms);
                sf.vpcs.put(ve.getKey(), pv);
            }
            Path tmp = STATE_FILE.resolveSibling("state.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), sf);
            Files.move(tmp, STATE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("DvrManager persistState failed: {}", e.getMessage());
        }
    }

    /**
     * Re-apply every flow derived from persisted state. Intended for agent
     * restart. {@link VxlanTunnelManager#bootstrapFromState} is the sibling
     * call; they are independent.
     */
    public synchronized void bootstrapFromState() {
        int reArp = 0;
        int reRoute = 0;
        for (Map.Entry<String, VpcState> ve : vpcs.entrySet()) {
            VpcState vpc = ve.getValue();
            // Re-install ARP responders for each tier that has at least one
            // local VM still tracked.
            Set<Integer> tiersWithLocalVms = new LinkedHashSet<>();
            for (VmEntry vm : vpc.vms.values()) {
                tiersWithLocalVms.add(vm.vni);
            }
            for (Integer vni : tiersWithLocalVms) {
                TierState t = vpc.tiers.get(vni);
                if (t == null) {
                    continue;
                }
                if (installArpResponder(t.foldedTag, t.gatewayIp)) {
                    t.arpInstalled = true;
                    reArp++;
                }
            }
            // Re-install L3 routes for each local VM against every OTHER tier.
            for (Map.Entry<String, VmEntry> vmEntry : vpc.vms.entrySet()) {
                VmEntry vm = vmEntry.getValue();
                TierState dstTier = vpc.tiers.get(vm.vni);
                if (dstTier == null) {
                    continue;
                }
                for (Map.Entry<Integer, TierState> te : vpc.tiers.entrySet()) {
                    if (te.getKey() == vm.vni) {
                        continue;
                    }
                    if (installL3Route(te.getValue().foldedTag, vm.ip, vm.mac, dstTier.foldedTag)) {
                        reRoute++;
                    }
                }
            }
        }
        LOGGER.info("DvrManager bootstrapFromState: re-applied arp={} routes={}", reArp, reRoute);
    }

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    // -------------------------------------------------------------------
    // MVP LIMITATIONS (preserved here in code so the next reader has the
    // context without digging through commit history):
    //
    //  1. IPv4 only. No IPv6 gateway proxy / ND responder installed.
    //  2. Intra-host cross-tier only. Cross-host cross-tier still flows
    //     via the VR (baseline CloudStack behavior unchanged).
    //  3. No per-ACL rule enforcement. Cross-tier is either permitted
    //     (both tiers registered in VPC) or routed through the VR as
    //     a fallback. VPC ACL is honored transparently when traffic
    //     lands on the VR.
    //  4. No live-migration sync. On migration, the source host's
    //     unplug removes the VM's routes; the destination host's plug
    //     re-installs them. Transient window: seconds.
    //  5. Single VPC tested. Multi-VPC support exists in the data model
    //     (keyed by vpcId) but was not validated in MVP.
    //  6. No conntrack sync — deliberate: cross-tier is pure L3, no NAT
    //     in scope, so stateful tracking is not required.
    //
    // These limits are documented in the commit message and in the
    // class-level Javadoc above. Removing any of them moves us out of
    // MVP territory (weeks of work).
    // -------------------------------------------------------------------

    /** Intentionally package-private for direct pokes from tests. */
    List<String> debugListPersistedVpcs() {
        return new ArrayList<>(vpcs.keySet());
    }
}
