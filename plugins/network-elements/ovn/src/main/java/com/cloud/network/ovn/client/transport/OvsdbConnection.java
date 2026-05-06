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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.network.ovn.client.OvnException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single-endpoint OVSDB JSON-RPC 1.0 connection (RFC 7047 §4).
 *
 * <p>The protocol is JSON-RPC 1.0: every request carries a string id and the
 * server echoes that id in its reply. There is no length prefix or framing —
 * messages are concatenated JSON values. We use Jackson's
 * {@code MappingIterator} to read one value at a time off the stream.
 *
 * <p>This class is single-threaded by design: the higher-level
 * {@link OvsdbConnectionPool} serialises calls through it. Concurrent
 * transactions go through separate connections.
 */
public class OvsdbConnection implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(OvsdbConnection.class);

    private final OvsdbEndpoint endpoint;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idGen = new AtomicLong(0L);
    private final int connectTimeoutMs;
    private final int soTimeoutMs;

    private Socket socket;
    private OutputStream out;
    private InputStream in;

    public OvsdbConnection(final OvsdbEndpoint endpoint, final int connectTimeoutMs, final int soTimeoutMs) {
        this.endpoint = endpoint;
        this.connectTimeoutMs = connectTimeoutMs;
        this.soTimeoutMs = soTimeoutMs;
    }

    /** Establishes the TCP socket. Idempotent — repeated calls are no-ops. */
    public synchronized void open() {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), connectTimeoutMs);
            socket.setSoTimeout(soTimeoutMs);
            socket.setTcpNoDelay(true);
            out = socket.getOutputStream();
            in = socket.getInputStream();
            LOGGER.debug("OVSDB connected to {}", endpoint);
        } catch (final IOException ioe) {
            close();
            throw new OvnException("OVSDB connect failed: " + endpoint, ioe);
        }
    }

    /**
     * Sends a JSON-RPC method call and returns the {@code result} field of
     * the server reply. Throws {@link OvnException} when the server replies
     * with a non-null {@code error} or the transport breaks.
     *
     * @param method name of the OVSDB method (e.g. {@code transact},
     *               {@code list_dbs}, {@code get_schema})
     * @param params raw {@code params} array as a Jackson node (callers
     *               typically build it via {@code mapper.createArrayNode()})
     * @return the {@code result} field of the reply
     */
    public synchronized JsonNode call(final String method, final JsonNode params) {
        open();
        final String id = "cs-" + idGen.incrementAndGet();
        final ObjectNode req = mapper.createObjectNode();
        req.put("method", method);
        req.set("params", params);
        req.put("id", id);
        try {
            out.write(mapper.writeValueAsBytes(req));
            out.flush();
            return readReply(id);
        } catch (final IOException ioe) {
            throw new OvnException("OVSDB I/O failed on " + endpoint + " method=" + method, ioe);
        }
    }

    private JsonNode readReply(final String expectedId) throws IOException {
        final JsonNode reply = mapper.readTree(in);
        if (reply == null || reply.isMissingNode()) {
            throw new OvnException("OVSDB closed connection while waiting for reply id=" + expectedId);
        }
        final JsonNode replyId = reply.get("id");
        if (replyId == null || !expectedId.equals(replyId.asText())) {
            throw new OvnException("OVSDB id mismatch expected=" + expectedId + " got=" + replyId);
        }
        final JsonNode error = reply.get("error");
        if (error != null && !error.isNull()) {
            throw new OvnException("OVSDB error reply: " + error.toString());
        }
        return reply.get("result");
    }

    @Override
    public synchronized void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (final IOException ignored) {
            // best effort
        } finally {
            socket = null;
            out = null;
            in = null;
        }
    }

    public OvsdbEndpoint getEndpoint() {
        return endpoint;
    }

    /**
     * Exposes the shared {@link ObjectMapper}. Operation builders use it to
     * craft the {@code params} array without instantiating their own mapper.
     */
    public ObjectMapper mapper() {
        return mapper;
    }
}
