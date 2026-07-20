// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.cloud.agent.api.routing.MigrationIdentityActionAnswer;
import com.cloud.agent.api.routing.MigrationIdentityActionCommand;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;
import com.cloud.hypervisor.kvm.resource.VfHostLifecycleLock;

public class LibvirtMigrationIdentityActionCommandWrapperTest {
    @Test
    public void rejectsWildcardIdentityBeforeHostMutation() {
        final MigrationIdentityActionCommand command = new MigrationIdentityActionCommand(
                "vm", "vm-uuid", "work", 4L, "ROLLING_BACK",
                MigrationIdentityActionCommand.Action.RESTORE_SOURCE, List.of());
        final MigrationIdentityActionAnswer answer = (MigrationIdentityActionAnswer)
                new LibvirtMigrationIdentityActionCommandWrapper().execute(command, null);
        assertEquals(MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, answer.getStatus());
    }

    @Test
    public void rejectsExpiredLeaseBeforeEachMutatingAction() {
        for (MigrationIdentityActionCommand.Action action : List.of(
                MigrationIdentityActionCommand.Action.VERIFY_AND_RESTAMP,
                MigrationIdentityActionCommand.Action.CLEAN_DESTINATION_PREP,
                MigrationIdentityActionCommand.Action.RESTORE_SOURCE,
                MigrationIdentityActionCommand.Action.CLEAN_SOURCE_AFTER_COMMIT)) {
            final MigrationIdentityActionCommand command = new MigrationIdentityActionCommand(
                    "vm", "vm-uuid", "work", 4L, "TRANSFERRING", action,
                    List.of(identity("VF_PASSTHROUGH")), "lease");
            command.setRecoveryLeaseVersion(9L);
            command.setRecoveryLeaseExpiresAt(1L);
            final MigrationIdentityActionAnswer answer = (MigrationIdentityActionAnswer)
                    new LibvirtMigrationIdentityActionCommandWrapper().execute(command, null);
            assertEquals(MigrationIdentityActionAnswer.Status.PRECONDITION_FAILED, answer.getStatus());
            assertTrue(answer.getDetails().contains("expired"));
        }
    }

    @Test
    public void identityContractIsKindSpecific() {
        final ObserveVdpaMigrationCommand.NicIdentity virtio = identity("VIRTIO_OVN", null,
                "/dev/vhost-vdpa-1", "mlx5_core");
        assertEquals(true, LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(virtio));
        final ObserveVdpaMigrationCommand.NicIdentity vf = identity("VF_PASSTHROUGH");
        vf.setExpectedVfId(null);
        assertEquals(false, LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(vf));
        final ObserveVdpaMigrationCommand.NicIdentity vdpa = identity("VDPA", "0000:01:00.1",
                null, "mlx5_core");
        assertEquals(false, LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(vdpa));
    }

    @Test
    public void actionUsesAlreadyHeldBdfLockWithoutNestedAcquisition() {
        final String bdf = "0000:01:00.1";
        final java.util.concurrent.locks.ReentrantLock lock = VfHostLifecycleLock.forBdf(bdf);
        lock.lock();
        try {
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(VfHostLifecycleLock.isHeldByCurrentThread(bdf));
            assertEquals(lock, LibvirtObserveVdpaMigrationCommandWrapper.lockFor(identity("VF_PASSTHROUGH")));
        } finally {
            lock.unlock();
        }
    }

    @Test
    public void mixedNicSetRequiresEveryKindContract() {
        final ObserveVdpaMigrationCommand.NicIdentity virtio = identity("VIRTIO_OVN", null,
                "/dev/vhost-vdpa-1", "mlx5_core");
        final ObserveVdpaMigrationCommand.NicIdentity vf = identity("VF_PASSTHROUGH");
        final ObserveVdpaMigrationCommand.NicIdentity vdpa = identity("VDPA");
        assertTrue(LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(virtio));
        assertTrue(LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(vf));
        assertTrue(LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(vdpa));
        final ObserveVdpaMigrationCommand.NicIdentity vfWithoutDriver = identity("VF_PASSTHROUGH",
                "0000:01:00.1", "/dev/vhost-vdpa-1", null);
        assertEquals(false, LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(vfWithoutDriver));
    }

    private static ObserveVdpaMigrationCommand.NicIdentity identity(final String kind) {
        return identity(kind, "0000:01:00.1", "/dev/vhost-vdpa-1", "mlx5_core");
    }

    private static ObserveVdpaMigrationCommand.NicIdentity identity(final String kind,
            final String expectedBdf, final String expectedVdpaDevice, final String expectedDriver) {
        final String expectedVdpaName = "vdpa1";
        final String expectedMac = "aa:bb:cc:dd:ee:ff";
        final String expectedVlan = "100";
        final String expectedRepresentor = "pf0vf1";
        final String expectedBridge = "br-int";
        final String expectedPort = "port1";
        final String expectedInterface = "rep1";
        final String expectedExternalIds = "{\"iface-id\":\"lsp-1\"}";
        final ObserveVdpaMigrationCommand.NicIdentity identity = new ObserveVdpaMigrationCommand.NicIdentity(
                1L, "lsp-1", kind, expectedBdf, expectedVdpaName, expectedVdpaDevice,
                expectedMac, expectedVlan, expectedDriver, expectedRepresentor, expectedBridge, expectedPort,
                expectedInterface, expectedExternalIds, "lsp-1", "chassis1");
        identity.setExpectedNicUuid("nic-1");
        identity.setExpectedPf("pf0");
        identity.setExpectedVfId(1);
        identity.setExpectedRepresentorPhysPortName("pf0vf1");
        identity.setExpectedRepresentorBdf("0000:01:00.0");
        return identity;
    }
}
