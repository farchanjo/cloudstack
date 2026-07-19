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
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class OvsRepresentorCasTest {
    @Test
    public void transactionUsesProtocolExactTypesAndGC() {
        final JsonArray request = JsonParser.parseString(OvsRepresentorCas.transactionForTest(
                "iface-1", "port-1", "bridge-1", "rep0", "lsp-1")).getAsJsonArray();
        assertEquals("Open_vSwitch", request.get(0).getAsString());
        for (int index = 1; index <= 3; index++) {
            final JsonObject wait = request.get(index).getAsJsonObject();
            assertEquals(0, wait.get("timeout").getAsInt());
            assertTrue(wait.get("timeout").getAsJsonPrimitive().isNumber());
        }
        final JsonArray name = request.get(1).getAsJsonObject().getAsJsonArray("where")
                .get(1).getAsJsonArray();
        assertTrue(name.get(2).isJsonPrimitive());
        assertEquals("rep0", name.get(2).getAsString());
        assertEquals("Interface", request.get(6).getAsJsonObject().get("table").getAsString());
    }

    @Test
    public void removeRequiresIfaceIdBeforeAnyExecutorCall() {
        final int[] calls = {0};
        assertFalse(OvsRepresentorCas.remove(args -> {
            calls[0]++;
            return new OvsRepresentorCas.Result(true, "", "");
        }, "unix:/var/run/openvswitch/db.sock", "rep0", null));
        assertEquals(0, calls[0]);
    }

    @Test
    public void removeRejectsMalformedOrPartialMutationResponse() {
        final Deque<String> responses = discoveryResponses("lsp-1");
        responses.add("[{},{},{},{\"count\":1}]");
        assertFalse(OvsRepresentorCas.remove(executor(responses), "unix:/var/run/openvswitch/db.sock",
                "rep0", "lsp-1"));
    }

    @Test
    public void removeRejectsZeroCountAndOperationError() {
        for (String mutationResponse : Arrays.asList(
                "[{},{},{},{\"count\":0},{\"count\":1},{\"count\":1}]",
                "[{\"rows\":[{}]},{\"rows\":[{}]},{\"rows\":[{}]},{\"error\":\"constraint\"},{\"count\":1},{\"count\":1}]")) {
            final Deque<String> responses = discoveryResponses("lsp-1");
            responses.add(mutationResponse.replace("{\"rows\":[{}]}", "{}"));
            assertFalse(OvsRepresentorCas.remove(executor(responses), "unix:/var/run/openvswitch/db.sock",
                    "rep0", "lsp-1"));
        }
    }

    @Test
    public void removeRejectsIfaceMismatchBeforeMutation() {
        final Deque<String> responses = discoveryResponses("lsp-other");
        final int[] transactions = {0};
        assertFalse(OvsRepresentorCas.remove(args -> {
            final String response = responses.removeFirst();
            if (args.length > 3 && "transact".equals(args[1])
                    && JsonParser.parseString(args[3]).getAsJsonArray().size() > 2) {
                transactions[0]++;
            }
            return new OvsRepresentorCas.Result(true, response, "");
        }, "unix:/var/run/openvswitch/db.sock", "rep0", "lsp-1"));
        assertEquals(0, transactions[0]);
    }

    @Test
    public void removeRejectsRecreatedInterfaceAtFinalPostcondition() {
        final Deque<String> responses = discoveryResponses("lsp-1");
        responses.add("[{\"rows\":[{\"_uuid\":[\"uuid\",\"new-iface\"],\"name\":\"rep0\",\"external_ids\":[\"map\",[[\"iface-id\",\"lsp-1\"]]]}]}]");
        responses.add("[{\"rows\":[{\"_uuid\":[\"uuid\",\"port-1\"],\"name\":\"rep0\",\"interfaces\":[\"set\",[[\"uuid\",\"iface-1\"]]]}]}]");
        assertFalse(OvsRepresentorCas.remove(executorWithValidMutation(responses),
                "unix:/var/run/openvswitch/db.sock", "rep0", "lsp-1"));
    }

    private static Deque<String> discoveryResponses(final String ifaceId) {
        final Deque<String> responses = new ArrayDeque<>();
        responses.add("[{\"rows\":[{\"_uuid\":[\"uuid\",\"iface-1\"],\"name\":\"rep0\",\"external_ids\":[\"map\",[[\"iface-id\",\""
                + ifaceId + "\"]]]}]}]");
        responses.add("[{\"rows\":[{\"_uuid\":[\"uuid\",\"port-1\"],\"name\":\"rep0\",\"interfaces\":[\"set\",[[\"uuid\",\"iface-1\"]]]}]}]");
        responses.add("[{\"rows\":[{\"_uuid\":[\"uuid\",\"bridge-1\"],\"ports\":[\"set\",[[\"uuid\",\"port-1\"]]]}]}]");
        return responses;
    }

    private static OvsRepresentorCas.Executor executor(final Deque<String> responses) {
        return args -> new OvsRepresentorCas.Result(true, responses.removeFirst(), "");
    }

    private static OvsRepresentorCas.Executor executorWithValidMutation(final Deque<String> responses) {
        return args -> {
            boolean mutation = false;
            if (args.length > 3 && "transact".equals(args[1])) {
                for (JsonElement element : JsonParser.parseString(args[3]).getAsJsonArray()) {
                    if (element.getAsJsonObject().has("op")
                            && "mutate".equals(element.getAsJsonObject().get("op").getAsString())) {
                        mutation = true;
                        break;
                    }
                }
            }
            if (mutation) {
                return new OvsRepresentorCas.Result(true,
                        "[{},{},{},{\"count\":1},{\"count\":1},{\"count\":1}]", "");
            }
            return new OvsRepresentorCas.Result(true, responses.removeFirst(), "");
        };
    }
}
