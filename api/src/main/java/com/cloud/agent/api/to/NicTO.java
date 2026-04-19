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
package com.cloud.agent.api.to;

import com.cloud.offering.NetworkOffering;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NicTO extends NetworkTO {
    int deviceId;
    Integer networkRateMbps;
    Integer networkRateMulticastMbps;
    boolean defaultNic;
    boolean pxeDisable;
    String nicUuid;
    List<String> nicSecIps;
    Map<NetworkOffering.Detail, String> details;
    boolean dpdkEnabled;
    Integer mtu;
    Long networkId;
    boolean enabled;

    String networkSegmentName;

    /* SR-IOV VF passthrough (HW Offload). All optional; null/false means traditional bridge/TAP. */
    String vfPciAddress;       // VF PCI bus address, e.g. "0000:01:00.2"
    Boolean useHwOffload;      // wrapper Boolean → null preserves wire compat with older agents
    String vfPfName;           // Physical Function netdev name (e.g. "dx6p0")

    /* SR-IOV Sub-Function with vDPA. All optional; null/false means no SF/vDPA. */
    String vdpaDevice;         // vDPA device path, e.g. "/dev/vhost-vdpa-0"
    Boolean useSfVdpa;         // wrapper Boolean → null preserves wire compat with older agents
    String sfRepresentorName;  // SF representor name, e.g. "dx6p0sf0"

    /**
     * Free-form String-keyed detail map propagated from the management server
     * to the agent. Distinct from {@link #details} (which is typed by
     * {@link NetworkOffering.Detail}) because some agent-side features need
     * to carry values that don't fit an enum — e.g. dynamic VXLAN peer lists
     * ({@code vxlan.peers}), the local host mgmt IP ({@code vxlan.local.ip}),
     * or the owning VM instance name ({@code vxlan.vm.name}) used for
     * ref-counted tunnel cleanup.
     *
     * <p>Null-preserving wire compat: older agents that don't know about this
     * field simply ignore it.
     */
    Map<String, String> nicDetails;

    public NicTO() {
        super();
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public Integer getNetworkRateMbps() {
        return networkRateMbps;
    }

    public void setNetworkRateMbps(Integer networkRateMbps) {
        this.networkRateMbps = networkRateMbps;
    }

    public Integer getNetworkRateMulticastMbps() {
        return networkRateMulticastMbps;
    }

    public boolean isDefaultNic() {
        return defaultNic;
    }

    public void setDefaultNic(boolean defaultNic) {
        this.defaultNic = defaultNic;
    }

    public void setPxeDisable(boolean pxeDisable) {
        this.pxeDisable = pxeDisable;
    }

    public boolean getPxeDisable() {
        return pxeDisable;
    }

    @Override
    public String getUuid() {
        return nicUuid;
    }

    @Override
    public void setUuid(String uuid) {
        this.nicUuid = uuid;
    }

    public String getNicUuid() {
        return nicUuid;
    }

    public void setNicUuid(String nicUuid) {
        this.nicUuid = nicUuid;
    }

    @Override
    public String toString() {
        return new StringBuilder("[Nic:").append(type).append("-").append(ip).append("-").append(broadcastUri).append("]").toString();
    }

    public void setNicSecIps(List<String> secIps) {
        this.nicSecIps = secIps;
    }

    public List<String> getNicSecIps() {
        return nicSecIps;
    }

    public String getNetworkUuid() {
        return super.getUuid();
    }

    public void setNetworkUuid(String uuid) {
        super.setUuid(uuid);
    }

    public Map<NetworkOffering.Detail, String> getDetails() {
        return details;
    }

    public void setDetails(final Map<NetworkOffering.Detail, String> details) {
        this.details = details;
    }

    public boolean isDpdkEnabled() {
        return dpdkEnabled;
    }

    public void setDpdkEnabled(boolean dpdkEnabled) {
        this.dpdkEnabled = dpdkEnabled;
    }

    public Integer getMtu() {
        return mtu;
    }

    public void setMtu(Integer mtu) {
        this.mtu = mtu;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(Long networkId) {
        this.networkId = networkId;
    }

    public String getNetworkSegmentName() {
        return networkSegmentName;
    }

    public void setNetworkSegmentName(String networkSegmentName) {
        this.networkSegmentName = networkSegmentName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getVfPciAddress() {
        return vfPciAddress;
    }

    public void setVfPciAddress(String vfPciAddress) {
        this.vfPciAddress = vfPciAddress;
    }

    public Boolean getUseHwOffload() {
        return useHwOffload;
    }

    public boolean isUseHwOffload() {
        return Boolean.TRUE.equals(useHwOffload);
    }

    public void setUseHwOffload(Boolean useHwOffload) {
        this.useHwOffload = useHwOffload;
    }

    public String getVfPfName() {
        return vfPfName;
    }

    public void setVfPfName(String vfPfName) {
        this.vfPfName = vfPfName;
    }

    public String getVdpaDevice() {
        return vdpaDevice;
    }

    public void setVdpaDevice(String vdpaDevice) {
        this.vdpaDevice = vdpaDevice;
    }

    public Boolean getUseSfVdpa() {
        return useSfVdpa;
    }

    public boolean isUseSfVdpa() {
        return Boolean.TRUE.equals(useSfVdpa);
    }

    public void setUseSfVdpa(Boolean useSfVdpa) {
        this.useSfVdpa = useSfVdpa;
    }

    public String getSfRepresentorName() {
        return sfRepresentorName;
    }

    public void setSfRepresentorName(String sfRepresentorName) {
        this.sfRepresentorName = sfRepresentorName;
    }

    /**
     * Return the raw custom detail map, or {@code null} if none was set.
     */
    public Map<String, String> getNicDetails() {
        return nicDetails;
    }

    /**
     * Replace the full custom detail map. Prefer {@link #setNicDetail(String, String)}
     * for adding individual keys from the enrichment path.
     */
    public void setNicDetails(final Map<String, String> nicDetails) {
        this.nicDetails = nicDetails;
    }

    /**
     * Set a single custom detail key/value — allocates the backing map lazily.
     *
     * @param key   detail key, never {@code null}
     * @param value detail value; {@code null} is accepted but stored verbatim
     */
    public void setNicDetail(final String key, final String value) {
        if (key == null) {
            return;
        }
        if (nicDetails == null) {
            nicDetails = new HashMap<>();
        }
        nicDetails.put(key, value);
    }

    /**
     * Lookup a single custom detail value; returns {@code null} if the map is
     * absent or the key is missing.
     */
    public String getNicDetail(final String key) {
        if (nicDetails == null || key == null) {
            return null;
        }
        return nicDetails.get(key);
    }
}
