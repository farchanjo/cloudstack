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
package com.cloud.network.ovn.element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * PARSEL-V6 — pins the pure IPv6 public-transport derivation helpers on
 * {@link OvnPublicNetworkManager}: per-VPC GUA derived from the VPC's IPv4
 * public octet, the chassis anchor CIDR, and the config-prefix parsing. These
 * are ConfigKey-free (values passed in) so they are stable across
 * {@code value()} cache rules.
 */
public class OvnPublicNetworkManagerV6Test {

    private static final String PREFIX = "2a13:8740:0:7::/64";

    // ---- per-VPC GUA derivation (217.179.89.34 -> 2a13:8740:0:7::34/64) ----

    @Test
    public void composeDerivesGuaFromV4LastOctet() {
        assertEquals("2a13:8740:0:7::34/64", OvnPublicNetworkManager.composeV6Cidr(PREFIX, "217.179.89.34"));
        assertEquals("2a13:8740:0:7::42/64", OvnPublicNetworkManager.composeV6Cidr(PREFIX, "217.179.89.42"));
        assertEquals("2a13:8740:0:7::5/64", OvnPublicNetworkManager.composeV6Cidr(PREFIX, "217.179.89.5"));
    }

    @Test
    public void composeIsCollisionFreeAcrossDistinctV4Octets() {
        // Distinct VPC v4 public octets must map to distinct v6 GUAs.
        final String a = OvnPublicNetworkManager.composeV6Cidr(PREFIX, "217.179.89.34");
        final String b = OvnPublicNetworkManager.composeV6Cidr(PREFIX, "217.179.89.43");
        assertNotEquals(a, b);
    }

    @Test
    public void composeReturnsNullWhenPrefixDisabled() {
        // Blank / null prefix => v6 path off => no GUA (v4-only, zero regression).
        assertNull(OvnPublicNetworkManager.composeV6Cidr("", "217.179.89.34"));
        assertNull(OvnPublicNetworkManager.composeV6Cidr(null, "217.179.89.34"));
    }

    @Test
    public void composeReturnsNullWhenNoV4Ip() {
        // VPC not public-bound (no v4 LRP to derive from).
        assertNull(OvnPublicNetworkManager.composeV6Cidr(PREFIX, null));
        assertNull(OvnPublicNetworkManager.composeV6Cidr(PREFIX, "not-an-ip"));
    }

    // ---- chassis anchor cidr (<base>::2/<plen>) ----

    @Test
    public void anchorCidrIsBaseColonTwoWithPrefixLen() {
        assertEquals("2a13:8740:0:7::2/64", OvnPublicNetworkManager.composeV6AnchorCidr(PREFIX));
        assertNull(OvnPublicNetworkManager.composeV6AnchorCidr(""));
    }

    // ---- prefix parsing edge cases ----

    @Test
    public void parseBaseStripsTrailingDoubleColonGroup() {
        assertEquals("2a13:8740:0:7", OvnPublicNetworkManager.parseV6Base(PREFIX));
        assertEquals("fd00:cafe:1", OvnPublicNetworkManager.parseV6Base("fd00:cafe:1::/64"));
    }

    @Test
    public void parseBaseRejectsUncompressedOrBlank() {
        assertNull(OvnPublicNetworkManager.parseV6Base(""));
        assertNull(OvnPublicNetworkManager.parseV6Base(null));
        // Fully-expanded (no '::') is deliberately rejected — operators use the
        // compressed <base>::/<plen> form.
        assertNull(OvnPublicNetworkManager.parseV6Base("2a13:8740:0:7:0:0:0:0/64"));
    }

    @Test
    public void parsePrefixLenReadsAndBoundsChecks() {
        assertEquals(Integer.valueOf(64), OvnPublicNetworkManager.parseV6PrefixLen(PREFIX));
        assertEquals(Integer.valueOf(56), OvnPublicNetworkManager.parseV6PrefixLen("2a13:8740:0:7::/56"));
        assertNull(OvnPublicNetworkManager.parseV6PrefixLen("2a13:8740:0:7::"));   // no '/'
        assertNull(OvnPublicNetworkManager.parseV6PrefixLen("2a13:8740:0:7::/129")); // out of range
        assertNull(OvnPublicNetworkManager.parseV6PrefixLen("2a13:8740:0:7::/x"));   // unparseable
    }

    @Test
    public void lastOctetExtractsDecimalOrNull() {
        assertEquals("34", OvnPublicNetworkManager.lastOctet("217.179.89.34"));
        assertEquals("254", OvnPublicNetworkManager.lastOctet("217.179.89.254"));
        assertNull(OvnPublicNetworkManager.lastOctet("2a13:8740:0:7::1"));  // not a dotted quad
        assertNull(OvnPublicNetworkManager.lastOctet(null));
    }
}
