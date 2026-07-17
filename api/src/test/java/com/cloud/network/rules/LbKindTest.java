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
package com.cloud.network.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cloud.network.rules.LoadBalancerContainer.LbKind;

public class LbKindTest {

    @Test
    public void defaultFromNullIsCtLb() {
        assertEquals(LbKind.CT_LB, LbKind.fromString(null));
        assertEquals(LbKind.CT_LB, LbKind.fromString(""));
        assertEquals(LbKind.CT_LB, LbKind.fromString("  "));
    }

    @Test
    public void parseApiWireNames() {
        assertEquals(LbKind.CT_LB, LbKind.fromString("ct_lb"));
        assertEquals(LbKind.CT_LB, LbKind.fromString("CT_LB"));
        assertEquals(LbKind.DSR_SOFTWARE, LbKind.fromString("dsr_software"));
        assertEquals(LbKind.DSR_SOFTWARE, LbKind.fromString("DSR_SOFTWARE"));
    }

    @Test
    public void apiNamesStable() {
        assertEquals("ct_lb", LbKind.CT_LB.getApiName());
        assertEquals("dsr_software", LbKind.DSR_SOFTWARE.getApiName());
        assertTrue(LbKind.DSR_SOFTWARE.isDsr());
        assertFalse(LbKind.CT_LB.isDsr());
        assertTrue(LbKind.CT_LB.isCtLb());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTokenRejected() {
        LbKind.fromString("ipvs_dr");
    }
}
