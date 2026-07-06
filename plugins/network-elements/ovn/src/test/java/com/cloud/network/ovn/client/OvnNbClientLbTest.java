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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void attachLbSetsRouterForceSnatWhenAbsent() {
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
        assertEquals("lb_force_snat_ip must be written", true, found);
        assertEquals("pre-existing option keys must be preserved", true, preserved);
    }

    @Test
    public void attachLbForceSnatIsIdempotentWhenAlreadySet() {
        when(pool.call(anyString(), any())).thenReturn(
                emptyReply(),
                selectOptionsReply(OvnNbClient.LR_OPT_LB_FORCE_SNAT, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP));

        client.attachLoadBalancerToLogicalRouter("lr-uuid", "lb-uuid-11");

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

    @Test(expected = OvnException.class)
    public void updateBackendsWithNullMapFails() {
        client.updateLoadBalancerBackends("lb-uuid", null);
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
}
