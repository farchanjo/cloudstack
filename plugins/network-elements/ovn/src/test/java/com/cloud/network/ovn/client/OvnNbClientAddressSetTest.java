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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire-format assertions for the {@code Address_Set} primitives on {@link
 * OvnNbClient} backing the SNAT destination-exemption feature.
 */
public class OvnNbClientAddressSetTest {

    private OvsdbConnectionPool pool;
    private OvnNbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        pool = mock(OvsdbConnectionPool.class);
        client = new OvnNbClient(pool);
    }

    @Test
    public void ensureAddressSet_createsRow_whenMissing() {
        when(pool.call(anyString(), any()))
                .thenReturn(emptySelectReply())
                .thenReturn(singleInsertReply("aset-uuid-1"));

        final String uuid = client.ensureAddressSet("rr_snat_exempt", List.of("217.179.88.34", "217.179.88.35"));

        assertEquals("aset-uuid-1", uuid);
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, times(2)).call(anyString(), captor.capture());
        final ArrayNode insertParams = (ArrayNode) captor.getAllValues().get(1);
        assertEquals("insert", insertParams.get(1).get("op").asText());
        assertEquals("Address_Set", insertParams.get(1).get("table").asText());
        assertEquals("rr_snat_exempt", insertParams.get(1).get("row").get("name").asText());
        final JsonNode addresses = insertParams.get(1).get("row").get("addresses");
        assertEquals("set", addresses.get(0).asText());
        assertEquals(2, addresses.get(1).size());
    }

    @Test
    public void ensureAddressSet_updatesRow_whenAddressesDrift() {
        when(pool.call(anyString(), any()))
                .thenReturn(selectUuidReply("aset-uuid-2"))
                .thenReturn(selectAddressesReply("217.179.88.34"))
                .thenReturn(emptyReply());

        final String uuid = client.ensureAddressSet("rr_snat_exempt", List.of("217.179.88.34", "217.179.88.36"));

        assertEquals("aset-uuid-2", uuid);
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, times(3)).call(anyString(), captor.capture());
        final ArrayNode updateParams = (ArrayNode) captor.getAllValues().get(2);
        assertEquals("update", updateParams.get(1).get("op").asText());
        assertEquals("Address_Set", updateParams.get(1).get("table").asText());
    }

    @Test
    public void ensureAddressSet_isNoOp_whenAddressesAlreadyMatch() {
        when(pool.call(anyString(), any()))
                .thenReturn(selectUuidReply("aset-uuid-3"))
                .thenReturn(selectAddressesReply("217.179.88.34", "217.179.88.35"));

        final String uuid = client.ensureAddressSet("rr_snat_exempt",
                List.of("217.179.88.35", "217.179.88.34"));

        assertEquals("aset-uuid-3", uuid);
        // Only the two lookups fire — no update op when the set is already correct.
        verify(pool, times(2)).call(anyString(), any());
    }

    @Test
    public void findAddressSetUuidByName_returnsUuid_whenFound() {
        when(pool.call(anyString(), any())).thenReturn(selectUuidReply("aset-uuid-4"));
        assertEquals("aset-uuid-4", client.findAddressSetUuidByName("rr_snat_exempt"));
    }

    @Test
    public void findAddressSetUuidByName_returnsNull_whenMissing() {
        when(pool.call(anyString(), any())).thenReturn(emptySelectReply());
        assertNull(client.findAddressSetUuidByName("rr_snat_exempt"));
    }

    @Test
    public void listAddressSetAddresses_parsesMultiElementSet() {
        when(pool.call(anyString(), any())).thenReturn(selectAddressesReply("217.179.88.34", "217.179.88.35"));
        final List<String> addresses = client.listAddressSetAddresses("aset-uuid-5");
        assertEquals(2, addresses.size());
        assertTrue(addresses.contains("217.179.88.34"));
        assertTrue(addresses.contains("217.179.88.35"));
    }

    @Test
    public void listAddressSetAddresses_parsesSingleElementAtomForm() {
        // RFC 7047 5.1: a single-element set MAY be sent as the bare atom.
        when(pool.call(anyString(), any())).thenReturn(selectAddressesAtomReply("217.179.88.34"));
        final List<String> addresses = client.listAddressSetAddresses("aset-uuid-6");
        assertEquals(1, addresses.size());
        assertEquals("217.179.88.34", addresses.get(0));
    }

    @Test
    public void natSetExemptedExtIps_emitsUpdateWithSingletonRealUuidRef() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.natSetExemptedExtIps("nat-uuid-1", "aset-uuid-7");

        final ArrayNode params = captureTransactCall();
        assertEquals("update", params.get(1).get("op").asText());
        assertEquals("NAT", params.get(1).get("table").asText());
        final JsonNode exempted = params.get(1).get("row").get("exempted_ext_ips");
        assertEquals("set", exempted.get(0).asText());
        assertEquals("uuid", exempted.get(1).get(0).get(0).asText());
        assertEquals("aset-uuid-7", exempted.get(1).get(0).get(1).asText());
    }

    private ArrayNode captureTransactCall() {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool).call(anyString(), captor.capture());
        return (ArrayNode) captor.getValue();
    }

    private ArrayNode singleInsertReply(final String uuid) {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode node = mapper.createObjectNode();
        final ArrayNode uuidArr = mapper.createArrayNode();
        uuidArr.add("uuid");
        uuidArr.add(uuid);
        node.set("uuid", uuidArr);
        reply.add(node);
        return reply;
    }

    private ArrayNode emptyReply() {
        final ArrayNode reply = mapper.createArrayNode();
        reply.add(mapper.createObjectNode());
        return reply;
    }

    /** Builds a select reply with a single row's {@code _uuid} column set. */
    private ArrayNode selectUuidReply(final String uuid) {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        final ArrayNode rows = mapper.createArrayNode();
        final ObjectNode row = mapper.createObjectNode();
        final ArrayNode uuidRef = mapper.createArrayNode();
        uuidRef.add("uuid");
        uuidRef.add(uuid);
        row.set("_uuid", uuidRef);
        rows.add(row);
        entry.set("rows", rows);
        reply.add(entry);
        return reply;
    }

    /** Builds a select reply with zero rows. */
    private ArrayNode emptySelectReply() {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        entry.set("rows", mapper.createArrayNode());
        reply.add(entry);
        return reply;
    }

    /** Builds a select reply with an {@code addresses} column as a plain string set. */
    private ArrayNode selectAddressesReply(final String... addresses) {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        final ArrayNode rows = mapper.createArrayNode();
        final ObjectNode row = mapper.createObjectNode();
        final ArrayNode setNode = mapper.createArrayNode();
        setNode.add("set");
        final ArrayNode elements = mapper.createArrayNode();
        for (final String a : addresses) {
            elements.add(a);
        }
        setNode.add(elements);
        row.set("addresses", setNode);
        rows.add(row);
        entry.set("rows", rows);
        reply.add(entry);
        return reply;
    }

    /** Builds a select reply with a single-element {@code addresses} column sent as a bare atom. */
    private ArrayNode selectAddressesAtomReply(final String address) {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        final ArrayNode rows = mapper.createArrayNode();
        final ObjectNode row = mapper.createObjectNode();
        row.put("addresses", address);
        rows.add(row);
        entry.set("rows", rows);
        reply.add(entry);
        return reply;
    }
}
