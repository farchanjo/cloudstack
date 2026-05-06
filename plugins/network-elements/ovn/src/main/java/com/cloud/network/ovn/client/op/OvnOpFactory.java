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
package com.cloud.network.ovn.client.op;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builders for the OVSDB JSON-RPC operation envelopes (RFC 7047 §5.2):
 * {@code insert}, {@code update}, {@code mutate}, {@code delete},
 * {@code select}.
 *
 * <p>Each method returns a fresh {@link ObjectNode} ready to drop into a
 * {@code transact} params array.
 */
public final class OvnOpFactory {

    private OvnOpFactory() {
    }

    public static ObjectNode insert(final String table, final String namedUuid, final ObjectNode row) {
        final ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("op", "insert");
        op.put("table", table);
        op.put("uuid-name", namedUuid);
        op.set("row", row);
        return op;
    }

    public static ObjectNode update(final String table, final ArrayNode where, final ObjectNode row) {
        final ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("op", "update");
        op.put("table", table);
        op.set("where", where);
        op.set("row", row);
        return op;
    }

    public static ObjectNode delete(final String table, final ArrayNode where) {
        final ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("op", "delete");
        op.put("table", table);
        op.set("where", where);
        return op;
    }

    public static ObjectNode select(final String table, final ArrayNode where, final ArrayNode columns) {
        final ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("op", "select");
        op.put("table", table);
        op.set("where", where);
        if (columns != null) {
            op.set("columns", columns);
        }
        return op;
    }

    /**
     * Builds an OVSDB mutation operation (RFC 7047 §5.2.4). Used to append
     * to a set column without having to re-write the whole set.
     */
    public static ObjectNode mutateInsertSet(final String table, final ArrayNode where, final String column,
                                             final ArrayNode setValue) {
        final ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("op", "mutate");
        op.put("table", table);
        op.set("where", where);
        final ArrayNode mutations = JsonNodeFactory.instance.arrayNode();
        final ArrayNode mutation = JsonNodeFactory.instance.arrayNode();
        mutation.add(column);
        mutation.add("insert");
        mutation.add(setValue);
        mutations.add(mutation);
        op.set("mutations", mutations);
        return op;
    }

    /**
     * Builds the standard {@code where} clause that matches a row by its
     * primary {@code _uuid} column.
     */
    public static ArrayNode whereUuid(final String uuid) {
        final ArrayNode where = JsonNodeFactory.instance.arrayNode();
        final ArrayNode condition = JsonNodeFactory.instance.arrayNode();
        condition.add("_uuid");
        condition.add("==");
        condition.add(OvnRowRef.realUuid(uuid));
        where.add(condition);
        return where;
    }

    /** Empty where = matches every row in the table. */
    public static ArrayNode whereAll() {
        return JsonNodeFactory.instance.arrayNode();
    }
}
