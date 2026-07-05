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
        if (!withdraw) {
            ensureAnchor(cmd.getAnchorCidr(), cmd.getVlan(), cmd.getNetworkGatewayIp());
        }

        final int prefixLen = resolvePrefixLen(cmd.getPrefixLength());
        final Answer routeErr = applyDatapathRoute(publicIp, cmd.getGatewayIp(), withdraw, cmd, asn, prefixLen);
        if (routeErr != null) {
            return routeErr;
        }

        final String netClause = (withdraw ? "no " : "") + "network " + publicIp + "/" + prefixLen;
        // Single vtysh -c chain so FRR sees configure -> router bgp -> network
        // as one transaction (matters when the running config is locked by
        // another caller; vtysh internally serialises -c chains).
        final String command = String.format(
                "%s -c \"configure terminal\" -c \"router bgp %d\" -c \"%s\"",
                vtysh, asn, netClause);
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
                    LOGGER.warn("OvnBgpAnnounce {} {}/32: vtysh stderr: {}", operation, publicIp, stderr);
                    return new OvnBgpAnnounceAnswer(cmd, false, stderr.trim(), asn);
                }
            }
            LOGGER.info("OvnBgpAnnounce {} {}/32: ok (asn={}, vtysh={})",
                    operation, publicIp, asn, vtysh);
            return new OvnBgpAnnounceAnswer(cmd, true, "ok", asn);
        } catch (RuntimeException re) {
            LOGGER.warn("OvnBgpAnnounce {} {}/32: vtysh invocation failed: {}",
                    operation, publicIp, re.getMessage());
            return new OvnBgpAnnounceAnswer(cmd, false, re.getMessage(), asn);
        }
    }

    /**
     * Install (announce) or remove (withdraw) the {@code <publicIp>/32} kernel
     * route that delivers inbound N-S traffic into OVN via the VPC public
     * LRP next-hop.
     *
     * <ul>
     *   <li>withdraw → {@code ip route del <ip>/32} (best-effort; a never-installed
     *       route or a stale one is not an error).</li>
     *   <li>announce with a non-blank {@code gatewayIp} → {@code ip route replace
     *       <ip>/32 via <gatewayIp>} (idempotent; the device is auto-resolved
     *       from the on-link gateway).</li>
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
                                      final Long asn, final int prefixLen) {
        if (withdraw) {
            // Best-effort delete by prefix; ignore "No such process" etc.
            Script.executeCommand(String.format("ip route del %s/%d", publicIp, prefixLen));
            return null;
        }
        if (gatewayIp == null || gatewayIp.isEmpty()) {
            return null; // advertise-only
        }
        final Pair<String, String> result =
                Script.executeCommand(String.format("ip route replace %s/%d via %s", publicIp, prefixLen, gatewayIp));
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
    private void ensureAnchor(final String anchorCidr, final String vlan, final String networkGatewayIp) {
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
        // the tag / `ip addr replace` steps are idempotent.
        Script.runSimpleBashScript(String.format(
                "ovs-vsctl --may-exist add-port %s %s -- set interface %s type=internal",
                bridge, ANCHOR_PORT, ANCHOR_PORT));
        // The OVN provider localnet is realized on a tagged VLAN — its ingress
        // is matched on `dl_vlan=<vlan>`. The anchor MUST therefore be an ACCESS
        // port on that VLAN, or host-originated frames (untagged) never match
        // the localnet ingress and are dropped, breaking egress-return and FIP
        // ingress. The tag is set explicitly (not only on --may-exist create)
        // so a pre-existing untagged anchor is corrected in place. Blank/invalid
        // vlan -> leave untagged (pre-fix behaviour, e.g. untagged providers).
        if (vlan != null && vlan.trim().matches("\\d+")) {
            Script.runSimpleBashScript(String.format(
                    "ovs-vsctl set port %s tag=%s", ANCHOR_PORT, vlan.trim()));
        }
        Script.runSimpleBashScript("ip link set " + ANCHOR_PORT + " up");
        final Pair<String, String> res = Script.executeCommand(
                String.format("ip addr replace %s dev %s", anchorCidr, ANCHOR_PORT));
        final String stderr = res == null ? null : res.second();
        if (stderr != null && !stderr.trim().isEmpty()) {
            LOGGER.warn("OvnBgpAnnounce: anchor {} on {} (port {}) — ip addr replace stderr: {}",
                    anchorCidr, bridge, ANCHOR_PORT, stderr.trim());
        } else {
            LOGGER.info("OvnBgpAnnounce: anchor {} ensured on {} (port {}, vlan {})",
                    anchorCidr, bridge, ANCHOR_PORT, vlan);
        }
        // Hold the public network gateway IP on the anchor so the VPC LR's
        // egress next-hop is answered locally — in the BGP-to-host model there
        // is no physical gateway device on that address — and enable IPv4
        // forwarding so egress traffic landing on the host is routed upstream
        // (the FIP /32s are BGP-originated from here). Idempotent.
        if (networkGatewayIp != null && !networkGatewayIp.trim().isEmpty()) {
            Script.executeCommand(String.format(
                    "ip addr replace %s/32 dev %s", networkGatewayIp.trim(), ANCHOR_PORT));
            Script.runSimpleBashScript("sysctl -w net.ipv4.ip_forward=1");
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

    /** Resolve the advertised prefix length: the command value when a sane
     *  1..32 is supplied, else the legacy {@code /32} host-route default. */
    private int resolvePrefixLen(final Integer fromCommand) {
        if (fromCommand != null && fromCommand > 0 && fromCommand <= 32) {
            return fromCommand;
        }
        return 32;
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
