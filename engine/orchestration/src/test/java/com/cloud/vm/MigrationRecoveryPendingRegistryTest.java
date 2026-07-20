// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.vm;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class MigrationRecoveryPendingRegistryTest {
    @Test
    public void rebuildMarksBothInvolvedHostsAndVm() {
        final MigrationRecoveryPendingRegistry registry = new MigrationRecoveryPendingRegistry();
        final ItWorkVO work = new ItWorkVO("work", 1L, VirtualMachine.State.Migrating,
                VirtualMachine.Type.User, 42L);
        work.setMigrationGeneration(3L);
        work.setMigrationPhase(ItWorkVO.MigrationPhase.TRANSFERRING);
        final MigrationNicVO nic = new MigrationNicVO("work", 3L, 42L, "vm", 7L, "nic", "VIRTIO_OVN",
                "lsp-nic");
        nic.setSourceHostId(10L);
        nic.setDestinationHostId(20L);
        final MigrationNicDao dao = org.mockito.Mockito.mock(MigrationNicDao.class);
        org.mockito.Mockito.when(dao.listByWorkAndGeneration("work", 3L)).thenReturn(List.of(nic));
        registry.rebuild(List.of(work), dao);
        assertTrue(registry.isPending(42L));
        assertTrue(registry.isHostPending(10L));
        assertTrue(registry.isHostPending(20L));
    }
}
