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
import com.cloud.agent.properties.AgentProperties;
import com.cloud.agent.properties.AgentPropertiesFileHandler;
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

        // In switchdev mode (lookupRepresentor returns the rep), the PF/e-switch
        // does NOT accept legacy `ip link set vf vlan N` — VLAN tagging is owned
        // by OVS at the rep boundary (we set `tag=N` on the rep port). Pushing
        // VLAN config via the PF in switchdev fails with "Operation not
        // supported" and prevents the VR from booting.
        // Same for the libvirt hostdev <vlan> element — it triggers the same
        // ip link command on libvirt's side and fails. So we pass vlanTag=0
        // to defHostdevNet (no <vlan> element) and let the OVS rep tag handle
        // VLAN push/pop on egress/ingress.
        String repName = lookupRepresentor(pciAddress);
        boolean switchdev = repName != null;
        Integer pfVlanTag = switchdev ? null : vlanTag;
        configureVfOnPf(pfName, pciAddress, nic.getMac(), pfVlanTag);

        if (repName != null) {
            // Auto-plumb OVS VXLAN tunnels to peer data nodes before we drop
            // the rep into the bridge; safe no-op for legacy VLAN segments.
            ensureVxlanMeshIfNeeded(nic, vlanTag);
            addRepresentorToOvs(repName, vlanTag);
            // Register the tier / VM in DvrManager so OpenFlow cross-tier
            // shortcut + ACL flows get installed on br-bond. Runs right after
            // the representor is in the bridge. Silent no-op if missing data.
            registerDvrIntent(nic, vlanTag, repName);
            installLocalVmFdbRule(repName, vlanTag, nic.getMac());
        }

        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        int xmlVlanTag = switchdev ? 0 : (vlanTag != null ? vlanTag : 0);
        intf.defHostdevNet(pciAddress, nic.getMac(), xmlVlanTag);
        intf.setLinkStateUp(nic.isEnabled());

        logger.info("VF passthrough plug: pci={} pf={} mac={} vlan={} rep={} switchdev={}",
                pciAddress, pfName, nic.getMac(), vlanTag, repName, switchdev);
        return intf;
    }

    @Override
    public void unplug(LibvirtVMDef.InterfaceDef iface, boolean delete) {
        String pciAddress = iface.getPciAddress();
        String mac = iface.getMacAddress();
        logger.info("VfPassthroughVifDriver.unplug ENTRY: pci={} mac={} netType={} delete={}",
                pciAddress, mac, iface.getNetType(), delete);
        // When pciAddress is blank (libvirt teardown strips host info from
        // the iface on destroy paths), fall back to looking up the VF that
        // carries this MAC across both PFs.
        if (StringUtils.isBlank(pciAddress) && StringUtils.isNotBlank(mac)) {
            pciAddress = lookupVfPciByMac(mac);
            if (pciAddress != null) {
                logger.info("VfPassthroughVifDriver.unplug: resolved VF pci={} via mac={}", pciAddress, mac);
            }
        }
        if (StringUtils.isBlank(pciAddress)) {
            logger.info("VfPassthroughVifDriver.unplug: no pciAddress, skipping");
            // Still notify DvrManager by MAC so state/flows are reaped even
            // when we cannot find the physical VF.
            notifyDvrUnregister(mac);
            return;
        }
        String repName = lookupRepresentor(pciAddress);
        if (repName != null) {
            Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null", repName));
            Script.runSimpleBashScript(String.format("ovs-vsctl --if-exists del-port br-bond %s", repName));
            logger.info("VF unplug: removed rep {} from OVS and cleared TC", repName);
        }
        // Clear the static FDB pin OF rule installed at plug time. Match by
        // dl_dst alone (no --strict) so we don't need to know the access tag
        // — that info is already gone from the rep we just deleted.
        removeLocalVmFdbRuleByMac(mac);
        // Notify DvrManager so it reaps the VM entry + shortcut flows.
        notifyDvrUnregister(mac);
        String pfName = lookupPfFromVf(pciAddress);
        Integer vfId = lookupVfIdFromPci(pciAddress);
        if (pfName != null && vfId != null) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
        }
        // VF representor has been detached from br-bond; the split-horizon
        // group for its tag still contains the now-freed ofport. Rebuild all
        // tracked groups so their buckets match the live port set.
        if (_libvirtComputingResource != null && _libvirtComputingResource.vxlanTunnelManager != null) {
            try {
                _libvirtComputingResource.vxlanTunnelManager.refreshAllLocalFlood();
            } catch (RuntimeException e) {
                logger.warn("refreshAllLocalFlood on VF unplug failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Resolve the VF PCI BDF (e.g. {@code 0000:01:00.2}) that currently
     * carries the given MAC, scanning both physical functions. Returns null
     * when no VF matches — caller falls back to skipping the PCI-scoped
     * teardown steps.
     */
    private String lookupVfPciByMac(String mac) {
        if (StringUtils.isBlank(mac)) {
            return null;
        }
        String norm = mac.trim().toLowerCase();
        for (String pf : new String[]{"dx6p0", "dx6p1"}) {
            String cmd = String.format("ip link show dev %s 2>/dev/null | awk -v m=\"%s\" "
                            + "'/vf / {split($0,a,\" \"); for(i=1;i<=NF;i++) if(a[i]==\"link/ether\"||a[i]==\"MAC\") "
                            + "{if(tolower(a[i+1])==m) print $2}'", pf, norm);
            try {
                String out = Script.runSimpleBashScript(cmd, 5000);
                if (StringUtils.isBlank(out)) {
                    continue;
                }
                int vfIdx = Integer.parseInt(out.trim());
                // Map vfIdx -> PCI BDF via /sys/bus/pci/devices/<pf_bdf>/virtfn<N>
                String pfBdf = pf.equals("dx6p0") ? "0000:01:00.0" : "0000:01:00.1";
                String bdfCmd = String.format("readlink /sys/bus/pci/devices/%s/virtfn%d 2>/dev/null | awk -F/ '{print $NF}'",
                        pfBdf, vfIdx);
                String bdf = Script.runSimpleBashScript(bdfCmd, 5000);
                if (StringUtils.isNotBlank(bdf)) {
                    return "0000:" + bdf.trim();
                }
            } catch (RuntimeException ignored) {
                // next PF
            }
        }
        return null;
    }

    private void notifyDvrUnregister(String mac) {
        if (_libvirtComputingResource == null || _libvirtComputingResource.dvrManager == null) {
            return;
        }
        if (StringUtils.isBlank(mac)) {
            return;
        }
        try {
            _libvirtComputingResource.dvrManager.unregisterVmByMac(mac);
        } catch (RuntimeException e) {
            logger.warn("DvrManager.unregisterVmByMac({}) failed: {}", mac, e.getMessage());
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
        // Only set legacy PF-side VF VLAN when caller passed a non-null vlanTag
        // (legacy/non-switchdev path). In switchdev mode the caller passes null
        // and OVS rep tag handles VLAN — `ip link set vf vlan` returns
        // "Operation not supported" on the e-switch and would fail the plug.
        if (vlanTag == null) {
            return;
        }
        if (vlanTag > 0 && vlanTag < 4095) {
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

    private static final java.util.regex.Pattern PF_VF_PHYS_PORT =
            java.util.regex.Pattern.compile("pf(\\d+)vf(\\d+)");

    private void addRepresentorToOvs(String repName, Integer vlanTag) {
        // OVS-DOCA HW offload path: when openvswitch.dpdk.enabled=true the rep is
        // added as type=doca (NOT type=dpdk). OVS-DOCA auto-completes dv_flow_en=2 +
        // dv_xmeta_en=4 in dpdk-devargs and programs flows via DOCA Flow API into the
        // mlx5 e-switch HW table (with LAG offload when kernel bond is detected).
        // Falls back to kernel netdev (linux_tc) on any resolution failure.
        boolean dpdkMode = Boolean.TRUE.equals(
                AgentPropertiesFileHandler.getPropertyValue(AgentProperties.OPENVSWITCH_DPDK_ENABLED));
        if (dpdkMode) {
            String physPort = readPhysPortName(repName);
            java.util.regex.Matcher m = physPort != null ? PF_VF_PHYS_PORT.matcher(physPort) : null;
            if (m != null && m.matches()) {
                int pfIdx = Integer.parseInt(m.group(1));
                int vfIdx = Integer.parseInt(m.group(2));
                String pfNet = findPfByPhysPortIndex(pfIdx);
                String pfPci = pfNet != null ? resolvePfPciAddress(pfNet) : null;
                if (pfPci != null) {
                    // OVS-DOCA expects type=doca (NOT type=dpdk) for HW offload via DOCA Flow.
                    // dpdk-lsc-interrupt=true enables LSC events for representor link state.
                    // representor=vf[N] is the canonical syntax for switchdev VF representors.
                    Script.runSimpleBashScript(String.format(
                        "ovs-vsctl --may-exist add-port br-bond %s -- set Interface %s type=doca options:dpdk-devargs=\"%s,representor=vf[%d]\" options:dpdk-lsc-interrupt=true",
                        repName, repName, pfPci, vfIdx));
                    applyAccessTagOnRep(repName, vlanTag, true);
                    logger.info("Added VF representor {} as DOCA port (pf={} vf={} segment={})",
                            repName, pfPci, vfIdx, vlanTag);
                    return;
                }
                logger.warn("DOCA rep add: failed to resolve PF PCI for rep={} physPort={}; falling back to kernel netdev path",
                        repName, physPort);
            } else {
                logger.warn("DOCA rep add: rep={} has unexpected phys_port_name={}; falling back to kernel netdev path",
                        repName, physPort);
            }
        }
        // Kernel netdev path (default / fallback). The boot-time mlx-switchdev.sh
        // script may already have added the rep (untagged trunk); we (re)apply
        // the VLAN tag below.
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl --may-exist add-port br-bond %s", repName));
        applyAccessTagOnRep(repName, vlanTag, false);
        Script.runSimpleBashScript(String.format("tc qdisc add dev %s clsact 2>/dev/null", repName));
        logger.info("Added VF representor {} to OVS br-bond (segment={}) with clsact qdisc", repName, vlanTag);
    }

    /**
     * Apply an OVS access tag on a representor port. Runs separately from add-port
     * because --may-exist add-port doesn't update tag of an existing port, so we
     * always re-enforce the desired state on every plug.
     */
    private void applyAccessTagOnRep(String repName, Integer vlanTag, boolean dpdkMode) {
        if (vlanTag != null && vlanTag > 0) {
            int ovsTag = toOvsAccessTag(vlanTag);
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl set port %s tag=%d", repName, ovsTag));
            if (ovsTag != vlanTag) {
                String suffix = dpdkMode
                    ? " (DOCA port; segment > 4094 = VXLAN VNI; deterministic mod-4094 mapping ensures all VFs of the same network share the tag)"
                    : " (segment > 4094 = VXLAN VNI; deterministic mod-4094 mapping ensures all VFs of the same network share the tag)";
                logger.info("Mapped network segment {} → internal OVS tag {}{}", vlanTag, ovsTag, suffix);
            }
        } else {
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl clear port %s tag", repName));
        }
    }

    /**
     * Find PF netdev by phys_port_name index (eg. pfIdx=0 -> netdev with phys_port_name=p0).
     */
    private String findPfByPhysPortIndex(int pfIdx) {
        String expected = "p" + pfIdx;
        File netDir = new File("/sys/class/net");
        String[] ifaces = netDir.list();
        if (ifaces == null) {
            return null;
        }
        for (String iface : ifaces) {
            if (expected.equals(readPhysPortName(iface))) {
                return iface;
            }
        }
        return null;
    }

    /**
     * Resolve the PCI bus address (eg. 0000:01:00.0) of a PF netdev via the
     * /sys/class/net/&lt;pf&gt;/device symlink.
     */
    private String resolvePfPciAddress(String pfName) {
        try {
            File devLink = new File("/sys/class/net/" + pfName + "/device");
            return devLink.getCanonicalFile().getName();
        } catch (java.io.IOException e) {
            logger.warn("resolvePfPciAddress failed for {}: {}", pfName, e.getMessage());
            return null;
        }
    }

    /**
     * Map a network segment ID (VLAN tag or VXLAN VNI) to a 12-bit OVS access tag (1..4094).
     *
     * For VLAN-isolated networks the segment is already in range and is used as-is.
     * For VXLAN-isolated networks (VNI may be up to 16M) we collapse into the 12-bit
     * VLAN-tag range using ((vni - 1) % 4094) + 1. This keeps the mapping deterministic
     * (every host derives the same internal tag for the same VNI), so VFs of the same
     * tenant network land on the same OVS broadcast domain on every host where they're
     * placed. Cross-tenant collisions are mathematically possible but unlikely with the
     * VNI ranges CloudStack actually allocates (low thousands), and downstream HW offload
     * TC rules use the real VNI for upstream encap when needed.
     */
    public static int toOvsAccessTag(int segmentId) {
        if (segmentId >= 1 && segmentId <= 4094) {
            return segmentId;
        }
        return ((segmentId - 1) % 4094) + 1;
    }

    /**
     * Ensure the OVS VXLAN tunnel mesh exists for the given segment before
     * we attach the representor port to the bridge. This is a no-op when
     * segmentId is in the legacy VLAN range (1..4094) or when the scheme is
     * not {@code vxlan://}, and safely swallows errors so plug is not
     * blocked by data-plane issues.
     */
    /**
     * Mirror of {@code OvsVifDriver.registerDvrIntent} adapted for VF
     * passthrough plug: the representor is already in OVS (br-bond) at
     * this point so DvrManager flows can take effect. Silent no-op when
     * the required bits are absent; the VR path remains the fallback.
     */
    private void registerDvrIntent(NicTO nic, Integer segmentId, String repName) {
        if (nic == null || segmentId == null || segmentId <= 0) {
            return;
        }
        if (_libvirtComputingResource == null || _libvirtComputingResource.dvrManager == null) {
            return;
        }
        if (nic.getType() != com.cloud.network.Networks.TrafficType.Guest) {
            return;
        }
        String vmIp = nic.getIp();
        String vmMac = nic.getMac();
        String gateway = nic.getGateway();
        if (org.apache.commons.lang3.StringUtils.isBlank(vmIp)
                || org.apache.commons.lang3.StringUtils.isBlank(vmMac)
                || org.apache.commons.lang3.StringUtils.isBlank(gateway)) {
            return;
        }
        try {
            String cidr = buildCidrFromIpNetmask(vmIp, nic.getNetmask());
            String vpcId = nic.getNicDetail("dvr.vpc.id");
            if (org.apache.commons.lang3.StringUtils.isBlank(vpcId)) {
                vpcId = nic.getNicDetail("vpc.id");
            }
            String gatewayMac = nic.getNicDetail("dvr.gw.mac");
            if (org.apache.commons.lang3.StringUtils.isBlank(gatewayMac)) {
                gatewayMac = nic.getNicDetail("gateway.mac");
            }
            String vmName = nic.getNicDetail("vxlan.vm.name");
            if (org.apache.commons.lang3.StringUtils.isBlank(vmName)) {
                vmName = nic.getUuid();
            }
            _libvirtComputingResource.dvrManager.registerTier(vpcId, segmentId,
                    cidr != null ? cidr : (gateway + "/24"), gateway);
            if (vmIp.equals(gateway)) {
                _libvirtComputingResource.dvrManager.registerGatewayMac(vpcId, segmentId, vmMac);
            } else if (org.apache.commons.lang3.StringUtils.isNotBlank(gatewayMac)) {
                _libvirtComputingResource.dvrManager.registerGatewayMac(vpcId, segmentId, gatewayMac);
            }
            if (!vmIp.equals(gateway)) {
                _libvirtComputingResource.dvrManager.registerVmInTier(vpcId, vmName, segmentId, vmIp, vmMac, repName);
            }
            logger.info("registerDvrIntent (hostdev): vpc={} vni={} ip={} mac={} gwMac={} rep={}",
                    vpcId, segmentId, vmIp, vmMac, gatewayMac, repName);
        } catch (RuntimeException e) {
            logger.warn("registerDvrIntent (hostdev) failed for segment={} ip={}: {}",
                    segmentId, nic.getIp(), e.getMessage());
        }
    }

    /**
     * Simple dotted-netmask → {@code a.b.c.d/N} conversion. Falls back to null
     * when any bit is missing — DvrManager uses the CIDR purely as diagnostic
     * context, so null is tolerated.
     */
    private static String buildCidrFromIpNetmask(String ip, String netmask) {
        if (org.apache.commons.lang3.StringUtils.isBlank(ip)
                || org.apache.commons.lang3.StringUtils.isBlank(netmask)) {
            return null;
        }
        try {
            String[] nm = netmask.trim().split("\\.");
            if (nm.length != 4) {
                return null;
            }
            int prefix = 0;
            for (String p : nm) {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) {
                    return null;
                }
                prefix += Integer.bitCount(v);
            }
            String[] ipp = ip.trim().split("\\.");
            if (ipp.length != 4) {
                return null;
            }
            long ipLong = 0;
            for (String p : ipp) {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) {
                    return null;
                }
                ipLong = (ipLong << 8) | v;
            }
            long mask = prefix == 0 ? 0 : (-1L << (32 - prefix)) & 0xffffffffL;
            long net = ipLong & mask;
            return String.format("%d.%d.%d.%d/%d",
                    (net >>> 24) & 0xff, (net >>> 16) & 0xff,
                    (net >>> 8) & 0xff, net & 0xff, prefix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void ensureVxlanMeshIfNeeded(NicTO nic, Integer segmentId) {
        if (segmentId == null || segmentId <= 4094) {
            return;
        }
        if (nic == null || nic.getBroadcastUri() == null) {
            return;
        }
        String scheme = nic.getBroadcastUri().getScheme();
        if (scheme == null || !"vxlan".equalsIgnoreCase(scheme)) {
            return;
        }
        if (_libvirtComputingResource == null || _libvirtComputingResource.vxlanTunnelManager == null) {
            return;
        }
        try {
            java.util.List<String> peers = parseCsvDetail(nic, "vxlan.peers");
            String vmName = nic.getNicDetail("vxlan.vm.name");
            _libvirtComputingResource.vxlanTunnelManager.ensureMeshForVni(vmName, segmentId, peers);
        } catch (RuntimeException e) {
            logger.warn("ensureVxlanMeshIfNeeded: failed for segment={}: {}", segmentId, e.getMessage());
        }
    }

    /**
     * Parse a comma-separated NIC detail into a list. Null means "detail
     * absent" — the tunnel manager falls back to agent.properties.
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

    private Integer extractVlanTag(NicTO nic) {
        if (nic.getBroadcastUri() == null) {
            return null;
        }
        // Don't trust nic.getBroadcastType() — VPC public NICs can come through
        // with broadcastType=Vxlan even though their broadcastUri is "vlan://N"
        // (CloudStack inconsistency). Check the URI scheme directly instead.
        //
        // Accept BOTH "vlan://N" AND "vxlan://N":
        //   - vlan://N is used for Public NICs (CloudStack Public network type)
        //   - vxlan://N is used for VPC Guest tiers when broadcastType=Vxlan
        // On our data nodes (no actual VXLAN encap — segmentation is plain
        // VLAN trunking on bond1) both schemes carry the segmentation ID that
        // OVS should program as access tag on the representor port.
        String scheme = nic.getBroadcastUri().getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase();
        if (!"vlan".equals(scheme) && !"vxlan".equals(scheme) && !"lswitch".equals(scheme)) {
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
    /**
     * Install a high-priority static FDB OF rule that pins {@code vmMac} on
     * {@code repName}'s ofport for the given access tag. This short-circuits
     * the OVS NORMAL FDB lookup, so even if FDB gets polluted (e.g. by mesh
     * hairpin loops), packets destined to this VM are always delivered to the
     * correct local representor.
     *
     * <p>Idempotent: re-installs (replaces) the rule on every plug. Cleaned up
     * by {@link #removeLocalVmFdbRule}.
     */
    private void installLocalVmFdbRule(String repName, Integer vlanTag, String vmMac) {
        if (repName == null || vmMac == null || vlanTag == null) {
            return;
        }
        try {
            int ovsTag = toOvsAccessTag(vlanTag);
            String ofportStr = Script.runSimpleBashScript(String.format(
                "ovs-vsctl get interface %s ofport 2>/dev/null", repName));
            if (StringUtils.isBlank(ofportStr) || "-1".equals(ofportStr.trim())) {
                logger.warn("installLocalVmFdbRule: rep {} has no ofport yet; skipping",
                    repName);
                return;
            }
            int ofport = Integer.parseInt(ofportStr.trim());
            // Delete any stale rule for this (tag, mac) before adding the fresh one.
            // NOTE: must NOT use --strict — strict assumes priority=0 for unspecified
            // fields and won't match the priority=400 rule we want to clear.
            Script.runSimpleBashScript(String.format(
                "ovs-ofctl del-flows br-bond \"table=0,dl_vlan=%d,dl_dst=%s\" 2>/dev/null",
                ovsTag, vmMac));
            Script.runSimpleBashScript(String.format(
                "ovs-ofctl add-flow br-bond \"table=0,priority=400,dl_vlan=%d,dl_dst=%s,actions=output:%d\"",
                ovsTag, vmMac, ofport));
            logger.info("installLocalVmFdbRule: pinned mac={} tag={} -> ofport={} ({})",
                vmMac, ovsTag, ofport, repName);
        } catch (RuntimeException e) {
            logger.warn("installLocalVmFdbRule failed: rep={} tag={} mac={} err={}",
                repName, vlanTag, vmMac, e.getMessage());
        }
    }

    /**
     * Remove the static FDB rule installed by
     * {@link #installLocalVmFdbRule}. Called on unplug. Safe to call when the
     * rule never existed.
     */
    private void removeLocalVmFdbRule(Integer vlanTag, String vmMac) {
        if (vmMac == null || vlanTag == null) {
            return;
        }
        try {
            int ovsTag = toOvsAccessTag(vlanTag);
            // NOTE: must NOT use --strict here either — strict mode treats
            // unspecified fields as priority=0 and won't match the priority=400
            // pin we're trying to clear.
            Script.runSimpleBashScript(String.format(
                "ovs-ofctl del-flows br-bond \"table=0,dl_vlan=%d,dl_dst=%s\" 2>/dev/null",
                ovsTag, vmMac));
            logger.debug("removeLocalVmFdbRule: cleared mac={} tag={}", vmMac, ovsTag);
        } catch (RuntimeException e) {
            logger.debug("removeLocalVmFdbRule: cleanup failed mac={} tag={}: {}",
                vmMac, vlanTag, e.getMessage());
        }
    }

    /**
     * Remove the static FDB pin OF rule by MAC alone — used from unplug when
     * we no longer have the access tag (the rep is already gone). Non-strict
     * del-flows wildcards everything except dl_dst so any priority=400 entry
     * for this VM mac is wiped, regardless of which tier vlan tag it had.
     */
    private void removeLocalVmFdbRuleByMac(String vmMac) {
        if (org.apache.commons.lang3.StringUtils.isBlank(vmMac)) {
            return;
        }
        try {
            Script.runSimpleBashScript(String.format(
                "ovs-ofctl del-flows br-bond \"table=0,dl_dst=%s\" 2>/dev/null", vmMac));
            logger.debug("removeLocalVmFdbRuleByMac: cleared mac={}", vmMac);
        } catch (RuntimeException e) {
            logger.debug("removeLocalVmFdbRuleByMac: cleanup failed mac={}: {}",
                vmMac, e.getMessage());
        }
    }

}
