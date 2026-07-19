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
package com.cloud.network.router;

import java.util.List;
import java.util.Objects;

/** Structured, read-only migration admission result. */
public final class MigrationPreflightResult {

    private final boolean allowed;
    private final long vmId;
    private final long destinationHostId;
    private final int requiredVdpaVfs;
    private final int freeVdpaVfs;
    private final String denialReason;
    private final List<MigrationNicPreflightStatus> nicStatuses;
    private final boolean requestedChassisOk;
    private final boolean hostdevLiveRejected;

    public MigrationPreflightResult(final boolean allowed, final long vmId, final long destinationHostId,
            final int requiredVdpaVfs, final int freeVdpaVfs, final String denialReason,
            final List<MigrationNicPreflightStatus> nicStatuses, final boolean requestedChassisOk,
            final boolean hostdevLiveRejected) {
        this.allowed = allowed;
        this.vmId = vmId;
        this.destinationHostId = destinationHostId;
        this.requiredVdpaVfs = requiredVdpaVfs;
        this.freeVdpaVfs = freeVdpaVfs;
        this.denialReason = denialReason;
        this.nicStatuses = nicStatuses;
        this.requestedChassisOk = requestedChassisOk;
        this.hostdevLiveRejected = hostdevLiveRejected;
    }

    public MigrationPreflightResult(final boolean allowed, final long vmId, final long destinationHostId,
            final int requiredVdpaVfs, final int freeVdpaVfs, final String denialReason) {
        this(allowed, vmId, destinationHostId, requiredVdpaVfs, freeVdpaVfs, denialReason,
                List.of(), true, false);
    }

    public MigrationPreflightResult(final boolean allowed, final long vmId, final long destinationHostId,
            final int requiredVdpaVfs, final int freeVdpaVfs, final String denialReason,
            final List<MigrationNicPreflightStatus> nicStatuses) {
        this(allowed, vmId, destinationHostId, requiredVdpaVfs, freeVdpaVfs, denialReason,
                nicStatuses, true, false);
    }

    public boolean allowed() { return allowed; }
    public long vmId() { return vmId; }
    public long destinationHostId() { return destinationHostId; }
    public int requiredVdpaVfs() { return requiredVdpaVfs; }
    public int freeVdpaVfs() { return freeVdpaVfs; }
    public String denialReason() { return denialReason; }
    public List<MigrationNicPreflightStatus> nicStatuses() { return nicStatuses; }
    public boolean requestedChassisOk() { return requestedChassisOk; }
    public boolean hostdevLiveRejected() { return hostdevLiveRejected; }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationPreflightResult)) {
            return false;
        }
        final MigrationPreflightResult that = (MigrationPreflightResult) other;
        return allowed == that.allowed && vmId == that.vmId && destinationHostId == that.destinationHostId
                && requiredVdpaVfs == that.requiredVdpaVfs && freeVdpaVfs == that.freeVdpaVfs
                && requestedChassisOk == that.requestedChassisOk && hostdevLiveRejected == that.hostdevLiveRejected
                && Objects.equals(denialReason, that.denialReason) && Objects.equals(nicStatuses, that.nicStatuses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowed, vmId, destinationHostId, requiredVdpaVfs, freeVdpaVfs,
                denialReason, nicStatuses, requestedChassisOk, hostdevLiveRejected);
    }

    @Override
    public String toString() {
        return "MigrationPreflightResult[allowed=" + allowed + ", vmId=" + vmId
                + ", destinationHostId=" + destinationHostId + ", requiredVdpaVfs=" + requiredVdpaVfs
                + ", freeVdpaVfs=" + freeVdpaVfs + ", denialReason=" + denialReason
                + ", nicStatuses=" + nicStatuses + ", requestedChassisOk=" + requestedChassisOk
                + ", hostdevLiveRejected=" + hostdevLiveRejected + "]";
    }
}
