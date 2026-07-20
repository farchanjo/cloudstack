// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.vm;

import java.util.List;

/**
 * Management-authorized, fail-closed recovery decision point.  Remote
 * observation is deliberately an input: absence of an observation never
 * authorizes cleanup.
 */
public final class VdpaMigrationRecovery {
    public enum Action { CLEAN_DESTINATION_PREP, FINISH_DESTINATION_COMMIT, RESTORE_SOURCE,
        FINISH_SOURCE_CLEANUP, MANUAL_INTERVENTION }

    public static final class NicObservation {
        private final long nicId;
        private final boolean sourceExact;
        private final boolean destinationExact;
        private final boolean guestAttached;
        private final boolean ownershipCommitted;

        public NicObservation(final long nicId, final boolean sourceExact, final boolean destinationExact,
                final boolean guestAttached, final boolean ownershipCommitted) {
            this.nicId = nicId;
            this.sourceExact = sourceExact;
            this.destinationExact = destinationExact;
            this.guestAttached = guestAttached;
            this.ownershipCommitted = ownershipCommitted;
        }
        public long nicId() { return nicId; }
        public boolean sourceExact() { return sourceExact; }
        public boolean destinationExact() { return destinationExact; }
        public boolean guestAttached() { return guestAttached; }
        public boolean ownershipCommitted() { return ownershipCommitted; }
    }

    public static final class Observation {
        private final boolean sourceAvailable;
        private final boolean destinationAvailable;
        private final List<NicObservation> nics;

        public Observation(final boolean sourceAvailable, final boolean destinationAvailable,
                final List<NicObservation> nics) {
            this.sourceAvailable = sourceAvailable;
            this.destinationAvailable = destinationAvailable;
            this.nics = nics;
        }
        public boolean sourceAvailable() { return sourceAvailable; }
        public boolean destinationAvailable() { return destinationAvailable; }
        public List<NicObservation> nics() { return nics; }
    }

    private VdpaMigrationRecovery() {
    }

    public static Action decide(final ItWorkVO work, final Observation observation) {
        if (work == null || observation == null || observation.nics() == null
                || observation.nics().isEmpty() || !allExact(observation.nics())) {
            return Action.MANUAL_INTERVENTION;
        }
        if (work.getMigrationPhase() == ItWorkVO.MigrationPhase.OWNERSHIP_COMMITTED
                && observation.sourceAvailable() && observation.destinationAvailable()) {
            return Action.FINISH_SOURCE_CLEANUP;
        }
        if (observation.destinationAvailable() && allDestinationReady(observation.nics())) {
            return Action.FINISH_DESTINATION_COMMIT;
        }
        if (observation.destinationAvailable() && anyDestinationResource(observation.nics())
                && observation.sourceAvailable()) {
            return Action.CLEAN_DESTINATION_PREP;
        }
        if (observation.sourceAvailable() && allSourceReady(observation.nics())) {
            return Action.RESTORE_SOURCE;
        }
        return Action.MANUAL_INTERVENTION;
    }

    private static boolean anyDestinationResource(final List<NicObservation> nics) {
        return nics.stream().anyMatch(nic -> nic.destinationExact() && !nic.guestAttached());
    }

    private static boolean allExact(final List<NicObservation> nics) {
        return nics.stream().allMatch(nic -> nic.nicId() > 0
                && (nic.sourceExact() || nic.destinationExact()));
    }

    private static boolean allDestinationReady(final List<NicObservation> nics) {
        return nics.stream().allMatch(nic -> nic.destinationExact() && nic.guestAttached());
    }

    private static boolean allSourceReady(final List<NicObservation> nics) {
        return nics.stream().allMatch(nic -> nic.sourceExact() && !nic.guestAttached());
    }
}
