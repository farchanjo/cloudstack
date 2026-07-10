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
package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure unit tests for {@link OvnBgpRedistributeManager#host6CsId} /
 * {@link OvnBgpRedistributeManager#canonicalizeHost6Vip}: IPv6 VIP identity must
 * be form-stable (compressed vs expanded) and always land in the signed-INT
 * negative range {@code [Integer.MIN_VALUE, -1]} so it fits the
 * {@code ovn_logical_id_map.cs_id} column and never collides with positive
 * {@code public_ip_address.id}.
 */
public class OvnBgpHost6CsIdTest {

    @Test
    public void host6CsIdIsStableAcrossCompressedExpandedForms() {
        final String compressed = "2a13:8740:0:7::100";
        final String expanded = "2a13:8740:0000:0007:0000:0000:0000:0100";
        final long a = OvnBgpRedistributeManager.host6CsId(compressed);
        final long b = OvnBgpRedistributeManager.host6CsId(expanded);
        assertEquals(a, b);
        assertFitsSignedNegativeInt(a);
    }

    @Test
    public void host6CsIdDiffersForDistinctVips() {
        final long a = OvnBgpRedistributeManager.host6CsId("2a13:8740:0:7::100");
        final long b = OvnBgpRedistributeManager.host6CsId("2a13:8740:0:7::101");
        assertTrue(a != b);
        assertFitsSignedNegativeInt(a);
        assertFitsSignedNegativeInt(b);
    }

    @Test
    public void host6CsIdAlwaysFitsSignedNegativeInt() {
        // Sample of LAX-ish VIPs + edge-ish addresses; every result must sit in
        // [Integer.MIN_VALUE, -1] (the range that survives a signed INT column).
        final String[] vips = {
                "2a13:8740:0:7::100",
                "2a13:8740:0:7::101",
                "2a13:8740:0:a::1",
                "::1",
                "2001:db8::",
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        };
        for (final String vip : vips) {
            assertFitsSignedNegativeInt(OvnBgpRedistributeManager.host6CsId(vip));
        }
    }

    @Test
    public void canonicalizeHost6VipStandardizes() {
        final String out = OvnBgpRedistributeManager.canonicalizeHost6Vip(
                "2a13:8740:0000:0007:0000:0000:0000:0100");
        assertEquals("2a13:8740:0:7::100", out);
    }

    /** cs_id must be negative and still representable as a signed 32-bit int. */
    private static void assertFitsSignedNegativeInt(final long csId) {
        assertTrue("host6CsId must be negative, got " + csId, csId < 0);
        assertTrue("host6CsId must be >= Integer.MIN_VALUE, got " + csId,
                csId >= Integer.MIN_VALUE);
        assertEquals("host6CsId must not lose bits when narrowed to int: " + csId,
                csId, (long) (int) csId);
    }
}
