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
        final String netClause = (withdraw ? "no " : "") + "network " + publicIp + "/32";
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
