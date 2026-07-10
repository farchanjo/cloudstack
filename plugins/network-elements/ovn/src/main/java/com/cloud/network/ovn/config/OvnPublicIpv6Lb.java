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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.net.NetUtils;

/**
 * Pure parser for {@link OvnNetworkConfig#LrPublicIpv6Lb}
 * ({@code ovn.lr.public.ipv6.lb}). Turns the per-network map value into a list of
 * {@link Entry} records — one OVN {@code Load_Balancer} VIP per entry — so the
 * reconciler can program public IPv6 LBs on VPC Logical_Routers without touching
 * CloudStack {@code user_ip_address} / {@code createLoadBalancerRule}.
 *
 * <p>Value syntax:
 * {@code <network-uuid>=[vip]:<vport>-><be1>:<p>|<be2>:<p>|...;<network-uuid>=...}.
 * IPv6 VIP and backend addresses <strong>must</strong> use brackets when a port
 * is present (colon ambiguity with IPv6). Example:
 * {@code a4226ad6-...=[2a13:8740:0:7::100]:80->[2a13:8740:0:a::14]:80|[2a13:8740:0:a::15]:80}.
 *
 * <p>Validation is family-strict and deterministic: VIP and every backend must be
 * valid IPv6 (IPv4 and cross-family are rejected). Malformed entries are logged
 * at WARN and skipped; blank/null input yields an empty list (feature disabled).
 * Insertion order is preserved for deterministic logging. Backend-inside-the-
 * tier-{@code ip6Cidr} membership is a network-specific check the caller
 * performs (it needs the live network CIDR), not this pure parser.
 */
public final class OvnPublicIpv6Lb {

    private static final Logger LOGGER = LogManager.getLogger(OvnPublicIpv6Lb.class);

    /** Separator between per-entry specs in the ConfigKey value. */
    private static final String ENTRY_SEP = ";";

    /** Separator between a network UUID and its LB spec. */
    private static final String KV_SEP = "=";

    /** Separator between the VIP side and the backend list. */
    private static final String ARROW = "->";

    /** Separator between backends within one entry. */
    private static final String BE_SEP = "|";

    private OvnPublicIpv6Lb() {
        // Static utility — no instances.
    }

    /**
     * Parse the {@code ovn.lr.public.ipv6.lb} value into a list of entries.
     * Malformed entries are logged at WARN and skipped; a blank/null input
     * yields an empty list (feature disabled).
     *
     * @param cfg raw ConfigKey value
     * @return ordered list of well-formed entries, never {@code null}
     */
    public static List<Entry> parse(final String cfg) {
        final List<Entry> out = new ArrayList<>();
        if (StringUtils.isBlank(cfg)) {
            return out;
        }
        for (final String rawEntry : cfg.split(ENTRY_SEP)) {
            final Entry entry = parseEntry(rawEntry);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    private static Entry parseEntry(final String rawEntry) {
        final String entry = StringUtils.trimToEmpty(rawEntry);
        if (entry.isEmpty()) {
            return null;
        }
        final int kv = entry.indexOf(KV_SEP);
        if (kv <= 0 || kv == entry.length() - 1) {
            LOGGER.warn("OvnPublicIpv6Lb: skipping malformed entry "
                    + "(expected '<uuid>=[vip]:port->[be]:port|...'): {}", entry);
            return null;
        }
        final String networkUuid = entry.substring(0, kv).trim();
        if (networkUuid.isEmpty()) {
            LOGGER.warn("OvnPublicIpv6Lb: skipping entry with blank uuid: {}", entry);
            return null;
        }
        return parseSpec(networkUuid, entry.substring(kv + 1));
    }

    private static Entry parseSpec(final String networkUuid, final String spec) {
        final int arrow = spec.indexOf(ARROW);
        if (arrow <= 0 || arrow >= spec.length() - ARROW.length()) {
            LOGGER.warn("OvnPublicIpv6Lb: skipping LB spec without '{}' or empty side: {}", ARROW, spec);
            return null;
        }
        final HostPort vip = parseHostPort(spec.substring(0, arrow).trim(), "VIP");
        if (vip == null || !isStrictIpv6(vip.host)) {
            LOGGER.warn("OvnPublicIpv6Lb: skipping entry with invalid IPv6 VIP side: {}", spec);
            return null;
        }
        final List<HostPort> backends = validBackends(spec.substring(arrow + ARROW.length()));
        if (backends.isEmpty()) {
            LOGGER.warn("OvnPublicIpv6Lb: skipping VIP {} — no valid IPv6 backend", vip);
            return null;
        }
        return new Entry(networkUuid, vip.host, vip.port, backends);
    }

    private static List<HostPort> validBackends(final String csv) {
        final LinkedHashSet<HostPort> hops = new LinkedHashSet<>();
        for (final String raw : StringUtils.split(csv, BE_SEP)) {
            final String token = StringUtils.trimToEmpty(raw);
            if (token.isEmpty()) {
                continue;
            }
            final HostPort be = parseHostPort(token, "backend");
            if (be == null || !isStrictIpv6(be.host)) {
                LOGGER.warn("OvnPublicIpv6Lb: ignoring malformed / non-IPv6 backend '{}'", token);
                continue;
            }
            hops.add(be);
        }
        return new ArrayList<>(hops);
    }

    /**
     * Parse {@code [addr]:port} (required for IPv6) or {@code addr:port}
     * (IPv4-shaped — accepted by the tokenizer so we can reject the family
     * cleanly). Port must be 1..65535.
     */
    static HostPort parseHostPort(final String raw, final String role) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        final String s = raw.trim();
        final String host;
        final String portStr;
        if (s.startsWith("[")) {
            final int close = s.indexOf(']');
            if (close <= 1) {
                LOGGER.warn("OvnPublicIpv6Lb: {} missing closing bracket: {}", role, s);
                return null;
            }
            host = s.substring(1, close).trim();
            if (close + 1 >= s.length() || s.charAt(close + 1) != ':') {
                LOGGER.warn("OvnPublicIpv6Lb: {} missing ':port' after bracket: {}", role, s);
                return null;
            }
            portStr = s.substring(close + 2).trim();
        } else {
            // Unbracketed form — only valid for IPv4-shaped tokens (rejected
            // later by the family check). IPv6 without brackets is ambiguous
            // and is rejected here when more than one colon is present.
            final int colon = s.lastIndexOf(':');
            if (colon <= 0 || colon == s.length() - 1) {
                LOGGER.warn("OvnPublicIpv6Lb: {} missing host:port: {}", role, s);
                return null;
            }
            if (s.indexOf(':') != colon) {
                // Multiple colons without brackets — refuse (IPv6 must be bracketed).
                LOGGER.warn("OvnPublicIpv6Lb: {} IPv6 address must be bracketed as [addr]:port: {}", role, s);
                return null;
            }
            host = s.substring(0, colon).trim();
            portStr = s.substring(colon + 1).trim();
        }
        final int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException nfe) {
            LOGGER.warn("OvnPublicIpv6Lb: {} has non-numeric port '{}': {}", role, portStr, s);
            return null;
        }
        if (port < 1 || port > 65535) {
            LOGGER.warn("OvnPublicIpv6Lb: {} port out of range {}: {}", role, port, s);
            return null;
        }
        if (host.isEmpty()) {
            return null;
        }
        return new HostPort(host, port);
    }

