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
package com.cloud.network.ovn.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.net.NetUtils;

/**
 * Pure composition helpers for an OVN {@code Logical_Switch_Port}'s
 * {@code addresses} / {@code port_security} tokens, including the per-network
 * extra-CIDR feature governed by
 * {@link OvnNetworkConfig#LspExtraPortSecurityCidrs}.
 *
 * <p>OVN encodes a guest NIC's port-security as a single set element of the
 * shape {@code "<mac> <ip4> <ip6> [cidr...]"}. The L3 spoof guard drops
 * from-lport packets whose source is outside that list and to-lport packets
 * whose destination is outside it. Appending extra CIDRs lets a tier natively
 * carry raw pod IPs (Calico {@code ipipMode: Never}), LB VIPs, and dual-stack
 * pod v6 without recreating VMs.
 *
 * <p>All methods are static and side-effect free except
 * {@link #extraCidrsForNetwork(String)}, which reads the global ConfigKey.
 * {@link #parse(String)} and {@link #compose(String, String, String, List)}
 * are fully deterministic so a resync re-applies idempotently.
 */
public final class OvnLspAddresses {

    private static final Logger LOGGER = LogManager.getLogger(OvnLspAddresses.class);

    /** Separator between per-network entries in the ConfigKey value. */
    private static final String ENTRY_SEP = ";";

    /** Separator between a network UUID and its CIDR list. */
    private static final String KV_SEP = "=";

    /** Separator between CIDRs within one entry. */
    private static final String CIDR_SEP = ",";

    private OvnLspAddresses() {
        // Static utility — no instances.
    }

    /**
     * Parse the {@code ovn.lsp.extra.port.security.cidrs} value into a
     * {@code network-uuid -> [validated CIDRs]} map. Malformed CIDRs and
     * entries without a UUID or any valid CIDR are logged at WARN and skipped;
     * a blank/null input yields an empty map (feature disabled). Insertion
     * order is preserved for deterministic logging.
     *
     * @param cfg raw ConfigKey value ({@code <uuid>=cidr,cidr;<uuid>=...})
     * @return per-network CIDR map, never {@code null}
     */
    public static Map<String, List<String>> parse(final String cfg) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        if (StringUtils.isBlank(cfg)) {
            return out;
        }
        for (final String rawEntry : cfg.split(ENTRY_SEP)) {
            parseEntry(rawEntry, out);
        }
        return out;
    }

    private static void parseEntry(final String rawEntry, final Map<String, List<String>> out) {
        final String entry = StringUtils.trimToEmpty(rawEntry);
        if (entry.isEmpty()) {
            return;
        }
        final int kv = entry.indexOf(KV_SEP);
        if (kv <= 0 || kv == entry.length() - 1) {
            LOGGER.warn("OvnLspAddresses: skipping malformed entry (expected '<uuid>=cidr,...'): {}", entry);
            return;
        }
        final String networkUuid = entry.substring(0, kv).trim();
        final List<String> cidrs = validCidrs(entry.substring(kv + 1));
        if (networkUuid.isEmpty() || cidrs.isEmpty()) {
            LOGGER.warn("OvnLspAddresses: skipping entry with blank uuid or no valid CIDRs: {}", entry);
            return;
        }
        out.put(networkUuid, cidrs);
    }

    private static List<String> validCidrs(final String csv) {
        final List<String> cidrs = new ArrayList<>();
        for (final String rawCidr : csv.split(CIDR_SEP)) {
            final String cidr = StringUtils.trimToEmpty(rawCidr);
            if (cidr.isEmpty()) {
                continue;
            }
            if (NetUtils.isValidIp4Cidr(cidr) || NetUtils.isValidIp6Cidr(cidr)) {
                cidrs.add(cidr);
            } else {
                LOGGER.warn("OvnLspAddresses: ignoring malformed CIDR '{}'", cidr);
            }
        }
        return cidrs;
    }

    /**
     * Build the single-element OVN address list for a NIC, appending any extra
     * CIDRs. With a {@code null}/empty {@code extraCidrs} the result is EXACTLY
     * the legacy token {@code "<mac> [<ip4>] [<ip6>]"} — zero regression.
     *
     * @param mac        NIC MAC address (required)
     * @param v4         IPv4 address or blank
     * @param v6         IPv6 address or blank
     * @param extraCidrs extra CIDRs to append, may be {@code null}/empty
     * @return one-element list holding the composed token
     */
    public static List<String> compose(final String mac, final String v4, final String v6,
                                       final List<String> extraCidrs) {
        final StringBuilder token = new StringBuilder(StringUtils.trimToEmpty(mac));
        if (StringUtils.isNotBlank(v4)) {
            token.append(' ').append(v4.trim());
        }
        if (StringUtils.isNotBlank(v6)) {
            token.append(' ').append(v6.trim());
        }
        if (extraCidrs != null) {
            for (final String cidr : extraCidrs) {
                if (StringUtils.isNotBlank(cidr)) {
                    token.append(' ').append(cidr.trim());
                }
            }
        }
        final List<String> out = new ArrayList<>(1);
        out.add(token.toString());
        return out;
    }

    /**
     * Resolve the configured extra CIDRs for a single network UUID by reading
     * the global ConfigKey. Returns an empty (immutable) list when the feature
     * is off or the network has no configured extras.
     *
     * @param networkUuid CloudStack network UUID
     * @return extra CIDRs for the network, never {@code null}
     */
    public static List<String> extraCidrsForNetwork(final String networkUuid) {
        if (StringUtils.isBlank(networkUuid)) {
            return Collections.emptyList();
        }
        return parse(OvnNetworkConfig.LspExtraPortSecurityCidrs.value())
                .getOrDefault(networkUuid, Collections.emptyList());
    }
}
