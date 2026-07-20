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
package com.cloud.vm;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.time.InaccurateClock;
import com.cloud.vm.VirtualMachine.State;

@Entity
@Table(name = "op_it_work")
public class ItWorkVO {
    enum ResourceType {
        Volume, Nic, Host
    }

    enum Step {
        Prepare, Starting, Started, Release, Done, Migrating, Reconfiguring
    }

    /** Durable checkpoints for cold VF/vDPA relocation. */
    public enum MigrationPhase {
        PREPARING_DESTINATION,
        DESTINATION_ALLOCATED,
        SOURCE_MANIFEST_INSTALLED,
        DESTINATION_DATAPLANE_PROVEN,
        TRANSFERRING,
        STARTING_DESTINATION,
        GUEST_TRANSFERRED_OR_STARTED,
        OWNERSHIP_COMMITTED,
        SOURCE_CLEANUP,
        POSTCONDITIONS_PROVEN,
        FENCE_CLEANUP_PENDING,
        ROLLING_BACK,
        DONE,
        MANUAL_INTERVENTION;

        public boolean canTransitionTo(final MigrationPhase next) {
            if (next == null) {
                return false;
            }
            switch (this) {
                case PREPARING_DESTINATION:
                    return next == DESTINATION_ALLOCATED || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case DESTINATION_ALLOCATED:
                    return next == SOURCE_MANIFEST_INSTALLED || next == DESTINATION_DATAPLANE_PROVEN
                            || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case SOURCE_MANIFEST_INSTALLED:
                    return next == DESTINATION_DATAPLANE_PROVEN || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case DESTINATION_DATAPLANE_PROVEN:
                    return next == TRANSFERRING || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case TRANSFERRING:
                    return next == STARTING_DESTINATION || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case STARTING_DESTINATION:
                    return next == GUEST_TRANSFERRED_OR_STARTED || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case GUEST_TRANSFERRED_OR_STARTED:
                    return next == OWNERSHIP_COMMITTED || next == ROLLING_BACK || next == MANUAL_INTERVENTION;
                case OWNERSHIP_COMMITTED:
                    return next == SOURCE_CLEANUP || next == MANUAL_INTERVENTION;
                case SOURCE_CLEANUP:
                    return next == POSTCONDITIONS_PROVEN || next == MANUAL_INTERVENTION;
                case POSTCONDITIONS_PROVEN:
                    return next == FENCE_CLEANUP_PENDING || next == MANUAL_INTERVENTION;
                case FENCE_CLEANUP_PENDING:
                    return next == DONE || next == MANUAL_INTERVENTION;
                case ROLLING_BACK:
                    return next == POSTCONDITIONS_PROVEN || next == MANUAL_INTERVENTION;
                case DONE:
                case MANUAL_INTERVENTION:
                    return false;
                default:
                    return false;
            }
        }
    }

    public enum MigrationMode { ORDINARY, ACCELERATED_COLD }

    @Id
    @Column(name = "id")
    String id;

    @Column(name = "created_at")
    long createdAt;

    @Column(name = "mgmt_server_id")
    long managementServerId;

    @Column(name = "type")
    State type;

    @Column(name = "thread")
    String threadName;

    @Column(name = "step")
    Step step;

    @Column(name = "updated_at")
    long updatedAt;

    @Column(name = "instance_id")
    long instanceId;

    public long getInstanceId() {
        return instanceId;
    }

    @Column(name = "resource_id")
    long resourceId;

    @Column(name = "resource_type")
    ResourceType resourceType;

    @Column(name = "vm_type")
    @Enumerated(value = EnumType.STRING)
    VirtualMachine.Type vmType;

    @Column(name = "migration_phase")
    String migrationPhase;

    @Column(name = "migration_mode")
    String migrationMode;

    @Column(name = "migration_generation")
    long migrationGeneration;

    @Column(name = "migration_vm_uuid")
    String migrationVmUuid;

    @Column(name = "migration_source_host_id")
    Long migrationSourceHostId;

    @Column(name = "migration_destination_host_id")
    Long migrationDestinationHostId;

    @Column(name = "migration_recovery_lease_token")
    String migrationRecoveryLeaseToken;

    @Column(name = "migration_recovery_lease_owner")
    Long migrationRecoveryLeaseOwner;

    @Column(name = "migration_recovery_lease_expires_at")
    Long migrationRecoveryLeaseExpiresAt;

    @Column(name = "migration_recovery_lease_version")
    long migrationRecoveryLeaseVersion;

