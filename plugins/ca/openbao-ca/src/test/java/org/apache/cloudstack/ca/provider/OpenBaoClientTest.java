//
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
//

package org.apache.cloudstack.ca.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.exception.CloudRuntimeException;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.gson.JsonObject;

@RunWith(MockitoJUnitRunner.class)
public class OpenBaoClientTest {

    private static final String ROLE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SECRET_ID = "22222222-2222-2222-2222-222222222222";
    private static final String TOKEN_A = "hvs.tokenA";
    private static final String TOKEN_B = "hvs.tokenB";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort().bindAddress("localhost"));

    private OpenBaoClient newClient() {
        return new OpenBaoClient(String.format("http://localhost:%d", wireMockRule.port()), ROLE_ID, SECRET_ID, false);
    }

    private void stubLogin(final String token) {
        wireMockRule.stubFor(post(urlEqualTo("/v1/auth/approle/login"))
                .willReturn(okJson(String.format("{\"auth\":{\"client_token\":\"%s\"}}", token))));
    }

    @Test
    public void testConstructorRejectsEmptyAddress() {
        try {
            new OpenBaoClient("", ROLE_ID, SECRET_ID, false);
            Assert.fail("expected CloudRuntimeException for empty address");
        } catch (final CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("address"));
        }
    }

    @Test
    public void testLoginReturnsClientToken() {
        stubLogin(TOKEN_A);
        final OpenBaoClient client = newClient();
        Assert.assertEquals(TOKEN_A, client.login());
        wireMockRule.verify(postRequestedFor(urlEqualTo("/v1/auth/approle/login"))
                .withRequestBody(containing(ROLE_ID))
                .withRequestBody(containing(SECRET_ID)));
    }

    @Test
    public void testLoginMissingClientTokenThrows() {
        wireMockRule.stubFor(post(urlEqualTo("/v1/auth/approle/login"))
                .willReturn(okJson("{\"auth\":{}}")));
        final OpenBaoClient client = newClient();
        try {
            client.login();
            Assert.fail("expected CloudRuntimeException when auth.client_token is missing");
        } catch (final CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("client token"));
        }
    }

    @Test
    public void testGetTriggersLoginWhenNoCachedToken() {
        stubLogin(TOKEN_A);
        wireMockRule.stubFor(get(urlEqualTo("/v1/pki_cloudstack/cert/ca_chain"))
                .willReturn(okJson("{\"data\":{\"ca_chain\":\"stub\"}}")));

        final OpenBaoClient client = newClient();
        final JsonObject response = client.get("/v1/pki_cloudstack/cert/ca_chain");

        Assert.assertEquals("stub", response.getAsJsonObject("data").get("ca_chain").getAsString());
        wireMockRule.verify(1, postRequestedFor(urlEqualTo("/v1/auth/approle/login")));
    }

    @Test
    public void testTokenIsCachedAcrossCalls() {
        stubLogin(TOKEN_A);
        wireMockRule.stubFor(get(urlEqualTo("/v1/pki_cloudstack/cert/ca_chain"))
                .willReturn(okJson("{\"data\":{\"ca_chain\":\"stub\"}}")));

        final OpenBaoClient client = newClient();
        client.get("/v1/pki_cloudstack/cert/ca_chain");
        client.get("/v1/pki_cloudstack/cert/ca_chain");

        wireMockRule.verify(1, postRequestedFor(urlEqualTo("/v1/auth/approle/login")));
    }

    @Test
    public void testRetryOn403ReLogsInAndSucceeds() {
        wireMockRule.stubFor(post(urlEqualTo("/v1/auth/approle/login"))
                .inScenario("token-expiry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson(String.format("{\"auth\":{\"client_token\":\"%s\"}}", TOKEN_A)))
                .willSetStateTo("first-token-issued"));

        wireMockRule.stubFor(post(urlEqualTo("/v1/auth/approle/login"))
                .inScenario("token-expiry")
                .whenScenarioStateIs("first-token-issued")
                .willReturn(okJson(String.format("{\"auth\":{\"client_token\":\"%s\"}}", TOKEN_B))));

        wireMockRule.stubFor(post(urlEqualTo("/v1/pki_cloudstack/revoke"))
                .withHeader("X-Vault-Token", com.github.tomakehurst.wiremock.client.WireMock.equalTo(TOKEN_A))
                .willReturn(aResponse().withStatus(403)));

        wireMockRule.stubFor(post(urlEqualTo("/v1/pki_cloudstack/revoke"))
                .withHeader("X-Vault-Token", com.github.tomakehurst.wiremock.client.WireMock.equalTo(TOKEN_B))
                .willReturn(ok()));

        final OpenBaoClient client = newClient();
        final Map<String, String> body = new HashMap<>();
        body.put("serial_number", "aa:bb:cc");
        final JsonObject response = client.post("/v1/pki_cloudstack/revoke", OpenBaoClient.buildBody(body));

        Assert.assertNotNull(response);
        wireMockRule.verify(2, postRequestedFor(urlEqualTo("/v1/auth/approle/login")));
    }

    @Test
    public void testNon2xxNon403ResponseThrows() {
        stubLogin(TOKEN_A);
        wireMockRule.stubFor(get(urlEqualTo("/v1/pki_cloudstack/cert/ca_chain"))
                .willReturn(aResponse().withStatus(500).withBody("mount not found")));

        final OpenBaoClient client = newClient();
        try {
            client.get("/v1/pki_cloudstack/cert/ca_chain");
            Assert.fail("expected CloudRuntimeException for HTTP 500");
        } catch (final CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("500"));
            Assert.assertTrue(e.getMessage().contains("mount not found"));
        }
    }

    @Test
    public void testBuildBodySkipsNullAndEmptyValues() {
        final Map<String, String> entries = new HashMap<>();
        entries.put("common_name", "host.example.org");
        entries.put("alt_names", null);
        entries.put("ip_sans", "");
        final JsonObject body = OpenBaoClient.buildBody(entries);
        Assert.assertTrue(body.has("common_name"));
        Assert.assertFalse(body.has("alt_names"));
        Assert.assertFalse(body.has("ip_sans"));
    }

    @Test
    public void testPostSerializesBodyAsJson() {
        stubLogin(TOKEN_A);
        wireMockRule.stubFor(post(urlEqualTo("/v1/pki_cloudstack/issue/cloudstack"))
                .willReturn(okJson("{\"data\":{\"certificate\":\"stub\"}}")));

        final OpenBaoClient client = newClient();
        final Map<String, String> body = new HashMap<>();
        body.put("common_name", "mgmt.slytherin.eonf.ltd");
        client.post("/v1/pki_cloudstack/issue/cloudstack", OpenBaoClient.buildBody(body));

        wireMockRule.verify(postRequestedFor(urlEqualTo("/v1/pki_cloudstack/issue/cloudstack"))
                .withRequestBody(containing("mgmt.slytherin.eonf.ltd"))
                .withHeader("X-Vault-Token", com.github.tomakehurst.wiremock.client.WireMock.equalTo(TOKEN_A)));
    }
}
