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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.cloud.network.ovn.config.OvnEcmpAutoClusters.Binding;

public class OvnEcmpAutoClustersTest {

    private static final String NET = "a4226ad6-604a-4cd6-883e-777958562fe1";
    private static final String CKS = "11111111-2222-3333-4444-555555555555";

    @Test
    public void emptyAndNullYieldEmpty() {
        assertTrue(OvnEcmpAutoClusters.parse(null).isEmpty());
        assertTrue(OvnEcmpAutoClusters.parse("").isEmpty());
        assertTrue(OvnEcmpAutoClusters.parse("   ").isEmpty());
    }

    @Test
    public void parseSingleDualStack() {
        final List<Binding> list = OvnEcmpAutoClusters.parse(
                NET + "=" + CKS + "|10.140.0.0/24|2a13:8740:0:10::/64");
        assertEquals(1, list.size());
        assertEquals(NET, list.get(0).getNetworkUuid());
        assertEquals(CKS, list.get(0).getClusterUuid());
        assertEquals("10.140.0.0/24", list.get(0).getV4Prefix());
        assertEquals("2a13:8740:0:10::/64", list.get(0).getV6Prefix());
    }

    @Test
    public void parseV4Only() {
        final List<Binding> list = OvnEcmpAutoClusters.parse(NET + "=" + CKS + "|10.140.0.0/24|");
        assertEquals(1, list.size());
        assertEquals("10.140.0.0/24", list.get(0).getV4Prefix());
        assertNull(list.get(0).getV6Prefix());
    }

    @Test
    public void parseV6Only() {
        final List<Binding> list = OvnEcmpAutoClusters.parse(NET + "=" + CKS + "||2a13:8740:0:10::/64");
        assertEquals(1, list.size());
        assertNull(list.get(0).getV4Prefix());
        assertEquals("2a13:8740:0:10::/64", list.get(0).getV6Prefix());
    }

    @Test
    public void parseMultiNetworkOrderStable() {
        final String snapeNet = "d46c5f93-4f6f-47fc-89ad-b4b10fb30f90";
        final List<Binding> list = OvnEcmpAutoClusters.parse(
                NET + "=" + CKS + "|10.140.0.0/24|;"
                        + snapeNet + "=" + CKS + "|10.141.0.0/24|");
        assertEquals(2, list.size());
        assertEquals(NET, list.get(0).getNetworkUuid());
        assertEquals(snapeNet, list.get(1).getNetworkUuid());
    }

    @Test
    public void malformedSkipped() {
        assertTrue(OvnEcmpAutoClusters.parse("not-an-entry").isEmpty());
        assertTrue(OvnEcmpAutoClusters.parse(NET + "=" + CKS + "|not-a-cidr|").isEmpty());
        assertTrue(OvnEcmpAutoClusters.parse(NET + "=" + CKS + "||").isEmpty());
    }
}
