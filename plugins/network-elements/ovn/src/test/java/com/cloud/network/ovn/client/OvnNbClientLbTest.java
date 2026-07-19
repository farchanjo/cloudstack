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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire-format assertions for the load_balancer primitives on
 * {@link OvnNbClient}.
 */
public class OvnNbClientLbTest {

    private OvsdbConnectionPool pool;
    private OvnNbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        pool = mock(OvsdbConnectionPool.class);
        client = new OvnNbClient(pool);
    }

    @Test
    public void createLbEmitsInsertWithVipsMapAndReturnsUuid() {
        when(pool.call(anyString(), any())).thenReturn(singleInsertReply("lb-uuid-1"));

        final String uuid = client.createLoadBalancer("cs-lb-1",
                Map.of("192.168.100.10:80", "10.0.0.5:80,10.0.0.6:80"),
                OvnNbClient.LB_PROTOCOL_TCP,
                List.of("ip_src", "ip_dst"),
                Map.of("cs_kind", "LOAD_BALANCER", "cs_id", "1"));
        assertEquals("lb-uuid-1", uuid);

        final ArrayNode params = captureTransactCall();
        assertEquals("OVN_Northbound", params.get(0).asText());
        assertEquals("insert", params.get(1).get("op").asText());
        assertEquals("Load_Balancer", params.get(1).get("table").asText());
        final JsonNode row = params.get(1).get("row");
        assertEquals("cs-lb-1", row.get("name").asText());
        assertEquals("tcp", row.get("protocol").asText());
        // vips -> ["map", [ ["192.168.100.10:80", "10.0.0.5:80,10.0.0.6:80"] ]]
        final JsonNode vips = row.get("vips");
        assertEquals("map", vips.get(0).asText());
        assertEquals("192.168.100.10:80", vips.get(1).get(0).get(0).asText());
        // selection_fields -> ["set", [ ... ]]
        final JsonNode sel = row.get("selection_fields");
        assertEquals("set", sel.get(0).asText());
        assertNotNull(sel.get(1));
    }

    @Test
    public void createLbWithoutSelectionFieldsOmitsTheColumn() {
        when(pool.call(anyString(), any())).thenReturn(singleInsertReply("lb-uuid-2"));

        client.createLoadBalancer("cs-lb-2",
                Map.of("10.10.0.1:443", "172.16.0.5:443"),
                OvnNbClient.LB_PROTOCOL_TCP,
                List.of(),
                Map.of("cs_id", "2"));

        final ArrayNode params = captureTransactCall();
        final JsonNode row = params.get(1).get("row");
        assertNull("selection_fields must be absent for empty list", row.get("selection_fields"));
    }

    @Test
    public void attachLbToLrEmitsMutateInsertOnLoadBalancerColumn() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.attachLoadBalancerToLogicalRouter("lr-uuid", "lb-uuid-9");

        // Attach also probes the LR options for the force-SNAT assertion;
        // the empty reply (row missing) makes that probe a no-op.
        final List<JsonNode> calls = captureTransactCalls(2);
        final JsonNode mutate = ((ArrayNode) calls.get(0)).get(1);
        assertEquals("mutate", mutate.get("op").asText());
        assertEquals("Logical_Router", mutate.get("table").asText());
        final JsonNode mutation = mutate.get("mutations").get(0);
        assertEquals("load_balancer", mutation.get(0).asText());
        assertEquals("insert", mutation.get(1).asText());
    }

    @Test
    public void attachLbSetsRouterForceSnatWhenAbsentOnCentralizedRouter() {
        when(pool.call(anyString(), any())).thenReturn(
                emptyReply(),
                selectOptionsReply("chassis", "gw1"),
                emptyReply());

        client.attachLoadBalancerToLogicalRouter("lr-uuid", "lb-uuid-10");

        final List<JsonNode> calls = captureTransactCalls(3);
        final JsonNode update = ((ArrayNode) calls.get(2)).get(1);
        assertEquals("update", update.get("op").asText());
        assertEquals("Logical_Router", update.get("table").asText());
        final JsonNode options = update.get("row").get("options");
        assertEquals("map", options.get(0).asText());
        boolean found = false;
        boolean preserved = false;
        for (final JsonNode pair : options.get(1)) {
            if (OvnNbClient.LR_OPT_LB_FORCE_SNAT.equals(pair.get(0).asText())) {
                assertEquals(OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, pair.get(1).asText());
                found = true;
            }
            if ("chassis".equals(pair.get(0).asText())) {
                preserved = true;
            }
        }
        assertEquals("lb_force_snat_ip must be written on centralized LR", true, found);
        assertEquals("pre-existing option keys must be preserved", true, preserved);
    }

    @Test
    public void attachLbForceSnatIsIdempotentWhenAlreadySetOnCentralizedRouter() {
        when(pool.call(anyString(), any())).thenReturn(
                emptyReply(),
                selectOptionsReply("chassis", "gw1",
                        OvnNbClient.LR_OPT_LB_FORCE_SNAT, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP));

        client.attachLoadBalancerToLogicalRouter("lr-uuid", "lb-uuid-11");

        // mutate + options probe only; NO third update transaction.
        captureTransactCalls(2);
    }

    /**
     * Regression for OVN northd {@code "bad ip router_ip"} log spam on
     * CloudStack distributed VPC logical routers. The {@code router_ip}
     * magic value is only resolvable by northd when the LR carries
     * {@code options:chassis}; a distributed LR (no {@code options:chassis})
     * must never receive it, and any legacy value left by a prior plugin
     * version must be stripped on the next attach / reconcile pass.
     */
    @Test
    public void attachLbOnDistributedRouterStripsLegacyRouterIpMagic() {
        when(pool.call(anyString(), any())).thenReturn(
                emptyReply(),
                selectOptionsReply(OvnNbClient.LR_OPT_LB_FORCE_SNAT, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP),
                emptyReply());

        client.attachLoadBalancerToLogicalRouter("lr-uuid-distributed", "lb-uuid-strip");

        final List<JsonNode> calls = captureTransactCalls(3);
        final JsonNode update = ((ArrayNode) calls.get(2)).get(1);
        assertEquals("update", update.get("op").asText());
        assertEquals("Logical_Router", update.get("table").asText());
        final JsonNode options = update.get("row").get("options");
        assertEquals("map", options.get(0).asText());
        for (final JsonNode pair : options.get(1)) {
            assertFalse("distributed LR must not carry lb_force_snat_ip=router_ip",
                    OvnNbClient.LR_OPT_LB_FORCE_SNAT.equals(pair.get(0).asText())
                            && OvnNbClient.LB_FORCE_SNAT_ROUTER_IP.equals(pair.get(1).asText()));
        }
        assertEquals("legacy router_ip must be the only key removed", 0, options.get(1).size());
    }

    /**
     * Distributed router with no {@code lb_force_snat_ip} at all: the
     * attach path probes options, sees a distributed topology + absent
     * magic value, and must NOT emit any third update transaction (nothing
     * to write, nothing to strip).
     */
    @Test
    public void attachLbOnDistributedRouterWithoutLegacyValueIsNoOp() {
        when(pool.call(anyString(), any())).thenReturn(
                emptyReply(),
                // Some unrelated option (e.g. always_learn_from_arp) but NO
                // chassis and NO lb_force_snat_ip -> distributed + clean.
                selectOptionsReply("always_learn_from_arp", "true"));

        client.attachLoadBalancerToLogicalRouter("lr-uuid-distributed", "lb-uuid-noop");

        // mutate + options probe only; NO third update transaction.
        captureTransactCalls(2);
    }

    /**
     * A distributed router may still carry an explicit IPv4/IPv6
     * {@code lb_force_snat_ip} set by an operator (or a future CloudStack
     * feature that derives one). The plugin must NOT touch that value on a
     * distributed router — only the magic {@code router_ip} token is
     * provably inert there.
     */
    @Test
    public void attachLbOnDistributedRouterPreservesExplicitSnatIp() {
        when(pool.call(anyString(), any())).thenReturn(
                emptyReply(),
                selectOptionsReply(OvnNbClient.LR_OPT_LB_FORCE_SNAT, "203.0.113.10"),
                emptyReply());

        client.attachLoadBalancerToLogicalRouter("lr-uuid-distributed", "lb-uuid-explicit");

        // mutate + options probe only; NO third update transaction.
        captureTransactCalls(2);
    }

    @Test
    public void detachLbFromLrEmitsMutateDelete() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.detachLoadBalancerFromLogicalRouter("lr-uuid", "lb-uuid-9");

        final ArrayNode params = captureTransactCall();
        final JsonNode mutation = params.get(1).get("mutations").get(0);
        assertEquals("load_balancer", mutation.get(0).asText());
        assertEquals("delete", mutation.get(1).asText());
    }

    @Test
    public void detachLbFromLsEmitsMutateDeleteOnLogicalSwitch() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.detachLoadBalancerFromLogicalSwitch("ls-uuid", "lb-uuid-9");

        final ArrayNode params = captureTransactCall();
        assertEquals("Logical_Switch", params.get(1).get("table").asText());
        final JsonNode mutation = params.get(1).get("mutations").get(0);
        assertEquals("load_balancer", mutation.get(0).asText());
        assertEquals("delete", mutation.get(1).asText());
    }

    @Test
    public void configureHealthCheckEmitsInsertReferenceAndMappings() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.configureLoadBalancerHealthCheck("lb-uuid-hc",
                "217.179.89.35:6443",
                Map.of("10.45.0.125", "lsp-aaa:10.45.0.254"),
                Map.of("interval", "5", "timeout", "3", "success_count", "1", "failure_count", "3"),
                Map.of("cs_id", "882"));

        final ArrayNode params = captureTransactCall();
        // op1: insert Load_Balancer_Health_Check with the vip + options.
        final JsonNode insert = params.get(1);
        assertEquals("insert", insert.get("op").asText());
        assertEquals("Load_Balancer_Health_Check", insert.get("table").asText());
        assertEquals("217.179.89.35:6443", insert.get("row").get("vip").asText());
        assertEquals("map", insert.get("row").get("options").get(0).asText());
        // op2: mutate LB.health_check inserting the named uuid.
        final JsonNode mutate = params.get(2);
        assertEquals("mutate", mutate.get("op").asText());
        assertEquals("Load_Balancer", mutate.get("table").asText());
        assertEquals("health_check", mutate.get("mutations").get(0).get(0).asText());
        assertEquals("insert", mutate.get("mutations").get(0).get(1).asText());
        // op3: update LB.ip_port_mappings.
        final JsonNode update = params.get(3);
        assertEquals("update", update.get("op").asText());
        final JsonNode mappings = update.get("row").get("ip_port_mappings");
        assertEquals("map", mappings.get(0).asText());
        assertEquals("10.45.0.125", mappings.get(1).get(0).get(0).asText());
        assertEquals("lsp-aaa:10.45.0.254", mappings.get(1).get(0).get(1).asText());
    }

    @Test(expected = OvnException.class)
    public void configureHealthCheckWithoutMappingsFails() {
        client.configureLoadBalancerHealthCheck("lb-uuid", "1.2.3.4:80", Map.of(), Map.of(), Map.of());
    }

    @Test
    public void updateIpPortMappingsEmitsFullReplace() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        // Desired state after member destroy+recreate: old IP gone, new LSP.
        client.updateLoadBalancerIpPortMappings("lb-uuid-hc",
                Map.of("10.45.0.200", "lsp-new:10.45.0.254"));

        final ArrayNode params = captureTransactCall();
        final JsonNode update = params.get(1);
        assertEquals("update", update.get("op").asText());
        assertEquals("Load_Balancer", update.get("table").asText());
        final JsonNode mappings = update.get("row").get("ip_port_mappings");
        assertEquals("map", mappings.get(0).asText());
        assertEquals(1, mappings.get(1).size());
        assertEquals("10.45.0.200", mappings.get(1).get(0).get(0).asText());
        assertEquals("lsp-new:10.45.0.254", mappings.get(1).get(0).get(1).asText());
    }

    @Test
    public void updateIpPortMappingsEmptyClearsColumn() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.updateLoadBalancerIpPortMappings("lb-uuid-hc", Map.of());

        final ArrayNode params = captureTransactCall();
        final JsonNode mappings = params.get(1).get("row").get("ip_port_mappings");
        assertEquals("map", mappings.get(0).asText());
        assertEquals(0, mappings.get(1).size());
    }

    @Test(expected = OvnException.class)
    public void updateIpPortMappingsNullMapFails() {
        client.updateLoadBalancerIpPortMappings("lb-uuid", null);
    }

    @Test
    public void updateBackendsEmitsUpdateWithVips() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.updateLoadBalancerBackends("lb-uuid",
                Map.of("192.168.100.10:80", "10.0.0.5:80,10.0.0.6:80,10.0.0.7:80"));

        final ArrayNode params = captureTransactCall();
        assertEquals("update", params.get(1).get("op").asText());
        assertEquals("Load_Balancer", params.get(1).get("table").asText());
        final JsonNode vips = params.get(1).get("row").get("vips");
        assertEquals("map", vips.get(0).asText());
        // The full pool: vips["1.."] now includes 10.0.0.7:80.
        assertEquals(1, vips.get(1).size());
    }

    @Test
    public void deleteLbEmitsDeleteOnLoadBalancerTable() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.deleteLoadBalancer("lb-uuid-9");

        final ArrayNode params = captureTransactCall();
        assertEquals("delete", params.get(1).get("op").asText());
        assertEquals("Load_Balancer", params.get(1).get("table").asText());
    }

    @Test
    public void protocolConstantsMatchOvnNb() {
        assertEquals("tcp", OvnNbClient.LB_PROTOCOL_TCP);
        assertEquals("udp", OvnNbClient.LB_PROTOCOL_UDP);
        assertEquals("sctp", OvnNbClient.LB_PROTOCOL_SCTP);
    }

    @Test(expected = OvnException.class)
    public void createLbWithoutVipsFails() {
        client.createLoadBalancer("bad", Map.of(), null, null, null);
    }

    @Test
    public void createLbWithOptionsEmitsOptionsColumn() {
        when(pool.call(anyString(), any())).thenReturn(singleInsertReply("lb-uuid-3"));

        client.createLoadBalancer("cs-lb-3",
                Map.of("203.0.113.42:80", "10.0.0.5:80,10.0.0.6:80"),
                OvnNbClient.LB_PROTOCOL_TCP,
                List.of(),
                Map.of("cs_id", "3"),
                Map.of("hairpin_snat_ip", "203.0.113.42"));

        final ArrayNode params = captureTransactCall();
        final JsonNode row = params.get(1).get("row");
        final JsonNode options = row.get("options");
        assertNotNull("options column must be present", options);
        assertEquals("map", options.get(0).asText());
        // options -> ["map", [ ["hairpin_snat_ip", "203.0.113.42"] ]]
        assertEquals("hairpin_snat_ip", options.get(1).get(0).get(0).asText());
        assertEquals("203.0.113.42", options.get(1).get(0).get(1).asText());
    }

    @Test
    public void createLbWithoutOptionsOmitsTheColumn() {
        when(pool.call(anyString(), any())).thenReturn(singleInsertReply("lb-uuid-4"));

        client.createLoadBalancer("cs-lb-4",
                Map.of("10.10.0.1:443", "172.16.0.5:443"),
                OvnNbClient.LB_PROTOCOL_TCP,
                List.of(),
                Map.of("cs_id", "4"));

        final ArrayNode params = captureTransactCall();
        final JsonNode row = params.get(1).get("row");
        assertNull("options must be absent when not supplied", row.get("options"));
    }

    // ------------------------------------------------------------------
    // ensureLbForceSnatOnRoutersWithLb — reconcile-time safety net.
    // Drives the topology-aware forced-SNAT reconcile: centralized routers
    // get router_ip asserted; distributed routers get any legacy
    // router_ip magic value stripped. This is the live-cleanup path that
    // removes the legacy options={lb_force_snat_ip=router_ip} CloudStack
    // wrote on the two distributed Slytherin VPC LRs before the fix.
    // ------------------------------------------------------------------

    @Test
    public void reconcileLbForceSnatAssertsRouterIpOnCentralizedRouter() {
        // LR-A: centralized (options:chassis set), no lb_force_snat_ip yet.
        when(pool.call(anyString(), any())).thenReturn(
                selectRoutersReply(routerRow("lr-centralized",
                        new String[]{"lb-1"},
                        new String[][]{{"chassis", "gw1"}})),
                // ensureLbForceSnat re-reads options then writes the merge.
                selectOptionsReply("chassis", "gw1"),
                emptyReply());

        final int fixed = client.ensureLbForceSnatOnRoutersWithLb();

        assertEquals("centralized router missing router_ip must be fixed", 1, fixed);
        final List<JsonNode> calls = captureTransactCalls(3);
        final JsonNode update = ((ArrayNode) calls.get(2)).get(1);
        assertEquals("update", update.get("op").asText());
        final JsonNode options = update.get("row").get("options");
        boolean found = false;
        boolean preserved = false;
        for (final JsonNode pair : options.get(1)) {
            if (OvnNbClient.LR_OPT_LB_FORCE_SNAT.equals(pair.get(0).asText())) {
                assertEquals(OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, pair.get(1).asText());
                found = true;
            }
            if ("chassis".equals(pair.get(0).asText())) {
                preserved = true;
            }
        }
        assertTrue("router_ip must be asserted on centralized LR", found);
        assertTrue("chassis must be preserved", preserved);
    }

    @Test
    public void reconcileLbForceSnatStripsLegacyRouterIpOnDistributedRouter() {
        // LR-B: distributed (no chassis), carries stale router_ip magic.
        when(pool.call(anyString(), any())).thenReturn(
                selectRoutersReply(routerRow("lr-distributed",
                        new String[]{"lb-2"},
                        new String[][]{{OvnNbClient.LR_OPT_LB_FORCE_SNAT,
                                OvnNbClient.LB_FORCE_SNAT_ROUTER_IP}})),
                selectOptionsReply(OvnNbClient.LR_OPT_LB_FORCE_SNAT,
                        OvnNbClient.LB_FORCE_SNAT_ROUTER_IP),
                emptyReply());

        final int fixed = client.ensureLbForceSnatOnRoutersWithLb();

        assertEquals("distributed router with legacy router_ip must be cleaned", 1, fixed);
        final List<JsonNode> calls = captureTransactCalls(3);
        final JsonNode update = ((ArrayNode) calls.get(2)).get(1);
        assertEquals("update", update.get("op").asText());
        final JsonNode options = update.get("row").get("options");
        assertEquals("map", options.get(0).asText());
        assertEquals("router_ip must be the only key removed from distributed LR",
                0, options.get(1).size());
    }

    @Test
    public void reconcileLbForceSnatLeavesCleanDistributedRouterAlone() {
        // LR-C: distributed, no legacy router_ip -> no write.
        when(pool.call(anyString(), any())).thenReturn(
                selectRoutersReply(routerRow("lr-distributed-clean",
                        new String[]{"lb-3"},
                        new String[][]{{"always_learn_from_arp", "true"}})));

        final int fixed = client.ensureLbForceSnatOnRoutersWithLb();

        assertEquals("clean distributed router must not be touched", 0, fixed);
        captureTransactCalls(1);
    }

    @Test
    public void reconcileLbForceSnatLeavesCentralizedRouterWithRouterIpAlone() {
        // LR-D: centralized + already has router_ip -> no write.
        when(pool.call(anyString(), any())).thenReturn(
                selectRoutersReply(routerRow("lr-centralized-ok",
                        new String[]{"lb-4"},
                        new String[][]{{"chassis", "gw1"},
                                {OvnNbClient.LR_OPT_LB_FORCE_SNAT,
                                        OvnNbClient.LB_FORCE_SNAT_ROUTER_IP}})));

        final int fixed = client.ensureLbForceSnatOnRoutersWithLb();

        assertEquals("centralized router already carrying router_ip must not be touched", 0, fixed);
        captureTransactCalls(1);
    }

    @Test
    public void reconcileLbForceSnatSkipsRouterWithoutLb() {
        // LR-E: distributed, stale router_ip, but NO LB attached -> skip.
        when(pool.call(anyString(), any())).thenReturn(
                selectRoutersReply(routerRow("lr-empty",
                        new String[]{},
                        new String[][]{{OvnNbClient.LR_OPT_LB_FORCE_SNAT,
                                OvnNbClient.LB_FORCE_SNAT_ROUTER_IP}})));

        final int fixed = client.ensureLbForceSnatOnRoutersWithLb();

        assertEquals("router without LB must be skipped", 0, fixed);
        captureTransactCalls(1);
    }

    @Test
    public void reconcileLbForceSnatPreservesExplicitSnatIpOnDistributedRouter() {
        // LR-F: distributed, carries an explicit IPv4 lb_force_snat_ip
        // (operator-set or future-derived). Plugin must NOT touch it.
        when(pool.call(anyString(), any())).thenReturn(
                selectRoutersReply(routerRow("lr-distributed-explicit",
                        new String[]{"lb-5"},
                        new String[][]{{OvnNbClient.LR_OPT_LB_FORCE_SNAT, "203.0.113.10"}})));

        final int fixed = client.ensureLbForceSnatOnRoutersWithLb();

        assertEquals("explicit SNAT IP on distributed router must be preserved", 0, fixed);
        captureTransactCalls(1);
    }

    @Test(expected = OvnException.class)
    public void updateBackendsWithNullMapFails() {
        client.updateLoadBalancerBackends("lb-uuid", null);
    }

    // ------------------------------------------------------------------
    // readLogicalRouterOptionsPublic — transport/decode contract for the
    // scoped VPC force-SNAT dry-run path. These tests exercise the REAL
    // OvnNbClient JSON decode (not a mock) so a wire-format regression is
    // caught here rather than silently degrading to null at the service
    // layer.
    // ------------------------------------------------------------------

    @Test
    public void readLogicalRouterOptionsReturnsDecodedMapWhenRowPresent() throws Exception {
        when(pool.call(anyString(), any())).thenReturn(
                selectOptionsReply("chassis", "gw1",
                        OvnNbClient.LR_OPT_LB_FORCE_SNAT, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP));

        final Map<String, String> options = client.readLogicalRouterOptionsPublic("lr-uuid-1");

        assertNotNull(options);
        assertEquals(2, options.size());
        assertEquals("gw1", options.get("chassis"));
        assertEquals(OvnNbClient.LB_FORCE_SNAT_ROUTER_IP,
                options.get(OvnNbClient.LR_OPT_LB_FORCE_SNAT));
    }

    @Test
    public void readLogicalRouterOptionsReturnsDecodedMapWhenDistributedWithLegacyToken() throws Exception {
        // Distributed router (no chassis key) carrying the stale router_ip
        // magic value — the exact live state of the two Slytherin VPC LRs.
        when(pool.call(anyString(), any())).thenReturn(
                selectOptionsReply(OvnNbClient.LR_OPT_LB_FORCE_SNAT, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP));

        final Map<String, String> options = client.readLogicalRouterOptionsPublic("lr-distributed");

        assertNotNull(options);
        assertEquals(1, options.size());
        assertFalse("distributed LR must not have chassis key", options.containsKey("chassis"));
        assertEquals(OvnNbClient.LB_FORCE_SNAT_ROUTER_IP,
                options.get(OvnNbClient.LR_OPT_LB_FORCE_SNAT));
    }

    @Test
    public void readLogicalRouterOptionsReturnsEmptyMapWhenNoOptions() throws Exception {
        // Row exists but options map is empty (no keys).
        when(pool.call(anyString(), any())).thenReturn(selectOptionsReply());

        final Map<String, String> options = client.readLogicalRouterOptionsPublic("lr-empty");

        assertNotNull("empty options map must decode to non-null empty map, not null",
                options);
        assertTrue(options.isEmpty());
    }

    @Test
    public void readLogicalRouterOptionsReturnsNullWhenRowsEmpty() throws Exception {
        // Row does not exist — select returns empty rows array.
        final ObjectNode result = mapper.createObjectNode();
        result.set("rows", mapper.createArrayNode());
        final ArrayNode reply = mapper.createArrayNode();
        reply.add(result);
        when(pool.call(anyString(), any())).thenReturn(reply);

        final Map<String, String> options = client.readLogicalRouterOptionsPublic("lr-missing");

        assertNull("missing LR row must return null, not empty map", options);
    }

    @Test
    public void readLogicalRouterOptionsReturnsNullWhenReplyNull() throws Exception {
        when(pool.call(anyString(), any())).thenReturn(null);

        final Map<String, String> options = client.readLogicalRouterOptionsPublic("lr-null");

        assertNull("null reply must return null", options);
    }

    @Test
    public void readLogicalRouterOptionsReturnsNullWhenReplyEmptyArray() throws Exception {
        when(pool.call(anyString(), any())).thenReturn(mapper.createArrayNode());

        final Map<String, String> options = client.readLogicalRouterOptionsPublic("lr-empty-reply");

        assertNull("empty array reply must return null", options);
    }

    @Test(expected = OvnException.class)
    public void readLogicalRouterOptionsPropagatesTransportException() throws Exception {
        // Transport failure (OVSDB connection lost) — must propagate as
        // OvnException, NOT be swallowed into null. The scoped VPC
        // reconciler relies on this to fail closed instead of silently
        // treating a transport error as "no options".
        when(pool.call(anyString(), any())).thenThrow(new OvnException("transport error"));

        client.readLogicalRouterOptionsPublic("lr-transport-fail");
    }

    private ArrayNode captureTransactCall() {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool).call(anyString(), captor.capture());
        return (ArrayNode) captor.getValue();
    }

    private List<JsonNode> captureTransactCalls(final int expected) {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, times(expected)).call(anyString(), captor.capture());
        return captor.getAllValues();
    }

    /** Select reply carrying one Logical_Router row with an options map. */
    private ArrayNode selectOptionsReply(final String... kv) {
        final ArrayNode pairs = mapper.createArrayNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            final ArrayNode pair = mapper.createArrayNode();
            pair.add(kv[i]);
            pair.add(kv[i + 1]);
            pairs.add(pair);
        }
        final ArrayNode map = mapper.createArrayNode();
        map.add("map");
        map.add(pairs);
        final ObjectNode row = mapper.createObjectNode();
        row.set("options", map);
        final ObjectNode result = mapper.createObjectNode();
        result.set("rows", mapper.createArrayNode().add(row));
        final ArrayNode reply = mapper.createArrayNode();
        reply.add(result);
        return reply;
    }

    private ArrayNode singleInsertReply(final String uuid) {
        final ArrayNode reply = mapper.createArrayNode();
        reply.add(insertedRow(uuid));
        return reply;
    }

    private ArrayNode emptyReply() {
        final ArrayNode reply = mapper.createArrayNode();
        reply.add(mapper.createObjectNode());
        reply.add(mapper.createObjectNode());
        return reply;
    }

    private ObjectNode insertedRow(final String uuid) {
        final ObjectNode node = mapper.createObjectNode();
        final ArrayNode uuidArr = JsonNodeFactory.instance.arrayNode();
        uuidArr.add("uuid");
        uuidArr.add(uuid);
        node.set("uuid", uuidArr);
        return node;
    }

    /**
     * Builds a {@code select} reply carrying one Logical_Router row with
     * {@code _uuid}, {@code load_balancer} (uuid set), and {@code options}
     * (map). Used by the {@code ensureLbForceSnatOnRoutersWithLb}
     * reconcile-path tests.
     */
    private ArrayNode selectRoutersReply(final ObjectNode row) {
        final ObjectNode result = mapper.createObjectNode();
        result.set("rows", mapper.createArrayNode().add(row));
        final ArrayNode reply = mapper.createArrayNode();
        reply.add(result);
        return reply;
    }

    /**
     * Builds one Logical_Router row for {@link #selectRoutersReply}.
     *
     * @param uuid    the LR _uuid value
     * @param lbUuids load_balancer set members (empty array = no LBs)
     * @param opts    options map entries as {@code [[k, v], ...]}
     */
    private ObjectNode routerRow(final String uuid, final String[] lbUuids, final String[][] opts) {
        final ObjectNode row = mapper.createObjectNode();
        // _uuid -> ["uuid", "<uuid>"]
        final ArrayNode uuidCol = JsonNodeFactory.instance.arrayNode();
        uuidCol.add("uuid");
        uuidCol.add(uuid);
        row.set("_uuid", uuidCol);
        // load_balancer -> ["set", [ ["uuid", "<lb1>"], ... ]]
        final ArrayNode lbSet = JsonNodeFactory.instance.arrayNode();
        lbSet.add("set");
        final ArrayNode lbElements = JsonNodeFactory.instance.arrayNode();
        for (final String lb : lbUuids) {
            final ArrayNode lbRef = JsonNodeFactory.instance.arrayNode();
            lbRef.add("uuid");
            lbRef.add(lb);
            lbElements.add(lbRef);
        }
        lbSet.add(lbElements);
        row.set("load_balancer", lbSet);
        // options -> ["map", [ ["k", "v"], ... ]]
        final ArrayNode optMap = JsonNodeFactory.instance.arrayNode();
        optMap.add("map");
        final ArrayNode optPairs = JsonNodeFactory.instance.arrayNode();
        for (final String[] kv : opts) {
            final ArrayNode pair = JsonNodeFactory.instance.arrayNode();
            pair.add(kv[0]);
            pair.add(kv[1]);
            optPairs.add(pair);
        }
        optMap.add(optPairs);
        row.set("options", optMap);
        return row;
    }
}
