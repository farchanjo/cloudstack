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
package com.cloud.network.ovn.client.transport;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.network.ovn.client.OvnException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Multi-endpoint OVSDB connection that walks the configured endpoint list
 * looking for the live RAFT leader. The OVN central RAFT cluster has at most
 * one leader at a time; followers reject {@code transact} calls with
 * {@code "not leader"} (at least on writes, configurable via OVN
 * {@code --no-leader-only}). The pool transparently fails over to the next
 * endpoint when:
 *
 * <ul>
 *   <li>the TCP connect fails (port collision, host down);
 *   <li>the OVSDB call returns an error containing {@code not leader};
 *   <li>the underlying socket throws on read/write.
 * </ul>
 *
 * <p>Endpoint order is preserved across calls (sticky), so a healthy leader
 * does not see traffic flap. The pool resets back to the head of the list
 * only when the current endpoint fails.
 */
public class OvsdbConnectionPool implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(OvsdbConnectionPool.class);

    private final List<OvsdbEndpoint> endpoints;
    private final int connectTimeoutMs;
    private final int soTimeoutMs;
    private final AtomicInteger cursor = new AtomicInteger(0);

    private volatile OvsdbConnection current;

    public OvsdbConnectionPool(final List<OvsdbEndpoint> endpoints, final int connectTimeoutMs, final int soTimeoutMs) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new OvnException("connection pool requires at least one endpoint");
        }
        this.endpoints = List.copyOf(endpoints);
        this.connectTimeoutMs = connectTimeoutMs;
        this.soTimeoutMs = soTimeoutMs;
    }

    /**
     * Calls the given method against the live leader, retrying across
     * endpoints when the current one fails. Throws {@link OvnException} when
     * every endpoint has been tried and none responded successfully.
     */
    public synchronized JsonNode call(final String method, final JsonNode params) {
        OvnException last = null;
        for (int attempt = 0; attempt < endpoints.size(); attempt++) {
            final OvsdbConnection conn = ensureConnection();
            try {
                return conn.call(method, params);
            } catch (final OvnException oe) {
                last = oe;
                LOGGER.warn("OVSDB endpoint {} failed (attempt {}/{}): {}",
                        conn.getEndpoint(), attempt + 1, endpoints.size(), oe.getMessage());
                conn.close();
                current = null;
                advanceCursor();
            }
        }
        throw new OvnException("all OVSDB endpoints failed for method=" + method, last);
    }

    private OvsdbConnection ensureConnection() {
        if (current != null) {
            return current;
        }
        final OvsdbEndpoint pick = endpoints.get(cursor.get() % endpoints.size());
        final OvsdbConnection conn = new OvsdbConnection(pick, connectTimeoutMs, soTimeoutMs);
        conn.open();
        current = conn;
        return conn;
    }

    private void advanceCursor() {
        cursor.updateAndGet(v -> (v + 1) % endpoints.size());
    }

    public List<OvsdbEndpoint> getEndpoints() {
        return endpoints;
    }

    /** Returns the endpoint currently believed to be the leader, or null. */
    public OvsdbEndpoint getActiveEndpoint() {
        final OvsdbConnection c = current;
        return c == null ? null : c.getEndpoint();
    }

    @Override
    public synchronized void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
