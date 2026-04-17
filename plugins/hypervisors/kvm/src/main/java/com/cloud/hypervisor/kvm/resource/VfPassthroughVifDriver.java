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

import java.io.File;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.network.Networks;
import com.cloud.utils.script.Script;

/**
 * VifDriver for SR-IOV Virtual Function passthrough. Generates libvirt
 * {@code <interface type='hostdev' managed='yes'>} XML so the VF is detached
 * from the host and re-attached to the guest at boot.
 *
 * <p>Used for VRs (or any VM) when the NIC's network offering has
 * {@code hw_offload_enabled=1} and the orchestrator allocated a VF
 * (NicTO.useHwOffload=true, vfPciAddress set).
 *
 * <p>This driver intentionally does NOT program any TC/HW offload rules.
 * Those are programmed separately by the host agent's {@code TcRuleProgrammer}
 * on the corresponding VF representor (eg. dx6p0r0 for VF at 0000:01:00.2),
 * driven by intent received from the VR.
 */
public class VfPassthroughVifDriver extends VifDriverBase {

    @Override
    public void configure(Map<String, Object> params) throws ConfigurationException {
        super.configure(params);
    }

    @Override
    public LibvirtVMDef.InterfaceDef plug(NicTO nic, String guestOsType, String nicAdapter,
            Map<String, String> extraConfig) throws InternalErrorException, LibvirtException {

        String pciAddress = nic.getVfPciAddress();
        if (StringUtils.isBlank(pciAddress)) {
            throw new InternalErrorException(
                "VfPassthroughVifDriver invoked without vfPciAddress on NicTO; check VfPoolManager allocation");
        }

        String pfName = nic.getVfPfName();
        Integer vlanTag = extractVlanTag(nic);

        configureVfOnPf(pfName, pciAddress, nic.getMac(), vlanTag);

        String repName = lookupRepresentor(pciAddress);
        if (repName != null) {
            addRepresentorToOvs(repName);
        }

        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        intf.defHostdevNet(pciAddress, nic.getMac(), vlanTag != null ? vlanTag : 0);
        intf.setLinkStateUp(nic.isEnabled());

        logger.info("VF passthrough plug: pci={} pf={} mac={} vlan={} rep={}",
                pciAddress, pfName, nic.getMac(), vlanTag, repName);
        return intf;
    }

