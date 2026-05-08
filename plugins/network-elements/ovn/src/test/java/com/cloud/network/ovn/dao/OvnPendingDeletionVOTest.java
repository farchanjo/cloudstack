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

import java.util.Date;

import org.junit.Test;

import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;

/**
 * Unit tests for {@link OvnPendingDeletionVO} field round-trips and soft-delete
 * semantics. No database harness required.
 *
 * <p>The actual DAO query paths (SearchBuilder execution, PreparedStatement
 * parameter binding) are validated end-to-end by the Marvin integration suites.
 * These tests guard the VO accessor contract and the invariant that broke in the
 * original bug: {@code getKind()} returns an enum and {@code getKindRaw()}
 * returns the raw String — only the Java field named {@code kind} maps to a
 * {@code @Column} annotation, so SearchBuilder construction must use
 * {@code entity().getKind()} (CGLib interceptor derives fieldName {@code "kind"})
 * and must NOT use {@code entity().getKindRaw()} (CGLib would derive
 * {@code "kindRaw"} which has no corresponding {@code @Column} field, yielding
 * a null {@code Attribute} and an NPE at PreparedStatement execution time).
 */
public class OvnPendingDeletionVOTest {

    @Test
    public void allArgsConstructorPopulatesFields() {
        final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                "row-uuid-1", 5L, 7L, Kind.NETWORK, "ovn-ls-uuid-abc", 42L);

        assertEquals("row-uuid-1", vo.getUuid());
        assertEquals(5L, vo.getControllerId());
        assertEquals(Long.valueOf(7L), vo.getZoneId());
        assertEquals(Kind.NETWORK, vo.getKind());
        assertEquals("NETWORK", vo.getKindRaw());
        assertEquals("ovn-ls-uuid-abc", vo.getOvnUuid());
        assertEquals(Long.valueOf(42L), vo.getCsId());
        assertEquals(0, vo.getAttempts());
        assertNull(vo.getLastAttemptAt());
        assertNull(vo.getLastError());
        assertNotNull(vo.getCreated());
        assertNull(vo.getRemoved());
    }

    @Test
    public void getKind_andGetKindRaw_referToSameField() {
        // The SearchBuilder construction bug was: entity().getKindRaw() was used
        // instead of entity().getKind().  CGLib derives the Java field name from
        // the getter name by stripping "get" and lowercasing the first char:
        //   getKind()    → "kind"    → matches @Column(name="kind") field
        //   getKindRaw() → "kindRaw" → no matching field → null Attribute → NPE
        // This test confirms the VO accessor contract that underpins the fix.
        for (final Kind k : Kind.values()) {
            final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                    "u", 1L, 1L, k, "x", null);
            assertEquals(k, vo.getKind());
            assertEquals(k.name(), vo.getKindRaw());
        }
    }

    @Test
    public void softDeleteSemantics_removedIsNullByDefault_thenSetByMarkSucceeded() {
        final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                "u2", 1L, 2L, Kind.VPC, "ovn-lr-uuid", 10L);
        assertNull("Row is pending until removed is set", vo.getRemoved());

        final Date now = new Date();
        vo.setRemoved(now);
        assertNotNull(vo.getRemoved());
        assertEquals(now, vo.getRemoved());
    }

    @Test
    public void markFailedFields_updateCorrectly() {
        final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                "u3", 2L, 3L, Kind.NIC, "ovn-lrp-uuid", 20L);
        assertEquals(0, vo.getAttempts());
        assertNull(vo.getLastAttemptAt());
        assertNull(vo.getLastError());

        vo.setAttempts(1);
        final Date ts = new Date();
        vo.setLastAttemptAt(ts);
        vo.setLastError("ovsdb timeout after 30s");

        assertEquals(1, vo.getAttempts());
        assertEquals(ts, vo.getLastAttemptAt());
        assertEquals("ovsdb timeout after 30s", vo.getLastError());
    }

    @Test
    public void lastError_isTruncatedAt2048Chars() {
        final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                "u4", 1L, 1L, Kind.NETWORK, "x", null);
        final String longError = "x".repeat(3000);
        vo.setLastError(longError);
        assertEquals(2048, vo.getLastError().length());
    }

    @Test
    public void lastError_acceptsNull() {
        final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                "u5", 1L, 1L, Kind.NETWORK, "x", null);
        vo.setLastError("some error");
        vo.setLastError(null);
        assertNull(vo.getLastError());
    }

    @Test
    public void nullZoneId_and_nullCsId_areAccepted() {
        final OvnPendingDeletionVO vo = new OvnPendingDeletionVO(
                "u6", 0L, null, Kind.VPC, "lr-uuid", null);
        assertNull(vo.getZoneId());
        assertNull(vo.getCsId());
        assertEquals(0L, vo.getControllerId());
    }
}
