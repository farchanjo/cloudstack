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

/**
 * Mocks the {@link OvsdbConnectionPool} so the test asserts the wire shape
 * produced by {@link OvnNbClient} for each NB operation.
 */
public class OvnNbClientTest {

    private OvsdbConnectionPool pool;
    private OvnNbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        pool = mock(OvsdbConnectionPool.class);
        client = new OvnNbClient(pool);
        // Default reply: one inserted row with a fixed UUID.
        when(pool.call(anyString(), any())).thenAnswer(inv -> singleInsertReply("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    public void createLogicalRouterEmitsInsertAndReturnsUuid() {
        final String uuid = client.createLogicalRouter("lr-test", Map.of("cs_kind", "VPC", "cs_id", "42"));
        assertEquals("00000000-0000-0000-0000-000000000001", uuid);

        final ArrayNode params = captureTransactCall();
        assertEquals("OVN_Northbound", params.get(0).asText());
        final JsonNode op = params.get(1);
        assertEquals("insert", op.get("op").asText());
        assertEquals("Logical_Router", op.get("table").asText());
        assertEquals("lr-test", op.get("row").get("name").asText());
        // external_ids must serialise as ["map", [[k,v], ...]]
        final JsonNode ext = op.get("row").get("external_ids");
        assertEquals("map", ext.get(0).asText());
    }

    @Test
    public void addLogicalRouterPortBatchesInsertAndMutate() {
        when(pool.call(anyString(), any())).thenAnswer(inv -> twoInsertReply("11", "12"));

        final String uuid = client.addLogicalRouterPort("router-uuid", "lrp-A", "02:00:00:01:01:01", List.of("10.101.0.1/24"));
        assertNotNull(uuid);

        final ArrayNode params = captureTransactCall();
        assertEquals("insert", params.get(1).get("op").asText());
        assertEquals("Logical_Router_Port", params.get(1).get("table").asText());
        assertEquals("mutate", params.get(2).get("op").asText());
        assertEquals("Logical_Router", params.get(2).get("table").asText());
        assertEquals("ports", params.get(2).get("mutations").get(0).get(0).asText());
    }

    @Test
    public void addNatRulePersistsRowAndAttachesToLr() {
        client.addNatRule("router-uuid", "snat", "192.168.100.1", "10.101.0.0/24", null);

        final ArrayNode params = captureTransactCall();
        assertEquals("insert", params.get(1).get("op").asText());
        assertEquals("NAT", params.get(1).get("table").asText());
        assertEquals("snat", params.get(1).get("row").get("type").asText());
        assertEquals("192.168.100.1", params.get(1).get("row").get("external_ip").asText());
        assertEquals("mutate", params.get(2).get("op").asText());
        assertEquals("nat", params.get(2).get("mutations").get(0).get(0).asText());
    }

    @Test
    public void bindLrToLsEmitsFourOpsInOneTransaction() {
        when(pool.call(anyString(), any())).thenAnswer(inv -> fourInsertReply("a", "b", "c", "d"));
        client.bindLrToLs(new OvnNbClient.BindRequest("lr", "ls", "lrp-A", "02:00:00:01:01:01", List.of("10.101.0.1/24"), "rsp-A"));

        final ArrayNode params = captureTransactCall();
        // params[0] = db name, then 4 ops: insert LRP, mutate LR.ports, insert LSP type=router, mutate LS.ports.
        assertEquals(5, params.size());
        assertEquals("Logical_Router_Port", params.get(1).get("table").asText());
        assertEquals("Logical_Router", params.get(2).get("table").asText());
        assertEquals("Logical_Switch_Port", params.get(3).get("table").asText());
        assertEquals("router", params.get(3).get("row").get("type").asText());
        assertEquals("Logical_Switch", params.get(4).get("table").asText());
        verify(pool, times(1)).call(anyString(), any());
    }

    private ArrayNode captureTransactCall() {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool).call(anyString(), captor.capture());
        return (ArrayNode) captor.getValue();
    }

    private ArrayNode singleInsertReply(final String uuid) {
        final ArrayNode reply = JsonNodeFactory.instance.arrayNode();
        reply.add(insertedRow(uuid));
        return reply;
    }

    private ArrayNode twoInsertReply(final String a, final String b) {
        final ArrayNode reply = JsonNodeFactory.instance.arrayNode();
        reply.add(insertedRow(a));
        reply.add(insertedRow(b));
        return reply;
    }

    private ArrayNode fourInsertReply(final String a, final String b, final String c, final String d) {
        final ArrayNode reply = JsonNodeFactory.instance.arrayNode();
        reply.add(insertedRow(a));
        reply.add(insertedRow(b));
        reply.add(insertedRow(c));
        reply.add(insertedRow(d));
        return reply;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode insertedRow(final String uuid) {
        final var node = mapper.createObjectNode();
        final ArrayNode uuidArr = mapper.createArrayNode();
        uuidArr.add("uuid");
        uuidArr.add(uuid);
        node.set("uuid", uuidArr);
        return node;
    }
}
