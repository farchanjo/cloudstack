// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.agent.api.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class MigrationIdentityActionCommandTest {
    @Test
    public void carriesSingleExplicitActionAndFence() {
        final MigrationIdentityActionCommand command = new MigrationIdentityActionCommand("vm", "vm-uuid",
                "work", 3L, "GUEST_TRANSFERRED_OR_STARTED",
                MigrationIdentityActionCommand.Action.CLEAN_SOURCE_AFTER_COMMIT,
                List.of(new ObserveVdpaMigrationCommand.NicIdentity(1L, "lsp-1", "VDPA",
                        "0000:01:00.2", "vdpa0", "/dev/vhost-vdpa-0", null, null, null,
                        null, null, null, null, null, null, null)));
        assertEquals(3L, command.getGeneration());
        assertEquals(MigrationIdentityActionCommand.Action.CLEAN_SOURCE_AFTER_COMMIT, command.getAction());
    }

    @Test
    public void exposesDomainStartRequiredAsTypedStatus() {
        final MigrationIdentityActionCommand command = new MigrationIdentityActionCommand("vm", "vm-uuid",
                "work", 3L, "ROLLING_BACK", MigrationIdentityActionCommand.Action.RESTORE_SOURCE, List.of());
        final MigrationIdentityActionAnswer answer = new MigrationIdentityActionAnswer(command, true,
                "dataplane restored; management must start domain",
                MigrationIdentityActionAnswer.Status.DATAPLANE_RESTORED_DOMAIN_START_REQUIRED, true, true, List.of());
        assertTrue(answer.getResult());
        assertEquals(MigrationIdentityActionAnswer.Status.DATAPLANE_RESTORED_DOMAIN_START_REQUIRED,
                answer.getStatus());
    }

    @Test
    public void retainsAllEightExplicitAgentActions() {
        assertEquals(List.of(
                MigrationIdentityActionCommand.Action.INSTALL_DESTINATION_FENCE,
                MigrationIdentityActionCommand.Action.CLEAR_FENCE_ONLY,
                MigrationIdentityActionCommand.Action.CLEAN_RECOVERY_FENCE,
                MigrationIdentityActionCommand.Action.VERIFY_AND_RESTAMP,
                MigrationIdentityActionCommand.Action.CLEAN_DESTINATION_PREP,
                MigrationIdentityActionCommand.Action.RESTORE_SOURCE,
                MigrationIdentityActionCommand.Action.CLEAN_SOURCE_AFTER_COMMIT,
                MigrationIdentityActionCommand.Action.ADOPT_RECOVERY_FENCE),
                List.of(MigrationIdentityActionCommand.Action.values()));
    }

    @Test
    public void carriesRecoveryLeaseFence() {
        final MigrationIdentityActionCommand command = new MigrationIdentityActionCommand("vm", "vm-uuid",
                "work", 3L, "ROLLING_BACK", MigrationIdentityActionCommand.Action.RESTORE_SOURCE,
                List.of(), "lease-3");
        assertEquals("lease-3", command.getRecoveryLeaseToken());
    }
}
