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

/** Read-only exact observation request for a fenced migration generation. */
public class ObserveVdpaMigrationCommand extends Command {
    private String vmInstanceName;
    private String workId;
    private long generation;
    private List<NicIdentity> nicIdentities;
    private boolean topologyDiscovery;

    protected ObserveVdpaMigrationCommand() {
    }

    public ObserveVdpaMigrationCommand(final String vmInstanceName, final String workId,
            final long generation, final List<NicIdentity> nicIdentities) {
        this.vmInstanceName = vmInstanceName;
        this.workId = workId;
        this.generation = generation;
        this.nicIdentities = nicIdentities == null ? new ArrayList<>()
                : nicIdentities.stream().map(NicIdentity::copy).toList();
    }

    public String getVmInstanceName() { return vmInstanceName; }
    public String getWorkId() { return workId; }
    public long getGeneration() { return generation; }
    public List<NicIdentity> getNicIdentities() {
        return nicIdentities == null ? Collections.emptyList() : Collections.unmodifiableList(nicIdentities);
    }
    public boolean isTopologyDiscovery() { return topologyDiscovery; }
    public void setTopologyDiscovery(final boolean value) { topologyDiscovery = value; }

    @Override
    public boolean executeInSequence() { return false; }

    public static class NicIdentity {
        private long nicId;
        private String lspId;
        private String expectedBdf;
        private String expectedVdpaName;
        private String expectedVdpaDevice;
        private String nicKind;
        private String expectedMac;
        private String expectedVlan;
        private String expectedDriver;
        private String expectedRepresentor;
        private String expectedOvsBridge;
        private String expectedOvsPort;
        private String expectedOvsInterface;
        private String expectedOvsExternalIds;
        private String expectedOvnPortBinding;
        private String expectedOvnChassis;
        private Long expectedVfPoolId;
        private String expectedLibvirtAlias;
        private String expectedLibvirtTarget;
        private String expectedLibvirtSource;
        private String expectedLibvirtType;
        private String expectedLibvirtModel;
        private String expectedPf;
        private Integer expectedVfId;
        private String expectedRepresentorPhysPortName;
        private String expectedRepresentorBdf;
        private String expectedMigrationWorkId;
        private Long expectedMigrationGeneration;
        private String expectedNicUuid;
        private Long expectedVfRowId;
        private String expectedTcExpectation;
        private String expectedFdbExpectation;
        private String expectedOvsBridgeUuid;
        private String expectedOvsPortUuid;
        private String expectedOvsInterfaceUuid;

        protected NicIdentity() { }

        public NicIdentity(final long nicId, final String lspId, final String expectedBdf,
                final String expectedVdpaName, final String expectedVdpaDevice) {
            this.nicId = nicId;
            this.lspId = lspId;
            this.expectedBdf = expectedBdf;
            this.expectedVdpaName = expectedVdpaName;
            this.expectedVdpaDevice = expectedVdpaDevice;
        }

        public NicIdentity(final long nicId, final String lspId, final String nicKind,
                final String expectedBdf, final String expectedVdpaName, final String expectedVdpaDevice,
                final String expectedMac, final String expectedVlan, final String expectedDriver,
                final String expectedRepresentor, final String expectedOvsBridge, final String expectedOvsPort,
                final String expectedOvsInterface, final String expectedOvsExternalIds,
                final String expectedOvnPortBinding, final String expectedOvnChassis) {
            this(nicId, lspId, expectedBdf, expectedVdpaName, expectedVdpaDevice);
            this.nicKind = nicKind;
            this.expectedMac = expectedMac;
            this.expectedVlan = expectedVlan;
            this.expectedDriver = expectedDriver;
            this.expectedRepresentor = expectedRepresentor;
            this.expectedOvsBridge = expectedOvsBridge;
            this.expectedOvsPort = expectedOvsPort;
            this.expectedOvsInterface = expectedOvsInterface;
            this.expectedOvsExternalIds = expectedOvsExternalIds;
            this.expectedOvnPortBinding = expectedOvnPortBinding;
            this.expectedOvnChassis = expectedOvnChassis;
        }

