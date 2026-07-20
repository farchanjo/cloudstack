// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
package com.cloud.vm;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/** One exact NIC/VF/vDPA identity belonging to a migration generation. */
@Entity
@Table(name = "op_it_migration_nic")
public class MigrationNicVO {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "work_id", nullable = false)
    private String workId;
    @Column(name = "generation", nullable = false)
    private long generation;
    @Column(name = "nic_id", nullable = false)
    private long nicId;
    @Column(name = "vm_id", nullable = false)
    private long vmId;
    @Column(name = "vm_uuid", nullable = false)
    private String vmUuid;
    @Column(name = "nic_uuid", nullable = false)
    private String nicUuid;
    @Column(name = "nic_kind", nullable = false)
    private String nicKind;
    @Column(name = "terminal", nullable = false)
    private boolean terminal;
    @Column(name = "mac_address")
    private String macAddress;
    @Column(name = "vlan")
    private String vlan;
    @Column(name = "source_host_id")
    private Long sourceHostId;
    @Column(name = "destination_host_id")
    private Long destinationHostId;
    @Column(name = "lsp_id", nullable = false)
    private String lspId;
    @Column(name = "source_vf_pool_id")
    private Long sourceVfPoolId;
    @Column(name = "destination_vf_pool_id")
    private Long destinationVfPoolId;
    @Column(name = "source_bdf")
    private String sourceBdf;
    @Column(name = "destination_bdf")
    private String destinationBdf;
    @Column(name = "source_vdpa_name")
    private String sourceVdpaName;
    @Column(name = "destination_vdpa_name")
    private String destinationVdpaName;
    @Column(name = "source_vdpa_device")
    private String sourceVdpaDevice;
    @Column(name = "destination_vdpa_device")
    private String destinationVdpaDevice;
    @Column(name = "source_driver")
    private String sourceDriver;
    @Column(name = "destination_driver")
    private String destinationDriver;
    @Column(name = "source_representor")
    private String sourceRepresentor;
    @Column(name = "destination_representor")
    private String destinationRepresentor;
    @Column(name = "source_representor_phys_port")
    private String sourceRepresentorPhysPort;
    @Column(name = "destination_representor_phys_port")
    private String destinationRepresentorPhysPort;
    @Column(name = "source_representor_bdf")
    private String sourceRepresentorBdf;
    @Column(name = "destination_representor_bdf")
    private String destinationRepresentorBdf;
    @Column(name = "source_pf")
    private String sourcePf;
    @Column(name = "destination_pf")
    private String destinationPf;
    @Column(name = "source_vf_id")
    private Integer sourceVfId;
    @Column(name = "destination_vf_id")
    private Integer destinationVfId;
    @Column(name = "ovs_bridge")
    private String ovsBridge;
    @Column(name = "ovs_port")
    private String ovsPort;
    @Column(name = "ovs_interface")
    private String ovsInterface;
    @Column(name = "ovs_external_ids")
    private String ovsExternalIds;
    @Column(name = "source_ovs_bridge")
    private String sourceOvsBridge;
    @Column(name = "destination_ovs_bridge")
    private String destinationOvsBridge;
    @Column(name = "source_ovs_port")
    private String sourceOvsPort;
    @Column(name = "destination_ovs_port")
    private String destinationOvsPort;
    @Column(name = "source_ovs_interface")
    private String sourceOvsInterface;
    @Column(name = "destination_ovs_interface")
    private String destinationOvsInterface;
    @Column(name = "source_ovs_external_ids")
    private String sourceOvsExternalIds;
    @Column(name = "destination_ovs_external_ids")
    private String destinationOvsExternalIds;
    @Column(name = "source_ovs_bridge_uuid")
    private String sourceOvsBridgeUuid;
    @Column(name = "destination_ovs_bridge_uuid")
    private String destinationOvsBridgeUuid;
    @Column(name = "source_ovs_port_uuid")
    private String sourceOvsPortUuid;
    @Column(name = "destination_ovs_port_uuid")
    private String destinationOvsPortUuid;
    @Column(name = "source_ovs_interface_uuid")
    private String sourceOvsInterfaceUuid;
    @Column(name = "destination_ovs_interface_uuid")
    private String destinationOvsInterfaceUuid;
    @Column(name = "ovn_port_binding")
    private String ovnPortBinding;
    @Column(name = "ovn_chassis")
    private String ovnChassis;
    @Column(name = "libvirt_alias")
    private String libvirtAlias;
    @Column(name = "libvirt_target")
    private String libvirtTarget;
    @Column(name = "libvirt_source")
    private String libvirtSource;
    @Column(name = "libvirt_type")
    private String libvirtType;
    @Column(name = "libvirt_model")
    private String libvirtModel;
    @Column(name = "tc_expectation")
    private String tcExpectation;
    @Column(name = "fdb_expectation")
    private String fdbExpectation;
    @Column(name = "offload_expectation")
    private String offloadExpectation;
    @Column(name = "identity_availability", nullable = false)
    private String identityAvailability = "AVAILABLE";

    protected MigrationNicVO() {
    }

    public MigrationNicVO(final String workId, final long generation, final long nicId,
            final String lspId) {
        this.workId = workId;
        this.generation = generation;
        this.nicId = nicId;
        this.lspId = lspId;
    }

    public MigrationNicVO(final String workId, final long generation, final long vmId, final String vmUuid,
            final long nicId, final String nicUuid, final String nicKind, final String lspId) {
        this(workId, generation, nicId, lspId);
        this.vmId = vmId;
        this.vmUuid = vmUuid;
        this.nicUuid = nicUuid;
        this.nicKind = nicKind;
    }

    public long getId() { return id; }
    public String getWorkId() { return workId; }
    public long getGeneration() { return generation; }
    public long getNicId() { return nicId; }
    public String getLspId() { return lspId; }
    public long getVmId() { return vmId; }
    public String getVmUuid() { return vmUuid; }
    public String getNicUuid() { return nicUuid; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(final boolean value) { terminal = value; }
    public String getNicKind() { return nicKind; }
    public void setNicKind(final String value) { nicKind = value; }
    public String getMacAddress() { return macAddress; }
    public void setMacAddress(final String value) { macAddress = value; }
    public String getVlan() { return vlan; }
    public void setVlan(final String value) { vlan = value; }
    public Long getSourceHostId() { return sourceHostId; }
    public void setSourceHostId(final Long value) { sourceHostId = value; }
    public Long getDestinationHostId() { return destinationHostId; }
    public void setDestinationHostId(final Long value) { destinationHostId = value; }
    public String getSourceOvsBridge() { return sourceOvsBridge; }
    public void setSourceOvsBridge(final String value) { sourceOvsBridge = value; }
    public String getDestinationOvsBridge() { return destinationOvsBridge; }
    public void setDestinationOvsBridge(final String value) { destinationOvsBridge = value; }
    public String getSourceOvsPort() { return sourceOvsPort; }
    public void setSourceOvsPort(final String value) { sourceOvsPort = value; }
    public String getDestinationOvsPort() { return destinationOvsPort; }
    public void setDestinationOvsPort(final String value) { destinationOvsPort = value; }
    public String getSourceOvsInterface() { return sourceOvsInterface; }
    public void setSourceOvsInterface(final String value) { sourceOvsInterface = value; }
    public String getDestinationOvsInterface() { return destinationOvsInterface; }
    public void setDestinationOvsInterface(final String value) { destinationOvsInterface = value; }
    public String getSourceOvsExternalIds() { return sourceOvsExternalIds; }
    public void setSourceOvsExternalIds(final String value) { sourceOvsExternalIds = value; }
    public String getDestinationOvsExternalIds() { return destinationOvsExternalIds; }
    public void setDestinationOvsExternalIds(final String value) { destinationOvsExternalIds = value; }
    public Long getSourceVfPoolId() { return sourceVfPoolId; }
    public void setSourceVfPoolId(final Long value) { sourceVfPoolId = value; }
    public Long getDestinationVfPoolId() { return destinationVfPoolId; }
    public void setDestinationVfPoolId(final Long value) { destinationVfPoolId = value; }
    public String getSourceBdf() { return sourceBdf; }
    public void setSourceBdf(final String value) { sourceBdf = value; }
    public String getDestinationBdf() { return destinationBdf; }
    public void setDestinationBdf(final String value) { destinationBdf = value; }
    public String getSourceVdpaName() { return sourceVdpaName; }
    public void setSourceVdpaName(final String value) { sourceVdpaName = value; }
    public String getDestinationVdpaName() { return destinationVdpaName; }
    public void setDestinationVdpaName(final String value) { destinationVdpaName = value; }
    public String getSourceVdpaDevice() { return sourceVdpaDevice; }
    public void setSourceVdpaDevice(final String value) { sourceVdpaDevice = value; }
    public String getDestinationVdpaDevice() { return destinationVdpaDevice; }
    public void setDestinationVdpaDevice(final String value) { destinationVdpaDevice = value; }
    public String getSourceDriver() { return sourceDriver; }
    public void setSourceDriver(final String value) { sourceDriver = value; }
    public String getDestinationDriver() { return destinationDriver; }
    public void setDestinationDriver(final String value) { destinationDriver = value; }
    public String getSourceRepresentor() { return sourceRepresentor; }
    public void setSourceRepresentor(final String value) { sourceRepresentor = value; }
    public String getDestinationRepresentor() { return destinationRepresentor; }
    public void setDestinationRepresentor(final String value) { destinationRepresentor = value; }
    public String getSourceRepresentorPhysPort() { return sourceRepresentorPhysPort; }
    public void setSourceRepresentorPhysPort(final String value) { sourceRepresentorPhysPort = value; }
    public String getDestinationRepresentorPhysPort() { return destinationRepresentorPhysPort; }
    public void setDestinationRepresentorPhysPort(final String value) { destinationRepresentorPhysPort = value; }
    public String getSourceRepresentorBdf() { return sourceRepresentorBdf; }
    public void setSourceRepresentorBdf(final String value) { sourceRepresentorBdf = value; }
    public String getDestinationRepresentorBdf() { return destinationRepresentorBdf; }
    public void setDestinationRepresentorBdf(final String value) { destinationRepresentorBdf = value; }
    public String getSourcePf() { return sourcePf; }
    public void setSourcePf(final String value) { sourcePf = value; }
    public String getDestinationPf() { return destinationPf; }
    public void setDestinationPf(final String value) { destinationPf = value; }
    public Integer getSourceVfId() { return sourceVfId; }
    public void setSourceVfId(final Integer value) { sourceVfId = value; }
    public Integer getDestinationVfId() { return destinationVfId; }
    public void setDestinationVfId(final Integer value) { destinationVfId = value; }
    public String getOvsBridge() { return ovsBridge; }
    public void setOvsBridge(final String value) { ovsBridge = value; }
    public String getOvsPort() { return ovsPort; }
    public void setOvsPort(final String value) { ovsPort = value; }
    public String getOvsInterface() { return ovsInterface; }
    public void setOvsInterface(final String value) { ovsInterface = value; }
    public String getOvsExternalIds() { return ovsExternalIds; }
    public void setOvsExternalIds(final String value) { ovsExternalIds = value; }
    public String getSourceOvsBridgeUuid() { return sourceOvsBridgeUuid; }
    public void setSourceOvsBridgeUuid(final String value) { sourceOvsBridgeUuid = value; }
    public String getDestinationOvsBridgeUuid() { return destinationOvsBridgeUuid; }
    public void setDestinationOvsBridgeUuid(final String value) { destinationOvsBridgeUuid = value; }
    public String getSourceOvsPortUuid() { return sourceOvsPortUuid; }
    public void setSourceOvsPortUuid(final String value) { sourceOvsPortUuid = value; }
    public String getDestinationOvsPortUuid() { return destinationOvsPortUuid; }
    public void setDestinationOvsPortUuid(final String value) { destinationOvsPortUuid = value; }
    public String getSourceOvsInterfaceUuid() { return sourceOvsInterfaceUuid; }
    public void setSourceOvsInterfaceUuid(final String value) { sourceOvsInterfaceUuid = value; }
    public String getDestinationOvsInterfaceUuid() { return destinationOvsInterfaceUuid; }
    public void setDestinationOvsInterfaceUuid(final String value) { destinationOvsInterfaceUuid = value; }
    public String getOvnPortBinding() { return ovnPortBinding; }
    public void setOvnPortBinding(final String value) { ovnPortBinding = value; }
    public String getOvnChassis() { return ovnChassis; }
    public void setOvnChassis(final String value) { ovnChassis = value; }
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
    public String getTcExpectation() { return tcExpectation; }
    public void setTcExpectation(final String value) { tcExpectation = value; }
    public String getFdbExpectation() { return fdbExpectation; }
    public void setFdbExpectation(final String value) { fdbExpectation = value; }
    public String getOffloadExpectation() { return offloadExpectation; }
    public void setOffloadExpectation(final String value) { offloadExpectation = value; }
    public String getIdentityAvailability() { return identityAvailability; }
    public void setIdentityAvailability(final String value) { identityAvailability = value; }
}
