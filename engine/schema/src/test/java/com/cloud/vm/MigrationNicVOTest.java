// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class MigrationNicVOTest {
    @Test
    public void retainsExplicitMultiNicDesiredIdentity() {
        final MigrationNicVO nic = new MigrationNicVO("work", 2L, 10L, "vm-uuid", 20L,
                "nic-uuid", "VF_PASSTHROUGH", "lsp-nic");
        nic.setMacAddress("aa:bb:cc:dd:ee:ff");
        nic.setSourceBdf("0000:01:00.2");
        nic.setDestinationBdf("0000:02:00.2");
        nic.setIdentityAvailability("AVAILABLE");
        assertEquals("VF_PASSTHROUGH", nic.getNicKind());
        assertEquals("0000:02:00.2", nic.getDestinationBdf());
        assertEquals("nic-uuid", nic.getNicUuid());
    }

    @Test
    public void generationAndUuidRemainTheMappingKeyWhenAnswersAreReordered() {
        final MigrationNicVO first = new MigrationNicVO("work", 8L, 10L, "vm-uuid", 101L,
                "nic-a", "VDPA", "lsp-nic-a");
        final MigrationNicVO second = new MigrationNicVO("work", 8L, 10L, "vm-uuid", 102L,
                "nic-b", "VF_PASSTHROUGH", "lsp-nic-b");
        first.setSourceBdf("0000:01:00.1");
        second.setSourceBdf("0000:01:00.2");

        // The answer order is intentionally reversed; UUID and DB ID are not positional.
        final java.util.Map<String, MigrationNicVO> byUuid = java.util.Map.of(
                second.getNicUuid(), second, first.getNicUuid(), first);
        assertEquals(101L, byUuid.get("nic-a").getNicId());
        assertEquals("0000:01:00.2", byUuid.get("nic-b").getSourceBdf());
        assertNotEquals(byUuid.get("nic-a").getNicId(), byUuid.get("nic-b").getNicId());
    }
}
