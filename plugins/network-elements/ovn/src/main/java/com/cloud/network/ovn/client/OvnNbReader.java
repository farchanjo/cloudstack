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
package com.cloud.network.ovn.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cloud.network.ovn.client.op.OvnOpFactory;
import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Read-only NB queries used by the import flow. Sits on top of the same
 * {@link OvsdbConnectionPool} the {@link OvnNbClient} already owns, so a
 * single OVN endpoint set / failover policy is reused. Decoded rows are
 * returned as plain Java POJOs ({@link Topology}) so the importer can be
 * unit-tested with a fixture {@code JsonNode} without dragging the OVSDB
 * transport.
 *
 * <p>Operations:
 *
 * <ul>
 *   <li>{@link #findLogicalRouter(String)} — looks up a logical router by
 *       name and returns the full topology bound to it (LRPs + NAT rules +
 *       LSes attached via router-patch + LSPs per LS).
 *   <li>{@link #decode(JsonNode)} — package-visible static helper used by
 *       the unit tests to feed a captured fixture into {@link Topology}.
 * </ul>
 *
 * <p>The reader never mutates NB state. The transport call always returns
 * a single {@code transact}/{@code select} reply array; the helper
 * {@link #rowsOf(JsonNode, int)} decodes the {@code rows} payload at the
 * given operation index.
 */
public class OvnNbReader {

    public static final String DB_NB = "OVN_Northbound";

    private final OvsdbConnectionPool pool;

    public OvnNbReader(final OvsdbConnectionPool pool) {
        this.pool = pool;
    }

    /** Convenience constructor that reuses the pool a {@link OvnNbClient} already opened. */
    public static OvnNbReader from(final OvnNbClient nb) {
        return new OvnNbReader(nb.getPool());
    }

    /**
     * Reads the LR topology matching {@code lrName}. Returns {@code null}
     * when no LR exists with that name. The returned {@link Topology}
     * carries every LRP, LS the LR is patched to, every LSP on each LS,
     * and every NAT rule referenced by the LR.
     */
    public Topology findLogicalRouter(final String lrName) {
        final ArrayNode whereLr = JsonNodeFactory.instance.arrayNode();
        final ArrayNode condition = JsonNodeFactory.instance.arrayNode();
        condition.add("name");
        condition.add("==");
        condition.add(lrName);
        whereLr.add(condition);

        final ArrayNode params = JsonNodeFactory.instance.arrayNode();
        params.add(DB_NB);
        params.add(OvnOpFactory.select("Logical_Router", whereLr,
                stringList("_uuid", "name", "ports", "nat", "external_ids")));
        final JsonNode lrReply = pool.call("transact", params);
        final List<JsonNode> lrRows = rowsOf(lrReply, 0);
        if (lrRows.isEmpty()) {
            return null;
        }
        final LrRow lr = decodeLr(lrRows.get(0));
        return assemble(lr);
    }

    private Topology assemble(final LrRow lr) {
        final List<LrpRow> lrps = readLrps(lr.portUuids);
        final List<NatRow> nats = readNats(lr.natUuids);
        // Identify LSes connected to the LR via router-patch LSPs (the LSP's
        // options.router-port matches an LRP name on the LR).
        final List<LsRow> allLses = listLogicalSwitches();
        final List<String> lrpNames = new ArrayList<>();
        for (final LrpRow lrp : lrps) {
            lrpNames.add(lrp.name);
        }
        final List<LsRow> attached = new ArrayList<>();
        final Map<String, List<LspRow>> lspsByLs = new HashMap<>();
        for (final LsRow ls : allLses) {
            final List<LspRow> lsps = readLsps(ls.portUuids);
            boolean attachedToLr = false;
            for (final LspRow lsp : lsps) {
                if ("router".equals(lsp.type)) {
                    final String routerPort = lsp.options.get("router-port");
                    if (routerPort != null && lrpNames.contains(routerPort)) {
                        attachedToLr = true;
                        break;
                    }
                }
            }
            if (attachedToLr) {
                attached.add(ls);
                lspsByLs.put(ls.uuid, lsps);
            }
        }
        return new Topology(lr, lrps, attached, lspsByLs, nats);
    }

    private List<LrpRow> readLrps(final List<String> uuids) {
        final List<LrpRow> out = new ArrayList<>();
        for (final String uuid : uuids) {
            final ArrayNode params = JsonNodeFactory.instance.arrayNode();
            params.add(DB_NB);
            params.add(OvnOpFactory.select("Logical_Router_Port",
                    OvnOpFactory.whereUuid(uuid),
                    stringList("_uuid", "name", "mac", "networks", "external_ids")));
            final JsonNode reply = pool.call("transact", params);
            for (final JsonNode row : rowsOf(reply, 0)) {
                out.add(decodeLrp(row));
            }
        }
        return out;
    }

    private List<NatRow> readNats(final List<String> uuids) {
        final List<NatRow> out = new ArrayList<>();
        for (final String uuid : uuids) {
            final ArrayNode params = JsonNodeFactory.instance.arrayNode();
            params.add(DB_NB);
            params.add(OvnOpFactory.select("NAT",
                    OvnOpFactory.whereUuid(uuid),
                    stringList("_uuid", "type", "external_ip", "logical_ip", "logical_port", "external_ids")));
            final JsonNode reply = pool.call("transact", params);
            for (final JsonNode row : rowsOf(reply, 0)) {
                out.add(decodeNat(row));
            }
        }
        return out;
    }

    private List<LsRow> listLogicalSwitches() {
        final ArrayNode params = JsonNodeFactory.instance.arrayNode();
        params.add(DB_NB);
        params.add(OvnOpFactory.select("Logical_Switch", OvnOpFactory.whereAll(),
                stringList("_uuid", "name", "ports", "external_ids")));
        final JsonNode reply = pool.call("transact", params);
        final List<LsRow> out = new ArrayList<>();
        for (final JsonNode row : rowsOf(reply, 0)) {
            out.add(decodeLs(row));
        }
        return out;
    }

    private List<LspRow> readLsps(final List<String> uuids) {
        final List<LspRow> out = new ArrayList<>();
        for (final String uuid : uuids) {
            final ArrayNode params = JsonNodeFactory.instance.arrayNode();
            params.add(DB_NB);
            params.add(OvnOpFactory.select("Logical_Switch_Port",
                    OvnOpFactory.whereUuid(uuid),
                    stringList("_uuid", "name", "type", "addresses", "tag", "options", "external_ids")));
            final JsonNode reply = pool.call("transact", params);
            for (final JsonNode row : rowsOf(reply, 0)) {
                out.add(decodeLsp(row));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Decoders (package-private so tests can hand-feed fixture JSON).
    // ------------------------------------------------------------------

    public static LrRow decodeLr(final JsonNode row) {
        final LrRow out = new LrRow();
        out.uuid = decodeUuidColumn(row, "_uuid");
        out.name = textOrNull(row, "name");
        out.portUuids = decodeUuidSet(row.get("ports"));
        out.natUuids = decodeUuidSet(row.get("nat"));
        out.externalIds = decodeMap(row.get("external_ids"));
        return out;
    }

    public static LrpRow decodeLrp(final JsonNode row) {
        final LrpRow out = new LrpRow();
        out.uuid = decodeUuidColumn(row, "_uuid");
        out.name = textOrNull(row, "name");
        out.mac = textOrNull(row, "mac");
        out.networks = decodeStringSet(row.get("networks"));
        out.externalIds = decodeMap(row.get("external_ids"));
        return out;
    }

    public static NatRow decodeNat(final JsonNode row) {
        final NatRow out = new NatRow();
        out.uuid = decodeUuidColumn(row, "_uuid");
        out.type = textOrNull(row, "type");
        out.externalIp = textOrNull(row, "external_ip");
        out.logicalIp = textOrNull(row, "logical_ip");
        final JsonNode lp = row.get("logical_port");
        out.logicalPort = (lp != null && lp.isTextual() && !lp.asText().isEmpty()) ? lp.asText() : null;
        out.externalIds = decodeMap(row.get("external_ids"));
        return out;
    }

    public static LsRow decodeLs(final JsonNode row) {
        final LsRow out = new LsRow();
        out.uuid = decodeUuidColumn(row, "_uuid");
        out.name = textOrNull(row, "name");
        out.portUuids = decodeUuidSet(row.get("ports"));
        out.externalIds = decodeMap(row.get("external_ids"));
        return out;
    }

    public static LspRow decodeLsp(final JsonNode row) {
        final LspRow out = new LspRow();
        out.uuid = decodeUuidColumn(row, "_uuid");
        out.name = textOrNull(row, "name");
        out.type = row.has("type") ? row.get("type").asText() : "";
        out.addresses = decodeStringSet(row.get("addresses"));
        out.options = decodeMap(row.get("options"));
        out.externalIds = decodeMap(row.get("external_ids"));
        final JsonNode tag = row.get("tag");
        if (tag != null && tag.isInt()) {
            out.tag = tag.asInt();
        } else {
            // ["set", []] = empty optional integer (no tag).
            out.tag = null;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // OVSDB JSON-RPC type helpers (RFC 7047 §5.1).
    // ------------------------------------------------------------------

    static String decodeUuidColumn(final JsonNode row, final String column) {
        final JsonNode node = row.get(column);
        if (node != null && node.isArray() && node.size() == 2) {
            return node.get(1).asText();
        }
        return null;
    }

    static List<String> decodeUuidSet(final JsonNode column) {
        final List<String> out = new ArrayList<>();
        if (column == null) {
            return out;
        }
        if (column.isArray() && column.size() == 2 && "uuid".equals(column.get(0).asText())) {
            out.add(column.get(1).asText());
            return out;
        }
        if (column.isArray() && column.size() == 2 && "set".equals(column.get(0).asText())) {
            final JsonNode elements = column.get(1);
            if (elements != null && elements.isArray()) {
                for (final JsonNode el : elements) {
                    if (el.isArray() && el.size() == 2 && "uuid".equals(el.get(0).asText())) {
                        out.add(el.get(1).asText());
                    }
                }
            }
        }
        return out;
    }

    static List<String> decodeStringSet(final JsonNode column) {
        final List<String> out = new ArrayList<>();
        if (column == null) {
            return out;
        }
        if (column.isTextual()) {
            out.add(column.asText());
            return out;
        }
        if (column.isArray() && column.size() == 2 && "set".equals(column.get(0).asText())) {
            final JsonNode elements = column.get(1);
            if (elements != null && elements.isArray()) {
                for (final JsonNode el : elements) {
                    if (el.isTextual()) {
                        out.add(el.asText());
                    }
                }
            }
        }
        return out;
    }

    static Map<String, String> decodeMap(final JsonNode column) {
        final Map<String, String> out = new HashMap<>();
        if (column == null) {
            return out;
        }
        if (column.isArray() && column.size() == 2 && "map".equals(column.get(0).asText())) {
            final JsonNode pairs = column.get(1);
            if (pairs != null && pairs.isArray()) {
                for (final JsonNode pair : pairs) {
                    if (pair.isArray() && pair.size() == 2) {
                        out.put(pair.get(0).asText(), pair.get(1).asText());
                    }
                }
            }
        }
        return out;
    }

    static List<JsonNode> rowsOf(final JsonNode reply, final int operationIndex) {
        final List<JsonNode> out = new ArrayList<>();
        if (!(reply instanceof ArrayNode) || reply.size() <= operationIndex) {
            return out;
        }
        final JsonNode entry = reply.get(operationIndex);
        if (entry == null) {
            return out;
        }
        final JsonNode rows = entry.get("rows");
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (final JsonNode row : rows) {
            out.add(row);
        }
        return out;
    }

    private static String textOrNull(final JsonNode row, final String column) {
        return row.has(column) && !row.get(column).isNull() ? row.get(column).asText() : null;
    }

    private static ArrayNode stringList(final String... names) {
        final ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (final String n : names) {
            arr.add(n);
        }
        return arr;
    }

    // ------------------------------------------------------------------
    // POJOs returned by decodeXXX.
    // ------------------------------------------------------------------

    /** Decoded {@code Logical_Router} row. */
    public static class LrRow {
        public String uuid;
        public String name;
        public List<String> portUuids = new ArrayList<>();
        public List<String> natUuids = new ArrayList<>();
        public Map<String, String> externalIds = new HashMap<>();
    }

    /** Decoded {@code Logical_Router_Port} row. */
    public static class LrpRow {
        public String uuid;
        public String name;
        public String mac;
        public List<String> networks = new ArrayList<>();
        public Map<String, String> externalIds = new HashMap<>();
    }

    /** Decoded {@code NAT} row. */
    public static class NatRow {
        public String uuid;
        /** {@code snat} | {@code dnat} | {@code dnat_and_snat}. */
        public String type;
        public String externalIp;
        public String logicalIp;
        public String logicalPort;
        public Map<String, String> externalIds = new HashMap<>();
    }

    /** Decoded {@code Logical_Switch} row. */
    public static class LsRow {
        public String uuid;
        public String name;
        public List<String> portUuids = new ArrayList<>();
        public Map<String, String> externalIds = new HashMap<>();
    }

    /** Decoded {@code Logical_Switch_Port} row. */
    public static class LspRow {
        public String uuid;
        public String name;
        /** Empty string (regular port) or {@code router} / {@code localnet} / etc. */
        public String type;
        public List<String> addresses = new ArrayList<>();
        public Map<String, String> options = new HashMap<>();
        public Map<String, String> externalIds = new HashMap<>();
        /** VLAN tag on a {@code localnet} port; {@code null} when unset. */
        public Integer tag;
    }

    /** Aggregate result of {@link #findLogicalRouter(String)}. */
    public static class Topology {
        public final LrRow lr;
        public final List<LrpRow> lrps;
        public final List<LsRow> attachedSwitches;
        public final Map<String, List<LspRow>> lspsByLsUuid;
        public final List<NatRow> nats;

        public Topology(final LrRow lr, final List<LrpRow> lrps, final List<LsRow> attachedSwitches,
                        final Map<String, List<LspRow>> lspsByLsUuid, final List<NatRow> nats) {
            this.lr = lr;
            this.lrps = lrps;
            this.attachedSwitches = attachedSwitches;
            this.lspsByLsUuid = lspsByLsUuid;
            this.nats = nats;
        }
    }
}
