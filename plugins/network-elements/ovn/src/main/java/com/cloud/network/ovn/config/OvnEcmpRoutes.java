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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.net.NetUtils;

/**
 * Pure parser for {@link OvnNetworkConfig#LrEcmpStaticRoutes}
 * ({@code ovn.lr.ecmp.static.routes}). Turns the per-network map value into a
 * {@code network-uuid -> List&lt;Route&gt;} structure, where each {@link Route}
 * is a single destination prefix plus the list of ECMP next-hops that should
 * back it on the owning VPC {@code Logical_Router}.
 *
 * <p>Value syntax:
 * {@code <network-uuid>=<prefix>-><nh1>|<nh2>;...}. The {@code ;} separates
 * stanzas (per-network entries), {@code =} splits the network UUID from its
 * route spec, {@code ->} splits the destination prefix from the next-hop list,
 * and {@code |} separates next-hops. Multiple stanzas may reuse the same
 * network UUID so dual-stack can declare an IPv4 and an IPv6 prefix
 * independently, e.g.
 * {@code a4226ad6-...=10.140.0.0/24->10.45.0.14|10.45.0.159;a4226ad6-...=fd00:cafe:2::/108->2a13:8740:0:a::5}.
 * A single stanza remains valid and yields a list of length 1.
 *
 * <p>When the same UUID appears more than once:
 * <ul>
 *   <li>same prefix — next-hops are merged (order-stable {@link LinkedHashSet});</li>
 *   <li>different prefixes — each becomes its own {@link Route} in the list.</li>
 * </ul>
 *
 * <p>Validation is IP-shape only and deterministic (so a resync re-parses
 * identically): the prefix must be a valid IPv4 or IPv6 CIDR and each next-hop a
 * valid IPv4 or IPv6 address. Malformed prefixes drop that stanza; malformed
 * next-hops are dropped individually; a stanza left with no valid next-hop is
 * dropped. Next-hop-inside-the-network-CIDR is a network-specific check the
 * caller performs (it needs the live network CIDR(s)), not this pure parser. A
 * blank/null input yields an empty map (feature disabled). Insertion order is
 * preserved for deterministic logging.
 */
public final class OvnEcmpRoutes {

    private static final Logger LOGGER = LogManager.getLogger(OvnEcmpRoutes.class);

    /** Separator between per-network entries in the ConfigKey value. */
    private static final String ENTRY_SEP = ";";

    /** Separator between a network UUID and its route spec. */
    private static final String KV_SEP = "=";

    /** Separator between the destination prefix and the next-hop list. */
    private static final String ARROW = "->";

    /** Separator between next-hops within one entry. */
    private static final String NH_SEP = "|";

    private OvnEcmpRoutes() {
        // Static utility — no instances.
    }

    /**
     * Parse the {@code ovn.lr.ecmp.static.routes} value into a
     * {@code network-uuid -> List&lt;Route&gt;} map. Multi-stanza entries for the
     * same UUID accumulate into one list (same-prefix next-hops merge;
     * different prefixes append). Malformed stanzas are logged at WARN and
     * skipped; a blank/null input yields an empty map (feature disabled).
     *
     * @param cfg raw ConfigKey value
     * @return per-network ECMP route lists, never {@code null}
     */
    public static Map<String, List<Route>> parse(final String cfg) {
        final Map<String, List<Route>> out = new LinkedHashMap<>();
        if (StringUtils.isBlank(cfg)) {
            return out;
        }
        for (final String rawEntry : cfg.split(ENTRY_SEP)) {
            parseEntry(rawEntry, out);
        }
        return out;
    }

    private static void parseEntry(final String rawEntry, final Map<String, List<Route>> out) {
        final String entry = StringUtils.trimToEmpty(rawEntry);
        if (entry.isEmpty()) {
            return;
        }
        final int kv = entry.indexOf(KV_SEP);
        if (kv <= 0 || kv == entry.length() - 1) {
            LOGGER.warn("OvnEcmpRoutes: skipping malformed entry (expected '<uuid>=<prefix>-><nh>|...'): {}", entry);
            return;
        }
        final String networkUuid = entry.substring(0, kv).trim();
        final Route route = parseRoute(entry.substring(kv + 1));
        if (networkUuid.isEmpty() || route == null) {
            LOGGER.warn("OvnEcmpRoutes: skipping entry with blank uuid or no valid route: {}", entry);
            return;
        }
        mergeOrAppend(out.computeIfAbsent(networkUuid, k -> new ArrayList<>()), route);
    }

    /**
     * Same prefix merges next-hops (order-stable); different prefixes append a
     * new {@link Route}.
     */
    private static void mergeOrAppend(final List<Route> routes, final Route route) {
        for (int i = 0; i < routes.size(); i++) {
            final Route existing = routes.get(i);
            if (existing.getPrefix().equals(route.getPrefix())) {
                final LinkedHashSet<String> hops = new LinkedHashSet<>(existing.getNextHops());
                hops.addAll(route.getNextHops());
                routes.set(i, new Route(existing.getPrefix(), new ArrayList<>(hops)));
                return;
            }
        }
        routes.add(route);
    }

    private static Route parseRoute(final String spec) {
        final int arrow = spec.indexOf(ARROW);
        if (arrow <= 0 || arrow >= spec.length() - ARROW.length()) {
            LOGGER.warn("OvnEcmpRoutes: skipping route spec without '{}' or empty side: {}", ARROW, spec);
            return null;
        }
        final String prefix = spec.substring(0, arrow).trim();
        if (!NetUtils.isValidIp4Cidr(prefix) && !NetUtils.isValidIp6Cidr(prefix)) {
            LOGGER.warn("OvnEcmpRoutes: skipping route with malformed prefix '{}'", prefix);
            return null;
        }
        final List<String> nextHops = validNextHops(spec.substring(arrow + ARROW.length()));
        if (nextHops.isEmpty()) {
            LOGGER.warn("OvnEcmpRoutes: skipping route '{}' — no valid next-hop", prefix);
            return null;
        }
        return new Route(prefix, nextHops);
    }

    private static List<String> validNextHops(final String csv) {
        final LinkedHashSet<String> hops = new LinkedHashSet<>();
        for (final String raw : StringUtils.split(csv, NH_SEP)) {
            final String nh = StringUtils.trimToEmpty(raw);
            if (nh.isEmpty()) {
                continue;
            }
            if (NetUtils.isValidIp4(nh) || NetUtils.isValidIp6(nh)) {
                hops.add(nh);
            } else {
                LOGGER.warn("OvnEcmpRoutes: ignoring malformed next-hop '{}'", nh);
            }
        }
        return new ArrayList<>(hops);
    }

    /**
     * One destination prefix plus its ECMP next-hops. Immutable and
     * value-comparable so a resync can diff desired against existing.
     */
    public static final class Route {

        private final String prefix;
        private final List<String> nextHops;

        public Route(final String prefix, final List<String> nextHops) {
            this.prefix = prefix;
            this.nextHops = Collections.unmodifiableList(new ArrayList<>(nextHops));
        }

        public String getPrefix() {
            return prefix;
        }

        public List<String> getNextHops() {
            return nextHops;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Route)) {
                return false;
            }
            final Route other = (Route) o;
            return prefix.equals(other.prefix) && nextHops.equals(other.nextHops);
        }

        @Override
        public int hashCode() {
            return 31 * prefix.hashCode() + nextHops.hashCode();
        }

        @Override
        public String toString() {
            return prefix + "->" + String.join("|", nextHops);
        }
    }
}
