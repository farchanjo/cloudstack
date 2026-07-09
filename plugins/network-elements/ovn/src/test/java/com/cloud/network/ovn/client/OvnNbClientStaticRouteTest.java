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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wire-format assertions for the {@code Logical_Router_Static_Route} primitives
 * on {@link OvnNbClient}, focused on the referential-integrity-safe delete path
 * used by the ECMP static-route reconciler's stale-row prune.
 *
 * <p>{@code Logical_Router.static_routes} is a strong reference set: a bare row
 * delete is rejected by OVSDB ({@code referential integrity violation}) while an
 * LR still lists the row. {@link OvnNbClient#deleteLogicalRouterStaticRouteDirect}
 * must therefore discover the owning LR and detach the row from
 * {@code static_routes} in the same transaction as the row delete.
 */
public class OvnNbClientStaticRouteTest {

    private static final String ROUTE_UUID = "route-uuid-1";
    private static final String LR_UUID = "lr-uuid-parent";

    private OvsdbConnectionPool pool;
    private OvnNbClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        pool = mock(OvsdbConnectionPool.class);
        client = new OvnNbClient(pool);
    }

    // ------------------------------------------------------------------
    // add
    // ------------------------------------------------------------------

    @Test
    public void addStaticRouteEmitsInsertAndMutateOnLogicalRouter() {
        when(pool.call(anyString(), any())).thenReturn(singleInsertReply(ROUTE_UUID));

        final String uuid = client.addLogicalRouterStaticRoute(LR_UUID, "10.141.0.0/24", "10.45.4.127",
                null, null, java.util.Map.of("cs-ecmp-route", "net-uuid"));
        assertEquals(ROUTE_UUID, uuid);

        final ArrayNode params = captureSingle();
        // params[0] = db name, params[1] = insert route, params[2] = mutate LR.static_routes.
        assertEquals("insert", params.get(1).get("op").asText());
        assertEquals("Logical_Router_Static_Route", params.get(1).get("table").asText());
        assertEquals("10.141.0.0/24", params.get(1).get("row").get("ip_prefix").asText());
        assertEquals("10.45.4.127", params.get(1).get("row").get("nexthop").asText());
        assertEquals("mutate", params.get(2).get("op").asText());
        assertEquals("Logical_Router", params.get(2).get("table").asText());
        final JsonNode mutation = params.get(2).get("mutations").get(0);
        assertEquals("static_routes", mutation.get(0).asText());
        assertEquals("insert", mutation.get(1).asText());
    }

    // ------------------------------------------------------------------
    // detach-aware delete (the fix)
    // ------------------------------------------------------------------

    @Test
    public void deleteDirectDetachesFromParentThenDeletesRow_whenLrFound() {
        // 1st call: select finds the owning LR. 2nd call: mutate-delete + delete.
        when(pool.call(anyString(), any()))
                .thenReturn(selectLrRowsReply(LR_UUID))
                .thenReturn(emptyReply());

        client.deleteLogicalRouterStaticRouteDirect(ROUTE_UUID);

        final List<ArrayNode> calls = captureAll(2);

        // First txn: a select on Logical_Router where static_routes includes the row.
        final JsonNode select = calls.get(0).get(1);
        assertEquals("select", select.get("op").asText());
        assertEquals("Logical_Router", select.get("table").asText());
        final JsonNode cond = select.get("where").get(0);
        assertEquals("static_routes", cond.get(0).asText());
        assertEquals("includes", cond.get(1).asText());

        // Second txn: detach from Logical_Router.static_routes, THEN delete the row.
        final ArrayNode del = calls.get(1);
        assertEquals("mutate", del.get(1).get("op").asText());
        assertEquals("Logical_Router", del.get(1).get("table").asText());
        final JsonNode mutation = del.get(1).get("mutations").get(0);
        assertEquals("static_routes", mutation.get(0).asText());
        assertEquals("delete", mutation.get(1).asText());
        assertEquals("delete", del.get(2).get("op").asText());
        assertEquals("Logical_Router_Static_Route", del.get(2).get("table").asText());
    }

    @Test
    public void deleteDirectDoesSingleDelete_whenNoLrReferencesRow() {
        // 1st call: select returns no LR. 2nd call: a single-op direct delete.
        when(pool.call(anyString(), any()))
                .thenReturn(emptyLrSelectReply())
                .thenReturn(emptyReply());

        client.deleteLogicalRouterStaticRouteDirect(ROUTE_UUID);

        final List<ArrayNode> calls = captureAll(2);
        final ArrayNode del = calls.get(1);
        assertEquals("delete", del.get(1).get("op").asText());
        assertEquals("Logical_Router_Static_Route", del.get(1).get("table").asText());
    }

    @Test
    public void deleteDirectIsNoOp_forNullUuid() {
        client.deleteLogicalRouterStaticRouteDirect(null);
        verify(pool, times(0)).call(anyString(), any());
    }

    @Test
    public void deleteDirectIsNoOp_forEmptyUuid() {
        client.deleteLogicalRouterStaticRouteDirect("");
        verify(pool, times(0)).call(anyString(), any());
    }

    // ------------------------------------------------------------------
    // explicit detach-and-delete (parent LR known)
    // ------------------------------------------------------------------

    @Test
    public void deleteWithLrEmitsMutateDeleteThenDeleteRow() {
        when(pool.call(anyString(), any())).thenReturn(emptyReply());

        client.deleteLogicalRouterStaticRoute(LR_UUID, ROUTE_UUID);

        final ArrayNode params = captureSingle();
        assertEquals("mutate", params.get(1).get("op").asText());
        assertEquals("Logical_Router", params.get(1).get("table").asText());
        final JsonNode mutation = params.get(1).get("mutations").get(0);
        assertEquals("static_routes", mutation.get(0).asText());
        assertEquals("delete", mutation.get(1).asText());
        assertEquals("delete", params.get(2).get("op").asText());
        assertEquals("Logical_Router_Static_Route", params.get(2).get("table").asText());
    }

    // ------------------------------------------------------------------
    // findLrForStaticRoute
    // ------------------------------------------------------------------

    @Test
    public void findLrForStaticRoute_returnsUuid_whenFound() {
        when(pool.call(anyString(), any())).thenReturn(selectLrRowsReply(LR_UUID));
        assertEquals(LR_UUID, client.findLrForStaticRoute(ROUTE_UUID));
    }

    @Test
    public void findLrForStaticRoute_returnsNull_whenNoneFound() {
        when(pool.call(anyString(), any())).thenReturn(emptyLrSelectReply());
        assertNull(client.findLrForStaticRoute(ROUTE_UUID));
    }

    // ------------------------------------------------------------------
    // helpers (mirrors OvnNbClientAclTest)
    // ------------------------------------------------------------------

    private ArrayNode captureSingle() {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool).call(anyString(), captor.capture());
        return (ArrayNode) captor.getValue();
    }

    private List<ArrayNode> captureAll(final int expected) {
        final ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, times(expected)).call(anyString(), captor.capture());
        return captor.getAllValues().stream().map(v -> (ArrayNode) v).collect(java.util.stream.Collectors.toList());
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

    /** Builds a select reply with a single Logical_Router row. */
    private ArrayNode selectLrRowsReply(final String lrUuid) {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        final ArrayNode rows = mapper.createArrayNode();
        final ObjectNode row = mapper.createObjectNode();
        final ArrayNode uuidRef = mapper.createArrayNode();
        uuidRef.add("uuid");
        uuidRef.add(lrUuid);
        row.set("_uuid", uuidRef);
        rows.add(row);
        entry.set("rows", rows);
        reply.add(entry);
        return reply;
    }

    /** Builds a select reply with zero rows. */
    private ArrayNode emptyLrSelectReply() {
        final ArrayNode reply = mapper.createArrayNode();
        final ObjectNode entry = mapper.createObjectNode();
        entry.set("rows", mapper.createArrayNode());
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
