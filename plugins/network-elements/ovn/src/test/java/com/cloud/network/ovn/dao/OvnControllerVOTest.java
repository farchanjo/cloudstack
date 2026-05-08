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
package com.cloud.network.ovn.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Exercises the {@link OvnControllerVO} field round-trip without standing up
 * a full DAO test harness. The DAOs themselves rely on
 * {@code com.cloud.utils.db.GenericDaoBase} which needs the CloudStack
 * test infrastructure; the persistence path is exercised end-to-end by the
 * Marvin suites.
 */
public class OvnControllerVOTest {

    @Test
    public void allArgsConstructorPopulatesFields() {
        final OvnControllerVO row = new OvnControllerVO(7L, "ovn-zone-1",
                "tcp:10.182.0.11:6641,tcp:10.182.0.12:6641", "tcp:10.182.0.11:6642");
        assertEquals(7L, row.getZoneId());
        assertEquals("ovn-zone-1", row.getName());
        assertEquals("tcp:10.182.0.11:6641,tcp:10.182.0.12:6641", row.getNbEndpoints());
        assertEquals("tcp:10.182.0.11:6642", row.getSbEndpoints());
        assertNotNull(row.getCreated());
        assertNull(row.getRemoved());
        assertNotNull(row.getUuid());
    }

    @Test
    public void settersUpdateFields() {
        final OvnControllerVO row = new OvnControllerVO();
        row.setZoneId(11L);
        row.setName("test");
        row.setNbEndpoints("tcp:host:6641");
        row.setSbEndpoints("tcp:host:6642");
        assertEquals(11L, row.getZoneId());
        assertEquals("test", row.getName());
    }
}