    @Column(name = "migration_recovery_lease_heartbeat")
    Long migrationRecoveryLeaseHeartbeat;

    public VirtualMachine.Type getVmType() {
        return vmType;
    }

    public MigrationPhase getMigrationPhase() {
        return migrationPhase == null ? null : MigrationPhase.valueOf(migrationPhase);
    }

    public String getMigrationPhaseValue() {
        return migrationPhase;
    }

    public MigrationMode getMigrationMode() {
        return migrationMode == null ? null : MigrationMode.valueOf(migrationMode);
    }

    public void setMigrationMode(final MigrationMode mode) {
        migrationMode = mode == null ? null : mode.name();
    }

    public String getMigrationModeValue() {
        return migrationMode;
    }

    public void setMigrationPhase(final MigrationPhase phase) {
        migrationPhase = phase == null ? null : phase.name();
    }

    public long getMigrationGeneration() {
        return migrationGeneration;
    }

    public void setMigrationGeneration(final long generation) {
        migrationGeneration = generation;
    }

    public String getMigrationVmUuid() {
        return migrationVmUuid;
    }

    public void setMigrationVmUuid(final String uuid) {
        migrationVmUuid = uuid;
    }

    public Long getMigrationSourceHostId() {
        return migrationSourceHostId;
    }

    public void setMigrationSourceHostId(final Long hostId) {
        migrationSourceHostId = hostId;
    }

    public Long getMigrationDestinationHostId() {
        return migrationDestinationHostId;
    }

    public void setMigrationDestinationHostId(final Long hostId) {
        migrationDestinationHostId = hostId;
    }

    public String getMigrationRecoveryLeaseToken() {
        return migrationRecoveryLeaseToken;
    }

    public void setMigrationRecoveryLeaseToken(final String token) {
        migrationRecoveryLeaseToken = token;
    }

    public Long getMigrationRecoveryLeaseOwner() { return migrationRecoveryLeaseOwner; }
    public void setMigrationRecoveryLeaseOwner(final Long owner) { migrationRecoveryLeaseOwner = owner; }
    public Long getMigrationRecoveryLeaseExpiresAt() { return migrationRecoveryLeaseExpiresAt; }
    public void setMigrationRecoveryLeaseExpiresAt(final Long expiresAt) { migrationRecoveryLeaseExpiresAt = expiresAt; }
    public long getMigrationRecoveryLeaseVersion() { return migrationRecoveryLeaseVersion; }
    public void setMigrationRecoveryLeaseVersion(final long version) { migrationRecoveryLeaseVersion = version; }
    public Long getMigrationRecoveryLeaseHeartbeat() { return migrationRecoveryLeaseHeartbeat; }
    public void setMigrationRecoveryLeaseHeartbeat(final Long heartbeat) { migrationRecoveryLeaseHeartbeat = heartbeat; }

    public long getResourceId() {
        return resourceId;
    }

    public void setResourceId(long resourceId) {
        this.resourceId = resourceId;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public void setResourceType(ResourceType resourceType) {
        this.resourceType = resourceType;
    }

    protected ItWorkVO() {
    }

    protected ItWorkVO(String id, long managementServerId, State type, VirtualMachine.Type vmType, long instanceId) {
        this.id = id;
        this.managementServerId = managementServerId;
        this.type = type;
        this.threadName = Thread.currentThread().getName();
        this.step = Step.Prepare;
        this.instanceId = instanceId;
        this.resourceType = null;
        this.createdAt = InaccurateClock.getTimeInSeconds();
        this.updatedAt = createdAt;
        this.vmType = vmType;
    }

    public String getId() {
        return id;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public long getManagementServerId() {
        return managementServerId;
    }

    public void setManagementServerId(long managementServerId) {
        this.managementServerId = managementServerId;
    }

    public State getType() {
        return type;
    }

    public void setType(State type) {
        this.type = type;
    }

    public String getThreadName() {
        return threadName;
    }

    public Step getStep() {
        return step;
    }

    public void setStep(Step step) {
        this.step = step;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getSecondsTaskIsInactive() {
        return InaccurateClock.getTimeInSeconds() - this.updatedAt;
    }

    public long getSecondsTaskHasBeenCreated() {
        return InaccurateClock.getTimeInSeconds() - this.createdAt;
    }

    @Override
    public String toString() {
        return new StringBuilder("ItWork[").append(id)
            .append("-")
            .append(type.toString())
            .append("-")
            .append(instanceId)
            .append("-")
            .append(step.toString())
            .append("]")
            .toString();
    }
}