    @Override
    public void unplug(LibvirtVMDef.InterfaceDef iface, boolean delete) {
        String pciAddress = iface.getPciAddress();
        if (StringUtils.isBlank(pciAddress)) {
            return;
        }
        String repName = lookupRepresentor(pciAddress);
        if (repName != null) {
            Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null", repName));
            Script.runSimpleBashScript(String.format("ovs-vsctl --if-exists del-port br-bond %s", repName));
            logger.info("VF unplug: removed rep {} from OVS and cleared TC", repName);
        }
        String pfName = lookupPfFromVf(pciAddress);
        Integer vfId = lookupVfIdFromPci(pciAddress);
        if (pfName != null && vfId != null) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
        }
    }

    @Override
    public void attach(LibvirtVMDef.InterfaceDef iface) {
        // Hot-plug with VF passthrough is not implemented; PCI assignment is fixed at boot.
        throw new UnsupportedOperationException("Hot-attach is not supported for VF passthrough interfaces");
    }

    @Override
    public void detach(LibvirtVMDef.InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-detach is not supported for VF passthrough interfaces");
    }

    @Override
    public void deleteBr(NicTO nic) {
        // No bridge to delete for VF passthrough.
    }

    @Override
    public void createControlNetwork(String privBrName) {
        // Control network (cloud0) uses a TAP/bridge NIC, not VF passthrough.
        // Nothing to do here — control NIC is handled by BridgeVifDriver.
    }

    @Override
    public boolean isExistingBridge(String bridgeName) {
        // VF passthrough doesn't use bridges.
        return false;
    }

    /**
     * Configure the VF on its parent PF: set MAC, allow MAC changes from guest (trust on),
     * disable spoof check (so guest can use unicast/broadcast MACs and VRRP virtual MACs),
     * set VLAN tag on the PF if applicable.
     */
    private void configureVfOnPf(String pfName, String pciAddress, String macAddr, Integer vlanTag) {
        if (StringUtils.isBlank(pfName)) {
            pfName = lookupPfFromVf(pciAddress);
        }
        Integer vfId = lookupVfIdFromPci(pciAddress);
        if (pfName == null || vfId == null) {
            logger.warn(String.format("Cannot configure VF on PF: pf=%s pci=%s", pfName, pciAddress));
            return;
        }
        if (StringUtils.isNotBlank(macAddr)) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d mac %s", pfName, vfId, macAddr));
        }
        Script.runSimpleBashScript(String.format("ip link set %s vf %d trust on", pfName, vfId));
        Script.runSimpleBashScript(String.format("ip link set %s vf %d spoofchk off", pfName, vfId));
        if (vlanTag != null && vlanTag > 0 && vlanTag < 4095) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d vlan %d", pfName, vfId, vlanTag));
        } else {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d vlan 0", pfName, vfId));
        }
    }

    /**
     * Resolve the PF netdev name for a VF PCI address by reading
     * {@code /sys/bus/pci/devices/<vf>/physfn/net/}.
     *
     * In mlx5 switchdev mode this directory can contain the PF netdev alongside its
     * VF representors (eg. dx6p0, dx6p0vf0, dx6p0vf1). Filter by phys_port_name so
     * we always return the actual PF (phys_port_name = p&lt;N&gt;) and never a representor
     * (phys_port_name = pf&lt;N&gt;vf&lt;M&gt;).
     */
    static String lookupPfFromVf(String vfPciAddress) {
        if (StringUtils.isBlank(vfPciAddress)) {
            return null;
        }
        File pfNetDir = new File(String.format("/sys/bus/pci/devices/%s/physfn/net", vfPciAddress));
        if (!pfNetDir.isDirectory()) {
            return null;
        }
        String[] entries = pfNetDir.list();
        if (entries == null || entries.length == 0) {
            return null;
        }
        for (String name : entries) {
            String port = readPhysPortName(name);
            if (port != null && port.matches("p\\d+")) {
                return name;
            }
        }
        for (String name : entries) {
            String port = readPhysPortName(name);
            if (port == null || !port.startsWith("pf")) {
                return name;
            }
        }
        return entries[0];
    }

    static String readPhysPortName(String iface) {
        File f = new File(String.format("/sys/class/net/%s/phys_port_name", iface));
        if (!f.exists()) {
            return null;
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve the VF index (0..N-1) under its parent PF, by inspecting
     * {@code /sys/bus/pci/devices/<pf>/virtfnN/} symlinks.
     */
    static Integer lookupVfIdFromPci(String vfPciAddress) {
        if (StringUtils.isBlank(vfPciAddress)) {
            return null;
        }
        File physfnLink = new File(String.format("/sys/bus/pci/devices/%s/physfn", vfPciAddress));
        if (!physfnLink.exists()) {
            return null;
        }
        try {
            String pfPath = physfnLink.getCanonicalPath();
            File pfDir = new File(pfPath);
            String[] entries = pfDir.list();
            if (entries == null) {
                return null;
            }
            for (String name : entries) {
                if (!name.startsWith("virtfn")) {
                    continue;
                }
                File vfLink = new File(pfDir, name);
                String vfTarget = vfLink.getCanonicalPath();
                if (vfTarget.endsWith(vfPciAddress)) {
                    return Integer.parseInt(name.substring("virtfn".length()));
                }
            }
        } catch (Exception e) {
            // fall through and return null
        }
        return null;
    }

    /**
     * Find the representor netdev for a VF by scanning sysfs phys_port_name.
     * In switchdev mode, each VF has a representor with phys_port_name=pf&lt;N&gt;vf&lt;M&gt;.
     * The PF index (N) is read from the PF's own phys_port_name (p&lt;N&gt;) rather than
     * parsed from the PF netdev name, so renames (eg. enp1s0f0np0) do not break lookup.
     */
    static String lookupRepresentor(String vfPciAddress) {
        Integer vfId = lookupVfIdFromPci(vfPciAddress);
        String pfName = lookupPfFromVf(vfPciAddress);
        if (vfId == null || pfName == null) {
            return null;
        }
        Integer pfIdx = null;
        String pfPhysPort = readPhysPortName(pfName);
        if (pfPhysPort != null && pfPhysPort.matches("p\\d+")) {
            try {
                pfIdx = Integer.parseInt(pfPhysPort.substring(1));
            } catch (NumberFormatException ignore) {
                // fall through to name-based fallback
            }
        }
        if (pfIdx == null) {
            pfIdx = (pfName.endsWith("p1") || pfName.contains("1")) ? 1 : 0;
        }
        String expectedPhysPort = String.format("pf%dvf%d", pfIdx, vfId);
        File netDir = new File("/sys/class/net");
        String[] ifaces = netDir.list();
        if (ifaces == null) {
            return null;
        }
        for (String iface : ifaces) {
            if (expectedPhysPort.equals(readPhysPortName(iface))) {
                return iface;
            }
        }
        return null;
    }

    private void addRepresentorToOvs(String repName) {
        Script.runSimpleBashScript(String.format("ovs-vsctl --may-exist add-port br-bond %s", repName));
        Script.runSimpleBashScript(String.format("tc qdisc add dev %s clsact 2>/dev/null", repName));
        logger.info("Added VF representor {} to OVS br-bond with clsact qdisc", repName);
    }

    private Integer extractVlanTag(NicTO nic) {
        if (nic.getBroadcastUri() == null) {
            return null;
        }
        if (nic.getBroadcastType() != Networks.BroadcastDomainType.Vlan) {
            return null;
        }
        String value = Networks.BroadcastDomainType.getValue(nic.getBroadcastUri());
        try {
            int tag = Integer.parseInt(value);
            return tag > 0 ? tag : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
