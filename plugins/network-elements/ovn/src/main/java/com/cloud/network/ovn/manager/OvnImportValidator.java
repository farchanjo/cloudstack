// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ovn.manager;

import java.util.ArrayList;
import java.util.List;

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbReader.LspRow;
import com.cloud.network.ovn.client.OvnNbReader.LsRow;
import com.cloud.network.ovn.client.OvnNbReader.Topology;

/**
 * MVP validator for {@code importOvnVpc}. Enforces the topology rules the
 * Phase I scope mandates so the importer can rely on a normalised input:
 *
 * <ul>
 *   <li>The LR must exist and have at least one LRP.
 *   <li>Every regular LSP (type empty) carries exactly one MAC + one IPv4.
 *       IPv6 addresses are explicitly rejected for MVP — the message
 *       points the operator at the deferred IPv6 work.
 *   <li>Exactly one of the LSes attached to the LR must be a public-side
 *       LS, identified by exactly one {@code type=localnet} LSP carrying
 *       a VLAN tag and {@code options.network_name} (the physnet
 *       declared in {@code bridge-mappings}).
 *   <li>Other attached LSes are tier (private) LSes — they MUST NOT host
 *       any {@code type=localnet} LSP.
 * </ul>
 *
 * <p>Validation results are exposed as a {@link Plan}: the parsed
 * {@link Topology} plus a clear classification of every LS into
 * {@code public} / {@code tier}. Failures throw {@link OvnException}.
 */
public final class OvnImportValidator {

    private OvnImportValidator() {
    }

    /** Walks the topology and produces a normalised {@link Plan}. */
    public static Plan validate(final Topology topology) {
        if (topology == null || topology.lr == null) {
            throw new OvnException("LR not found");
        }
        if (topology.lrps == null || topology.lrps.isEmpty()) {
            throw new OvnException("LR " + topology.lr.name + " has no LRPs");
        }
        final List<LsRow> tierLses = new ArrayList<>();
        LsRow publicLs = null;
        for (final LsRow ls : topology.attachedSwitches) {
            final List<LspRow> lsps = topology.lspsByLsUuid.getOrDefault(ls.uuid, new ArrayList<>());
            final List<LspRow> localnets = collectLocalnets(lsps);
            if (localnets.isEmpty()) {
                validateTierPorts(ls, lsps);
                tierLses.add(ls);
                continue;
            }
            if (localnets.size() > 1) {
                throw new OvnException("LS " + ls.name + " has " + localnets.size()
                        + " localnet ports; expected 0 (tier) or 1 (public)");
            }
            if (publicLs != null) {
                throw new OvnException("multiple public LSes attached to LR " + topology.lr.name
                        + " (" + publicLs.name + ", " + ls.name + "); expected exactly one");
            }
            validatePublicLocalnet(ls, localnets.get(0));
            publicLs = ls;
        }
        if (publicLs == null) {
            throw new OvnException("LR " + topology.lr.name + " has no public-side LS "
                    + "(no attached LS carries a localnet port)");
        }
        return new Plan(topology, tierLses, publicLs);
    }

    private static List<LspRow> collectLocalnets(final List<LspRow> lsps) {
        final List<LspRow> out = new ArrayList<>();
        for (final LspRow lsp : lsps) {
            if ("localnet".equals(lsp.type)) {
                out.add(lsp);
            }
        }
        return out;
    }

    private static void validateTierPorts(final LsRow ls, final List<LspRow> lsps) {
        for (final LspRow lsp : lsps) {
            if ("router".equals(lsp.type) || "localnet".equals(lsp.type)) {
                continue;
            }
            // Skip system-level LSPs (e.g. virtual / external / l2gateway / vtep).
            if (!lsp.type.isEmpty()) {
                continue;
            }
            assertSingleMacIpv4(ls.name, lsp);
        }
    }

    private static void assertSingleMacIpv4(final String lsName, final LspRow lsp) {
        // OVN encodes addresses as space-separated tokens: "<mac> <ip4> [ip4...]"
        // or special keywords like "router", "dynamic", "unknown".
        if (lsp.addresses == null || lsp.addresses.isEmpty()) {
            // No-address LSPs are tolerated (e.g. dhcp-bound LSPs come up with
            // empty addresses until the first lease).
            return;
        }
        if (lsp.addresses.size() > 1) {
            throw new OvnException("LSP " + lsp.name + " on LS " + lsName
                    + " has multiple address rows; MVP supports exactly one");
        }
        final String[] tokens = lsp.addresses.get(0).split("\\s+");
        if (tokens.length < 2) {
            throw new OvnException("LSP " + lsp.name + " on LS " + lsName
                    + " has malformed addresses: " + lsp.addresses.get(0));
        }
        // Walk tokens past the MAC (token 0): every remaining token must be IPv4.
        for (int i = 1; i < tokens.length; i++) {
            final String tok = tokens[i];
            if (tok.contains(":")) {
                throw new OvnException("LSP " + lsp.name + " on LS " + lsName
                        + " carries an IPv6 address (" + tok + "); MVP supports IPv4 only");
            }
        }
        final long ipv4Count = tokens.length - 1L;
        if (ipv4Count > 1) {
            throw new OvnException("LSP " + lsp.name + " on LS " + lsName
                    + " has " + ipv4Count + " IPv4 addresses; MVP allows exactly one");
        }
    }

    private static void validatePublicLocalnet(final LsRow ls, final LspRow localnet) {
        if (localnet.tag == null) {
            throw new OvnException("public LS " + ls.name + " localnet port " + localnet.name
                    + " has no VLAN tag; expected a tag matching the physnet bridge-mapping");
        }
        final String physnet = localnet.options.get("network_name");
        if (physnet == null || physnet.isEmpty()) {
            throw new OvnException("public LS " + ls.name + " localnet port " + localnet.name
                    + " has no options:network_name; expected the physnet name");
        }
    }

    /** Result of {@link #validate(Topology)}: parsed topology plus role labels. */
    public static class Plan {
        public final Topology topology;
        public final List<LsRow> tierLses;
        public final LsRow publicLs;

        public Plan(final Topology topology, final List<LsRow> tierLses, final LsRow publicLs) {
            this.topology = topology;
            this.tierLses = tierLses;
            this.publicLs = publicLs;
        }
    }
}
