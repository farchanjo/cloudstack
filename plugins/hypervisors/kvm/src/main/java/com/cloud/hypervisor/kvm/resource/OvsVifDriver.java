/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.resource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.naming.ConfigurationException;

import com.cloud.hypervisor.kvm.dpdk.DpdkDriver;
import com.cloud.hypervisor.kvm.dpdk.DpdkDriverImpl;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.properties.AgentProperties;
import com.cloud.agent.properties.AgentPropertiesFileHandler;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.network.Networks;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

public class OvsVifDriver extends VifDriverBase {
    private int _timeout;
    private String _controlCidr = NetUtils.getLinkLocalCIDR();
    private DpdkDriver dpdkDriver;

    @Override
    public void configure(Map<String, Object> params) throws ConfigurationException {
        super.configure(params);

        getPifs();

        if (BooleanUtils.isTrue(AgentPropertiesFileHandler.getPropertyValue(AgentProperties.OPENVSWITCH_DPDK_ENABLED))) {
            dpdkDriver = new DpdkDriverImpl();
        }

        _controlCidr = getControlCidr(_controlCidr);

        String value = (String)params.get("scripts.timeout");
        _timeout = NumbersUtil.parseInt(value, 30 * 60) * 1000;
    }

    public void getPifs() {
        final String cmdout = Script.runSimpleBashScript("ovs-vsctl list-br | sed '{:q;N;s/\\n/%/g;t q}'");
        logger.debug("cmdout was " + cmdout);
        final List<String> bridges = Arrays.asList(cmdout.split("%"));
        for (final String bridge : bridges) {
            logger.debug("looking for pif for bridge " + bridge);
            // String pif = getOvsPif(bridge);
            // Not really interested in the pif name at this point for ovs
            // bridges
            final String pif = bridge;
            if (_libvirtComputingResource.isPublicBridge(bridge)) {
                _pifs.put("public", pif);
            }
            if (_libvirtComputingResource.isGuestBridge(bridge)) {
                _pifs.put("private", pif);
            }
            _pifs.put(bridge, pif);
        }
        logger.debug("done looking for pifs, no more bridges");
    }

    /**
     * Plug interface with DPDK support:
     *      - Create a new port with DPDK support for the interface
     *      - Set the 'intf' path to the new port
     */
    protected void plugDPDKInterface(InterfaceDef intf, String trafficLabel, Map<String, String> extraConfig,
                                     String vlanId, String guestOsType, NicTO nic, String nicAdapter) {
        logger.debug("DPDK support enabled: configuring per traffic label " + trafficLabel);
        String dpdkOvsPath = _libvirtComputingResource.dpdkOvsPath;
        if (StringUtils.isBlank(dpdkOvsPath)) {
            throw new CloudRuntimeException("DPDK is enabled on the host but no OVS path has been provided");
        }
        String port = dpdkDriver.getNextDpdkPort();
        DpdkHelper.VHostUserMode dpdKvHostUserMode = dpdkDriver.getDpdkvHostUserMode(extraConfig);
        dpdkDriver.addDpdkPort(_pifs.get(trafficLabel), port, vlanId, dpdKvHostUserMode, dpdkOvsPath);
        String interfaceMode = dpdkDriver.getGuestInterfacesModeFromDpdkVhostUserMode(dpdKvHostUserMode);
        intf.defDpdkNet(dpdkOvsPath, port, nic.getMac(),
                getGuestNicModel(guestOsType, nicAdapter), 0,
                dpdkDriver.getExtraDpdkProperties(extraConfig),
                interfaceMode);
    }

