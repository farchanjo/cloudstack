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
import java.util.Map;

import com.cloud.agent.api.Answer;

/** Read-only migration observation; unavailable fields remain explicit. */
public class ObserveVdpaMigrationAnswer extends Answer {
    public enum ObservationState { OBSERVED, UNAVAILABLE, NOT_APPLICABLE }
    private String observedWorkId;
    private long observedGeneration;
    private boolean observationAvailable;
    private List<NicObservation> nicObservations;

    protected ObserveVdpaMigrationAnswer() { }

    public ObserveVdpaMigrationAnswer(final ObserveVdpaMigrationCommand command, final boolean success,
            final String details, final String observedWorkId, final long observedGeneration,
            final boolean observationAvailable, final List<NicObservation> nicObservations) {
        super(command, success, details);
        this.observedWorkId = observedWorkId;
        this.observedGeneration = observedGeneration;
        this.observationAvailable = observationAvailable;
        this.nicObservations = nicObservations == null ? new ArrayList<>() : new ArrayList<>(nicObservations);
    }

    public String getObservedWorkId() { return observedWorkId; }
    public long getObservedGeneration() { return observedGeneration; }
    public boolean isObservationAvailable() { return observationAvailable; }
    public List<NicObservation> getNicObservations() {
        return nicObservations == null ? Collections.emptyList() : Collections.unmodifiableList(nicObservations);
    }

    public static class NicObservation {
        private long nicId;
        private String nicUuid;
        private String lspId;
        private String actualBdf;
        private String actualVdpaName;
        private String actualVdpaDevice;
        private String domainState;
        private String vfDriver;
        private String mac;
        private String vlan;
        private String representor;
        private String ovsExternalIds;
        private String pf;
        private Integer vfId;
        private String representorPhysPortName;
        private String representorBdf;
        private String ovsBridge;
        private String ovsPort;
        private String ovsInterface;
        private String ovnMetadata;
        private String libvirtAlias;
        private String libvirtTarget;
        private String libvirtSource;
        private String libvirtType;
        private String libvirtModel;
        private String tcIdentity;
        private String fdbIdentity;
        private String ovsBridgeUuid;
        private String ovsPortUuid;
        private String ovsInterfaceUuid;
        private boolean exact;
        private boolean available;
        private Map<String, ObservationState> fieldStates;

        protected NicObservation() { }

        public NicObservation(final long nicId, final String lspId, final String actualBdf,
                final String actualVdpaName, final String actualVdpaDevice, final String domainState,
                final String vfDriver, final String mac, final String vlan, final String representor,
                final String ovsExternalIds, final boolean exact, final boolean available) {
            this.nicId = nicId;
            this.lspId = lspId;
            this.actualBdf = actualBdf;
            this.actualVdpaName = actualVdpaName;
            this.actualVdpaDevice = actualVdpaDevice;
            this.domainState = domainState;
            this.vfDriver = vfDriver;
            this.mac = mac;
            this.vlan = vlan;
            this.representor = representor;
            this.ovsExternalIds = ovsExternalIds;
            this.exact = exact;
            this.available = available;
        }

        public NicObservation(final long nicId, final String lspId, final String actualBdf,
                final String actualVdpaName, final String actualVdpaDevice, final String domainState,
                final String vfDriver, final String mac, final String vlan, final String representor,
                final String ovsExternalIds, final boolean exact, final boolean available,
                final Map<String, ObservationState> states) {
            this(nicId, lspId, actualBdf, actualVdpaName, actualVdpaDevice, domainState, vfDriver, mac, vlan,
                    representor, ovsExternalIds, exact, available);
            fieldStates = states;
        }

        public long getNicId() { return nicId; }
        public String getLspId() { return lspId; }
        public String getNicUuid() { return nicUuid; }
        public void setNicUuid(final String value) { nicUuid = value; }
        public String getActualBdf() { return actualBdf; }
        public String getActualVdpaName() { return actualVdpaName; }
        public String getActualVdpaDevice() { return actualVdpaDevice; }
        public String getDomainState() { return domainState; }
        public void setDomainState(final String value) { domainState = value; }
        public String getVfDriver() { return vfDriver; }
        public String getMac() { return mac; }
        public String getVlan() { return vlan; }
        public String getRepresentor() { return representor; }
        public String getOvsExternalIds() { return ovsExternalIds; }
        public boolean isExact() { return exact; }
        public boolean isAvailable() { return available; }
        public String getPf() { return pf; }
        public void setPf(final String value) { pf = value; }
        public Integer getVfId() { return vfId; }
        public void setVfId(final Integer value) { vfId = value; }
        public String getRepresentorPhysPortName() { return representorPhysPortName; }
        public void setRepresentorPhysPortName(final String value) { representorPhysPortName = value; }
        public String getRepresentorBdf() { return representorBdf; }
        public void setRepresentorBdf(final String value) { representorBdf = value; }
        public String getOvsBridge() { return ovsBridge; }
        public void setOvsBridge(final String value) { ovsBridge = value; }
        public String getOvsPort() { return ovsPort; }
        public void setOvsPort(final String value) { ovsPort = value; }
        public String getOvsInterface() { return ovsInterface; }
        public void setOvsInterface(final String value) { ovsInterface = value; }
        public String getOvnMetadata() { return ovnMetadata; }
        public void setOvnMetadata(final String value) { ovnMetadata = value; }
        public String getLibvirtAlias() { return libvirtAlias; }
        public void setLibvirtAlias(final String value) { libvirtAlias = value; }
        public String getLibvirtTarget() { return libvirtTarget; }
        public void setLibvirtTarget(final String value) { libvirtTarget = value; }
        public String getLibvirtSource() { return libvirtSource; }
        public void setLibvirtSource(final String value) { libvirtSource = value; }
        public String getLibvirtType() { return libvirtType; }
        public void setLibvirtType(final String value) { libvirtType = value; }
        public String getLibvirtModel() { return libvirtModel; }
        public void setLibvirtModel(final String value) { libvirtModel = value; }
        public String getTcIdentity() { return tcIdentity; }
        public void setTcIdentity(final String value) { tcIdentity = value; }
        public String getFdbIdentity() { return fdbIdentity; }
        public void setFdbIdentity(final String value) { fdbIdentity = value; }
        public String getOvsBridgeUuid() { return ovsBridgeUuid; }
        public void setOvsBridgeUuid(final String value) { ovsBridgeUuid = value; }
        public String getOvsPortUuid() { return ovsPortUuid; }
        public void setOvsPortUuid(final String value) { ovsPortUuid = value; }
        public String getOvsInterfaceUuid() { return ovsInterfaceUuid; }
        public void setOvsInterfaceUuid(final String value) { ovsInterfaceUuid = value; }
        public void setMac(final String value) { mac = value; }
        public void setVlan(final String value) { vlan = value; }
        public Map<String, ObservationState> getFieldStates() { return fieldStates == null ? Map.of() : fieldStates; }
        public void setFieldStates(final Map<String, ObservationState> states) { fieldStates = states; }
    }
}
