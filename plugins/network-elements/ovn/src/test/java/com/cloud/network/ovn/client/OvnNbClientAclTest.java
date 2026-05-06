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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * Wire-format assertions for the ACL primitives on {@link OvnNbClient}.
 */
public class OvnNbClientAclTest {

    private OvsdbConnectionPool pool;
    private OvnNbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        pool = mock(OvsdbConnectionPool.class);
        client = new OvnNbClient(pool);
    }

    @Test
    public void addAclEmitsInsertAndMutateOnLogicalSwitch() {
        when(pool.call(anyString(), any())).thenReturn(singleInsertReply("acl-uuid-1"));

        final String uuid = client.addAclToLogicalSwitch("ls-uuid", OvnNbClient.ACL_DIRECTION_FROM_LPORT,
                2000, "ip4.src == 10.0.0.0/8 && tcp.dst == 80", OvnNbClient.ACL_ACTION_ALLOW_RELATED,
                Map.of("cs_kind", "NETWORK_ACL", "cs_id", "101"), false, null, "csacl-101");
        assertEquals("acl-uuid-1", uuid);

        final ArrayNode params = captureTransactCall();
        // params[0] = db name, params[1] = insert ACL, params[2] = mutate Logical_Switch.
        assertEquals("OVN_Northbound", params.get(0).asText());
        assertEquals("insert", params.get(1).get("op").asText());
        assertEquals("ACL", params.get(1).get("table").asText());
        final JsonNode row = params.get(1).get("row");
        assertEquals("from-lport", row.get("direction").asText());
        assertEquals(2000, row.get("priority").asInt());
        assertEquals("ip4.src == 10.0.0.0/8 && tcp.dst == 80", row.get("match").asText());
        assertEquals("allow-related", row.get("action").asText());
        assertEquals(false, row.get("log").asBoolean());
        assertEquals("csacl-101", row.get("name").asText());

        // external_ids -> ["map", [ ["cs_kind","NETWORK_ACL"], ["cs_id","101"] ]].
        final JsonNode ext = row.get("external_ids");
        assertEquals("map", ext.get(0).asText());

        // Mutate op binds the new named-uuid into Logical_Switch.acls.
        assertEquals("mutate", params.get(2).get("op").asText());
        assertEquals("Logical_Switch", params.get(2).get("table").asText());
        final JsonNode mutation = params.get(2).get("mutations").get(0);
        assertEquals("acls", mutation.get(0).asText());
        assertEquals("insert", mutation.get(1).asText());
    }

    @Test
    public void removeAclEmitsMutateDeleteAndDeleteAcl() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.removeAclFromLogicalSwitch("ls-uuid", "acl-uuid-7");

        final ArrayNode params = captureTransactCall();
        // params[0] = db name, params[1] = mutate (delete), params[2] = delete ACL.
        assertEquals("mutate", params.get(1).get("op").asText());
        assertEquals("Logical_Switch", params.get(1).get("table").asText());
        final JsonNode mutation = params.get(1).get("mutations").get(0);
        assertEquals("acls", mutation.get(0).asText());
        assertEquals("delete", mutation.get(1).asText());

        assertEquals("delete", params.get(2).get("op").asText());
        assertEquals("ACL", params.get(2).get("table").asText());
    }

    @Test
    public void clearAllAclsEmitsUpdateWithEmptySet() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.clearAllAclsFromLogicalSwitch("ls-uuid");

        final ArrayNode params = captureTransactCall();
        assertEquals("update", params.get(1).get("op").asText());
        assertEquals("Logical_Switch", params.get(1).get("table").asText());
        final JsonNode acls = params.get(1).get("row").get("acls");
        assertEquals("set", acls.get(0).asText());
        assertEquals(0, acls.get(1).size());
    }

    @Test
    public void listAclsParsesUuidSetReply() {
        when(pool.call(anyString(), any())).thenReturn(selectReplyWithAclSet("a-1", "a-2"));
        final var ids = client.listAclsOnLogicalSwitch("ls-uuid");
        assertEquals(2, ids.size());
        assertTrue(ids.contains("a-1"));
        assertTrue(ids.contains("a-2"));
    }

    @Test
    public void allowActionsConstantsHaveExpectedSpellings() {
        assertEquals("allow", OvnNbClient.ACL_ACTION_ALLOW);
        assertEquals("allow-related", OvnNbClient.ACL_ACTION_ALLOW_RELATED);
        assertEquals("allow-stateless", OvnNbClient.ACL_ACTION_ALLOW_STATELESS);
        assertEquals("drop", OvnNbClient.ACL_ACTION_DROP);
        assertEquals("reject", OvnNbClient.ACL_ACTION_REJECT);
        assertEquals("from-lport", OvnNbClient.ACL_DIRECTION_FROM_LPORT);
        assertEquals("to-lport", OvnNbClient.ACL_DIRECTION_TO_LPORT);
    }

    private ArrayNode captureTransactCall() {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool).call(anyString(), captor.capture());
        return (ArrayNode) captor.getValue();
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
        reply.add(mapper.createObjectNode());
        return reply;
    }

    private ArrayNode selectReplyWithAclSet(final String... uuids) {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        final ArrayNode rows = mapper.createArrayNode();
        final ObjectNode row = mapper.createObjectNode();
        final ArrayNode setNode = mapper.createArrayNode();
        setNode.add("set");
        final ArrayNode elements = mapper.createArrayNode();
        for (final String u : uuids) {
            final ArrayNode uuidRef = mapper.createArrayNode();
            uuidRef.add("uuid");
            uuidRef.add(u);
            elements.add(uuidRef);
        }
        setNode.add(elements);
        row.set("acls", setNode);
        rows.add(row);
        entry.set("rows", rows);
        reply.add(entry);
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
