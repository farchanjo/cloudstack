// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.upgrade.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class Upgrade42433to42434Test {
    @Test
    public void exposesCanonicalVersionAndPrepareScript() {
        final Upgrade42433to42434 upgrade = new Upgrade42433to42434();
        assertEquals("4.24.1.33", upgrade.getUpgradableVersionRange()[0]);
        assertEquals("4.24.1.34", upgrade.getUpgradedVersion());
        assertFalse(upgrade.supportsRollingUpgrade());
        final InputStream[] scripts = upgrade.getPrepareScripts();
        assertEquals(1, scripts.length);
        assertNotNull(scripts[0]);
    }

    @Test
    public void createsMigrationGenerationIndexIdempotently() throws Exception {
        try (InputStream script = Upgrade42433to42434Test.class.getResourceAsStream(
                "/META-INF/db/schema-42433to42434.sql")) {
            final String sql = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(true, sql.contains("IDEMPOTENT_CREATE_UNIQUE_INDEX"));
            assertEquals(false, sql.contains("CREATE UNIQUE INDEX `uk_op_it_work_migration_generation`"));
            assertEquals(true, sql.contains("'(`instance_id`, `migration_mode`, `migration_generation`)'"));
        }
    }
}
