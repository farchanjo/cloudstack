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
package com.cloud.hypervisor.kvm.resource.hwoffload;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

/**
 * Bind-IP guard tests for {@link HwOffloadIntentApi#requireLinkLocal(String)}.
 * The intent API must never bind to a wildcard or routable address — only
 * IPv4 169.254.0.0/16 and IPv6 fe80::/10 are accepted, since those are the
 * cloud0 link-local addresses VRs reach the host on.
 */
public class HwOffloadIntentApiBindGuardTest {

    @Test
    public void rejectsNullBindIp() {
        assertRejected(null);
    }

    @Test
    public void rejectsBlankBindIp() {
        assertRejected("");
        assertRejected("   ");
    }

    @Test
    public void rejectsIpv4Wildcard() {
        assertRejected("0.0.0.0");
    }

    @Test
    public void rejectsIpv6Wildcard() {
        assertRejected("::");
        assertRejected("[::]");
    }

    @Test
    public void rejectsRoutableIpv4() {
        assertRejected("10.182.0.21");
        assertRejected("64.34.88.231");
        assertRejected("127.0.0.1");
    }

    @Test
    public void rejectsRoutableIpv6() {
        assertRejected("2a13:8740::5");
        assertRejected("::1");
    }

    @Test
    public void rejectsHostname() {
        // Hostnames are explicitly not resolved; only literal addresses pass.
        assertRejected("localhost");
        assertRejected("aragog");
    }

    @Test
    public void acceptsIpv4LinkLocal() throws IOException {
        // Reference link-local address used by cloud0.
        HwOffloadIntentApi.requireLinkLocal("169.254.0.1");
    }

    @Test
    public void acceptsIpv4LinkLocalRange() throws IOException {
        // Spot-check the high end of the 169.254.0.0/16 block.
        HwOffloadIntentApi.requireLinkLocal("169.254.255.254");
    }

    @Test
    public void acceptsIpv6LinkLocal() throws IOException {
        HwOffloadIntentApi.requireLinkLocal("fe80::1");
    }

    private static void assertRejected(String bindIp) {
        try {
            HwOffloadIntentApi.requireLinkLocal(bindIp);
            fail("Expected IOException for bindIp=" + bindIp);
        } catch (IOException e) {
            assertNotNull(e.getMessage());
            assertTrue("error message should explain refusal: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("refuses to bind"));
        }
    }
}