        public long getNicId() { return nicId; }
        public String getLspId() { return lspId; }
        public String getExpectedBdf() { return expectedBdf; }
        public String getExpectedVdpaName() { return expectedVdpaName; }
        public String getExpectedVdpaDevice() { return expectedVdpaDevice; }
        public String getNicKind() { return nicKind; }
        public String getExpectedMac() { return expectedMac; }
        public String getExpectedVlan() { return expectedVlan; }
        public String getExpectedDriver() { return expectedDriver; }
        public String getExpectedRepresentor() { return expectedRepresentor; }
        public String getExpectedOvsBridge() { return expectedOvsBridge; }
        public String getExpectedOvsPort() { return expectedOvsPort; }
        public String getExpectedOvsInterface() { return expectedOvsInterface; }
        public String getExpectedOvsExternalIds() { return expectedOvsExternalIds; }
        public String getExpectedOvnPortBinding() { return expectedOvnPortBinding; }
        public String getExpectedOvnChassis() { return expectedOvnChassis; }
        public Long getExpectedVfPoolId() { return expectedVfPoolId; }
        public void setExpectedVfPoolId(final Long value) { expectedVfPoolId = value; }
        public String getExpectedLibvirtAlias() { return expectedLibvirtAlias; }
        public void setExpectedLibvirtAlias(final String value) { expectedLibvirtAlias = value; }
        public String getExpectedLibvirtTarget() { return expectedLibvirtTarget; }
        public void setExpectedLibvirtTarget(final String value) { expectedLibvirtTarget = value; }
        public String getExpectedLibvirtSource() { return expectedLibvirtSource; }
        public void setExpectedLibvirtSource(final String value) { expectedLibvirtSource = value; }
        public String getExpectedLibvirtType() { return expectedLibvirtType; }
        public void setExpectedLibvirtType(final String value) { expectedLibvirtType = value; }
        public String getExpectedLibvirtModel() { return expectedLibvirtModel; }
        public void setExpectedLibvirtModel(final String value) { expectedLibvirtModel = value; }
        public String getExpectedPf() { return expectedPf; }
        public void setExpectedPf(final String value) { expectedPf = value; }
        public Integer getExpectedVfId() { return expectedVfId; }
        public void setExpectedVfId(final Integer value) { expectedVfId = value; }
        public String getExpectedRepresentorPhysPortName() { return expectedRepresentorPhysPortName; }
        public void setExpectedRepresentorPhysPortName(final String value) { expectedRepresentorPhysPortName = value; }
        public String getExpectedRepresentorBdf() { return expectedRepresentorBdf; }
        public void setExpectedRepresentorBdf(final String value) { expectedRepresentorBdf = value; }
        public String getExpectedMigrationWorkId() { return expectedMigrationWorkId; }
        public void setExpectedMigrationWorkId(final String value) { expectedMigrationWorkId = value; }
        public Long getExpectedMigrationGeneration() { return expectedMigrationGeneration; }
        public void setExpectedMigrationGeneration(final Long value) { expectedMigrationGeneration = value; }
        public String getExpectedNicUuid() { return expectedNicUuid; }
        public void setExpectedNicUuid(final String value) { expectedNicUuid = value; }
        public Long getExpectedVfRowId() { return expectedVfRowId; }
        public void setExpectedVfRowId(final Long value) { expectedVfRowId = value; }
        public String getExpectedTcExpectation() { return expectedTcExpectation; }
        public void setExpectedTcExpectation(final String value) { expectedTcExpectation = value; }
        public String getExpectedFdbExpectation() { return expectedFdbExpectation; }
        public void setExpectedFdbExpectation(final String value) { expectedFdbExpectation = value; }
        public String getExpectedOvsBridgeUuid() { return expectedOvsBridgeUuid; }
        public void setExpectedOvsBridgeUuid(final String value) { expectedOvsBridgeUuid = value; }
        public String getExpectedOvsPortUuid() { return expectedOvsPortUuid; }
        public void setExpectedOvsPortUuid(final String value) { expectedOvsPortUuid = value; }
        public String getExpectedOvsInterfaceUuid() { return expectedOvsInterfaceUuid; }
        public void setExpectedOvsInterfaceUuid(final String value) { expectedOvsInterfaceUuid = value; }

        public NicIdentity copy() {
            final NicIdentity copy = new NicIdentity();
            copy.nicId = nicId; copy.lspId = lspId; copy.expectedBdf = expectedBdf;
            copy.expectedVdpaName = expectedVdpaName; copy.expectedVdpaDevice = expectedVdpaDevice;
            copy.nicKind = nicKind; copy.expectedMac = expectedMac; copy.expectedVlan = expectedVlan;
            copy.expectedDriver = expectedDriver; copy.expectedRepresentor = expectedRepresentor;
            copy.expectedOvsBridge = expectedOvsBridge; copy.expectedOvsPort = expectedOvsPort;
            copy.expectedOvsInterface = expectedOvsInterface; copy.expectedOvsExternalIds = expectedOvsExternalIds;
            copy.expectedOvnPortBinding = expectedOvnPortBinding; copy.expectedOvnChassis = expectedOvnChassis;
            copy.expectedVfPoolId = expectedVfPoolId; copy.expectedLibvirtAlias = expectedLibvirtAlias;
            copy.expectedLibvirtTarget = expectedLibvirtTarget; copy.expectedLibvirtSource = expectedLibvirtSource;
            copy.expectedLibvirtType = expectedLibvirtType; copy.expectedLibvirtModel = expectedLibvirtModel;
            copy.expectedPf = expectedPf; copy.expectedVfId = expectedVfId;
            copy.expectedRepresentorPhysPortName = expectedRepresentorPhysPortName;
            copy.expectedRepresentorBdf = expectedRepresentorBdf; copy.expectedMigrationWorkId = expectedMigrationWorkId;
            copy.expectedMigrationGeneration = expectedMigrationGeneration; copy.expectedNicUuid = expectedNicUuid;
            copy.expectedVfRowId = expectedVfRowId; copy.expectedTcExpectation = expectedTcExpectation;
            copy.expectedFdbExpectation = expectedFdbExpectation;
            copy.expectedOvsBridgeUuid = expectedOvsBridgeUuid; copy.expectedOvsPortUuid = expectedOvsPortUuid;
            copy.expectedOvsInterfaceUuid = expectedOvsInterfaceUuid;
            return copy;
        }
    }
}
