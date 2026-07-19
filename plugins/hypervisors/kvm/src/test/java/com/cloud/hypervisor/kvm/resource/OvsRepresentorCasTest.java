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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonArray;
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
}
