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

import java.util.Objects;

/** Per-NIC migration admission evidence. */
public final class MigrationNicPreflightStatus {

    private final String nicId;
    private final boolean allowed;
    private final int requiredVdpaVfs;
    private final int freeVdpaVfs;
    private final String denialReason;
    private final String macAddress;
    private final String ifaceId;
    private final String requestedChassis;
    private final String sourceChassis;
    private final String destinationChassis;

    public MigrationNicPreflightStatus(final String nicId, final boolean allowed, final int requiredVdpaVfs,
            final int freeVdpaVfs, final String denialReason, final String macAddress, final String ifaceId,
            final String requestedChassis, final String sourceChassis, final String destinationChassis) {
        this.nicId = nicId;
        this.allowed = allowed;
        this.requiredVdpaVfs = requiredVdpaVfs;
        this.freeVdpaVfs = freeVdpaVfs;
        this.denialReason = denialReason;
        this.macAddress = macAddress;
        this.ifaceId = ifaceId;
        this.requestedChassis = requestedChassis;
        this.sourceChassis = sourceChassis;
        this.destinationChassis = destinationChassis;
    }

    public MigrationNicPreflightStatus(final String nicId, final boolean allowed,
            final int requiredVdpaVfs, final int freeVdpaVfs, final String denialReason) {
        this(nicId, allowed, requiredVdpaVfs, freeVdpaVfs, denialReason,
                null, null, null, null, null);
    }

    public String nicId() { return nicId; }
    public boolean allowed() { return allowed; }
    public int requiredVdpaVfs() { return requiredVdpaVfs; }
    public int freeVdpaVfs() { return freeVdpaVfs; }
    public String denialReason() { return denialReason; }
    public String macAddress() { return macAddress; }
    public String ifaceId() { return ifaceId; }
    public String requestedChassis() { return requestedChassis; }
    public String sourceChassis() { return sourceChassis; }
    public String destinationChassis() { return destinationChassis; }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationNicPreflightStatus)) {
            return false;
        }
        final MigrationNicPreflightStatus that = (MigrationNicPreflightStatus) other;
        return allowed == that.allowed && requiredVdpaVfs == that.requiredVdpaVfs && freeVdpaVfs == that.freeVdpaVfs
                && Objects.equals(nicId, that.nicId) && Objects.equals(denialReason, that.denialReason)
                && Objects.equals(macAddress, that.macAddress) && Objects.equals(ifaceId, that.ifaceId)
                && Objects.equals(requestedChassis, that.requestedChassis) && Objects.equals(sourceChassis, that.sourceChassis)
                && Objects.equals(destinationChassis, that.destinationChassis);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nicId, allowed, requiredVdpaVfs, freeVdpaVfs, denialReason, macAddress,
                ifaceId, requestedChassis, sourceChassis, destinationChassis);
    }

    @Override
    public String toString() {
        return "MigrationNicPreflightStatus[nicId=" + nicId + ", allowed=" + allowed
                + ", requiredVdpaVfs=" + requiredVdpaVfs + ", freeVdpaVfs=" + freeVdpaVfs
                + ", denialReason=" + denialReason + ", macAddress=" + macAddress + ", ifaceId=" + ifaceId
                + ", requestedChassis=" + requestedChassis + ", sourceChassis=" + sourceChassis
                + ", destinationChassis=" + destinationChassis + "]";
    }
}
