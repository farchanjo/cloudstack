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
package com.cloud.hypervisor.kvm.resource.hwoffload;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Local HTTP server that accepts intent payloads from VRs running on this host.
 * Listens on the cloud0 link-local interface (default 169.254.0.1:9999), so only
 * VRs running locally can reach it (no exposure to public/guest networks).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /v1/hwoffload/intent} — submit a fresh intent (overwrites previous)
 *   <li>{@code GET  /v1/hwoffload/state}  — query current applied state (debugging)
 * </ul>
 *
 * <p>Auth: VR provides {@code X-CS-VR-Id} (its own UUID) and {@code X-CS-Auth}
 * (HMAC-SHA256 hex of the request body keyed by the VR's shared secret, set when
 * the VR is provisioned via libvirt user-data). Host validates the HMAC against
 * a per-VR secret cached in {@link #registerVr(String, byte[])}.
 *
 * <p>This class is intentionally framework-light: the JDK built-in HttpServer is
 * sufficient for the very low request rate (1 request per VR per configure pass).
 */
public class HwOffloadIntentApi {

    private static final Logger LOGGER = LogManager.getLogger(HwOffloadIntentApi.class);
    private static final String AUTH_HEADER = "X-CS-Auth";
    private static final String VR_ID_HEADER = "X-CS-VR-Id";
    private static final String HMAC_ALG = "HmacSHA256";

    private final IntentReconciler reconciler;
    private final java.util.Map<String, byte[]> vrSecrets = new java.util.concurrent.ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private HttpServer server;

    public HwOffloadIntentApi(IntentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    /** Start the HTTP server. Bound to cloud0 (link-local), single thread is enough. */
    public synchronized void start(String bindIp, int port) throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(bindIp, port), 8);
        server.createContext("/v1/hwoffload/intent", new IntentHandler());
        server.createContext("/v1/hwoffload/state", new StateHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        server.start();
        LOGGER.info("HwOffloadIntentApi listening on {}:{}", bindIp, port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
    }

    /**
     * Register a VR's HMAC secret. Called by the management server when the VR is
     * being defined (libvirt agent receives the secret in {@code StartCommand} details).
     */
    public void registerVr(String vrId, byte[] secret) {
        vrSecrets.put(vrId, secret);
        LOGGER.debug("Registered HMAC secret for VR {}", vrId);
    }

    public void deregisterVr(String vrId) {
        vrSecrets.remove(vrId);
        LOGGER.debug("Deregistered HMAC secret for VR {}", vrId);
    }

    private final class IntentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendError(ex, HttpURLConnection.HTTP_BAD_METHOD, "POST only");
                    return;
                }
                byte[] body = ex.getRequestBody().readAllBytes();
                String vrId = firstHeader(ex, VR_ID_HEADER);
                String auth = firstHeader(ex, AUTH_HEADER);
                if (vrId == null || auth == null) {
                    sendError(ex, HttpURLConnection.HTTP_UNAUTHORIZED, "Missing auth headers");
                    return;
                }
                byte[] secret = vrSecrets.get(vrId);
                if (secret == null || !verifyHmac(body, secret, auth)) {
                    sendError(ex, HttpURLConnection.HTTP_FORBIDDEN, "Invalid HMAC");
                    return;
                }
                IntentSpec spec = gson.fromJson(new String(body, StandardCharsets.UTF_8), IntentSpec.class);
                if (spec == null || !vrId.equals(spec.vrId)) {
                    sendError(ex, HttpURLConnection.HTTP_BAD_REQUEST, "Mismatched vrId");
                    return;
                }
                reconciler.applyIntent(spec);
                sendJson(ex, HttpURLConnection.HTTP_OK, "{\"ok\":true,\"version\":" + spec.version + "}");
            } catch (Exception e) {
                LOGGER.error("Intent handler error", e);
                sendError(ex, HttpURLConnection.HTTP_INTERNAL_ERROR, e.getMessage());
            }
        }
    }

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String vrId = firstHeader(ex, VR_ID_HEADER);
            if (vrId == null) {
                sendError(ex, HttpURLConnection.HTTP_BAD_REQUEST, "Missing X-CS-VR-Id");
                return;
            }
            IntentSpec current = reconciler.currentIntent(vrId);
            if (current == null) {
                sendJson(ex, HttpURLConnection.HTTP_NOT_FOUND, "{}");
                return;
            }
            sendJson(ex, HttpURLConnection.HTTP_OK, gson.toJson(current));
        }
    }

    private static boolean verifyHmac(byte[] body, byte[] secret, String expectedHex) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            byte[] computed = mac.doFinal(body);
            String computedHex = Hex.encodeHexString(computed);
            return constantTimeEquals(computedHex, expectedHex);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String firstHeader(HttpExchange ex, String name) {
        java.util.List<String> v = ex.getRequestHeaders().get(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, payload.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(payload);
        }
    }

    private static void sendError(HttpExchange ex, int status, String msg) throws IOException {
        sendJson(ex, status, "{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }

    /**
     * Wire-format intent spec. Mirrors what the VR's CsHwOffloadIntent.py serializes.
     * All fields are public for simple Gson (de)serialization.
     */
    public static class IntentSpec {
        public String vrId;
        public long version;
        public String guestVfPci;
        public String publicVfPci;
        public Integer ctZone;
        public java.util.List<NatRule> natRules;
        public java.util.List<AclRule> aclRules;
        public java.util.List<LbRule> lbRules;
    }

    public static class NatRule {
        public String dir;          // "SNAT" or "DNAT"
        public String matchAddr;    // src or dst depending on dir
        public Integer matchPort;
        public String translateAddr;
        public String ipProto;      // "tcp" / "udp"
        public Integer prio;
    }

    public static class AclRule {
        public String matchSrcIp;
        public String matchDstIp;
        public Integer matchPort;
        public String ipProto;
        public String action;       // "DROP" / "ACCEPT"
        public Boolean stateful;
        public Integer prio;
    }

    public static class LbRule {
        public String vip;
        public Integer port;
        public java.util.List<String> backends;
        public String method;       // "hash" / "round_robin"
        public Integer prio;
    }
}