    /** True when {@code host} is a valid IPv6 literal and not IPv4. */
    private static boolean isStrictIpv6(final String host) {
        if (StringUtils.isBlank(host)) {
            return false;
        }
        if (NetUtils.isValidIp4(host)) {
            return false;
        }
        return NetUtils.isValidIp6(host);
    }

    /**
     * Stable entry key used as {@code external_ids:cs-pub6-lb} value so the
     * reconciler can diff add / update / remove without colliding when one
     * network owns multiple VIP:port pairs.
     */
    public static String entryKey(final String networkUuid, final String vip, final int vipPort) {
        return networkUuid + '|' + vip + '|' + vipPort;
    }

    /**
     * Format an OVN {@code Load_Balancer.vips} map key / backend token.
     * IPv6 addresses are bracketed; IPv4 stays unbracketed.
     */
    public static String formatVipKey(final String ip, final int port) {
        if (ip != null && ip.contains(":")) {
            return "[" + ip + "]:" + port;
        }
        return ip + ":" + port;
    }

    /** One backend hop: IPv6 address + L4 port. Value-comparable. */
    public static final class HostPort {
        private final String host;
        private final int port;

        public HostPort(final String host, final int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        /** OVN vips-map token for this hop. */
        public String toVipToken() {
            return formatVipKey(host, port);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof HostPort)) {
                return false;
            }
            final HostPort other = (HostPort) o;
            return port == other.port && Objects.equals(host, other.host);
        }

        @Override
        public int hashCode() {
            return 31 * host.hashCode() + port;
        }

        @Override
        public String toString() {
            return toVipToken();
        }
    }

    /**
     * One public IPv6 LB entry: owning network UUID, VIP+port, and backend
     * hops. Immutable and value-comparable so a resync can diff desired
     * against existing.
     */
    public static final class Entry {
        private final String networkUuid;
        private final String vip;
        private final int vipPort;
        private final List<HostPort> backends;

        public Entry(final String networkUuid, final String vip, final int vipPort,
                     final List<HostPort> backends) {
            this.networkUuid = networkUuid;
            this.vip = vip;
            this.vipPort = vipPort;
            this.backends = Collections.unmodifiableList(new ArrayList<>(backends));
        }

        public String getNetworkUuid() {
            return networkUuid;
        }

        public String getVip() {
            return vip;
        }

        public int getVipPort() {
            return vipPort;
        }

        public List<HostPort> getBackends() {
            return backends;
        }

        /** Stable marker value for {@code external_ids:cs-pub6-lb}. */
        public String entryKey() {
            return OvnPublicIpv6Lb.entryKey(networkUuid, vip, vipPort);
        }

        /** OVN {@code vips} map: one key ({@code [vip]:port}) -&gt; comma-joined backends. */
        public java.util.Map<String, String> toVipsMap() {
            final List<String> tokens = new ArrayList<>(backends.size());
            for (final HostPort be : backends) {
                tokens.add(be.toVipToken());
            }
            return Collections.singletonMap(formatVipKey(vip, vipPort), String.join(",", tokens));
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            final Entry other = (Entry) o;
            return vipPort == other.vipPort
                    && Objects.equals(networkUuid, other.networkUuid)
                    && Objects.equals(vip, other.vip)
                    && Objects.equals(backends, other.backends);
        }

        @Override
        public int hashCode() {
            return Objects.hash(networkUuid, vip, vipPort, backends);
        }

        @Override
        public String toString() {
            return networkUuid + '=' + formatVipKey(vip, vipPort) + "->" + backends;
        }
    }
}
