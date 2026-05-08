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
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import com.cloud.network.ovn.client.transport.OvsdbEndpoint;

public class OvsdbEndpointTest {

    @Test
    public void parsesValidEndpoint() {
        final OvsdbEndpoint endpoint = OvsdbEndpoint.parse("tcp:10.182.0.12:6641");
        assertEquals("10.182.0.12", endpoint.getHost());
        assertEquals(6641, endpoint.getPort());
        assertEquals("tcp:10.182.0.12:6641", endpoint.toString());
    }

    @Test
    public void parsesProductionCsv() {
        final List<OvsdbEndpoint> list = OvsdbEndpoint.parseList(
                "tcp:10.182.0.11:6641,tcp:10.182.0.12:6641,tcp:10.182.0.13:6641");
        assertEquals(3, list.size());
        assertEquals("10.182.0.11", list.get(0).getHost());
        assertEquals("10.182.0.13", list.get(2).getHost());
    }

    @Test
    public void rejectsUnsupportedScheme() {
        assertThrows(OvnException.class, () -> OvsdbEndpoint.parse("ssl:host:6641"));
    }

    @Test
    public void rejectsMalformedFormat() {
        assertThrows(OvnException.class, () -> OvsdbEndpoint.parse("tcp:10.182.0.12"));
        assertThrows(OvnException.class, () -> OvsdbEndpoint.parse("tcp:10.182.0.12:abc"));
    }

    @Test
    public void rejectsEmptyCsv() {
        assertThrows(OvnException.class, () -> OvsdbEndpoint.parseList(""));
        assertThrows(OvnException.class, () -> OvsdbEndpoint.parseList(" , "));
    }

    @Test
    public void skipsBlankCsvEntries() {
        final List<OvsdbEndpoint> list = OvsdbEndpoint.parseList(" tcp:10.182.0.12:6641 , , tcp:10.182.0.13:6641 ");
        assertEquals(2, list.size());
    }
}
