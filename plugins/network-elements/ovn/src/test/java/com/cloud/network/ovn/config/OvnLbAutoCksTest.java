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
package com.cloud.network.ovn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.cloud.network.ovn.config.OvnLbAutoCks.Binding;

public class OvnLbAutoCksTest {

    private static final String CKS = "11111111-2222-3333-4444-555555555555";

    @Test
    public void emptyYieldsEmpty() {
        assertTrue(OvnLbAutoCks.parse(null).isEmpty());
        assertTrue(OvnLbAutoCks.parse("").isEmpty());
    }

    @Test
    public void parseSingleRule() {
        final List<Binding> list = OvnLbAutoCks.parse("1542=" + CKS + ":80");
        assertEquals(1, list.size());
        assertEquals(1542L, list.get(0).getRuleId());
        assertEquals(CKS, list.get(0).getClusterUuid());
        assertEquals(80, list.get(0).getDestPort());
    }

    @Test
    public void parseMultiRulesLastWinsOnDuplicateId() {
        final List<Binding> list = OvnLbAutoCks.parse(
                "1542=" + CKS + ":80;1545=" + CKS + ":443;1542=" + CKS + ":8080");
        assertEquals(2, list.size());
        // LinkedHashMap: first key order preserved; 1542 overwritten to 8080
        assertEquals(1542L, list.get(0).getRuleId());
        assertEquals(8080, list.get(0).getDestPort());
        assertEquals(1545L, list.get(1).getRuleId());
        assertEquals(443, list.get(1).getDestPort());
    }

    @Test
    public void malformedSkipped() {
        assertTrue(OvnLbAutoCks.parse("not-numeric=" + CKS + ":80").isEmpty());
        assertTrue(OvnLbAutoCks.parse("1542=" + CKS).isEmpty());
        assertTrue(OvnLbAutoCks.parse("1542=" + CKS + ":99999").isEmpty());
        assertTrue(OvnLbAutoCks.parse("0=" + CKS + ":80").isEmpty());
    }
}
