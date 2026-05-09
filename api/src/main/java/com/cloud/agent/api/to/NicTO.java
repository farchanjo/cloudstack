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

    /* SR-IOV VF binding. All optional; null/false means traditional bridge/TAP. */
    String vfPciAddress;       // VF PCI bus address, e.g. "0000:01:00.2"
    String vfPfName;           // Physical Function netdev name (e.g. "dx6p0")
    String vfRepName;          // VF representor netdev name on the host, e.g. "dx6p0vf20"

    /* HW-offload via VF passthrough (hostdev). */
    Boolean useHwOffload;      // wrapper Boolean → null preserves wire compat with older agents

    /*
     * vDPA path (vhost-vdpa mgmt device on top of an SR-IOV VF). Mutually
     * exclusive with the hostdev passthrough path above and with
     * {@link #dpdkEnabled}; the agent picks vDPA first when {@link #useVdpa}
     * is true. {@link #vdpaDevice} is populated by the agent at plug time
     * after parsing {@code vdpa dev show -j}; mgmt only sets the request
     * (useVdpa + maxVqs).
     */
    Boolean useVdpa;          // wrapper Boolean → null preserves wire compat
    String vdpaDevice;        // host-side /dev/vhost-vdpa-N path, agent-populated
    Integer vdpaMaxVqs;       // queues to request from `vdpa dev add ... max_vqs <N>`

    /*
     * OVN logical-switch binding for this NIC. When {@link #useOvn} is true the
     * agent attaches the libvirt interface to the integration bridge
     * ({@code br-int}) with {@code external_ids:iface-id=<ovnLspName>}; OVN's
     * {@code ovn-controller} on the chassis claims the {@code Port_Binding} row
     * and programs the OpenFlow pipeline. {@link #ovnLsName} carries the parent
     * OVN logical switch name (e.g. {@code ls-<networkUuid>}) for diagnostics
     * and is NOT consulted by the agent during plug — the LSP-side identity
     * stored in OVN_Northbound is what matters. {@link #ovnDhcpOptionsUuid}
     * lets the agent (rare path) re-pin the LSP's {@code dhcpv4_options}
     * column when management has rotated the DHCP profile after plug.
     *
     * <p>Mutually compatible with {@link #useHwOffload} and {@link #useVdpa}:
     * representor + br-int still works (TC flower offload via mlx5 switchdev),
     * vDPA datapath + br-int still works (OVN programs flows; vhost-vdpa
     * carries the data plane). Mutually exclusive with {@link #dpdkEnabled}
     * for now (DPDK-managed ports skip the kernel datapath OVN expects).
     *
     * <p>Wire compat: a {@code null} value means the older mgmt didn't set
     * the field; agent must fall back to its non-OVN path.
     */
    Boolean useOvn;
    String ovnLsName;
    String ovnLspName;
    String ovnDhcpOptionsUuid;

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

    /*
     * OVN NIC tunables, resolved by HypervisorGuruBase.populateOvnTunables
     * via OvnNicConfig.resolve (VM detail > network detail > offering detail
     * > global ConfigKey > hardcoded). All fields are wrapper types so {@code null}
     * means "not configured": agents older than this build silently ignore
     * the unknown JSON properties and keep their existing behavior.
     */

    /* SR-IOV VF tunables. */
    Boolean vfTrust;
    Boolean vfSpoofcheck;
    String vfLinkState;
    Integer vfMaxTxRate;
    Integer vfMinTxRate;
    Integer vfVlan;
    Integer vfQos;

    /* vhost / multiqueue tunables. */
    Integer vhostQueues;
    String vhostDriver;
    Integer vhostTxQueueSize;
    Integer vhostRxQueueSize;

    /* Generic NIC tunables (libvirt XML / ethtool). */
    Boolean tso;
    Boolean gso;
    Boolean gro;
    Boolean lro;
    Boolean csumOffload;
    String driverModel;

    /* OVS / TC offload. */
    Boolean tcOffload;

    /*
     * Per-port OVS hairpin flag (other_config:hairpin=true). When non-null
     * the agent stamps the freshly-attached br-int port with the resolved
     * value. Required for VF<->VF same-host hardware offload via TC flower
     * on mlx5 switchdev. Wire compat: null = older mgmt didn't resolve;
     * agent skips the stamp.
     */
    Boolean ovsHairpin;

    /*
     * Bridge-wide OVS tc-policy. When non-null the agent applies
     * {@code ovs-vsctl set Open_vSwitch . other_config:tc-policy=<value>}
     * once per JVM at the first OVN-aware plug. Whitelist enforced
     * mgmt-side; agent treats this as an opaque string and lets ovs-vsctl
     * validate. Wire compat: null = older mgmt didn't resolve; agent
     * leaves the existing tc-policy untouched.
     */
    String ovsTcPolicy;

    /* OVN binding / chassis. */
    String requestedChassis;
    Integer haChassisPriority;

    /* BFD. */
    Boolean bfdEnable;
    Integer bfdMinRx;
    Integer bfdMinTx;
    Integer bfdMultiplier;

    /* Conntrack timeouts. */
    Integer ctSnatTimeout;
    Integer ctTcpTimeout;
    Integer ctUdpTimeout;
    Integer ctIcmpTimeout;

    /*
     * OVN LSP ARP-proxy option. When true the management server sets
     * {@code Logical_Switch_Port.options:arp_proxy=<ip>} so OVN answers
     * ARP queries for this port's IP on the logical segment. Agent-side:
     * null = older mgmt didn't set; ignore and leave existing LSP unchanged.
     */
    Boolean lspArpProxy;

    /* vDPA fine-grained. */
    Boolean vdpaEventIdx;
    Boolean vdpaIndirectDesc;
    Boolean vdpaIommu;
    Boolean vdpaPacked;
    Integer vdpaQueuePairs;

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
        final StringBuilder sb = new StringBuilder("[Nic:")
                .append(type).append("-").append(ip).append("-").append(broadcastUri);
        appendIfSet(sb, "useOvn", useOvn);
        appendIfSet(sb, "useVdpa", useVdpa);
        appendIfSet(sb, "useHwOffload", useHwOffload);
        appendIfSet(sb, "vfTrust", vfTrust);
        appendIfSet(sb, "vfSpoofcheck", vfSpoofcheck);
        appendIfSet(sb, "vfLinkState", vfLinkState);
        appendIfSet(sb, "vfMaxTxRate", vfMaxTxRate);
        appendIfSet(sb, "vfMinTxRate", vfMinTxRate);
        appendIfSet(sb, "vfVlan", vfVlan);
        appendIfSet(sb, "vfQos", vfQos);
        appendIfSet(sb, "vhostQueues", vhostQueues);
        appendIfSet(sb, "vhostDriver", vhostDriver);
        appendIfSet(sb, "vhostTxQ", vhostTxQueueSize);
        appendIfSet(sb, "vhostRxQ", vhostRxQueueSize);
        appendIfSet(sb, "tso", tso);
        appendIfSet(sb, "gso", gso);
        appendIfSet(sb, "gro", gro);
        appendIfSet(sb, "lro", lro);
        appendIfSet(sb, "csumOffload", csumOffload);
        appendIfSet(sb, "driverModel", driverModel);
        appendIfSet(sb, "tcOffload", tcOffload);
        appendIfSet(sb, "ovsHairpin", ovsHairpin);
        appendIfSet(sb, "ovsTcPolicy", ovsTcPolicy);
        appendIfSet(sb, "requestedChassis", requestedChassis);
        appendIfSet(sb, "haChassisPriority", haChassisPriority);
        appendIfSet(sb, "bfdEnable", bfdEnable);
        appendIfSet(sb, "bfdMinRx", bfdMinRx);
        appendIfSet(sb, "bfdMinTx", bfdMinTx);
        appendIfSet(sb, "bfdMultiplier", bfdMultiplier);
        appendIfSet(sb, "ctSnatTimeout", ctSnatTimeout);
        appendIfSet(sb, "ctTcpTimeout", ctTcpTimeout);
        appendIfSet(sb, "ctUdpTimeout", ctUdpTimeout);
        appendIfSet(sb, "ctIcmpTimeout", ctIcmpTimeout);
        appendIfSet(sb, "lspArpProxy", lspArpProxy);
        appendIfSet(sb, "vdpaQueuePairs", vdpaQueuePairs);
        appendIfSet(sb, "vdpaEventIdx", vdpaEventIdx);
        appendIfSet(sb, "vdpaIndirectDesc", vdpaIndirectDesc);
        appendIfSet(sb, "vdpaIommu", vdpaIommu);
        appendIfSet(sb, "vdpaPacked", vdpaPacked);
        return sb.append("]").toString();
    }

    /** Append {@code -name=value} only when {@code value} is not null. Keeps log lines short. */
    private static void appendIfSet(final StringBuilder sb, final String name, final Object value) {
        if (value == null) {
            return;
        }
        sb.append("-").append(name).append("=").append(value);
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

    public String getVfRepName() {
        return vfRepName;
    }

    public void setVfRepName(String vfRepName) {
        this.vfRepName = vfRepName;
    }

    public Boolean getUseVdpa() {
        return useVdpa;
    }

    public boolean isUseVdpa() {
        return Boolean.TRUE.equals(useVdpa);
    }

    public void setUseVdpa(Boolean useVdpa) {
        this.useVdpa = useVdpa;
    }

    public String getVdpaDevice() {
        return vdpaDevice;
    }

    public void setVdpaDevice(String vdpaDevice) {
        this.vdpaDevice = vdpaDevice;
    }

    public Integer getVdpaMaxVqs() {
        return vdpaMaxVqs;
    }

    public void setVdpaMaxVqs(Integer vdpaMaxVqs) {
        this.vdpaMaxVqs = vdpaMaxVqs;
    }

    public Boolean getUseOvn() {
        return useOvn;
    }

    /**
     * Convenience guard: returns {@code true} only when management explicitly
     * opted this NIC into the OVN datapath. {@code null}/false means use the
     * legacy bridge/OVS path and skip every OVN-specific code branch.
     */
    public boolean isUseOvn() {
        return Boolean.TRUE.equals(useOvn);
    }

    public void setUseOvn(Boolean useOvn) {
        this.useOvn = useOvn;
    }

    public String getOvnLsName() {
        return ovnLsName;
    }

    public void setOvnLsName(String ovnLsName) {
        this.ovnLsName = ovnLsName;
    }

    public String getOvnLspName() {
        return ovnLspName;
    }

    public void setOvnLspName(String ovnLspName) {
        this.ovnLspName = ovnLspName;
    }

    public String getOvnDhcpOptionsUuid() {
        return ovnDhcpOptionsUuid;
    }

    public void setOvnDhcpOptionsUuid(String ovnDhcpOptionsUuid) {
        this.ovnDhcpOptionsUuid = ovnDhcpOptionsUuid;
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

    /* ---------- OVN tunable accessors (wrapper types: null = unset) ---------- */

    public Boolean getVfTrust() { return vfTrust; }
    public void setVfTrust(Boolean vfTrust) { this.vfTrust = vfTrust; }

    public Boolean getVfSpoofcheck() { return vfSpoofcheck; }
    public void setVfSpoofcheck(Boolean vfSpoofcheck) { this.vfSpoofcheck = vfSpoofcheck; }

    public String getVfLinkState() { return vfLinkState; }
    public void setVfLinkState(String vfLinkState) { this.vfLinkState = vfLinkState; }

    public Integer getVfMaxTxRate() { return vfMaxTxRate; }
    public void setVfMaxTxRate(Integer vfMaxTxRate) { this.vfMaxTxRate = vfMaxTxRate; }

    public Integer getVfMinTxRate() { return vfMinTxRate; }
    public void setVfMinTxRate(Integer vfMinTxRate) { this.vfMinTxRate = vfMinTxRate; }

    public Integer getVfVlan() { return vfVlan; }
    public void setVfVlan(Integer vfVlan) { this.vfVlan = vfVlan; }

    public Integer getVfQos() { return vfQos; }
    public void setVfQos(Integer vfQos) { this.vfQos = vfQos; }

    public Integer getVhostQueues() { return vhostQueues; }
    public void setVhostQueues(Integer vhostQueues) { this.vhostQueues = vhostQueues; }

    public String getVhostDriver() { return vhostDriver; }
    public void setVhostDriver(String vhostDriver) { this.vhostDriver = vhostDriver; }

    public Integer getVhostTxQueueSize() { return vhostTxQueueSize; }
    public void setVhostTxQueueSize(Integer vhostTxQueueSize) { this.vhostTxQueueSize = vhostTxQueueSize; }

    public Integer getVhostRxQueueSize() { return vhostRxQueueSize; }
    public void setVhostRxQueueSize(Integer vhostRxQueueSize) { this.vhostRxQueueSize = vhostRxQueueSize; }

    public Boolean getTso() { return tso; }
    public void setTso(Boolean tso) { this.tso = tso; }

    public Boolean getGso() { return gso; }
    public void setGso(Boolean gso) { this.gso = gso; }

    public Boolean getGro() { return gro; }
    public void setGro(Boolean gro) { this.gro = gro; }

    public Boolean getLro() { return lro; }
    public void setLro(Boolean lro) { this.lro = lro; }

    public Boolean getCsumOffload() { return csumOffload; }
    public void setCsumOffload(Boolean csumOffload) { this.csumOffload = csumOffload; }

    public String getDriverModel() { return driverModel; }
    public void setDriverModel(String driverModel) { this.driverModel = driverModel; }

    public Boolean getTcOffload() { return tcOffload; }
    public void setTcOffload(Boolean tcOffload) { this.tcOffload = tcOffload; }

    public Boolean getOvsHairpin() { return ovsHairpin; }
    public void setOvsHairpin(Boolean ovsHairpin) { this.ovsHairpin = ovsHairpin; }

    public String getOvsTcPolicy() { return ovsTcPolicy; }
    public void setOvsTcPolicy(String ovsTcPolicy) { this.ovsTcPolicy = ovsTcPolicy; }

    public String getRequestedChassis() { return requestedChassis; }
    public void setRequestedChassis(String requestedChassis) { this.requestedChassis = requestedChassis; }

    public Integer getHaChassisPriority() { return haChassisPriority; }
    public void setHaChassisPriority(Integer haChassisPriority) { this.haChassisPriority = haChassisPriority; }

    public Boolean getBfdEnable() { return bfdEnable; }
    public void setBfdEnable(Boolean bfdEnable) { this.bfdEnable = bfdEnable; }

    public Integer getBfdMinRx() { return bfdMinRx; }
    public void setBfdMinRx(Integer bfdMinRx) { this.bfdMinRx = bfdMinRx; }

    public Integer getBfdMinTx() { return bfdMinTx; }
    public void setBfdMinTx(Integer bfdMinTx) { this.bfdMinTx = bfdMinTx; }

    public Integer getBfdMultiplier() { return bfdMultiplier; }
    public void setBfdMultiplier(Integer bfdMultiplier) { this.bfdMultiplier = bfdMultiplier; }

    public Integer getCtSnatTimeout() { return ctSnatTimeout; }
    public void setCtSnatTimeout(Integer ctSnatTimeout) { this.ctSnatTimeout = ctSnatTimeout; }

    public Integer getCtTcpTimeout() { return ctTcpTimeout; }
    public void setCtTcpTimeout(Integer ctTcpTimeout) { this.ctTcpTimeout = ctTcpTimeout; }

    public Integer getCtUdpTimeout() { return ctUdpTimeout; }
    public void setCtUdpTimeout(Integer ctUdpTimeout) { this.ctUdpTimeout = ctUdpTimeout; }

    public Integer getCtIcmpTimeout() { return ctIcmpTimeout; }
    public void setCtIcmpTimeout(Integer ctIcmpTimeout) { this.ctIcmpTimeout = ctIcmpTimeout; }

    public Boolean getLspArpProxy() { return lspArpProxy; }
    public void setLspArpProxy(Boolean lspArpProxy) { this.lspArpProxy = lspArpProxy; }

    public Boolean getVdpaEventIdx() { return vdpaEventIdx; }
    public void setVdpaEventIdx(Boolean vdpaEventIdx) { this.vdpaEventIdx = vdpaEventIdx; }

    public Boolean getVdpaIndirectDesc() { return vdpaIndirectDesc; }
    public void setVdpaIndirectDesc(Boolean vdpaIndirectDesc) { this.vdpaIndirectDesc = vdpaIndirectDesc; }

    public Boolean getVdpaIommu() { return vdpaIommu; }
    public void setVdpaIommu(Boolean vdpaIommu) { this.vdpaIommu = vdpaIommu; }

    public Boolean getVdpaPacked() { return vdpaPacked; }
    public void setVdpaPacked(Boolean vdpaPacked) { this.vdpaPacked = vdpaPacked; }

    public Integer getVdpaQueuePairs() { return vdpaQueuePairs; }
    public void setVdpaQueuePairs(Integer vdpaQueuePairs) { this.vdpaQueuePairs = vdpaQueuePairs; }
}
