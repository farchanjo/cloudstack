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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/**
 * Pure unit tests for {@link OvnBgpRedistributeManager#host6CsId} /
 * {@link OvnBgpRedistributeManager#canonicalizeHost6Vip}: IPv6 VIP identity must
 * be form-stable (compressed vs expanded) and always land in the positive range
 * {@code [1, Integer.MAX_VALUE]} so it fits {@code ovn_logical_id_map.cs_id}
 * ({@code bigint unsigned}). Negative ids are rejected by MySQL
 * ({@code MysqlDataTruncation: Out of range value for column 'cs_id'}).
 */
public class OvnBgpHost6CsIdTest {

    /** Live-failing VIP from chaos logs (was hashed to a negative cs_id). */
    private static final String VIP_100 = "2a13:8740:0:7::100";
    private static final String VIP_101 = "2a13:8740:0:7::101";

    @Test
    public void host6CsIdIsStableAcrossCompressedExpandedForms() {
        final String compressed = VIP_100;
        final String expanded = "2a13:8740:0000:0007:0000:0000:0000:0100";
        final long a = OvnBgpRedistributeManager.host6CsId(compressed);
        final long b = OvnBgpRedistributeManager.host6CsId(expanded);
        assertEquals(a, b);
        assertPositiveCsId(a);
    }

    @Test
    public void host6CsIdDiffersForDistinctVips() {
        final long a = OvnBgpRedistributeManager.host6CsId(VIP_100);
        final long b = OvnBgpRedistributeManager.host6CsId(VIP_101);
        assertNotEquals(a, b);
        assertPositiveCsId(a);
        assertPositiveCsId(b);
    }

    @Test
    public void host6CsIdForLiveVipsIsPositiveAndStable() {
        final long a1 = OvnBgpRedistributeManager.host6CsId(VIP_100);
        final long a2 = OvnBgpRedistributeManager.host6CsId(VIP_100);
        final long b1 = OvnBgpRedistributeManager.host6CsId(VIP_101);
        final long b2 = OvnBgpRedistributeManager.host6CsId(VIP_101);
        assertPositiveCsId(a1);
        assertPositiveCsId(b1);
        assertEquals("::100 must be stable across calls", a1, a2);
        assertEquals("::101 must be stable across calls", b1, b2);
        assertNotEquals("::100 and ::101 must not share a cs_id", a1, b1);
    }

    @Test
    public void host6CsIdAlwaysPositiveForSampleAndRandomV6() {
        final String[] samples = {
                VIP_100,
                VIP_101,
                "2a13:8740:0:a::1",
                "::1",
                "2001:db8::",
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        };
        for (final String vip : samples) {
            assertPositiveCsId(OvnBgpRedistributeManager.host6CsId(vip));
        }

        // Random IPv6 addresses: always positive and stable for the same string.
        final Random rng = new Random(0xB6F1057L);
        final Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 256; i++) {
            final String vip = randomIpv6(rng);
            final long id1 = OvnBgpRedistributeManager.host6CsId(vip);
            final long id2 = OvnBgpRedistributeManager.host6CsId(vip);
            assertPositiveCsId(id1);
            assertEquals("random v6 must be stable: " + vip, id1, id2);
            seen.add(id1);
        }
        // Sanity: a 256-sample set should not collapse to a handful of ids.
        assertTrue("expected more distinct cs_id values, got " + seen.size(), seen.size() > 200);
    }

    @Test
    public void canonicalizeHost6VipStandardizes() {
        final String out = OvnBgpRedistributeManager.canonicalizeHost6Vip(
                "2a13:8740:0000:0007:0000:0000:0000:0100");
        assertEquals("2a13:8740:0:7::100", out);
    }

    /** cs_id must be strictly positive and fit a non-negative 31-bit range. */
    private static void assertPositiveCsId(final long csId) {
        assertTrue("host6CsId must be > 0 (bigint unsigned), got " + csId, csId > 0);
        assertTrue("host6CsId must be <= Integer.MAX_VALUE, got " + csId,
                csId <= Integer.MAX_VALUE);
    }

    private static String randomIpv6(final Random rng) {
        final StringBuilder sb = new StringBuilder(39);
        for (int g = 0; g < 8; g++) {
            if (g > 0) {
                sb.append(':');
            }
            sb.append(String.format("%x", rng.nextInt(0x10000)));
        }
        return sb.toString();
    }
}
