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
package com.cloud.network.ovn.client;

import java.util.ArrayList;
import java.util.List;

import com.cloud.network.ovn.client.op.OvnOpFactory;
import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.cloud.network.ovn.client.transport.OvsdbEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Read-only OVN Southbound client. Used for diagnostics and the chassis
 * registration hook (Phase I.6) — the SB DB carries the live chassis table
 * (system-id, hostname, encap, etc.).
 */
public class OvnSbClient implements AutoCloseable {

    public static final String DB_SB = "OVN_Southbound";

    private final OvsdbConnectionPool pool;

    public OvnSbClient(final OvsdbConnectionPool pool) {
        this.pool = pool;
    }

    public static OvnSbClient fromCsv(final String csv) {
        final List<OvsdbEndpoint> endpoints = OvsdbEndpoint.parseList(csv);
        final OvsdbConnectionPool pool = new OvsdbConnectionPool(endpoints,
                OvnNbClient.DEFAULT_CONNECT_TIMEOUT_MS, OvnNbClient.DEFAULT_SO_TIMEOUT_MS);
        return new OvnSbClient(pool);
    }

    /**
     * Lists every {@code Chassis} row. Fields exposed: {@code uuid},
     * {@code name} (the system-id), {@code hostname}.
     */
    public List<ChassisRow> listChassis() {
        final ArrayNode params = JsonNodeFactory.instance.arrayNode();
        params.add(DB_SB);
        params.add(OvnOpFactory.select("Chassis", OvnOpFactory.whereAll(),
                stringArray("_uuid", "name", "hostname")));
        final JsonNode reply = pool.call("transact", params);
        return decodeChassisRows(reply);
    }

    /**
     * Looks up the SB chassis row by hostname. Returns {@code null} when no
     * chassis with the given hostname is registered.
     */
    public ChassisRow findChassisByHostname(final String hostname) {
        for (final ChassisRow row : listChassis()) {
            if (hostname.equals(row.hostname)) {
                return row;
            }
        }
        return null;
    }

    /** Counts Port_Binding rows for an LSP that have a non-empty chassis. */
    public int countActivePortBindingClaims(final String logicalPort) {
        final ArrayNode params = JsonNodeFactory.instance.arrayNode();
        params.add(DB_SB);
        params.add(OvnOpFactory.select("Port_Binding", OvnOpFactory.whereAll(),
                stringArray("logical_port", "chassis")));
        final JsonNode reply = pool.call("transact", params);
        if (!(reply instanceof ArrayNode) || reply.size() == 0 || !reply.get(0).has("rows")) {
            return -1;
        }
        int count = 0;
        for (final JsonNode row : reply.get(0).get("rows")) {
            if (!logicalPort.equals(row.path("logical_port").asText())) {
                continue;
            }
            final JsonNode chassis = row.get("chassis");
            if (hasChassis(chassis)) {
                count++;
            }
        }
        return count;
    }

    private boolean hasChassis(final JsonNode chassis) {
        if (chassis == null || !chassis.isArray() || chassis.size() == 0) {
            return false;
        }
        return !(chassis.size() == 2 && "set".equals(chassis.get(0).asText())
                && chassis.get(1).isArray() && chassis.get(1).size() == 0);
    }

    private List<ChassisRow> decodeChassisRows(final JsonNode reply) {
        final List<ChassisRow> out = new ArrayList<>();
        if (!(reply instanceof ArrayNode) || reply.size() == 0) {
            return out;
        }
        final JsonNode first = reply.get(0);
        if (first == null) {
            return out;
        }
        final JsonNode rows = first.get("rows");
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (final JsonNode row : rows) {
            final JsonNode uuidNode = row.get("_uuid");
            final String uuid = uuidNode != null && uuidNode.size() == 2 ? uuidNode.get(1).asText() : null;
            final String name = row.has("name") ? row.get("name").asText() : null;
            final String hostname = row.has("hostname") ? row.get("hostname").asText() : null;
            out.add(new ChassisRow(uuid, name, hostname));
        }
        return out;
    }

    private ArrayNode stringArray(final String... names) {
        final ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (final String n : names) {
            arr.add(n);
        }
        return arr;
    }

    public OvsdbConnectionPool getPool() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }

    /** Minimal projection of the SB {@code Chassis} table. */
    public static class ChassisRow {
        public final String uuid;
        public final String name;
        public final String hostname;

        public ChassisRow(final String uuid, final String name, final String hostname) {
            this.uuid = uuid;
            this.name = name;
            this.hostname = hostname;
        }
    }
}
