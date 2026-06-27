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

package org.apache.cloudstack.ca.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Minimal OpenBao/Vault HTTP client built on the JDK {@link HttpClient}.
 *
 * <p>Handles AppRole login, token caching with re-login on authorization
 * failures, and the PKI secrets-engine endpoints required by the CA provider
 * (sign, issue, ca_chain and revoke). No third-party HTTP dependency is
 * introduced; JSON is (de)serialized with the reactor-provided Gson.</p>
 */
public class OpenBaoClient {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final Gson GSON = new Gson();
    private static final String VAULT_TOKEN_HEADER = "X-Vault-Token";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String address;
    private final String roleId;
    private final String secretId;
    private final HttpClient httpClient;

    private String cachedToken;

    public OpenBaoClient(final String address, final String roleId, final String secretId, final boolean tlsSkipVerify) {
        if (StringUtils.isEmpty(address)) {
            throw new CloudRuntimeException("OpenBao address is not configured");
        }
        this.address = StringUtils.removeEnd(address.trim(), "/");
        this.roleId = roleId;
        this.secretId = secretId;
        this.httpClient = buildHttpClient(tlsSkipVerify);
    }

    private HttpClient buildHttpClient(final boolean tlsSkipVerify) {
        final HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT);
        if (tlsSkipVerify) {
            logger.warn("OpenBao client configured to skip TLS verification; do not use in production");
            builder.sslContext(insecureSslContext());
        }
        return builder.build();
    }

    private SSLContext insecureSslContext() {
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            final TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                }

                @Override
                public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            sslContext.init(null, trustAll, new SecureRandom());
            return sslContext;
        } catch (final GeneralSecurityException e) {
            throw new CloudRuntimeException("Failed to build insecure SSL context for OpenBao client", e);
        }
    }

    /**
     * Authenticates against the AppRole auth backend and caches the client token.
     */
    public synchronized String login() {
        final JsonObject payload = new JsonObject();
        payload.addProperty("role_id", roleId);
        payload.addProperty("secret_id", secretId);
        final JsonObject response = sendUnauthenticated("POST", "/v1/auth/approle/login", payload);
        final JsonObject auth = response.getAsJsonObject("auth");
        if (auth == null || !auth.has("client_token")) {
            throw new CloudRuntimeException("OpenBao AppRole login did not return a client token");
        }
        cachedToken = auth.get("client_token").getAsString();
        logger.debug("Obtained OpenBao client token via AppRole login");
        return cachedToken;
    }

    private String getToken() {
        if (StringUtils.isEmpty(cachedToken)) {
            return login();
        }
        return cachedToken;
    }

    /**
     * Sends an authenticated request, re-logging in once on a 403 (token expired/invalid).
     */
    public JsonObject post(final String path, final JsonObject body) {
        return send("POST", path, body, true);
    }

    public JsonObject get(final String path) {
        return send("GET", path, null, true);
    }

    private JsonObject send(final String method, final String path, final JsonObject body, final boolean retryOnAuthFailure) {
        final HttpRequest request = newRequestBuilder(method, path, body)
                .header(VAULT_TOKEN_HEADER, getToken())
                .build();
        final HttpResponse<String> response = execute(request);
        if (response.statusCode() == 403 && retryOnAuthFailure) {
            logger.debug("OpenBao returned 403, refreshing AppRole token and retrying");
            cachedToken = null;
            login();
            return send(method, path, body, false);
        }
        return parseResponse(response, method, path);
    }

    private JsonObject sendUnauthenticated(final String method, final String path, final JsonObject body) {
        return parseResponse(execute(newRequestBuilder(method, path, body).build()), method, path);
    }

    private HttpRequest.Builder newRequestBuilder(final String method, final String path, final JsonObject body) {
        final HttpRequest.BodyPublisher publisher = (body == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(GSON.toJson(body));
        return HttpRequest.newBuilder()
                .uri(URI.create(address + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .method(method, publisher);
    }

    private HttpResponse<String> execute(final HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CloudRuntimeException("OpenBao request failed: " + request.uri(), e);
        }
    }

    private JsonObject parseResponse(final HttpResponse<String> response, final String method, final String path) {
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new CloudRuntimeException(String.format("OpenBao %s %s failed with HTTP %d: %s",
                    method, path, status, StringUtils.abbreviate(response.body(), 512)));
        }
        if (StringUtils.isEmpty(response.body())) {
            return new JsonObject();
        }
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    /**
     * Builds a JSON body from the provided key/value entries, skipping null/empty values.
     */
    public static JsonObject buildBody(final Map<String, String> entries) {
        final JsonObject body = new JsonObject();
        for (final Map.Entry<String, String> entry : entries.entrySet()) {
            if (StringUtils.isNotEmpty(entry.getValue())) {
                body.addProperty(entry.getKey(), entry.getValue());
            }
        }
        return body;
    }
}
