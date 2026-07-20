// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.agent.api.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.cloud.agent.api.Command;

/** Single, identity-fenced management-authorized migration action. */
public class MigrationIdentityActionCommand extends Command {
    public enum Action { INSTALL_DESTINATION_FENCE, @Deprecated CLEAR_FENCE_ONLY, CLEAN_RECOVERY_FENCE, VERIFY_AND_RESTAMP, CLEAN_DESTINATION_PREP, RESTORE_SOURCE, CLEAN_SOURCE_AFTER_COMMIT,
        ADOPT_RECOVERY_FENCE }

    private String vmInstanceName;
    private String vmUuid;
    private String workId;
    private long generation;
    private String expectedPhase;
    private Action action;
    private String recoveryLeaseToken;
    private String oldFenceToken;
    private long oldFenceVersion;
    private long recoveryLeaseVersion;
    private long recoveryLeaseExpiresAt;
    private List<ObserveVdpaMigrationCommand.NicIdentity> nicIdentities;

    protected MigrationIdentityActionCommand() { }

    public MigrationIdentityActionCommand(final String vmInstanceName, final String vmUuid, final String workId,
            final long generation, final String expectedPhase, final Action action,
            final List<ObserveVdpaMigrationCommand.NicIdentity> nicIdentities) {
        this.vmInstanceName = vmInstanceName;
        this.vmUuid = vmUuid;
        this.workId = workId;
        this.generation = generation;
        this.expectedPhase = expectedPhase;
        this.action = action;
        this.nicIdentities = nicIdentities == null ? new ArrayList<>()
                : nicIdentities.stream().map(ObserveVdpaMigrationCommand.NicIdentity::copy).toList();
    }

    public MigrationIdentityActionCommand(final String vmInstanceName, final String vmUuid, final String workId,
            final long generation, final String expectedPhase, final Action action,
            final List<ObserveVdpaMigrationCommand.NicIdentity> nicIdentities, final String recoveryLeaseToken) {
        this(vmInstanceName, vmUuid, workId, generation, expectedPhase, action, nicIdentities);
        this.recoveryLeaseToken = recoveryLeaseToken;
    }

    public String getVmInstanceName() { return vmInstanceName; }
    public String getVmUuid() { return vmUuid; }
    public String getWorkId() { return workId; }
    public long getGeneration() { return generation; }
    public String getExpectedPhase() { return expectedPhase; }
    public Action getAction() { return action; }
    public String getRecoveryLeaseToken() { return recoveryLeaseToken; }
    public String getOldFenceToken() { return oldFenceToken; }
    public void setOldFenceToken(final String value) { oldFenceToken = value; }
    public long getOldFenceVersion() { return oldFenceVersion; }
    public void setOldFenceVersion(final long value) { oldFenceVersion = value; }
    public long getRecoveryLeaseVersion() { return recoveryLeaseVersion; }
    public void setRecoveryLeaseVersion(final long value) { recoveryLeaseVersion = value; }
    public long getRecoveryLeaseExpiresAt() { return recoveryLeaseExpiresAt; }
    public void setRecoveryLeaseExpiresAt(final long value) { recoveryLeaseExpiresAt = value; }
    public List<ObserveVdpaMigrationCommand.NicIdentity> getNicIdentities() {
        return nicIdentities == null ? Collections.emptyList() : Collections.unmodifiableList(nicIdentities);
    }

    @Override
    public boolean executeInSequence() { return false; }
}
