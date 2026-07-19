/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.resource;

import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** UUID-bound, atomic OVSDB removal used by every VF representor path. */
public final class OvsRepresentorCas {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private OvsRepresentorCas() {
    }

    public interface Executor {
        Result run(String... argv);
    }

    public static final class Result {
        private final boolean success;
        private final String output;
        private final String error;

        public Result(final boolean success, final String output, final String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public boolean success() { return success; }
        public String output() { return output; }
        public String error() { return error; }
    }

    public static boolean remove(final Executor executor, final String socket, final String name,
                                 final String expectedIfaceId) {
        if (name == null || name.isBlank() || expectedIfaceId == null || expectedIfaceId.isBlank()
                || !NAME.matcher(name).matches() || executor == null || socket == null || socket.isBlank()) {
            return false;
        }
        final Identity identity;
        try {
            identity = discover(executor, socket, name);
        } catch (RuntimeException e) {
            return false;
        }
        if (identity == null) {
            return true;
        }
        if (!expectedIfaceId.equals(identity.ifaceId)) {
            return false;
        }
        final Result transaction = executor.run("ovsdb-client", "transact", socket,
                transaction(identity, expectedIfaceId));
        if (!transaction.success() || !validResponse(transaction.output())) {
            return false;
        }
        try {
            return discover(executor, socket, name) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Return the authoritative iface-id for a representor, or null if absent/invalid. */
    public static String readIfaceId(final Executor executor, final String socket, final String name) {
        if (executor == null || socket == null || socket.isBlank() || name == null || name.isBlank()
                || !NAME.matcher(name).matches()) {
            return null;
        }
        try {
            final Identity identity = discover(executor, socket, name);
            return identity == null ? null : identity.ifaceId;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Strict read used before a representor is reused for a new LSP identity. */
    public static String readIfaceIdStrict(final Executor executor, final String socket, final String name) {
        if (executor == null || socket == null || socket.isBlank() || name == null || name.isBlank()
                || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid representor identity lookup");
        }
        final Identity identity = discover(executor, socket, name);
        return identity == null ? null : identity.ifaceId;
    }

    static String transactionForTest(final String ifaceUuid, final String portUuid, final String bridgeUuid,
                                     final String name, final String expectedIfaceId) {
        return transaction(new Identity(ifaceUuid, portUuid, bridgeUuid, name, expectedIfaceId), expectedIfaceId);
    }

    private static Identity discover(final Executor executor, final String socket, final String name) {
        final JsonArray iface = select(executor, socket, "Interface", where("name", name),
                List.of("_uuid", "name", "external_ids"));
        final JsonArray port = select(executor, socket, "Port", where("name", name),
                List.of("_uuid", "name", "interfaces"));
        if (iface.isEmpty() && port.isEmpty()) {
            return null;
        }
        if (iface.size() != 1 || port.size() != 1) {
            throw new IllegalStateException("OVS representor identity is ambiguous");
        }
        final JsonObject i = iface.get(0).getAsJsonObject();
        final JsonObject p = port.get(0).getAsJsonObject();
        final String iu = uuid(i.get("_uuid"));
        final String pu = uuid(p.get("_uuid"));
        final String ifaceId = mapValue(i.get("external_ids"), "iface-id");
        if (ifaceId == null || ifaceId.isBlank()) {
            throw new IllegalStateException("OVS representor iface-id ownership is missing");
        }
        final JsonArray interfaces = unwrap(p.get("interfaces"));
        if (!name.equals(text(i.get("name"))) || !name.equals(text(p.get("name")))
                || interfaces.size() != 1 || !iu.equals(uuid(interfaces.get(0)))) {
            throw new IllegalStateException("OVS representor identity changed during discovery");
        }
        final JsonArray bridge = select(executor, socket, "Bridge", includes("ports", pu),
                List.of("_uuid", "ports"));
        if (bridge.size() != 1) {
            throw new IllegalStateException("OVS representor bridge identity is ambiguous");
        }
        return new Identity(iu, pu, uuid(bridge.get(0).getAsJsonObject().get("_uuid")), name, ifaceId);
    }

    private static JsonArray select(final Executor executor, final String socket, final String table,
                                    final JsonArray where, final List<String> columns) {
        final JsonObject op = new JsonObject();
        op.addProperty("op", "select");
        op.addProperty("table", table);
        op.add("where", where);
        final JsonArray cols = new JsonArray();
        columns.forEach(cols::add);
        op.add("columns", cols);
        final JsonArray request = new JsonArray();
        request.add("Open_vSwitch");
        request.add(op);
        final Result result = executor.run("ovsdb-client", "transact", socket, request.toString());
        if (!result.success()) {
            throw new IllegalStateException("OVSDB discovery failed: " + result.error());
        }
        final JsonArray response = JsonParser.parseString(result.output()).getAsJsonArray();
        if (response.size() != 1 || response.get(0).getAsJsonObject().has("error")
                || !response.get(0).getAsJsonObject().has("rows")
                || response.get(0).getAsJsonObject().entrySet().size() != 1) {
            throw new IllegalStateException("malformed OVSDB discovery response");
        }
        return response.get(0).getAsJsonObject().getAsJsonArray("rows");
    }

    private static String transaction(final Identity i, final String expectedIfaceId) {
        final JsonArray ops = new JsonArray();
        ops.add(wait("Interface", all(eqUuid("_uuid", i.interfaceUuid), eq("name", i.name),
                expectedIfaceId == null ? null : includesMap("external_ids", "iface-id", expectedIfaceId))));
        ops.add(wait("Port", all(eqUuid("_uuid", i.portUuid), eq("name", i.name),
                includesSet("interfaces", i.interfaceUuid))));
        ops.add(wait("Bridge", all(eqUuid("_uuid", i.bridgeUuid), includesSet("ports", i.portUuid))));
        final JsonObject mutate = op("mutate", "Bridge");
        mutate.add("where", all(eqUuid("_uuid", i.bridgeUuid)));
        final JsonArray mutations = new JsonArray();
        final JsonArray mutation = new JsonArray();
        mutation.add("ports"); mutation.add("delete"); mutation.add(uuidSet(i.portUuid));
        mutations.add(mutation); mutate.add("mutations", mutations); ops.add(mutate);
        final JsonObject deletePort = op("delete", "Port");
        deletePort.add("where", all(eqUuid("_uuid", i.portUuid), eq("name", i.name),
                includesSet("interfaces", i.interfaceUuid))); ops.add(deletePort);
        final JsonObject deleteIface = op("delete", "Interface");
        deleteIface.add("where", all(eqUuid("_uuid", i.interfaceUuid), eq("name", i.name))); ops.add(deleteIface);
        final JsonArray request = new JsonArray(); request.add("Open_vSwitch"); request.addAll(ops);
        return request.toString();
    }

    private static JsonObject wait(final String table, final JsonArray where) {
        final JsonObject op = op("wait", table); op.add("where", where);
        op.add("columns", new JsonArray()); op.addProperty("until", "!="); op.add("rows", new JsonArray());
        op.addProperty("timeout", 0); return op;
    }

    private static JsonObject op(final String operation, final String table) {
        final JsonObject op = new JsonObject(); op.addProperty("op", operation); op.addProperty("table", table); return op;
    }

    private static JsonArray where(final String field, final String value) { return all(eq(field, value)); }
    private static JsonArray includes(final String field, final String uuid) { return all(includesSet(field, uuid)); }
    private static JsonArray all(final JsonArray... clauses) { final JsonArray out = new JsonArray(); for (JsonArray c : clauses) if (c != null) out.add(c); return out; }
    private static JsonArray eq(final String field, final String value) { final JsonArray c = new JsonArray(); c.add(field); c.add("=="); c.add(value); return c; }
    private static JsonArray eqUuid(final String field, final String value) { final JsonArray c = new JsonArray(); c.add(field); c.add("=="); c.add(uuid(value)); return c; }
    private static JsonArray includesSet(final String field, final String value) { final JsonArray c = new JsonArray(); c.add(field); c.add("includes"); c.add(uuidSet(value)); return c; }
    private static JsonArray includesMap(final String field, final String key, final String value) { final JsonArray c = new JsonArray(); c.add(field); c.add("includes"); final JsonArray map = new JsonArray(); map.add("map"); final JsonArray pair = new JsonArray(); pair.add(key); pair.add(value); final JsonArray pairs = new JsonArray(); pairs.add(pair); map.add(pairs); c.add(map); return c; }
    private static JsonArray uuidSet(final String value) { final JsonArray set = new JsonArray(); set.add("set"); final JsonArray values = new JsonArray(); values.add(uuid(value)); set.add(values); return set; }
    private static JsonArray uuid(final String value) { final JsonArray u = new JsonArray(); u.add("uuid"); u.add(value); return u; }
    private static String uuid(final JsonElement value) { return value.getAsJsonArray().get(1).getAsString(); }
    private static String text(final JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            if (value != null && value.isJsonArray() && value.getAsJsonArray().size() == 2
                    && "string".equals(value.getAsJsonArray().get(0).getAsString())) {
                return value.getAsJsonArray().get(1).getAsString();
            }
            throw new IllegalStateException("OVS string value is malformed");
        }
        return value.getAsString();
    }
    private static JsonArray unwrap(final JsonElement value) { return "set".equals(value.getAsJsonArray().get(0).getAsString()) ? value.getAsJsonArray().get(1).getAsJsonArray() : value.getAsJsonArray(); }
    private static boolean validResponse(final String output) {
        try {
            final JsonArray response = JsonParser.parseString(output).getAsJsonArray();
            if (response.size() != 6) {
                return false;
            }
            for (int index = 0; index < 3; index++) {
                final JsonObject wait = response.get(index).getAsJsonObject();
                // OVS 3.3 returns an empty object for a satisfied wait when
                // columns=[] and until=!=; it does not return rows here.
                if (!wait.entrySet().isEmpty()) {
                    return false;
                }
            }
            for (int index = 3; index < response.size(); index++) {
                final JsonObject mutation = response.get(index).getAsJsonObject();
                if (mutation.has("error") || mutation.entrySet().size() != 1
                        || !mutation.has("count") || mutation.get("count").getAsInt() != 1) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String mapValue(final JsonElement value, final String wantedKey) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 2
                || !"map".equals(value.getAsJsonArray().get(0).getAsString())) {
            throw new IllegalStateException("OVS external_ids map is malformed");
        }
        for (JsonElement entry : value.getAsJsonArray().get(1).getAsJsonArray()) {
            final JsonArray pair = entry.getAsJsonArray();
            if (pair.size() == 2 && wantedKey.equals(pair.get(0).getAsString())) {
                return pair.get(1).getAsString();
            }
        }
        return null;
    }
    private static final class Identity {
        private final String interfaceUuid;
        private final String portUuid;
        private final String bridgeUuid;
        private final String name;
        private final String ifaceId;

        private Identity(final String interfaceUuid, final String portUuid, final String bridgeUuid,
                         final String name, final String ifaceId) {
            this.interfaceUuid = interfaceUuid;
            this.portUuid = portUuid;
            this.bridgeUuid = bridgeUuid;
            this.name = name;
            this.ifaceId = ifaceId;
        }
    }
}
