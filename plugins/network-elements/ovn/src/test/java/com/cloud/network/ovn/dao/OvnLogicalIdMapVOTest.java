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

import org.junit.Test;

import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;

public class OvnLogicalIdMapVOTest {

    @Test
    public void kindRoundTripsAsString() {
        final OvnLogicalIdMapVO row = new OvnLogicalIdMapVO(Kind.VPC, 17L, 1L,
                "abcd", "lr-test");
        assertEquals(Kind.VPC, row.getKind());
        assertEquals("VPC", row.getCsKind());
    }

    @Test
    public void supportsAllKindValues() {
        for (final Kind k : Kind.values()) {
            final OvnLogicalIdMapVO row = new OvnLogicalIdMapVO(k, 1L, 1L, "x", "y");
            assertEquals(k, row.getKind());
        }
    }
}
