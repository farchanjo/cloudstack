//
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
//

package com.cloud.agent.api;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;
import java.util.ArrayList;
import java.util.List;

public class PrepareForMigrationCommand extends Command {
    private VirtualMachineTO vm;
    private boolean rollback;
    private String migrationWorkId;
    private long migrationGeneration;
    private String migrationLeaseToken;
    private long migrationLeaseVersion;
    private long migrationLeaseExpiry;
    private List<ObserveVdpaMigrationCommand.NicIdentity> migrationIdentities = new ArrayList<>();

    protected PrepareForMigrationCommand() {
    }

    public PrepareForMigrationCommand(VirtualMachineTO vm) {
        this.vm = vm;
    }

    public VirtualMachineTO getVirtualMachine() {
        return vm;
    }

    public void setRollback(boolean rollback) {
        this.rollback = rollback;
    }

    public boolean isRollback() {
        return rollback;
    }

    public String getMigrationWorkId() { return migrationWorkId; }
    public void setMigrationWorkId(final String value) { migrationWorkId = value; }
    public long getMigrationGeneration() { return migrationGeneration; }
    public void setMigrationGeneration(final long value) { migrationGeneration = value; }
    public String getMigrationLeaseToken() { return migrationLeaseToken; }
    public void setMigrationLeaseToken(final String value) { migrationLeaseToken = value; }
    public long getMigrationLeaseVersion() { return migrationLeaseVersion; }
    public void setMigrationLeaseVersion(final long value) { migrationLeaseVersion = value; }
    public long getMigrationLeaseExpiry() { return migrationLeaseExpiry; }
    public void setMigrationLeaseExpiry(final long value) { migrationLeaseExpiry = value; }
    public List<ObserveVdpaMigrationCommand.NicIdentity> getMigrationIdentities() { return migrationIdentities; }
    public void setMigrationIdentities(final List<ObserveVdpaMigrationCommand.NicIdentity> value) {
        migrationIdentities = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
