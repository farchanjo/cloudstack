// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.agent.api.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;

import org.junit.Test;

public class ObserveVdpaMigrationCommandTest {
    @Test
    public void commandCarriesExactFencedIdentityWithoutMutationFlags() {
        final ObserveVdpaMigrationCommand command = new ObserveVdpaMigrationCommand("vm-1", "work-1", 4L,
                List.of(new ObserveVdpaMigrationCommand.NicIdentity(9L, "lsp-nic-9",
                        "0000:01:00.2", "vdpa-9", "/dev/vhost-vdpa-9")));
        assertEquals("work-1", command.getWorkId());
        assertEquals(4L, command.getGeneration());
        assertEquals(1, command.getNicIdentities().size());
        assertFalse(command.executeInSequence());
        assertFalse(command.isTopologyDiscovery());
    }

    @Test
    public void topologyDiscoveryIsExplicitAndCopySafe() {
        final ObserveVdpaMigrationCommand command = new ObserveVdpaMigrationCommand("vm-1", "work-1", 4L,
                List.of(new ObserveVdpaMigrationCommand.NicIdentity(9L, "lsp-nic-9", "virtio", null,
                        null)));
        command.setTopologyDiscovery(true);
        assertEquals(true, command.isTopologyDiscovery());
    }
}
