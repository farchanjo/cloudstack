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

/**
 * Pure parser for {@link OvnNetworkConfig#LbAutoCks}
 * ({@code ovn.lb.auto.cks}).
 *
 * <p>Syntax: {@code <lb-rule-id>=<cks-cluster-uuid>:<dest-port>;...}
 * Rule id is the numeric CloudStack load_balancer / firewall_rules id.
 * Dest port is the backend port programmed on worker guest IPs.
 */
public final class OvnLbAutoCks {

    private static final Logger LOGGER = LogManager.getLogger(OvnLbAutoCks.class);

    private static final String ENTRY_SEP = ";";
    private static final String KV_SEP = "=";
    private static final String PORT_SEP = ":";

    private OvnLbAutoCks() {
    }

    public static List<Binding> parse(final String cfg) {
        if (StringUtils.isBlank(cfg)) {
            return Collections.emptyList();
        }
        final Map<Long, Binding> byRule = new LinkedHashMap<>();
        for (final String raw : cfg.split(ENTRY_SEP)) {
            final Binding b = parseEntry(raw);
            if (b != null) {
                byRule.put(b.getRuleId(), b);
            }
        }
        return new ArrayList<>(byRule.values());
    }

    private static Binding parseEntry(final String rawEntry) {
        final String entry = StringUtils.trimToEmpty(rawEntry);
        if (entry.isEmpty()) {
            return null;
        }
        final int kv = entry.indexOf(KV_SEP);
        if (kv <= 0 || kv == entry.length() - 1) {
            LOGGER.warn("OvnLbAutoCks: skipping malformed entry (expected "
                    + "'<rule-id>=<cks-uuid>:<port>'): {}", entry);
            return null;
        }
        final String ruleRaw = entry.substring(0, kv).trim();
        final long ruleId;
        try {
            ruleId = Long.parseLong(ruleRaw);
        } catch (final NumberFormatException nfe) {
            LOGGER.warn("OvnLbAutoCks: skipping entry — rule id not numeric: {}", ruleRaw);
            return null;
        }
        if (ruleId < 1) {
            LOGGER.warn("OvnLbAutoCks: skipping entry — rule id must be >= 1: {}", ruleId);
            return null;
        }
        final String rhs = entry.substring(kv + 1).trim();
        final int colon = rhs.lastIndexOf(PORT_SEP);
        if (colon <= 0 || colon == rhs.length() - 1) {
            LOGGER.warn("OvnLbAutoCks: skipping entry without '<cks-uuid>:<port>': {}", entry);
            return null;
        }
        final String clusterUuid = rhs.substring(0, colon).trim();
        if (clusterUuid.isEmpty()) {
            LOGGER.warn("OvnLbAutoCks: skipping entry with blank cluster uuid: {}", entry);
            return null;
        }
        final String portRaw = rhs.substring(colon + 1).trim();
        final int port;
        try {
            port = Integer.parseInt(portRaw);
        } catch (final NumberFormatException nfe) {
            LOGGER.warn("OvnLbAutoCks: skipping entry — dest port not numeric: {}", portRaw);
            return null;
        }
        if (port < 1 || port > 65535) {
            LOGGER.warn("OvnLbAutoCks: skipping entry — dest port out of range: {}", port);
            return null;
        }
        return new Binding(ruleId, clusterUuid, port);
    }

    public static final class Binding {
        private final long ruleId;
        private final String clusterUuid;
        private final int destPort;

        public Binding(final long ruleId, final String clusterUuid, final int destPort) {
            this.ruleId = ruleId;
            this.clusterUuid = clusterUuid;
            this.destPort = destPort;
        }

        public long getRuleId() {
            return ruleId;
        }

        public String getClusterUuid() {
            return clusterUuid;
        }

        public int getDestPort() {
            return destPort;
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
            return ruleId == other.ruleId
                    && destPort == other.destPort
                    && clusterUuid.equals(other.clusterUuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleId, clusterUuid, destPort);
        }

        @Override
        public String toString() {
            return "Binding{rule=" + ruleId + ", cks=" + clusterUuid + ", port=" + destPort + '}';
        }
    }
}