    @Override
    public InterfaceDef plug(NicTO nic, String guestOsType, String nicAdapter, Map<String, String> extraConfig) throws InternalErrorException, LibvirtException {
        logger.debug("plugging nic=" + nic);

        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        if (!_libvirtComputingResource.dpdkSupport || !nic.isDpdkEnabled()) {
            // Let libvirt handle OVS ports creation when DPDK property is disabled or when it is enabled but disabled for the nic
            // For DPDK support, libvirt does not handle ports creation, invoke 'addDpdkPort' method
            intf.setVirtualPortType("openvswitch");
        }

        String vlanId = null;
        String logicalSwitchUuid = null;
        if (nic.getBroadcastType() == Networks.BroadcastDomainType.Vlan) {
            vlanId = Networks.BroadcastDomainType.getValue(nic.getBroadcastUri());
        } else if (nic.getBroadcastType() == Networks.BroadcastDomainType.Lswitch) {
            logicalSwitchUuid = Networks.BroadcastDomainType.getValue(nic.getBroadcastUri());
        } else if (nic.getBroadcastType() == Networks.BroadcastDomainType.Pvlan) {
            // TODO consider moving some of this functionality from NetUtils to Networks....
            vlanId = NetUtils.getPrimaryPvlanFromUri(nic.getBroadcastUri());
        } else if (nic.getBroadcastType() == Networks.BroadcastDomainType.Vxlan) {
            // VNI (possibly > 4094). toOvsAccessTag() folds to 12-bit OVS tag.
            vlanId = Networks.BroadcastDomainType.getValue(nic.getBroadcastUri());
            ensureVxlanMesh(nic, vlanId);
            registerDvrIntent(nic, vlanId);
        } else {
            vlanId = getVlanIdFromUri(nic);
        }
        String trafficLabel = nic.getName();
        if (nic.getType() == Networks.TrafficType.Guest) {
            Integer networkRateKBps = getNetworkRateKbps(nic);
            if (vlanId != null && !vlanId.equalsIgnoreCase("untagged") &&
                    (nic.getBroadcastType() == Networks.BroadcastDomainType.Vlan
                     || nic.getBroadcastType() == Networks.BroadcastDomainType.Pvlan
                     || nic.getBroadcastType() == Networks.BroadcastDomainType.Vxlan
                     || nic.getBroadcastType() == Networks.BroadcastDomainType.Netris)) {
                if (trafficLabel != null && !trafficLabel.isEmpty()) {
                    if (_libvirtComputingResource.dpdkSupport && nic.isDpdkEnabled()) {
                        plugDPDKInterface(intf, trafficLabel, extraConfig, vlanId, guestOsType, nic, nicAdapter);
                    } else {
                        logger.debug("creating a vlan dev and bridge for guest traffic per traffic label " + trafficLabel);
                        intf.defBridgeNet(_pifs.get(trafficLabel), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
                        intf.setVlanTag(toOvsAccessTag(Integer.parseInt(vlanId)));
                    }
                } else {
                    intf.defBridgeNet(_pifs.get("private"), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
                    intf.setVlanTag(toOvsAccessTag(Integer.parseInt(vlanId)));
                }
            } else if (nic.getBroadcastType() == Networks.BroadcastDomainType.Lswitch || nic.getBroadcastType() == Networks.BroadcastDomainType.OpenDaylight) {
                logger.debug("nic " + nic + " needs to be connected to LogicalSwitch " + logicalSwitchUuid);
                intf.setVirtualPortInterfaceId(nic.getUuid());
                String brName = (trafficLabel != null && !trafficLabel.isEmpty()) ? _pifs.get(trafficLabel) : _pifs.get("private");
                intf.defBridgeNet(brName, null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
            } else if (nic.getBroadcastType() == Networks.BroadcastDomainType.Vswitch) {
                String brName = getOvsTunnelNetworkName(nic.getBroadcastUri().getAuthority());
                logger.debug("nic " + nic + " needs to be connected to Open vSwitch bridge " + brName);
                intf.defBridgeNet(brName, null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
            } else {
                intf.defBridgeNet(_bridges.get("guest"), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
            }
        } else if (nic.getType() == Networks.TrafficType.Control) {
            /* Make sure the network is still there */
            createControlNetwork(_bridges.get("linklocal"));
            intf.defBridgeNet(_bridges.get("linklocal"), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter));
        } else if (nic.getType() == Networks.TrafficType.Public) {
            Integer networkRateKBps = getNetworkRateKbps(nic);
            if (vlanId != null && !vlanId.equalsIgnoreCase("untagged")) {
                if (trafficLabel != null && !trafficLabel.isEmpty()) {
                    logger.debug("creating a vlan dev and bridge for public traffic per traffic label " + trafficLabel);
                    intf.defBridgeNet(_pifs.get(trafficLabel), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
                    intf.setVlanTag(toOvsAccessTag(Integer.parseInt(vlanId)));
                } else {
                    intf.defBridgeNet(_pifs.get("public"), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
                    intf.setVlanTag(toOvsAccessTag(Integer.parseInt(vlanId)));
                }
            } else {
                intf.defBridgeNet(_bridges.get("public"), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter), networkRateKBps);
            }
        } else if (nic.getType() == Networks.TrafficType.Management) {
            if (vlanId != null) {
                String brName = (trafficLabel != null && !trafficLabel.isEmpty()) ? _pifs.get(trafficLabel) : _bridges.get("private");
                intf.defBridgeNet(brName, null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter));
                intf.setVlanTag(toOvsAccessTag(Integer.parseInt(vlanId)));
            } else {
                intf.defBridgeNet(_bridges.get("private"), null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter));
            }
        } else if (nic.getType() == Networks.TrafficType.Storage) {
            String storageBrName = nic.getName() == null ? _bridges.get("private") : nic.getName();
            intf.defBridgeNet(storageBrName, null, nic.getMac(), getGuestNicModel(guestOsType, nicAdapter));
            if (vlanId != null) {
                intf.setVlanTag(toOvsAccessTag(Integer.parseInt(vlanId)));
            }
        }
        return intf;
    }

    private String getVlanIdFromUri(NicTO nic) {
        if (nic.getBroadcastUri() == null) {
            return null;
        }
        String scheme = nic.getBroadcastUri().getScheme();
        if ("vlan".equals(scheme) || "storage".equals(scheme)) {
            String vlanId = Networks.BroadcastDomainType.getValue(nic.getBroadcastUri());
            if (vlanId != null && !vlanId.equalsIgnoreCase("untagged")) {
                return vlanId;
            }
        }
        return null;
    }

    /**
     * Map a (potentially >4094) broadcast segment id (e.g. VXLAN VNI) to a
     * valid OVS/IEEE 802.1Q access VLAN tag (1..4094). Segment ids in range
     * are returned as-is. Out-of-range ids are folded back into 1..4094 using
     * modulo so two different VNIs don't silently collide on the same host.
     *
     * Without this mapping libvirt emits {@code <vlan><tag id='10197'/></vlan>},
     * OVS rejects the out-of-range tag and the port ends up untagged — which
     * breaks VM connectivity for that tier. Mirrors VfPassthroughVifDriver.
     */
    public static int toOvsAccessTag(int segmentId) {
        if (segmentId >= 1 && segmentId <= 4094) {
            return segmentId;
        }
        return ((segmentId - 1) % 4094) + 1;
    }

    /**
     * Ensure OVS VXLAN tunnel mesh exists for the given VNI before libvirt
     * attaches the tap to the bridge. Peer list and owning VM name are read
     * from dynamic NIC details populated by the management server; falls
     * back to {@code agent.properties} ({@code vxlan.peers}) when the
     * details are absent (e.g. old mgmt talking to new agent).
     *
     * <p>Safe no-op when the manager is not configured or the string is
     * unparseable — plug must not fail on a recoverable data-plane issue.
     */
    private void ensureVxlanMesh(NicTO nic, String vlanId) {
        if (StringUtils.isBlank(vlanId)) {
            return;
        }
        if (_libvirtComputingResource == null || _libvirtComputingResource.vxlanTunnelManager == null) {
            return;
        }
        try {
            int vni = Integer.parseInt(vlanId.trim());
            java.util.Collection<String> peers = parseCsvDetail(nic, "vxlan.peers");
            String vmName = nic != null ? nic.getNicDetail("vxlan.vm.name") : null;
            _libvirtComputingResource.vxlanTunnelManager.ensureMeshForVni(vmName, vni, peers);
        } catch (NumberFormatException e) {
            logger.warn("ensureVxlanMesh: could not parse VNI '{}': {}", vlanId, e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("ensureVxlanMesh: failed for vni='{}': {}", vlanId, e.getMessage());
        }
    }

    /**
     * Register the NIC with the Distributed Virtual Router manager so
     * intra-host cross-tier routing can bypass the VR kernel.
     *
     * <p>MVP: IPv4 only, Guest traffic only, silent no-op when required
     * bits are missing (absent vpc id / gateway / ip / mac). The DVR
     * never fails a plug; the VR path remains the safe fallback.
     */
    private void registerDvrIntent(NicTO nic, String vlanId) {
        if (nic == null || _libvirtComputingResource == null) {
            return;
        }
        if (_libvirtComputingResource.dvrManager == null) {
            return;
        }
        if (nic.getType() != Networks.TrafficType.Guest) {
            return;
        }
        if (StringUtils.isBlank(vlanId)) {
            return;
        }
        String vmIp = nic.getIp();
        String vmMac = nic.getMac();
        String gateway = nic.getGateway();
        if (StringUtils.isBlank(vmIp) || StringUtils.isBlank(vmMac) || StringUtils.isBlank(gateway)) {
            return;
        }
        try {
            int vni = Integer.parseInt(vlanId.trim());
            String cidr = buildCidrFromIpNetmask(vmIp, nic.getNetmask());
            String vpcId = nic.getNicDetail("dvr.vpc.id");
            if (StringUtils.isBlank(vpcId)) {
                vpcId = nic.getNicDetail("vpc.id");
            }
            // When mgmt doesn't supply a vpc id yet (older mgmt), fold all
            // local tiers into a single synthetic VPC bucket ("*"). Fine
            // for the single-VPC MVP; multi-VPC requires the NIC detail.
            String vmName = nic.getNicDetail("vxlan.vm.name");
            if (StringUtils.isBlank(vmName)) {
                vmName = nic.getUuid();
            }
            _libvirtComputingResource.dvrManager.registerTier(vpcId, vni,
                    cidr != null ? cidr : (gateway + "/24"), gateway);
            _libvirtComputingResource.dvrManager.registerVmInTier(vpcId, vmName, vni, vmIp, vmMac);
        } catch (NumberFormatException e) {
            logger.warn("registerDvrIntent: non-numeric vlanId '{}': {}", vlanId, e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("registerDvrIntent: failed for vlanId='{}' ip={}: {}", vlanId, nic.getIp(), e.getMessage());
        }
    }

    /**
     * Build a simple {@code ip/prefix} CIDR from a dotted netmask. Falls
     * back to {@code null} when the netmask is missing or unparseable;
     * DVR uses the gateway-derived network only as a diagnostic string,
     * so null is tolerated.
     */
    private static String buildCidrFromIpNetmask(String ip, String netmask) {
        if (StringUtils.isBlank(ip) || StringUtils.isBlank(netmask)) {
            return null;
        }
        try {
            String[] nm = netmask.trim().split("\\.");
            if (nm.length != 4) {
                return null;
            }
            long mask = 0;
            for (String p : nm) {
                mask = (mask << 8) | (Integer.parseInt(p) & 0xff);
            }
            int prefix = Long.bitCount(mask);
            // Network = ip bit-AND mask
            String[] ipParts = ip.trim().split("\\.");
            long ipL = 0;
            for (String p : ipParts) {
                ipL = (ipL << 8) | (Integer.parseInt(p) & 0xff);
            }
            long net = ipL & mask;
            return String.format("%d.%d.%d.%d/%d",
                    (net >> 24) & 0xff, (net >> 16) & 0xff, (net >> 8) & 0xff, net & 0xff, prefix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse a comma-separated NIC detail into a list. Returns {@code null}
     * when the detail is absent, which signals {@code VxlanTunnelManager}
     * to consult its {@code agent.properties} fallback.
     */
    private static java.util.List<String> parseCsvDetail(NicTO nic, String key) {
        if (nic == null) {
            return null;
        }
        String raw = nic.getNicDetail(key);
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : raw.split(",")) {
            String v = t.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private String getOvsTunnelNetworkName(final String broadcastUri) {
        if (broadcastUri.contains(".")) {
            final String[] parts = broadcastUri.split("\\.");
            return "OVS-DR-VPC-Bridge" + parts[0];
        } else {
            try {
                return "OVSTunnel" + broadcastUri;
            } catch (final Exception e) {
                return null;
            }
        }
    }

    @Override
    public void unplug(InterfaceDef iface, boolean deleteBr) {
        // Libvirt apparently takes care of this, see BridgeVifDriver unplug
        if (_libvirtComputingResource.dpdkSupport && StringUtils.isNotBlank(iface.getDpdkSourcePort())) {
            // If DPDK is enabled, we'll need to cleanup the port as libvirt won't
            String dpdkPort = iface.getDpdkSourcePort();
            String cmd = String.format("ovs-vsctl del-port %s", dpdkPort);
            logger.debug("Removing DPDK port: " + dpdkPort);
            Script.runSimpleBashScript(cmd);
        }
        // Local tap is going away: rebuild every split-horizon group on this
        // host so its bucket list drops the now-stale ofport. Tag-level refresh
        // would require us to know which tag the iface had; full-refresh is
        // cheap and idempotent for any number of tags.
        if (_libvirtComputingResource != null && _libvirtComputingResource.vxlanTunnelManager != null) {
            try {
                _libvirtComputingResource.vxlanTunnelManager.refreshAllLocalFlood();
            } catch (RuntimeException e) {
                logger.warn("refreshAllLocalFlood on unplug failed: {}", e.getMessage());
            }
        }
    }


    @Override
    public void attach(LibvirtVMDef.InterfaceDef iface) {
        Script.runSimpleBashScript("ovs-vsctl add-port " + iface.getBrName() + " " + iface.getDevName());
    }

    @Override
    public void detach(LibvirtVMDef.InterfaceDef iface) {
        Script.runSimpleBashScript("ovs-vsctl port-to-br " + iface.getDevName() + " && ovs-vsctl del-port " + iface.getBrName() + " " + iface.getDevName());
    }

    private void deleteExitingLinkLocalRouteTable(String linkLocalBr) {
        Script command = new Script("/bin/bash", _timeout);
        command.add("-c");
        command.add("ip route | grep " + _controlCidr);
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = command.execute(parser);
        boolean foundLinkLocalBr = false;
        if (result == null && parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                String[] tokens = line.split(" ");
                if (!tokens[2].equalsIgnoreCase(linkLocalBr)) {
                    Script.runSimpleBashScript("ip route del " + _controlCidr);
                } else {
                    foundLinkLocalBr = true;
                }
            }
        }
        if (!foundLinkLocalBr) {
            Script.runSimpleBashScript("ip address add " + NetUtils.getLinkLocalAddressFromCIDR(_controlCidr) + " dev " + linkLocalBr + ";" + "ip route add " + _controlCidr + " dev " + linkLocalBr + " src " +
                    NetUtils.getLinkLocalGateway(_controlCidr));
        }
    }

    @Override
    public void createControlNetwork(String privBrName) {
        deleteExitingLinkLocalRouteTable(privBrName);
        if (!isExistingBridge(privBrName)) {
            Script.runSimpleBashScript("ovs-vsctl add-br " + privBrName + "; ip link set " + privBrName + " up; ip address add " + NetUtils.getLinkLocalAddressFromCIDR(_controlCidr) + " dev " + privBrName, _timeout);
        }
    }

    @Override
    public boolean isExistingBridge(String bridgeName) {
        Script command = new Script("/bin/sh", _timeout);
        command.add("-c");
        command.add("ovs-vsctl br-exists " + bridgeName);
        String result = command.execute(null);
        if ("0".equals(result)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void deleteBr(NicTO nic) {
    }
}
