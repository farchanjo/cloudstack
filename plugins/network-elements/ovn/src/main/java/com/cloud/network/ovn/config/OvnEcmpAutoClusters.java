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
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.net.NetUtils;

/**
 * Pure parser for {@link OvnNetworkConfig#LrEcmpAutoClusters}
 * ({@code ovn.lr.ecmp.auto.clusters}).
 *
 * <p>Syntax: {@code <network-uuid>=<cks-cluster-uuid>|<v4-prefix>|<v6-prefix>;...}
 * Either prefix may be blank to skip that family. Malformed stanzas are
 * logged and skipped. Blank/null input yields an empty list (feature off).
 */
public final class OvnEcmpAutoClusters {

    private static final Logger LOGGER = LogManager.getLogger(OvnEcmpAutoClusters.class);

    private static final String ENTRY_SEP = ";";
    private static final String KV_SEP = "=";
    private static final String FIELD_SEP = "|";

    private OvnEcmpAutoClusters() {
    }

    /**
     * Parse the ConfigKey value into ordered binding entries.
     *
     * @param cfg raw ConfigKey value
     * @return never {@code null}
     */
    public static List<Binding> parse(final String cfg) {
        if (StringUtils.isBlank(cfg)) {
            return Collections.emptyList();
        }
        final List<Binding> out = new ArrayList<>();
        // Dedup by networkUuid|v4|v6 so repeats collapse (last wins order-stable).
        final Map<String, Binding> byKey = new LinkedHashMap<>();
        for (final String raw : cfg.split(ENTRY_SEP)) {
            final Binding b = parseEntry(raw);
            if (b != null) {
                byKey.put(b.entryKey(), b);
            }
        }
        out.addAll(byKey.values());
        return out;
    }

    private static Binding parseEntry(final String rawEntry) {
        final String entry = StringUtils.trimToEmpty(rawEntry);
        if (entry.isEmpty()) {
            return null;
        }
        final int kv = entry.indexOf(KV_SEP);
        if (kv <= 0 || kv == entry.length() - 1) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping malformed entry (expected "
                    + "'<net-uuid>=<cks-uuid>|<v4-prefix>|<v6-prefix>'): {}", entry);
            return null;
        }
        final String networkUuid = entry.substring(0, kv).trim();
        if (networkUuid.isEmpty()) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping entry with blank network uuid: {}", entry);
            return null;
        }
        final String[] fields = StringUtils.splitPreserveAllTokens(entry.substring(kv + 1), FIELD_SEP);
        if (fields == null || fields.length < 1) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping entry without cluster uuid: {}", entry);
            return null;
        }
        final String clusterUuid = StringUtils.trimToEmpty(fields[0]);
        if (clusterUuid.isEmpty()) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping entry with blank cluster uuid: {}", entry);
            return null;
        }
        final String v4 = fields.length > 1 ? StringUtils.trimToEmpty(fields[1]) : "";
        final String v6 = fields.length > 2 ? StringUtils.trimToEmpty(fields[2]) : "";
        if (!v4.isEmpty() && !NetUtils.isValidIp4Cidr(v4)) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping entry — malformed v4 prefix '{}'", v4);
            return null;
        }
        if (!v6.isEmpty() && !NetUtils.isValidIp6Cidr(v6)) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping entry — malformed v6 prefix '{}'", v6);
            return null;
        }
        if (v4.isEmpty() && v6.isEmpty()) {
            LOGGER.warn("OvnEcmpAutoClusters: skipping entry — both prefixes blank: {}", entry);
            return null;
        }
        return new Binding(networkUuid, clusterUuid, v4.isEmpty() ? null : v4, v6.isEmpty() ? null : v6);
    }

    /**
     * One auto-ECMP binding: CKS workers on a guest network feed next-hops for
     * the given VIP prefix(es).
     */
    public static final class Binding {
        private final String networkUuid;
        private final String clusterUuid;
        private final String v4Prefix;
        private final String v6Prefix;

        public Binding(final String networkUuid, final String clusterUuid,
                       final String v4Prefix, final String v6Prefix) {
            this.networkUuid = networkUuid;
            this.clusterUuid = clusterUuid;
            this.v4Prefix = v4Prefix;
            this.v6Prefix = v6Prefix;
        }

        public String getNetworkUuid() {
            return networkUuid;
        }

        public String getClusterUuid() {
            return clusterUuid;
        }

        /** Nullable when family skipped. */
        public String getV4Prefix() {
            return v4Prefix;
        }

        /** Nullable when family skipped. */
        public String getV6Prefix() {
            return v6Prefix;
        }

        String entryKey() {
            return networkUuid + '|' + Objects.toString(v4Prefix, "") + '|' + Objects.toString(v6Prefix, "");
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Binding)) {
                return false;
            }
            final Binding other = (Binding) o;
            return networkUuid.equals(other.networkUuid)
                    && clusterUuid.equals(other.clusterUuid)
                    && Objects.equals(v4Prefix, other.v4Prefix)
                    && Objects.equals(v6Prefix, other.v6Prefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(networkUuid, clusterUuid, v4Prefix, v6Prefix);
        }

        @Override
        public String toString() {
            return "Binding{net=" + networkUuid + ", cks=" + clusterUuid
                    + ", v4=" + v4Prefix + ", v6=" + v6Prefix + '}';
        }
    }
}
