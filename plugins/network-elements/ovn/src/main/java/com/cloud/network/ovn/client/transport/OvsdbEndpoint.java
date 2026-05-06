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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.cloud.network.ovn.client.OvnException;

/**
 * Parses OVSDB endpoint strings as accepted by ovn-nbctl / ovsdb-client:
 *
 * <ul>
 *   <li>{@code tcp:HOST:PORT}
 *   <li>{@code ssl:HOST:PORT} (not yet supported by the native transport)
 *   <li>{@code unix:/path/to/socket} (not yet supported)
 * </ul>
 *
 * <p>The CloudStack {@code ovn_controller.nb_endpoints} column is a CSV of
 * endpoints; this class also exposes a parser for that form.
 */
public final class OvsdbEndpoint {

    /** Default OVSDB-NB port. */
    public static final int DEFAULT_NB_PORT = 6641;
    /** Default OVSDB-SB port. */
    public static final int DEFAULT_SB_PORT = 6642;

    private final String host;
    private final int port;

    public OvsdbEndpoint(final String host, final int port) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "tcp:" + host + ":" + port;
    }

    /**
     * Parses a single endpoint string. Throws {@link OvnException} when the
     * scheme is unsupported or the form is malformed.
     */
    public static OvsdbEndpoint parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new OvnException("endpoint is empty");
        }
        final String trimmed = raw.trim();
        if (!trimmed.startsWith("tcp:")) {
            throw new OvnException("unsupported OVSDB scheme (only tcp: is supported by the native transport): " + trimmed);
        }
        final String hostPort = trimmed.substring(4);
        final int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx == hostPort.length() - 1) {
            throw new OvnException("malformed endpoint, expected tcp:host:port: " + trimmed);
        }
        final String host = hostPort.substring(0, idx);
        final int port;
        try {
            port = Integer.parseInt(hostPort.substring(idx + 1));
        } catch (final NumberFormatException nfe) {
            throw new OvnException("malformed endpoint, port not numeric: " + trimmed, nfe);
        }
        return new OvsdbEndpoint(host, port);
    }

    /**
     * Parses a comma-separated endpoint list (e.g.
     * {@code tcp:10.182.0.11:6641,tcp:10.182.0.12:6641}). Empty entries are
     * skipped. The returned list preserves order so callers can implement
     * round-robin / leader probing in a stable way.
     */
    public static List<OvsdbEndpoint> parseList(final String csv) {
        if (csv == null || csv.isBlank()) {
            throw new OvnException("endpoint list is empty");
        }
        final List<OvsdbEndpoint> out = new ArrayList<>();
        for (final String token : csv.split(",")) {
            final String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.add(parse(trimmed));
        }
        if (out.isEmpty()) {
            throw new OvnException("no usable endpoints in: " + csv);
        }
        return out;
    }
}
