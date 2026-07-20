// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.vm;

import java.util.List;

/** Exact post-reboot readiness result; service-up alone is never sufficient. */
public final class VdpaMigrationReadiness {
    private final Status status;
    private final List<Long> mismatchedNicIds;

    public VdpaMigrationReadiness(final Status status, final List<Long> mismatchedNicIds) {
        this.status = status;
        this.mismatchedNicIds = mismatchedNicIds;
    }

    public Status status() { return status; }
    public List<Long> mismatchedNicIds() { return mismatchedNicIds; }
    public enum Status { READY, RECOVERY_REQUIRED, OBSERVATION_UNAVAILABLE }

    public static VdpaMigrationReadiness evaluate(final ItWorkVO work,
            final List<VdpaMigrationRecovery.NicObservation> observations,
            final boolean observationsAvailable) {
        if (!observationsAvailable || work == null || observations == null) {
            return new VdpaMigrationReadiness(Status.OBSERVATION_UNAVAILABLE, List.of());
        }
        final List<Long> mismatches = observations.stream()
                .filter(nic -> nic.nicId() <= 0 || !nic.sourceExact() && !nic.destinationExact())
                .map(VdpaMigrationRecovery.NicObservation::nicId).toList();
        final boolean stable = work.getMigrationPhase() == ItWorkVO.MigrationPhase.DONE
                || work.getMigrationPhase() == ItWorkVO.MigrationPhase.OWNERSHIP_COMMITTED
                || work.getMigrationPhase() == ItWorkVO.MigrationPhase.SOURCE_CLEANUP;
        return new VdpaMigrationReadiness(mismatches.isEmpty() && stable ? Status.READY : Status.RECOVERY_REQUIRED,
                mismatches);
    }
}
