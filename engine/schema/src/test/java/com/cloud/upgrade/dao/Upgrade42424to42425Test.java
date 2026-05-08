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
package com.cloud.upgrade.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * Schema upgrade smoke tests for {@link Upgrade42424to42425}. The schema file
 * itself is idempotent; we only need to confirm the upgrader points at it,
 * advertises the expected version range, and the SQL file holds the keys
 * downstream paths rely on.
 */
public class Upgrade42424to42425Test {

    @Test
    public void versionRangeIsExactlyOneStep() {
        Upgrade42424to42425 upgrade = new Upgrade42424to42425();
        String[] range = upgrade.getUpgradableVersionRange();
        assertEquals(2, range.length);
        assertEquals("4.24.1.24", range[0]);
        assertEquals("4.24.1.25", range[1]);
        assertEquals("4.24.1.25", upgrade.getUpgradedVersion());
    }

    @Test
    public void prepareScriptResolvesAndIsNotEmpty() throws Exception {
        Upgrade42424to42425 upgrade = new Upgrade42424to42425();
        InputStream[] scripts = upgrade.getPrepareScripts();
        assertEquals(1, scripts.length);
        assertNotNull(scripts[0]);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(scripts[0], StandardCharsets.UTF_8))) {
            String body = r.lines().collect(Collectors.joining("\n"));
            // last_seen column added — the load-bearing SQL of this upgrade.
            assertTrue("script must add last_seen column",
                    body.contains("`last_seen`") || body.contains("last_seen"));
            // Phase H.1 reconcile knobs.
            assertTrue("script must register vf.pool.suspect.timeout.seconds",
                    body.contains("vf.pool.suspect.timeout.seconds"));
            assertTrue("script must register vf.pool.reconcile.interval.seconds",
                    body.contains("vf.pool.reconcile.interval.seconds"));
            assertTrue("script must register vdpa.sf.reconcile.interval.seconds",
                    body.contains("vdpa.sf.reconcile.interval.seconds"));
            // Idempotent constructs.
            assertTrue("script must use ON DUPLICATE KEY UPDATE for configuration",
                    body.contains("ON DUPLICATE KEY UPDATE"));
            assertTrue("script must use ADD COLUMN IF NOT EXISTS for the column",
                    body.contains("ADD COLUMN IF NOT EXISTS"));
        }
    }

    @Test
    public void cleanupScriptListIsEmpty() {
        Upgrade42424to42425 upgrade = new Upgrade42424to42425();
        assertEquals(0, upgrade.getCleanupScripts().length);
    }

    @Test
    public void prepareScriptCanBeReReadIdempotently() throws Exception {
        // Acquiring the script twice confirms the loader does not consume the
        // resource pointer.
        Upgrade42424to42425 upgrade = new Upgrade42424to42425();
        try (InputStream first = upgrade.getPrepareScripts()[0];
             InputStream second = upgrade.getPrepareScripts()[0]) {
            assertNotNull(first);
            assertNotNull(second);
        }
    }
}
