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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.junit.Test;

/** Verifies the explicit no-op 4.24.1.32 to 4.24.1.33 resource pair. */
public class Upgrade42432to42433Test {

    @Test
    public void versionRangeIsExactlyOneStep() {
        Upgrade42432to42433 upgrade = new Upgrade42432to42433();

        assertEquals(2, upgrade.getUpgradableVersionRange().length);
        assertEquals("4.24.1.32", upgrade.getUpgradableVersionRange()[0]);
        assertEquals("4.24.1.33", upgrade.getUpgradableVersionRange()[1]);
        assertEquals("4.24.1.33", upgrade.getUpgradedVersion());
        assertFalse(upgrade.supportsRollingUpgrade());
    }

    @Test
    public void exactClasspathResourcesAreLoadable() throws Exception {
        Upgrade42432to42433 upgrade = new Upgrade42432to42433();

        assertCommentOnly(upgrade.getPrepareScripts()[0], "4.24.1.32 to 4.24.1.33");
        assertCommentOnly(upgrade.getCleanupScripts()[0], "4.24.1.32 to 4.24.1.33");
    }

    private static void assertCommentOnly(InputStream script, String versionRange) throws Exception {
        assertNotNull(script);
        try (InputStream input = script;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String body = reader.lines().collect(Collectors.joining("\n"));
            assertTrue(body.contains(versionRange));
            assertTrue(body.lines().filter(line -> !line.isBlank())
                    .allMatch(line -> line.trim().startsWith("--")));
        }
    }
}
