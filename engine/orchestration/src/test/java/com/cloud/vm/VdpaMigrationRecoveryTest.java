// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class VdpaMigrationRecoveryTest {
    private ItWorkVO work(final ItWorkVO.MigrationPhase phase) {
        final ItWorkVO work = new ItWorkVO("work", 1L, VirtualMachine.State.Migrating,
                VirtualMachine.Type.User, 10L);
        work.setMigrationGeneration(7L);
        work.setMigrationPhase(phase);
        work.setMigrationMode(ItWorkVO.MigrationMode.ACCELERATED_COLD);
        return work;
    }

    @Test
    public void phaseCannotMoveBackwards() {
        assertTrue(ItWorkVO.MigrationPhase.TRANSFERRING
                .canTransitionTo(ItWorkVO.MigrationPhase.STARTING_DESTINATION));
        assertFalse(ItWorkVO.MigrationPhase.TRANSFERRING
                .canTransitionTo(ItWorkVO.MigrationPhase.GUEST_TRANSFERRED_OR_STARTED));
        assertFalse(ItWorkVO.MigrationPhase.GUEST_TRANSFERRED_OR_STARTED
                .canTransitionTo(ItWorkVO.MigrationPhase.DESTINATION_ALLOCATED));
        assertFalse(ItWorkVO.MigrationPhase.PREPARING_DESTINATION
                .canTransitionTo(ItWorkVO.MigrationPhase.DONE));
        assertTrue(ItWorkVO.MigrationPhase.GUEST_TRANSFERRED_OR_STARTED
                .canTransitionTo(ItWorkVO.MigrationPhase.ROLLING_BACK));
    }

    @Test
    public void unavailableObservationNeverAuthorizesMutation() {
        final VdpaMigrationRecovery.Observation unavailable =
                new VdpaMigrationRecovery.Observation(false, false, null);
        assertEquals(VdpaMigrationRecovery.Action.MANUAL_INTERVENTION,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.TRANSFERRING), unavailable));
    }

    @Test
    public void recoveryIsPerNicAndRequiresEveryExactIdentity() {
        final List<VdpaMigrationRecovery.NicObservation> nics = List.of(
                new VdpaMigrationRecovery.NicObservation(1L, false, true, true, false),
                new VdpaMigrationRecovery.NicObservation(2L, false, false, true, false));
        assertEquals(VdpaMigrationRecovery.Action.MANUAL_INTERVENTION,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.TRANSFERRING),
                        new VdpaMigrationRecovery.Observation(true, true, nics)));
    }

    @Test
    public void readinessBlocksUnavailableObservation() {
        final VdpaMigrationReadiness readiness = VdpaMigrationReadiness.evaluate(
                work(ItWorkVO.MigrationPhase.DESTINATION_ALLOCATED), List.of(), false);
        assertEquals(VdpaMigrationReadiness.Status.OBSERVATION_UNAVAILABLE, readiness.status());
    }

    @Test
    public void missingVdpaIdentityBlocksStableReadiness() {
        final VdpaMigrationReadiness readiness = VdpaMigrationReadiness.evaluate(
                work(ItWorkVO.MigrationPhase.DONE),
                List.of(new VdpaMigrationRecovery.NicObservation(1L, false, false, true, false)), true);
        assertEquals(VdpaMigrationReadiness.Status.RECOVERY_REQUIRED, readiness.status());
    }

    @Test
    public void ordinaryCapabilityIsNotForcedCold() {
        final MigrationCapability capability = MigrationCapability.ordinary();
        assertFalse(capability.coldOnly());
        assertTrue(capability.isAllowed());
    }

    @Test
    public void destinationOrphanIsCleanedBeforeSourceRestore() {
        final List<VdpaMigrationRecovery.NicObservation> nics = List.of(
                new VdpaMigrationRecovery.NicObservation(1L, true, true, false, false));
        assertEquals(VdpaMigrationRecovery.Action.CLEAN_DESTINATION_PREP,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.TRANSFERRING),
                        new VdpaMigrationRecovery.Observation(true, true, nics)));
    }

    @Test
    public void reachesDestinationCommitOnlyWhenEveryGuestIsAttached() {
        final List<VdpaMigrationRecovery.NicObservation> nics = List.of(
                new VdpaMigrationRecovery.NicObservation(1L, false, true, true, false));
        assertEquals(VdpaMigrationRecovery.Action.FINISH_DESTINATION_COMMIT,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.TRANSFERRING),
                        new VdpaMigrationRecovery.Observation(false, true, nics)));
    }

    @Test
    public void reachesSourceRestoreOnlyAfterDestinationIsUnavailable() {
        final List<VdpaMigrationRecovery.NicObservation> nics = List.of(
                new VdpaMigrationRecovery.NicObservation(1L, true, false, false, false));
        assertEquals(VdpaMigrationRecovery.Action.RESTORE_SOURCE,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.ROLLING_BACK),
                        new VdpaMigrationRecovery.Observation(true, false, nics)));
    }

    @Test
    public void reachesSourceCleanupOnlyAfterOwnershipCommit() {
        final List<VdpaMigrationRecovery.NicObservation> nics = List.of(
                new VdpaMigrationRecovery.NicObservation(1L, true, true, true, true));
        assertEquals(VdpaMigrationRecovery.Action.FINISH_SOURCE_CLEANUP,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.OWNERSHIP_COMMITTED),
                        new VdpaMigrationRecovery.Observation(true, true, nics)));
    }

    @Test
    public void ordinaryWorkCannotEnterAcceleratedRecoveryMode() {
        final ItWorkVO ordinary = work(ItWorkVO.MigrationPhase.TRANSFERRING);
        ordinary.setMigrationMode(ItWorkVO.MigrationMode.ORDINARY);
        assertEquals(ItWorkVO.MigrationMode.ORDINARY, ordinary.getMigrationMode());
        assertFalse(ordinary.getMigrationMode() == ItWorkVO.MigrationMode.ACCELERATED_COLD);
    }

    @Test
    public void recoveryWorkCarriesStableLeaseFence() {
        final ItWorkVO work = work(ItWorkVO.MigrationPhase.ROLLING_BACK);
        work.setMigrationRecoveryLeaseToken("lease-7");
        work.setMigrationRecoveryLeaseOwner(42L);
        work.setMigrationRecoveryLeaseVersion(11L);
        work.setMigrationRecoveryLeaseExpiresAt(Long.MAX_VALUE);
        assertEquals("lease-7", work.getMigrationRecoveryLeaseToken());
        assertEquals(Long.valueOf(42L), work.getMigrationRecoveryLeaseOwner());
        assertEquals(11L, work.getMigrationRecoveryLeaseVersion());
        assertEquals(Long.valueOf(Long.MAX_VALUE), work.getMigrationRecoveryLeaseExpiresAt());
    }

    @Test
    public void terminalPhasesRejectFurtherRecoveryTransitions() {
        assertFalse(ItWorkVO.MigrationPhase.DONE.canTransitionTo(ItWorkVO.MigrationPhase.ROLLING_BACK));
        assertFalse(ItWorkVO.MigrationPhase.MANUAL_INTERVENTION.canTransitionTo(
                ItWorkVO.MigrationPhase.OWNERSHIP_COMMITTED));
        assertEquals(VdpaMigrationRecovery.Action.MANUAL_INTERVENTION,
                VdpaMigrationRecovery.decide(work(ItWorkVO.MigrationPhase.TRANSFERRING),
                        new VdpaMigrationRecovery.Observation(true, true, List.of())));
    }

    @Test
    public void terminalizationRequiresPostconditionsThenFenceCleanup() {
        assertTrue(ItWorkVO.MigrationPhase.SOURCE_CLEANUP
                .canTransitionTo(ItWorkVO.MigrationPhase.POSTCONDITIONS_PROVEN));
        assertTrue(ItWorkVO.MigrationPhase.POSTCONDITIONS_PROVEN
                .canTransitionTo(ItWorkVO.MigrationPhase.FENCE_CLEANUP_PENDING));
        assertTrue(ItWorkVO.MigrationPhase.FENCE_CLEANUP_PENDING
                .canTransitionTo(ItWorkVO.MigrationPhase.DONE));
        assertFalse(ItWorkVO.MigrationPhase.POSTCONDITIONS_PROVEN
                .canTransitionTo(ItWorkVO.MigrationPhase.DONE));
    }
}
