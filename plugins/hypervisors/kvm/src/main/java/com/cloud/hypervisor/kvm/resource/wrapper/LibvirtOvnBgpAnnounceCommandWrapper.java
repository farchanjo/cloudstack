//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.OvnBgpAnnounceAnswer;
import com.cloud.agent.api.OvnBgpAnnounceCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;

/**
 * Announces / withdraws a {@code /32} host route through the local FRR daemon
 * by calling host-side {@code vtysh}. Host-local effect: writes the
 * {@code network <ip>/32} (or {@code no network ...}) line into the running
 * BGP config of {@code router bgp <asn>}.
 *
 * <p>The wrapper is opt-in (gated by management server config
 * {@code ovn.bgp.redistribute.public_ips}) and has no effect on the OVS /
 * OVN datapath. It is the host-side half of the OVN plugin's
 * {@code OvnBgpRedistributeManager} — invoked only on the gateway-chassis
 * node currently hosting the VPC's distributed-gateway LRP.
 *
 * <p>Wire-compat: agents predating this wrapper return
 * {@code Unsupported command}; the management caller logs the warning and
 * skips the announce — N-S inbound traffic falls back to the prior
 * ECMP-without-/32 behaviour described in the operator runbook.
 */
@ResourceWrapper(handles = OvnBgpAnnounceCommand.class)
public final class LibvirtOvnBgpAnnounceCommandWrapper extends
        CommandWrapper<OvnBgpAnnounceCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOGGER = LogManager.getLogger(LibvirtOvnBgpAnnounceCommandWrapper.class);

    private static final String DEFAULT_VTYSH = "/usr/bin/vtysh";

    /** Dedicated OVS internal port that carries the on-link datapath anchor IP
     *  on the provider-localnet bridge of the gateway chassis. */
    private static final String ANCHOR_PORT = "pub-anchor";

    @Override
    public Answer execute(final OvnBgpAnnounceCommand cmd, final LibvirtComputingResource resource) {
        final String publicIp = cmd.getPublicIp();
        final String operation = cmd.getOperation();
        if (publicIp == null || publicIp.isEmpty() || operation == null || operation.isEmpty()) {
            return new OvnBgpAnnounceAnswer(cmd, false, "missing publicIp or operation");
        }
        final String vtysh = pickVtysh(cmd.getVtyshPath());
        final boolean withdraw = OvnBgpAnnounceCommand.OP_WITHDRAW.equalsIgnoreCase(operation);
        if (!withdraw && !OvnBgpAnnounceCommand.OP_ANNOUNCE.equalsIgnoreCase(operation)) {
            return new OvnBgpAnnounceAnswer(cmd, false, "unknown operation: " + operation);
        }
        final Long asn = resolveAsn(vtysh, cmd.getAsn());
        if (asn == null) {
            LOGGER.warn("OvnBgpAnnounce: could not resolve FRR BGP ASN (vtysh={}); skipping {} {}",
                    vtysh, operation, publicIp);
            return new OvnBgpAnnounceAnswer(cmd, false,
                    "ASN auto-detect failed; configure ovn.bgp.frr.asn explicitly");
        }

        // Datapath half: steer the /32 INTO OVN on the gateway chassis. The
        // next-hop is the VPC's OVN LR public-port IP — its MAC answers ARP on
        // the provider localnet (reachable once the chassis carries an anchor
        // IP in the public segment), so ingress lands on the LR port and OVN
        // performs the DNAT. Installing the kernel route ALSO seeds zebra's RIB
        // so the `network <ip>/32` below passes import-check and is truly
        // originated (it was inert before — advertise-only, never forwarded).
        // Anchor half: on announce, ensure the derived on-link anchor IP exists
        // on a dedicated pub-anchor OVS internal port of the provider-localnet
        // bridge, so the LRP next-hop (gatewayIp) is ARP-resolvable and the /32
        // route below can actually install. Non-fatal + idempotent; skipped on
        // withdraw (the anchor is chassis-level, shared across all FIPs).
        // PARSEL-V6 — the same command carries either an IPv4 or an IPv6
        // network; the address family is sniffed from the address literal (a
        // ':' can only appear in an IPv6 address). The v6 path uses `ip -6`,
        // holds the anchor with `ip -6 addr`, and writes the BGP `network` into
        // the `address-family ipv6 unicast` block instead of the default v4 AF.
        final boolean ipv6 = isIpv6(publicIp);
        if (!withdraw) {
            ensureAnchor(cmd.getAnchorCidr(), cmd.getVlan(), cmd.getNetworkGatewayIp(), ipv6);
        }

        final int prefixLen = resolvePrefixLen(cmd.getPrefixLength(), ipv6);
        final Answer routeErr = applyDatapathRoute(publicIp, cmd.getGatewayIp(), withdraw, cmd, asn, prefixLen, ipv6);
        if (routeErr != null) {
            return routeErr;
        }

        final String netClause = (withdraw ? "no " : "") + "network " + publicIp + "/" + prefixLen;
        // Single vtysh -c chain so FRR sees configure -> router bgp -> [af] ->
        // network as one transaction (matters when the running config is locked
        // by another caller; vtysh internally serialises -c chains).
        final String command = buildVtyshNetworkCommand(vtysh, asn, netClause, ipv6);
        try {
            final Pair<String, String> result = Script.executeCommand(command);
            // Script.executeCommand surfaces the exit code via stderr presence
            // (non-null stderr from script.execute = non-zero exit). vtysh
            // returns 0 on success even when "no network" matches no row, so
            // a successful exit-code check is enough.
            final String stderr = result == null ? null : result.second();
            if (stderr != null && !stderr.trim().isEmpty()) {
                // vtysh may emit informational lines on stderr even when the
                // config write succeeded (e.g. "BGP: Inserting route into RIB").
                // Inspect for hard-fail markers; everything else passes through.
                final String lower = stderr.toLowerCase();
                if (lower.contains("error") || lower.contains("fail") || lower.contains("can't")
                        || lower.contains("invalid")) {
                    LOGGER.warn("OvnBgpAnnounce {} {}/{}: vtysh stderr: {}", operation, publicIp, prefixLen, stderr);
                    return new OvnBgpAnnounceAnswer(cmd, false, stderr.trim(), asn);
                }
            }
            LOGGER.info("OvnBgpAnnounce {} {}/{}: ok (asn={}, af={}, vtysh={})",
                    operation, publicIp, prefixLen, ipv6 ? "ipv6" : "ipv4", vtysh);
            return new OvnBgpAnnounceAnswer(cmd, true, "ok", asn);
        } catch (RuntimeException re) {
            LOGGER.warn("OvnBgpAnnounce {} {}/{}: vtysh invocation failed: {}",
                    operation, publicIp, prefixLen, re.getMessage());
            return new OvnBgpAnnounceAnswer(cmd, false, re.getMessage(), asn);
        }
    }

    /** {@code true} when the address literal is IPv6 (a {@code ':'} can only
     *  appear in an IPv6 address, never in a dotted-quad IPv4). */
    private static boolean isIpv6(final String addr) {
        return addr != null && addr.indexOf(':') >= 0;
    }

    /** Build the vtysh {@code -c} chain, injecting {@code address-family ipv6
     *  unicast} before the {@code network} clause for the v6 path so the prefix
     *  originates in the IPv6 unicast AF (never the default v4 AF). */
    private String buildVtyshNetworkCommand(final String vtysh, final long asn, final String netClause,
                                            final boolean ipv6) {
        if (ipv6) {
            return String.format(
                    "%s -c \"configure terminal\" -c \"router bgp %d\" -c \"address-family ipv6 unicast\" -c \"%s\"",
                    vtysh, asn, netClause);
        }
        return String.format(
                "%s -c \"configure terminal\" -c \"router bgp %d\" -c \"%s\"",
                vtysh, asn, netClause);
    }

    /**
     * Install (announce) the {@code <publicIp>/<prefix>} kernel route that
     * steers inbound N-S traffic into OVN via the VPC public LRP next-hop.
     *
     * <p><b>Ownership split (RCA 2026-07-18 Snape canary):</b>
     * <ul>
     *   <li><b>BGP advertisement lifecycle</b> ({@code network} / {@code no network}
     *       in FRR) is owned by this wrapper on announce/withdraw. CT cutover
     *       (DSR {@code withdrawCtLbBgpDualStack}) may withdraw the control-plane
     *       host advertisement.</li>
     *   <li><b>Kernel transport host route</b> ({@code via <pub-LRP> dev pub-anchor})
     *       is fleet-owned by config-mgmt {@code public_vip_host_routes} and must
     *       remain continuously installed for OVN LR DSR recursive resolution.
     *       Withdraw must <em>never</em> {@code ip route del} / {@code ip -6 route del}
     *       that prefix — deleting it lets the covering {@code 2a13:8740::/48}
     *       blackhole win and blackholes VIP N-S (observed: {@code ::101/128}
     *       flapped on worker HVs after {@code ctWithdrawn=1} while v4 stayed
     *       because v4 announce bookkeeping hit fewer hosts).</li>
     * </ul>
     *
     * <ul>
     *   <li>withdraw → <b>preserve</b> kernel transport route; only BGP
     *       {@code no network} is applied by the caller.</li>
     *   <li>announce with a non-blank {@code gatewayIp} → {@code ip route replace
     *       <ip>/<len> via <gatewayIp>} (idempotent bootstrap; config-mgmt
     *       re-converges the same prefix if an external actor deletes it).</li>
     *   <li>announce with a blank {@code gatewayIp} → no route (advertise-only
     *       fall-back for pre-datapath managers / unbound VPCs).</li>
     * </ul>
     *
     * <p>A failed route install is NON-fatal: it is logged and the caller still
     * proceeds to the BGP {@code network} advertisement. Rationale — the two
     * halves deploy independently; if the provider-side anchor (the on-link
     * {@code gatewayIp}) is not present yet the route cannot resolve, but
     * dropping the BGP advertise too would REGRESS the announce below its
     * pre-datapath (advertise-only) behaviour. Degrading to advertise-only (a
     * loud warning, forwarding restored once the anchor lands) is strictly
     * safer than failing the whole announce.
     *
     * @return always {@code null}; the caller always proceeds to the vtysh
     *         network write. Kept as an {@link Answer} return for call-site
     *         symmetry / future hard-fail cases.
     */
    private Answer applyDatapathRoute(final String publicIp, final String gatewayIp,
                                      final boolean withdraw, final OvnBgpAnnounceCommand cmd,
                                      final Long asn, final int prefixLen, final boolean ipv6) {
        // `ip -6` for the v6 family; the on-link next-hop is the VPC public LRP.
        final String ipTool = ipv6 ? "ip -6 route" : "ip route";
        if (withdraw) {
            // BGP-only withdraw: do NOT delete the kernel transport host route.
            // config-mgmt public_vip_host_routes + public-vip-transport-routes
            // own continuous install for OVN LR DSR. LRP neighbour is chassis-
            // shared and must also stay.
            LOGGER.info("OvnBgpAnnounce: withdraw preserves kernel transport {}/{} "
                    + "(BGP no-network only; transport owned by config-mgmt)",
                    publicIp, prefixLen);
            return null;
        }
        if (gatewayIp == null || gatewayIp.isEmpty()) {
            return null; // advertise-only
        }
        // Pin the LRP next-hop L2 address on pub-anchor. Relying on live ARP/NDP
        // is flaky when multiple CR-LRPs share the localnet (live: snape ::32
        // stayed INCOMPLETE while salazar ::34 worked — guest→RR v6 blackholed).
        ensureGatewayNeighbour(gatewayIp, cmd.getGatewayMac(), ipv6);
        final Pair<String, String> result =
                Script.executeCommand(String.format("%s replace %s/%d via %s", ipTool, publicIp, prefixLen, gatewayIp));
        final String stderr = result == null ? null : result.second();
        if (stderr != null && !stderr.trim().isEmpty()) {
            // Non-fatal: log + fall through to advertise-only (see javadoc).
            // Typically "Network is unreachable" until the provider anchor
            // (gatewayIp on-link on the gateway chassis) is provisioned.
            LOGGER.warn("OvnBgpAnnounce: /32 datapath route {} via {} failed ({}); "
                    + "advertising BGP anyway (advertise-only until the provider anchor is present)",
                    publicIp, gatewayIp, stderr.trim());
        }
        return null;
    }

    /**
     * Install a permanent neighbour for the OVN public LRP next-hop on
     * {@link #ANCHOR_PORT}. Non-fatal and skipped when {@code gatewayMac} is blank
     * (older managers / wire-compat). Idempotent.
     */
    private void ensureGatewayNeighbour(final String gatewayIp, final String gatewayMac, final boolean ipv6) {
        if (gatewayIp == null || gatewayIp.isEmpty()
                || gatewayMac == null || gatewayMac.trim().isEmpty()) {
            return;
        }
        final String mac = gatewayMac.trim();
        // Loose MAC sanity — avoid shell injection; OVN MACs are colon-hex.
        if (!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            LOGGER.warn("OvnBgpAnnounce: refusing neighbour install for {} — bad MAC '{}'", gatewayIp, mac);
            return;
        }
        final String neighTool = ipv6 ? "ip -6 neigh" : "ip neigh";
        final Pair<String, String> result = Script.executeCommand(String.format(
                "%s replace %s lladdr %s dev %s nud permanent",
                neighTool, gatewayIp, mac, ANCHOR_PORT));
        final String stderr = result == null ? null : result.second();
        if (stderr != null && !stderr.trim().isEmpty()) {
            LOGGER.warn("OvnBgpAnnounce: neighbour {} lladdr {} on {} failed ({})",
                    gatewayIp, mac, ANCHOR_PORT, stderr.trim());
        } else {
            LOGGER.info("OvnBgpAnnounce: neighbour {} lladdr {} on {} (permanent, af={})",
                    gatewayIp, mac, ANCHOR_PORT, ipv6 ? "ipv6" : "ipv4");
        }
    }

    /**
     * Ensure the single on-link datapath anchor address exists on a dedicated
     * {@code pub-anchor} OVS internal port of the provider-localnet bridge, so
     * the VPC public LRP next-hop is ARP-resolvable on the localnet and the
     * {@code /32} route can be installed. Idempotent and NON-fatal: any failure
     * is logged and the caller still proceeds (degrading to advertise-/route-
     * only). No-op when {@code anchorCidr} is blank (anchor feature disabled or
     * not derivable on the management side) or the provider bridge cannot be
     * discovered.
     *
     * <p>The bridge is DISCOVERED from {@code ovn-bridge-mappings} (never
     * hardcoded); the anchor address is supplied by the management server,
     * itself derived from CloudStack's public IP range (never hardcoded).
     */
    private void ensureAnchor(final String anchorCidr, final String vlan, final String networkGatewayIp,
                              final boolean ipv6) {
        if (anchorCidr == null || anchorCidr.trim().isEmpty()) {
            return;
        }
        final String bridge = detectLocalnetBridge();
        if (bridge == null) {
            LOGGER.warn("OvnBgpAnnounce: cannot discover provider-localnet bridge from "
                    + "ovn-bridge-mappings; skipping anchor {} (advertise-/route-only)", anchorCidr);
            return;
        }
        // Create the internal port if missing, then (re)assert its VLAN and
        // addresses. `--may-exist` makes add-port a no-op when the port exists;
        // the tag / `ip addr replace` steps are idempotent. The SAME pub-anchor
        // port carries both the v4 and the v6 anchor addresses (dual-stack) — v6
        // only ADDS its addresses, never disturbing the v4 anchor.
        Script.runSimpleBashScript(String.format(
                "ovs-vsctl --may-exist add-port %s %s -- set interface %s type=internal",
                bridge, ANCHOR_PORT, ANCHOR_PORT));
        // The OVN provider localnet is realized on a tagged VLAN — its ingress
        // is matched on `dl_vlan=<vlan>`. The anchor MUST therefore be an ACCESS
        // port on that VLAN, or host-originated frames (untagged) never match
        // the localnet ingress and are dropped, breaking egress-return and FIP
        // ingress. The tag is set explicitly (not only on --may-exist create)
        // so a pre-existing untagged anchor is corrected in place. Blank/invalid
        // vlan -> leave untagged (pre-fix behaviour, e.g. untagged providers /
        // the routed v6 public /64 which carries no VLAN, exactly like v4 .89/24).
        if (vlan != null && vlan.trim().matches("\\d+")) {
            Script.runSimpleBashScript(String.format(
                    "ovs-vsctl set port %s tag=%s", ANCHOR_PORT, vlan.trim()));
        }
        Script.runSimpleBashScript("ip link set " + ANCHOR_PORT + " up");
        final String addrTool = ipv6 ? "ip -6 addr" : "ip addr";
        // IPv6 anchors are intentionally identical on every gateway chassis
        // (shared on-link foot for the public localnet). Without nodad, Linux
        // DAD marks them dadfailed/tentative when a peer HV already holds the
        // same GUA — the address never becomes usable. preferred_lft 0 keeps the
        // GUA off the host's source-address selection (avoids hairpin/OVN
        // breakage when the shared ::1 is chosen as src). v4 has no DAD.
        final String v6AddrFlags = ipv6 ? " nodad preferred_lft 0" : "";
        final Pair<String, String> res = Script.executeCommand(
                String.format("%s replace %s dev %s%s", addrTool, anchorCidr, ANCHOR_PORT, v6AddrFlags));
        final String stderr = res == null ? null : res.second();
        if (stderr != null && !stderr.trim().isEmpty()) {
            LOGGER.warn("OvnBgpAnnounce: anchor {} on {} (port {}, af={}) — ip addr replace stderr: {}",
                    anchorCidr, bridge, ANCHOR_PORT, ipv6 ? "ipv6" : "ipv4", stderr.trim());
        } else {
            LOGGER.info("OvnBgpAnnounce: anchor {} ensured on {} (port {}, vlan {}, af={})",
                    anchorCidr, bridge, ANCHOR_PORT, vlan, ipv6 ? "ipv6" : "ipv4");
        }
        // Hold the public network gateway IP on the anchor so the VPC LR's
        // egress next-hop is answered locally — in the BGP-to-host model there
        // is no physical gateway device on that address — and enable forwarding
        // for the matching family so traffic landing on the host is routed
        // upstream (the routed prefixes are BGP-originated from here). Idempotent.
        if (networkGatewayIp != null && !networkGatewayIp.trim().isEmpty()) {
            final String hostMask = ipv6 ? "/128" : "/32";
            final String fwdKey = ipv6 ? "net.ipv6.conf.all.forwarding" : "net.ipv4.ip_forward";
            Script.executeCommand(String.format(
                    "%s replace %s%s dev %s%s", addrTool, networkGatewayIp.trim(), hostMask, ANCHOR_PORT, v6AddrFlags));
            Script.runSimpleBashScript("sysctl -w " + fwdKey + "=1");
        }
    }

    /**
     * Discover the OVS bridge carrying the OVN provider localnet, from
     * {@code ovs-vsctl get open_vswitch . external_ids:ovn-bridge-mappings}
     * (value {@code "physnet:bridge[,physnet:bridge...]"}). A single mapping is
     * used directly; with multiple mappings the first is used and a warning is
     * logged (the deployment carries one provider mapping per KVM host).
     * Returns {@code null} when the mapping is absent or empty.
     */
    private String detectLocalnetBridge() {
        String raw = Script.runSimpleBashScript(
                "ovs-vsctl --if-exists get open_vswitch . external_ids:ovn-bridge-mappings");
        if (raw == null) {
            return null;
        }
        raw = raw.trim().replaceAll("^\"|\"$", "");   // strip ovs quoting
        if (raw.isEmpty()) {
            return null;
        }
        final String[] mappings = raw.split(",");
        if (mappings.length > 1) {
            LOGGER.warn("OvnBgpAnnounce: multiple ovn-bridge-mappings [{}]; using the first for the "
                    + "anchor port", raw);
        }
        final String[] pair = mappings[0].split(":");
        return pair.length == 2 ? pair[1].trim() : null;
    }

    /** Resolve the advertised prefix length: the command value when in the sane
     *  family range ({@code 1..32} v4, {@code 1..128} v6), else the host-route
     *  default ({@code /32} v4, {@code /128} v6). */
    private int resolvePrefixLen(final Integer fromCommand, final boolean ipv6) {
        final int max = ipv6 ? 128 : 32;
        if (fromCommand != null && fromCommand > 0 && fromCommand <= max) {
            return fromCommand;
        }
        return max;
    }

    private String pickVtysh(final String fromCommand) {
        if (fromCommand != null && !fromCommand.isEmpty()) {
            return fromCommand;
        }
        return DEFAULT_VTYSH;
    }

    /**
     * Resolve the BGP ASN: prefer the value carried by the command (operator
     * forced via ConfigKey); fall back to scraping FRR's running config.
     *
     * <p>FRR emits two summary line shapes depending on version:
     * <ul>
     *   <li>{@code BGP router identifier ..., local AS number 24452 VRF default vrf-id 0}
     *       — modern FRR (10.x).</li>
     *   <li>{@code BGP router identifier ..., local AS 24452 ...} — older
     *       FRR.</li>
     * </ul>
     * The regex captures the first integer that follows {@code "local AS"}
     * (optionally with the {@code "number"} keyword in between), tolerating
     * trailing tokens like {@code VRF default vrf-id 0}.
     */
    private Long resolveAsn(final String vtysh, final Long fromCommand) {
        if (fromCommand != null && fromCommand.longValue() > 0) {
            return fromCommand;
        }
        final String probe = String.format(
                "%s -c 'show ip bgp summary' 2>/dev/null | grep -i 'local AS' | head -1",
                vtysh);
        try {
            final String line = Script.runSimpleBashScript(probe);
            if (line == null) {
                return null;
            }
            // Match `local AS [number] <integer>` — case-insensitive, optional
            // `number` keyword between AS and the digits.
            final java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                    "(?i)local\\s+AS(?:\\s+number)?\\s+(\\d+)");
            final java.util.regex.Matcher m = pat.matcher(line);
            if (m.find()) {
                return parseAsn(m.group(1));
            }
            // Last-ditch fallback: scan tokens for any integer after the
            // first AS-keyword. Skips known non-ASN words like `number`,
            // `default`, `vrf-id`, etc. so we do not pick up the VRF id 0.
            final String[] tokens = line.split("\\s+");
            boolean afterAs = false;
            for (final String tok : tokens) {
                if (!afterAs) {
                    if ("as".equalsIgnoreCase(tok)) {
                        afterAs = true;
                    }
                    continue;
                }
                final Long asn = parseAsn(tok);
                if (asn != null && asn > 0) {
                    return asn;
                }
            }
        } catch (RuntimeException re) {
            LOGGER.debug("OvnBgpAnnounce: ASN auto-detect failed: {}", re.getMessage());
        }
        return null;
    }

    private static Long parseAsn(final String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.replaceAll("[^0-9]", "");
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            // 4-byte private ASNs (4200000000-4294967294) overflow Integer;
            // the whole chain is Long for that reason.
            final long value = Long.parseLong(trimmed);
            return value > 0 ? value : null;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }
}
