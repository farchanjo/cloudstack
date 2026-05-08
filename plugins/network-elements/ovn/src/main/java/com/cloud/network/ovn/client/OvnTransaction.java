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

import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fluent builder for an OVSDB {@code transact} call (RFC 7047 §4.1.3).
 *
 * <p>Each call to {@link #add(ObjectNode)} appends one operation to the
 * pending transaction. {@link #commit()} hands the whole batch to the
 * transport. The returned {@link Result} object exposes the indexed reply
 * array so callers can correlate inserts with their assigned {@code uuid}.
 *
 * <p>Atomicity is the OVSDB server guarantee: either every operation
 * succeeds or none of them apply (RFC 7047 §4.1.3).
 */
public class OvnTransaction {

    private final OvsdbConnectionPool pool;
    private final String dbName;
    private final List<ObjectNode> ops = new ArrayList<>();

    public OvnTransaction(final OvsdbConnectionPool pool, final String dbName) {
        this.pool = pool;
        this.dbName = dbName;
    }

    /** Appends one operation. Returns {@code this} for fluent chaining. */
    public OvnTransaction add(final ObjectNode op) {
        ops.add(op);
        return this;
    }

    /**
     * Submits the transaction to the leader and decodes the response. Throws
     * {@link OvnException} when any individual operation reports an
     * {@code error} (the server marks the failed op with an {@code error}
     * field but the transaction itself returns a non-error reply).
     */
    public Result commit() {
        if (ops.isEmpty()) {
            return new Result(JsonNodeFactory.instance.arrayNode());
        }
        final ArrayNode params = JsonNodeFactory.instance.arrayNode();
        params.add(dbName);
        for (final ObjectNode op : ops) {
            params.add(op);
        }
        final JsonNode reply = pool.call("transact", params);
        if (!(reply instanceof ArrayNode)) {
            throw new OvnException("expected array reply from transact, got: " + reply);
        }
        final ArrayNode arr = (ArrayNode) reply;
        // Per-op error scan. OVSDB sometimes appends one extra "transaction-
        // wide" entry past the per-op replies (RFC 7047 §4.1.3 — used for
        // commit-time aborts and lock failures). That trailer has no
        // matching ops.get(i), so we cap the per-op loop at ops.size() and
        // surface the trailer separately to avoid IOOBE on otherwise-OK
        // transactions like a single delete that returns two reply slots.
        final int perOp = Math.min(arr.size(), ops.size());
        for (int i = 0; i < perOp; i++) {
            final JsonNode entry = arr.get(i);
            if (entry != null && entry.has("error") && !entry.get("error").isNull()) {
                throw new OvnException("OVSDB op " + i + " (" + ops.get(i).get("op").asText()
                        + ") failed: " + entry.toString());
            }
        }
        if (arr.size() > ops.size()) {
            final JsonNode trailer = arr.get(arr.size() - 1);
            if (trailer != null && trailer.has("error") && !trailer.get("error").isNull()) {
                throw new OvnException("OVSDB transaction trailer error: " + trailer);
            }
        }
        return new Result(arr);
    }

    /** Read-only view of the indexed reply array. */
    public static class Result {
        private final ArrayNode replies;

        public Result(final ArrayNode replies) {
            this.replies = replies;
        }

        /** Returns the {@code uuid} field of the i-th insert reply. */
        public String insertedUuid(final int index) {
            final JsonNode entry = replies.get(index);
            if (entry == null) {
                throw new OvnException("no reply at index " + index);
            }
            final JsonNode uuid = entry.get("uuid");
            if (uuid == null || uuid.size() < 2) {
                throw new OvnException("no uuid at reply index " + index + ": " + entry);
            }
            // ["uuid", "<real-uuid>"]
            return uuid.get(1).asText();
        }

        public ArrayNode raw() {
            return replies;
        }
    }
}
