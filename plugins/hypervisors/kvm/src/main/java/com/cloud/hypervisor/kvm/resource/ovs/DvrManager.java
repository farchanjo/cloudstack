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
    /** Cookie stamped on L3 routing flows in table 0 (direct mode). */
    static final String DVR_COOKIE_ROUTE = "0x0dc7e007e";
    /** Cookie stamped on L3 routing flows in table 2 (ACL pipeline mode). */
    static final String DVR_COOKIE_ROUTE_T2 = "0x0dc7e007f";
    /** Cookie stamped on the priority=450 table-0 ACL trigger per tier. */
    static final String DVR_COOKIE_ACL_ENTRY = "0x0dc7ace01";
    /** Cookie stamped on per-rule ACL allow entries in table 1. */
    static final String DVR_COOKIE_ACL_RULE = "0x0dc7ace02";
    /** Cookie stamped on the default drop in table 1. */
    static final String DVR_COOKIE_ACL_DROP = "0x0dc7ace03";
    /** Combined mask covering all DVR cookies for bulk del-flows. */
    static final String DVR_COOKIE_MASK = "0x0dc7ffffe";

    private static final int PRIORITY_ARP_RESPONDER = 500;
    /** Priority of the table-0 ACL entry trigger. Must beat L3 shortcut. */
    private static final int PRIORITY_ACL_ENTRY = 450;
    /** Priority for shortcut-mode L3 routes (real VR MAC). Must beat virtual. */
    private static final int PRIORITY_L3_SHORTCUT = 400;
    private static final int PRIORITY_L3_ROUTE = 300;
    /** Default priority applied to ACL rule allow entries in table 1. */
    private static final int PRIORITY_ACL_RULE_DEFAULT = 800;
    /** Priority of the default drop in table 1 (ACL closed-by-default). */
    private static final int PRIORITY_ACL_DROP = 100;
    /** OVS table id for the ACL allow list. */
    private static final int TABLE_ACL = 1;
    /** OVS table id for the route copy (only populated while ACL is active). */
    private static final int TABLE_ROUTE_T2 = 2;

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
    /**
     * Translate the subset of {@link HwOffloadIntentApi.AclRule} fields that
     * map cleanly to our OpenFlow ACL (src cidr + dst proto/port) and atomically
     * replace the rule set for a VPC. Intended to be called from
     * {@code LibvirtComputingResource.prepareNetworkElementCommand(SetNetworkACLCommand)}.
     *
     * <p>Semantic note: this replaces the WHOLE ACL for the VPC (matching how
     * mgmt sends the full rule set each call). Rules without a src CIDR / dst
     * port / proto become wildcards; rules with ACTION=DROP are skipped since
     * OpenFlow default is drop after table-1 miss.
     *
     * @param vpcId       vpcId (uuid); if blank, silently skipped
     * @param hwOffRules  list of HwOffloadIntentApi.AclRule as received by the handler
     */
    public synchronized void translateAndSetAclRules(String vpcId,
            java.util.List<com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.AclRule> hwOffRules) {
        if (StringUtils.isBlank(vpcId)) {
            return;
        }
        java.util.List<AclRule> out = new ArrayList<>();
        if (hwOffRules != null) {
            int p = 800;
            for (var hw : hwOffRules) {
                if (hw == null) {
                    continue;
                }
                // Skip DROP rules: OpenFlow default in table 1 IS drop; only ACCEPT
                // rules are needed to carve exceptions. DROP rules as positive
                // match would require priority ordering vs allows — out of MVP scope.
                if (!"ACCEPT".equalsIgnoreCase(hw.action)) {
                    continue;
                }
                AclRule r = new AclRule();
                r.srcCidr = hw.matchSrcIp;
                r.ipProto = hw.ipProto;
                r.dstPort = hw.matchPort;
                r.prio = (hw.prio != null) ? (800 + hw.prio) : p++;
                out.add(r);
            }
        }
        setAclRules(vpcId, out);
    }

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
        // Fix asymmetric registration: when this tier is brand-new but the
        // VPC already has VMs in OTHER tiers, install inbound L3 route
        // flows so traffic originating from this new tier can reach those
        // VMs via OVS. registerVmInTier only walks other tiers when a VM
        // registers; without this back-fill the reverse direction stays
        // on the VR fallback path.
        for (Map.Entry<String, VmEntry> ve : vpc.vms.entrySet()) {
            VmEntry peerVm = ve.getValue();
            if (peerVm == null || peerVm.vni == vni) {
                continue;
            }
            TierState dstTier = vpc.tiers.get(peerVm.vni);
            if (dstTier == null) {
                continue;
            }
            installL3Route(folded, tier.gatewayMac, peerVm.ip, peerVm.mac, dstTier.foldedTag, dstTier.gatewayMac,
                    peerVm.repOfPort);
            maybeInstallRouteT2(vpc, folded, tier.gatewayMac, peerVm.ip, peerVm.mac,
                    dstTier.foldedTag, dstTier.gatewayMac);
        }
        reconcileArpResponders(vpc);
        // When ACL is active and a tier appears for the first time, the
        // tier's gateway MAC needs a priority=450 trigger so packets from
        // this new tier enter the ACL pipeline.
        if (isAclActive(vpc)) {
            reconcileAclEntryTriggers(vpc);
        }
        persistState();
    }

    /**
     * Learn the real MAC address of the VR for a given tier. Called when
     * the centralized VR plugs into this host (NIC IP == tier gateway IP).
     * Once known, the shortcut uses the VR's real MAC as the match/rewrite
     * key and disables the synthetic DVR gateway MAC ARP responder for
     * this tier (VR answers its own ARP normally — no race).
     *
     * <p>Cross-host shortcut (the piece the virtual-MAC DVR couldn't do)
     * works because the VM sends the packet to its gateway MAC, which is
     * the VR's real MAC. On the source host, OVS matches on that MAC and
     * rewrites + tunnels to the destination host where the target VM sits
     * — no dependency on the VR being local.
     *
     * @param vpcId opaque VPC id, same fold as {@link #registerTier}
     * @param vni   VXLAN VNI of the tier whose gateway MAC we learned
     * @param gwMac real MAC of the VR on that tier
     */
    public synchronized void registerGatewayMac(String vpcId, int vni, String gwMac) {
        if (vni <= 0 || StringUtils.isBlank(gwMac)) {
            return;
        }
        String vKey = vpcKey(vpcId);
        VpcState vpc = vpcs.get(vKey);
        if (vpc == null) {
            LOGGER.debug("DVR registerGatewayMac: no tier state for vpc={} vni={} yet", vKey, vni);
            return;
        }
        TierState tier = vpc.tiers.get(vni);
        if (tier == null) {
            LOGGER.debug("DVR registerGatewayMac: tier vni={} not yet registered for vpc={}", vni, vKey);
            return;
        }
        String normalized = gwMac.trim().toLowerCase();
        if (normalized.equals(tier.gatewayMac)) {
            return;
        }
        tier.gatewayMac = normalized;
        LOGGER.info("DVR registerGatewayMac: vpc={} vni={} tag={} gw={} -> real MAC {} (shortcut mode on)",
                vKey, vni, tier.foldedTag, tier.gatewayIp, normalized);
        // Tear down the virtual-MAC ARP responder for this tier if it was
        // installed — VR will answer its own ARP from now on.
        if (tier.arpInstalled) {
            removeArpResponder(tier.foldedTag, tier.gatewayIp);
            tier.arpInstalled = false;
        }
        // Re-install every L3 route that touches this tier (as src OR dst)
        // now that we know the real MAC — old virtual-MAC routes are
        // superseded by higher-priority real-MAC variants, both cookies
        // remain cleanly removable by del-flows cookie=DVR_COOKIE_ROUTE.
        reapplyRoutesTouchingTier(vpc, vni);
        reconcileArpResponders(vpc);
        persistState();
    }

    /**
     * Re-install every L3 route that has this tier on either side. Called
     * from {@link #registerGatewayMac} when a tier's real gw MAC becomes
     * known or changes. Idempotent: OVS replaces flows by (cookie, match)
     * key. Silently skips routes where either side still lacks a gw MAC —
     * those stay on the synthetic DVR MAC (backwards-compatible fallback).
     */
    private void reapplyRoutesTouchingTier(VpcState vpc, int touchedVni) {
        if (vpc == null) {
            return;
        }
        TierState touched = vpc.tiers.get(touchedVni);
        if (touched == null) {
            return;
        }
        for (Map.Entry<String, VmEntry> ve : vpc.vms.entrySet()) {
            VmEntry vm = ve.getValue();
            TierState dstTier = vpc.tiers.get(vm.vni);
            if (dstTier == null) {
                continue;
            }
            // Route where this tier is SRC: dst VM is in another tier, srcTag=touched.foldedTag
            if (vm.vni != touchedVni) {
                installL3Route(touched.foldedTag, touched.gatewayMac,
                        vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac, vm.repOfPort);
                maybeInstallRouteT2(vpc, touched.foldedTag, touched.gatewayMac,
                        vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac);
            } else {
                // Route where this tier is DST: all other tiers as src
                for (Map.Entry<Integer, TierState> te : vpc.tiers.entrySet()) {
                    if (te.getKey() == touchedVni) {
                        continue;
                    }
                    installL3Route(te.getValue().foldedTag, te.getValue().gatewayMac,
                            vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac, vm.repOfPort);
                    maybeInstallRouteT2(vpc, te.getValue().foldedTag, te.getValue().gatewayMac,
                            vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac);
                }
            }
        }
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
        registerVmInTier(vpcId, vmName, vni, vmIp, vmMac, null);
    }

    /**
     * Variant that accepts the local VF representor name (e.g. {@code dx6p0vf0}).
     * When present, we resolve its OVS ofport and use it as the direct output
     * target of shortcut flows, bypassing NORMAL's ingress VLAN check.
     */
    public synchronized void registerVmInTier(String vpcId, String vmName, int vni, String vmIp, String vmMac,
            String repName) {
        if (vni <= 0 || StringUtils.isBlank(vmName) || StringUtils.isBlank(vmIp) || StringUtils.isBlank(vmMac)) {
            return;
        }
        String vKey = vpcKey(vpcId);
        VpcState vpc = vpcs.computeIfAbsent(vKey, k -> new VpcState());
        VmEntry entry = new VmEntry();
        entry.vni = vni;
        entry.ip = vmIp.trim();
        entry.mac = vmMac.trim().toLowerCase();
        if (StringUtils.isNotBlank(repName)) {
            entry.repName = repName.trim();
            entry.repOfPort = resolveOfPort(entry.repName);
        }
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
            TierState srcTier = te.getValue();
            if (installL3Route(srcTier.foldedTag, srcTier.gatewayMac,
                    entry.ip, entry.mac, thisTier.foldedTag, thisTier.gatewayMac, entry.repOfPort)) {
                installed++;
            }
            maybeInstallRouteT2(vpc, srcTier.foldedTag, srcTier.gatewayMac,
                    entry.ip, entry.mac, thisTier.foldedTag, thisTier.gatewayMac);
        }
        reconcileArpResponders(vpc);
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
            // Rerun the cross-tier gate: if this was the last VM on its
            // tier OR the last in the only-peer tier, the gate may fall,
            // so every ARP responder becomes a liability and has to be
            // torn down. reconcileArpResponders handles both directions
            // (install / remove) based on current local cross-tier state.
            boolean stillHasLocal = false;
            for (VmEntry other : vpc.vms.values()) {
                if (other.vni == e.vni) {
                    stillHasLocal = true;
                    break;
                }
            }
            LOGGER.info("DVR unregisterVm: vpc={} vm={} ip={} (remaining tier={} localVms={})",
                    ve.getKey(), key, e.ip, e.vni, stillHasLocal);
        }
        for (VpcState vpc : vpcs.values()) {
            reconcileArpResponders(vpc);
        }
        persistState();
    }

    /** Diagnostic: return an unmodifiable snapshot of current VPC state. */
    public synchronized Map<String, VpcState> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(vpcs));
    }

    /**
     * Reverse lookup: given a set of MAC addresses (e.g., those of a VR's
     * NICs), return the vpcId whose tiers' gateway MACs overlap the set.
     * Used by the agent's SetNetworkACLCommand handler to find which VPC
     * an ACL update targets without depending on the management server
     * sending the vpcId explicitly.
     *
     * @param macs set of lowercase MAC addresses to probe
     * @return the matching vpcId, or null if no VPC has a matching tier
     */
    public synchronized String findVpcIdByAnyGatewayMac(java.util.Collection<String> macs) {
        if (macs == null || macs.isEmpty()) {
            return null;
        }
        Set<String> normalized = new java.util.HashSet<>();
        for (String m : macs) {
            if (m != null) {
                normalized.add(m.trim().toLowerCase());
            }
        }
        for (Map.Entry<String, VpcState> ve : vpcs.entrySet()) {
            for (TierState tier : ve.getValue().tiers.values()) {
                if (tier.gatewayMac != null && normalized.contains(tier.gatewayMac.toLowerCase())) {
                    return ve.getKey();
                }
            }
        }
        return null;
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
    /**
     * Walk every registered tier in this VPC and install the ARP responder
     * only when this host has at least two tiers with at least one local VM
     * each — i.e., cross-tier traffic is actually possible locally and the
     * L3-route flows will consume frames stamped with the DVR gateway MAC.
     *
     * <p>When the gate is open we install; when it is closed we tear down
     * any existing responder so that gateway ARP resolves back to the VR's
     * real MAC. Skipping this gate makes VMs address the DVR gateway MAC
     * for all north-south traffic, which has no route here and gets
     * flooded into oblivion — that was the production break observed on
     * single-tier-per-host layouts (VM ↔ centralized VR could not talk).
     */
    private void reconcileArpResponders(VpcState vpc) {
        if (vpc == null) {
            return;
        }
        Set<Integer> tiersWithLocalVms = new LinkedHashSet<>();
        for (VmEntry vm : vpc.vms.values()) {
            tiersWithLocalVms.add(vm.vni);
        }
        boolean gateOpen = tiersWithLocalVms.size() >= 2;
        for (Map.Entry<Integer, TierState> te : vpc.tiers.entrySet()) {
            TierState tier = te.getValue();
            // Shortcut mode: when we learned the VR's real MAC, disable
            // the virtual-MAC ARP responder. VR answers its own ARP.
            boolean shortcutMode = StringUtils.isNotBlank(tier.gatewayMac);
            boolean shouldInstall = !shortcutMode && gateOpen && tiersWithLocalVms.contains(te.getKey());
            if (shouldInstall && !tier.arpInstalled) {
                if (installArpResponder(tier.foldedTag, tier.gatewayIp)) {
                    tier.arpInstalled = true;
                    LOGGER.info("DVR reconcile: installed ARP responder vni={} tag={} gw={}",
                            te.getKey(), tier.foldedTag, tier.gatewayIp);
                }
            } else if (!shouldInstall && tier.arpInstalled) {
                removeArpResponder(tier.foldedTag, tier.gatewayIp);
                tier.arpInstalled = false;
                LOGGER.info("DVR reconcile: removed ARP responder vni={} tag={} gw={}"
                        + " ({})", te.getKey(), tier.foldedTag, tier.gatewayIp,
                        shortcutMode ? "shortcut mode — real VR MAC known"
                                : "no cross-tier peer");
            }
        }
    }

    private boolean installArpResponder(int foldedTag, String gatewayIp) {
        long gwInt = ipv4ToLong(gatewayIp);
        if (gwInt < 0) {
            LOGGER.warn("DVR installArpResponder: bad gw IP '{}'", gatewayIp);
            return false;
        }
        // Intentionally match vlan_tci=0x0000 instead of dl_vlan=%d: access
        // ports (the VM-facing vnetN side) strip the VLAN before table-0
        // classification, so the frame arrives with vlan_tci=0 even though
        // the port has an access tag configured. Historical dl_vlan match
        // never fired. Scope stays unambiguous because arp_tpa (tier gw IP)
        // is unique per tier within a VPC (each tier has its own CIDR).
        String flow = String.format(
                "cookie=%s,table=0,priority=%d,arp,vlan_tci=0x0000,arp_tpa=%s,arp_op=1,"
                        + "actions=move:NXM_OF_ETH_SRC[]->NXM_OF_ETH_DST[],"
                        + "mod_dl_src:%s,"
                        + "load:0x2->NXM_OF_ARP_OP[],"
                        + "move:NXM_NX_ARP_SHA[]->NXM_NX_ARP_THA[],"
                        + "move:NXM_OF_ARP_SPA[]->NXM_OF_ARP_TPA[],"
                        + "load:0x%s->NXM_NX_ARP_SHA[],"
                        + "load:0x%x->NXM_OF_ARP_SPA[],"
                        + "in_port",
                DVR_COOKIE_ARP, PRIORITY_ARP_RESPONDER, gatewayIp,
                DVR_GATEWAY_MAC,
                macToHex(DVR_GATEWAY_MAC),
                gwInt);
        // NOTE: priority=500 alone is sufficient to win the ARP race.
        // OVS is a single-pipeline matcher — only the highest-priority
        // match executes, never in parallel with priority=0 NORMAL. The
        // VM's ARP request for the tier gateway hits this flow and the
        // response goes out IN_PORT before any VXLAN flood can occur.
        // Consequence: the centralized VR on a remote host never sees
        // the request (it's consumed locally), so there is no race at
        // all. A previous iteration added a priority=600 drop fence for
        // ARP replies with arp_spa=<gw>; that turned out to be harmful
        // because it also blocked legitimate VR↔VM ARP replies used for
        // traffic that still needs to transit the central VR (default
        // route to internet, inter-VPC, etc. — the VR has the full L3
        // table; DVR only handles intra-VPC peer VMs).
        return addFlow(flow, "arp-responder tag=" + foldedTag + " gw=" + gatewayIp);
    }

    private void removeArpResponder(int foldedTag, String gatewayIp) {
        // Remove responder (new vlan_tci=0x0000 match). Old dl_vlan flows
        // from previous agent versions are swept by the cookie-mask clear
        // at bootstrap (bootstrapFromState issues a del-flows on the mask).
        String spec = String.format("cookie=%s/-1,table=0,arp,vlan_tci=0x0000,arp_tpa=%s",
                DVR_COOKIE_ARP, gatewayIp);
        delFlows(spec, "arp-responder tag=" + foldedTag);
    }

    /**
     * Install a cross-tier L3 route for a specific destination VM.
     *
     * <p>Two matching strategies live side by side:
     * <ul>
     *   <li><b>Shortcut mode</b> (both {@code srcGwMac} and {@code dstGwMac}
     *       present) — matches on the VR's real MAC in the source tier and
     *       rewrites to the VR's real MAC in the destination tier. Works
     *       cross-host because the VM already addresses the packet to the
     *       VR MAC via normal ARP. Priority {@value #PRIORITY_L3_SHORTCUT}
     *       so it wins over the synthetic-MAC variant when both are
     *       installed during a transition.</li>
     *   <li><b>Virtual mode</b> (either gwMac is null) — legacy DVR path
     *       matching on the synthetic {@value #DVR_GATEWAY_MAC}. Requires
     *       the ARP responder to answer with this MAC so VMs send packets
     *       to it. Priority {@value #PRIORITY_L3_ROUTE}.</li>
     * </ul>
     *
     * @param srcFoldedTag tag packets arrive on (source tier)
     * @param srcGwMac     VR's real MAC on the source tier; null → virtual mode
     * @param dstVmIp      destination VM's IP
     * @param dstVmMac     destination VM's MAC (rewritten into dl_dst)
     * @param dstFoldedTag tag packets exit on (destination tier)
     * @param dstGwMac     VR's real MAC on the destination tier; null → virtual mode
     */
    private boolean installL3Route(int srcFoldedTag, String srcGwMac, String dstVmIp, String dstVmMac,
                                   int dstFoldedTag, String dstGwMac) {
        return installL3Route(srcFoldedTag, srcGwMac, dstVmIp, dstVmMac, dstFoldedTag, dstGwMac, null);
    }

    private boolean installL3Route(int srcFoldedTag, String srcGwMac, String dstVmIp, String dstVmMac,
                                   int dstFoldedTag, String dstGwMac, Integer dstRepOfPort) {
        boolean shortcut = StringUtils.isNotBlank(srcGwMac) && StringUtils.isNotBlank(dstGwMac);
        int priority = shortcut ? PRIORITY_L3_SHORTCUT : PRIORITY_L3_ROUTE;
        String matchMac = shortcut ? srcGwMac : DVR_GATEWAY_MAC;
        String actions;
        if (shortcut && dstRepOfPort != null) {
            // Shortcut + local peer known: egress directly on the peer VF
            // representor ofport. Frame arrives with vlan_tci=0 (source access
            // port already stripped the tag). OVS will apply the destination
            // port's access tag automatically on egress.
            actions = String.format("mod_dl_src:%s,mod_dl_dst:%s,dec_ttl,output:%d",
                    dstGwMac, dstVmMac, dstRepOfPort);
        } else if (shortcut) {
            // Shortcut but peer lives on another host (no local ofport):
            // push the destination tier tag and hand to NORMAL — the FDB /
            // flood path delivers through the VXLAN tunnel with this tag;
            // tunnel ports are trunks and accept arbitrary tags.
            actions = String.format("mod_dl_src:%s,mod_dl_dst:%s,dec_ttl,push_vlan:0x8100,mod_vlan_vid:%d,NORMAL",
                    dstGwMac, dstVmMac, dstFoldedTag);
        } else {
            // Virtual-MAC legacy path: only rewrites dl_dst + tag.
            actions = String.format("mod_dl_dst:%s,push_vlan:0x8100,mod_vlan_vid:%d,NORMAL",
                    dstVmMac, dstFoldedTag);
        }
        // Access ports strip the VLAN tag before table-0 classification, so
        // the frame arrives with vlan_tci=0. Match on vlan_tci=0x0000 instead
        // of dl_vlan=<foldedTag>. Scope is preserved because (dl_dst=<VR MAC
        // on a specific tier> + nw_dst=<peer VM IP>) is unique per route.
        String flow = String.format("cookie=%s,table=0,priority=%d,ip,vlan_tci=0x0000,dl_dst=%s,nw_dst=%s,actions=%s",
                DVR_COOKIE_ROUTE, priority, matchMac, dstVmIp, actions);
        boolean ok = addFlow(flow, String.format("l3-route %s src_tag=%d%s -> %s/%s tag=%d%s%s",
                shortcut ? (dstRepOfPort != null ? "shortcut-local" : "shortcut-remote") : "virtual",
                srcFoldedTag, shortcut ? "/" + srcGwMac : "",
                dstVmIp, dstVmMac, dstFoldedTag, shortcut ? "/" + dstGwMac : "",
                dstRepOfPort != null ? " via_rep_ofport=" + dstRepOfPort : ""));
        // When ACL is active for this VPC, keep an identical-action copy in
        // table 2 so that packets resubmitted from the ACL allow list land
        // on the routing decision. Lookup by (srcTag, dstVmIp) happens in
        // the caller chain (installL3Route has no vpc handle) — we rely on
        // the caller also invoking {@link #installL3RouteT2} when relevant.
        return ok;
    }

    /**
     * Install the mirror of an L3 route flow in {@value #TABLE_ROUTE_T2}.
     * Same match + actions as the table-0 primary but a different cookie
     * ({@link #DVR_COOKIE_ROUTE_T2}) so ACL teardown can bulk-wipe only the
     * table-2 copies without touching the direct-mode route in table 0.
     * Only called by the ACL pipeline path.
     */
    private boolean installL3RouteT2(int srcFoldedTag, String srcGwMac, String dstVmIp, String dstVmMac,
                                     int dstFoldedTag, String dstGwMac) {
        boolean shortcut = StringUtils.isNotBlank(srcGwMac) && StringUtils.isNotBlank(dstGwMac);
        int priority = shortcut ? PRIORITY_L3_SHORTCUT : PRIORITY_L3_ROUTE;
        String matchMac = shortcut ? srcGwMac : DVR_GATEWAY_MAC;
        String actions;
        if (shortcut) {
            actions = String.format("mod_dl_src:%s,mod_dl_dst:%s,dec_ttl,mod_vlan_vid:%d,NORMAL",
                    dstGwMac, dstVmMac, dstFoldedTag);
        } else {
            actions = String.format("mod_dl_dst:%s,mod_vlan_vid:%d,NORMAL",
                    dstVmMac, dstFoldedTag);
        }
        String flow = String.format("cookie=%s,table=%d,priority=%d,ip,dl_vlan=%d,dl_dst=%s,nw_dst=%s,actions=%s",
                DVR_COOKIE_ROUTE_T2, TABLE_ROUTE_T2, priority, srcFoldedTag, matchMac, dstVmIp, actions);
        return addFlow(flow, String.format("l3-route-t2 %s src_tag=%d -> %s/%s tag=%d",
                shortcut ? "shortcut" : "virtual", srcFoldedTag, dstVmIp, dstVmMac, dstFoldedTag));
    }

    /**
     * Install a table-2 mirror only when the VPC has an active ACL list.
     * Callers use this after each primary {@link #installL3Route} so the
     * pipeline stays consistent without branching every call site.
     */
    private void maybeInstallRouteT2(VpcState vpc, int srcFoldedTag, String srcGwMac,
                                     String dstVmIp, String dstVmMac, int dstFoldedTag, String dstGwMac) {
        if (vpc == null || !isAclActive(vpc)) {
            return;
        }
        installL3RouteT2(srcFoldedTag, srcGwMac, dstVmIp, dstVmMac, dstFoldedTag, dstGwMac);
    }

    /** True when the VPC currently has at least one ACL rule programmed. */
    private static boolean isAclActive(VpcState vpc) {
        return vpc != null && vpc.aclRules != null && !vpc.aclRules.isEmpty();
    }

    private void removeL3RoutesForDstIp(String dstVmIp) {
        String spec = String.format("cookie=%s/-1,table=0,ip,nw_dst=%s",
                DVR_COOKIE_ROUTE, dstVmIp);
        delFlows(spec, "l3-route dst=" + dstVmIp);
        // Also wipe the table-2 mirror (idempotent — NOP if not present).
        String spec2 = String.format("cookie=%s/-1,table=%d,ip,nw_dst=%s",
                DVR_COOKIE_ROUTE_T2, TABLE_ROUTE_T2, dstVmIp);
        delFlows(spec2, "l3-route-t2 dst=" + dstVmIp);
    }

    /**
     * Resolve the OVS OpenFlow port number for a given interface name. Uses
     * {@code ovs-vsctl get interface &lt;name&gt; ofport}. Returns null when the
     * port is not attached to any OVS bridge — caller falls back to the
     * mod_vlan+NORMAL variant which works for tunneled cross-host delivery.
     */
    private Integer resolveOfPort(String ifaceName) {
        if (StringUtils.isBlank(ifaceName)) {
            return null;
        }
        String cmd = String.format("ovs-vsctl --if-exists get interface %s ofport 2>/dev/null", ifaceName);
        try {
            String out = Script.runSimpleBashScript(cmd, OVS_TIMEOUT_MS);
            if (out == null) {
                return null;
            }
            String trimmed = out.trim();
            if (trimmed.isEmpty() || "-1".equals(trimmed) || "[]".equals(trimmed)) {
                return null;
            }
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            LOGGER.debug("resolveOfPort: non-numeric output for {}: {}", ifaceName, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOGGER.debug("resolveOfPort: failed for {}: {}", ifaceName, e.getMessage());
            return null;
        }
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
    // Stateless OpenFlow ACL offload (tables 0 → 1 → 2)
    // -------------------------------------------------------------------

    /**
     * Shape of an ACL allow rule handed down from management. All CIDR /
     * proto / port fields are nullable — null means "any". Priority is
     * honored verbatim so that CloudStack's per-ACL ordering survives.
     */
    public static final class AclRule {
        /** nullable; {@code null} or {@code 0.0.0.0/0} means any. */
        public String srcCidr;
        /** nullable; {@code null} or {@code 0.0.0.0/0} means any. */
        public String dstCidr;
        /** {@code tcp} | {@code udp} | {@code icmp} | {@code null} (any). */
        public String ipProto;
        /** nullable; only honored for tcp/udp. */
        public Integer dstPort;
        /** nullable; defaults to {@value #PRIORITY_ACL_RULE_DEFAULT}. */
        public Integer prio;
    }

    /**
     * Add a single rule to the VPC ACL list. Preserves existing rules.
     * Idempotent with respect to (srcCidr, dstCidr, proto, port, prio) —
     * {@link #setAclRules} handles programming; this wrapper just appends
     * and delegates.
     *
     * @param vpcId opaque VPC identifier (same fold as {@link #registerTier})
     * @param rule  rule to add; null is a NOP
     */
    public synchronized void registerAclRule(String vpcId, AclRule rule) {
        if (rule == null) {
            return;
        }
        String vKey = vpcKey(vpcId);
        VpcState vpc = vpcs.computeIfAbsent(vKey, k -> new VpcState());
        List<AclRule> merged = new ArrayList<>(vpc.aclRules);
        merged.add(rule);
        setAclRules(vpcId, merged);
    }

    /**
     * Atomically replace the full ACL rule list for a VPC. This matches
     * the shape of CloudStack's {@code SetNetworkACLCommand} which sends
     * the entire effective rule set every time a user edits it.
     *
     * <p>Transitions:
     * <ul>
     *   <li><b>Empty → non-empty</b>: install per-tier priority=450 table-0
     *       trigger, table-1 allow rules + default drop, AND a table-2
     *       copy of every existing L3 route in the VPC.</li>
     *   <li><b>Non-empty → non-empty</b>: bulk-replace table-1 contents
     *       (per-rule cookie wipe + re-install). Table-0 trigger + table-2
     *       copies stay in place.</li>
     *   <li><b>Non-empty → empty</b>: same as {@link #clearAclRules}.</li>
     * </ul>
     *
     * @param vpcId opaque VPC identifier
     * @param rules new rule set; null or empty collapses the pipeline
     */
    public synchronized void setAclRules(String vpcId, List<AclRule> rules) {
        String vKey = vpcKey(vpcId);
        VpcState vpc = vpcs.computeIfAbsent(vKey, k -> new VpcState());
        List<AclRule> safe = rules == null ? Collections.emptyList() : new ArrayList<>(rules);
        boolean wasActive = isAclActive(vpc);
        boolean becomesActive = !safe.isEmpty();

        // Always wipe the per-rule cookie before re-installing — idempotent.
        delFlows(String.format("cookie=%s/-1,table=%d", DVR_COOKIE_ACL_RULE, TABLE_ACL),
                "acl-rules table=" + TABLE_ACL);

        vpc.aclRules = safe;

        if (becomesActive) {
            // Install every rule + the default drop in table 1.
            int installed = 0;
            for (AclRule r : safe) {
                if (installAclRule(r)) {
                    installed++;
                }
            }
            installAclDefaultDrop();
            // If we are transitioning empty → non-empty, also install the
            // per-tier entry trigger (table 0, priority=450) AND back-fill
            // the table-2 route copies. If we were already active, those
            // are already in place and need no touch.
            if (!wasActive) {
                reconcileAclEntryTriggers(vpc);
                backfillRouteTableTwo(vpc);
            }
            LOGGER.info("DVR setAclRules: vpc={} ruleCount={} installed={} active={}",
                    vKey, safe.size(), installed, becomesActive);
        } else {
            // Non-empty → empty: tear down triggers, table-1 drop, table-2.
            tearDownAclPipeline();
            LOGGER.info("DVR setAclRules: vpc={} cleared (pipeline collapsed to table 0)", vKey);
        }
        persistState();
    }

    /** Sugar for {@code setAclRules(vpcId, Collections.emptyList())}. */
    public synchronized void clearAclRules(String vpcId) {
        setAclRules(vpcId, Collections.emptyList());
    }

    /**
     * Install the per-tier priority=450 trigger so any IP packet addressed
     * to a known gateway MAC enters the ACL pipeline in table 1. Uses one
     * flow per tier gateway MAC — the synthetic DVR MAC plus whatever real
     * VR MACs we learned via {@link #registerGatewayMac}.
     *
     * <p>Idempotent: wipes the trigger cookie first, then re-adds the
     * current set.
     */
    private void reconcileAclEntryTriggers(VpcState vpc) {
        delFlows(String.format("cookie=%s/-1,table=0", DVR_COOKIE_ACL_ENTRY),
                "acl-entry-trigger table=0");
        if (vpc == null || !isAclActive(vpc)) {
            return;
        }
        // Collect every distinct gateway MAC in play. DVR gateway MAC is
        // universal; per-tier real VR MACs light up when shortcut mode is on.
        Set<String> macs = new LinkedHashSet<>();
        macs.add(DVR_GATEWAY_MAC);
        for (TierState tier : vpc.tiers.values()) {
            if (StringUtils.isNotBlank(tier.gatewayMac)) {
                macs.add(tier.gatewayMac);
            }
        }
        for (String mac : macs) {
            String flow = String.format("cookie=%s,table=0,priority=%d,ip,dl_dst=%s,actions=resubmit(,%d)",
                    DVR_COOKIE_ACL_ENTRY, PRIORITY_ACL_ENTRY, mac, TABLE_ACL);
            addFlow(flow, "acl-entry-trigger dl_dst=" + mac);
        }
    }

    /**
     * Back-fill table 2 with a copy of every currently installed L3 route
     * for this VPC. Called once, on empty→non-empty ACL transition.
     */
    private void backfillRouteTableTwo(VpcState vpc) {
        if (vpc == null) {
            return;
        }
        int copied = 0;
        for (VmEntry vm : vpc.vms.values()) {
            TierState dstTier = vpc.tiers.get(vm.vni);
            if (dstTier == null) {
                continue;
            }
            for (Map.Entry<Integer, TierState> te : vpc.tiers.entrySet()) {
                if (te.getKey() == vm.vni) {
                    continue;
                }
                TierState srcTier = te.getValue();
                if (installL3RouteT2(srcTier.foldedTag, srcTier.gatewayMac,
                        vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac)) {
                    copied++;
                }
            }
        }
        LOGGER.info("DVR backfillRouteTableTwo: copied={}", copied);
    }

    /**
     * Install one per-rule allow in table 1. Returns {@code true} on success.
     * Caller is responsible for having wiped the per-rule cookie beforehand
     * when doing a bulk replace.
     */
    private boolean installAclRule(AclRule r) {
        if (r == null) {
            return false;
        }
        int priority = r.prio == null ? PRIORITY_ACL_RULE_DEFAULT : r.prio;
        StringBuilder match = new StringBuilder();
        String proto = r.ipProto == null ? null : r.ipProto.trim().toLowerCase();
        boolean isIcmp = "icmp".equals(proto);
        boolean isTcpUdp = "tcp".equals(proto) || "udp".equals(proto);
        // Base layer: prefer the more specific proto keyword (OVS treats
        // {tcp,udp,icmp} as "ip,proto=N") so the match is as narrow as the
        // rule intends. Fallback to bare {@code ip} when proto is null/any.
        if (isIcmp) {
            match.append("icmp");
        } else if ("tcp".equals(proto)) {
            match.append("tcp");
        } else if ("udp".equals(proto)) {
            match.append("udp");
        } else {
            match.append("ip");
        }
        if (isCidrMeaningful(r.srcCidr)) {
            match.append(",nw_src=").append(r.srcCidr.trim());
        }
        if (isCidrMeaningful(r.dstCidr)) {
            match.append(",nw_dst=").append(r.dstCidr.trim());
        }
        if (isTcpUdp && r.dstPort != null) {
            match.append(",tp_dst=").append(r.dstPort);
        }
        String flow = String.format("cookie=%s,table=%d,priority=%d,%s,actions=resubmit(,%d)",
                DVR_COOKIE_ACL_RULE, TABLE_ACL, priority, match, TABLE_ROUTE_T2);
        return addFlow(flow, String.format("acl-rule prio=%d %s", priority, match));
    }

    /** Install the closed-by-default drop at {@link #PRIORITY_ACL_DROP} in table 1. */
    private void installAclDefaultDrop() {
        String flow = String.format("cookie=%s,table=%d,priority=%d,actions=drop",
                DVR_COOKIE_ACL_DROP, TABLE_ACL, PRIORITY_ACL_DROP);
        addFlow(flow, "acl-default-drop");
    }

    /**
     * Tear down every ACL-related flow across tables 0/1/2. Called when
     * the last rule is removed (non-empty → empty). Leaves table 0 p=400
     * L3 routes untouched — they resume direct routing.
     */
    private void tearDownAclPipeline() {
        delFlows(String.format("cookie=%s/-1,table=0", DVR_COOKIE_ACL_ENTRY),
                "acl-entry-trigger table=0 (teardown)");
        delFlows(String.format("cookie=%s/-1,table=%d", DVR_COOKIE_ACL_RULE, TABLE_ACL),
                "acl-rules table=" + TABLE_ACL + " (teardown)");
        delFlows(String.format("cookie=%s/-1,table=%d", DVR_COOKIE_ACL_DROP, TABLE_ACL),
                "acl-default-drop table=" + TABLE_ACL + " (teardown)");
        delFlows(String.format("cookie=%s/-1,table=%d", DVR_COOKIE_ROUTE_T2, TABLE_ROUTE_T2),
                "l3-route-t2 table=" + TABLE_ROUTE_T2 + " (teardown)");
    }

    private static boolean isCidrMeaningful(String cidr) {
        if (StringUtils.isBlank(cidr)) {
            return false;
        }
        String trimmed = cidr.trim();
        return !"0.0.0.0/0".equals(trimmed) && !"::/0".equals(trimmed);
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
        /**
         * ACL rule list, driven by {@link #setAclRules}. Non-empty flips
         * the VPC into the 3-table pipeline; empty collapses back to
         * single-table direct routing.
         */
        public List<AclRule> aclRules = new ArrayList<>();
    }

    public static final class TierState {
        public String cidr;
        public String gatewayIp;
        public int foldedTag;
        public boolean arpInstalled;
        /**
         * Real MAC of the VR on this tier, once observed via
         * {@link #registerGatewayMac}. Presence flips the tier into
         * "shortcut mode" — matches real VR MAC, disables virtual-MAC
         * ARP responder. Null until the VR plugs on this host.
         */
        public String gatewayMac;
    }

    public static final class VmEntry {
        public int vni;
        public String ip;
        public String mac;
        /**
         * OVS ofport of this VM's VF representor on the local bridge. Resolved
         * at register time via {@code ovs-vsctl get interface &lt;rep&gt; ofport}.
         * Shortcut flows in table 0 egress directly to this ofport after
         * {@code strip_vlan} — avoids the ingress VLAN check that NORMAL does.
         * When unknown (rep not on this host), falls back to legacy
         * {@code mod_vlan + NORMAL} which still works for VXLAN-tunneled
         * cross-host peers because the tunnel port accepts any tag.
         */
        public Integer repOfPort;
        public String repName;
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
        public List<AclRule> aclRules = new ArrayList<>();
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
                if (pv.aclRules != null) {
                    vpc.aclRules = new ArrayList<>(pv.aclRules);
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
                if (ve.getValue().aclRules != null) {
                    pv.aclRules = new ArrayList<>(ve.getValue().aclRules);
                }
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
        int reAcl = 0;
        for (Map.Entry<String, VpcState> ve : vpcs.entrySet()) {
            VpcState vpc = ve.getValue();
            // Re-install ARP responders for each tier that has at least one
            // local VM still tracked.
            Set<Integer> tiersWithLocalVms = new LinkedHashSet<>();
            for (VmEntry vm : vpc.vms.values()) {
                tiersWithLocalVms.add(vm.vni);
            }
            // Same gate as reconcileArpResponders: only reinstall when
            // the host has >=2 tiers with local VMs. Single-tier hosts
            // must leave the centralized VR as the gateway.
            boolean gateOpen = tiersWithLocalVms.size() >= 2;
            for (Integer vni : tiersWithLocalVms) {
                TierState t = vpc.tiers.get(vni);
                if (t == null) {
                    continue;
                }
                if (gateOpen && installArpResponder(t.foldedTag, t.gatewayIp)) {
                    t.arpInstalled = true;
                    reArp++;
                } else if (!gateOpen) {
                    t.arpInstalled = false;
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
                    TierState srcTier = te.getValue();
                    // Re-resolve ofport on bootstrap in case OVS assigned a
                    // different id after openvswitch-switch restart.
                    if (vm.repOfPort == null && StringUtils.isNotBlank(vm.repName)) {
                        vm.repOfPort = resolveOfPort(vm.repName);
                    }
                    if (installL3Route(srcTier.foldedTag, srcTier.gatewayMac,
                            vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac, vm.repOfPort)) {
                        reRoute++;
                    }
                    // When ACL was active before the restart, the table-2
                    // mirror is part of the persisted contract — replay it
                    // here rather than waiting for the next plug event.
                    maybeInstallRouteT2(vpc, srcTier.foldedTag, srcTier.gatewayMac,
                            vm.ip, vm.mac, dstTier.foldedTag, dstTier.gatewayMac);
                }
            }
            // Re-apply ACL pipeline state after routes so triggers fire into
            // a fully-populated table 2. Table-1 content is wiped + reinstalled
            // by installAclRule's cookie contract.
            if (isAclActive(vpc)) {
                // Wipe any stale ACL flows that may have survived a crash.
                delFlows(String.format("cookie=%s/-1,table=%d", DVR_COOKIE_ACL_RULE, TABLE_ACL),
                        "acl-rules table=" + TABLE_ACL + " (bootstrap)");
                for (AclRule r : vpc.aclRules) {
                    if (installAclRule(r)) {
                        reAcl++;
                    }
                }
                installAclDefaultDrop();
                reconcileAclEntryTriggers(vpc);
            }
        }
        LOGGER.info("DvrManager bootstrapFromState: re-applied arp={} routes={} aclRules={}",
                reArp, reRoute, reAcl);
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
