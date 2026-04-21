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
package com.cloud.hypervisor.kvm.resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Stream;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.utils.script.Script;

/**
 * VifDriver for VF+vDPA (ConnectX-6 Dx). Consumes the {@code /dev/vhost-vdpa-N}
 * chardev produced by {@link VfVdpaLifecycleManager#createVdpa} and attaches
 * it to the guest as {@code <interface type='vdpa'><source dev='...'/></interface>}.
 *
 * <p>Unlike {@link VfPassthroughVifDriver} (PCI hostdev), vDPA supports
 * hot-plug and lays the groundwork for live migration of the datapath.
 *
 * <p>The VF representor is assumed to be reachable as {@code nic.vfRepName}
 * (populated by the management-side allocator from the {@code sriov_vf_pool}
 * row). The driver ensures the rep is on {@code br-bond} with a {@code clsact}
 * TC qdisc so the host-side OpenFlow / TC flower pipeline can install HW
 * offload rules for this NIC.
 */
public class VdpaVifDriver extends VifDriverBase {

    private static final String OVS_BRIDGE = "br-bond";

    @Override
    public void configure(final Map<String, Object> params) throws ConfigurationException {
        super.configure(params);
    }

    @Override
    public LibvirtVMDef.InterfaceDef plug(final NicTO nic, final String guestOsType, final String nicAdapter,
                                          final Map<String, String> extraConfig)
            throws InternalErrorException, LibvirtException {
        final String vdpaDevice = nic.getVdpaDevice();
        if (StringUtils.isBlank(vdpaDevice)) {
            throw new InternalErrorException(
                    "VdpaVifDriver invoked without vdpaDevice on NicTO; check VfPoolManager allocation");
        }

        // sriov_vf_pool.representor_name is unreliable (observed to point at
        // the wrong rep for a given VF PCI). Derive the real rep from sysfs
        // using the VF PCI address (which is authoritative). Fall back to the
        // hint from NicTO only if sysfs resolution fails.
        String repName = resolveRepNameFromVfPci(nic.getVfPciAddress());
        final String repHint = nic.getVfRepName();
        if (StringUtils.isBlank(repName)) {
            repName = repHint;
        } else if (!repName.equals(repHint)) {
            logger.info("VF rep resolved via sysfs: {} (pool hint was {}) for VF {}",
                    repName, repHint, nic.getVfPciAddress());
        }
        if (StringUtils.isNotBlank(repName)) {
            ensureRepresentorOnOvs(repName);
            attachVfRepToNetwork(nic, repName);
        } else {
            logger.warn("VF representor name not set on NicTO for vDPA device {}; " +
                    "OVS port and TC qdisc will not be configured", vdpaDevice);
        }

        final LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        intf.defVdpaNet(vdpaDevice, nic.getMac(), nic.getVfPciAddress());
        logger.info("vDPA plug: dev={} rep={} mac={}", vdpaDevice, repName, nic.getMac());
        return intf;
    }

    @Override
    public void unplug(final LibvirtVMDef.InterfaceDef iface, final boolean deleteBr) {
        final String vdpaDevPath = iface.getVdpaDevPath();
        if (StringUtils.isBlank(vdpaDevPath)) {
            return;
        }
        // Representor teardown here is best-effort: the authoritative cleanup
        // happens in IntentReconciler.detachRepresentorFromBridge when the VR
        // stops. We intentionally do NOT run vdpa-dev-del here — the VF pool
        // release path (server side) issues DestroyVdpaCommand so the device
        // lifecycle stays aligned with the pool state machine.
        logger.info("vDPA unplug: dev={}", vdpaDevPath);
    }

    @Override
    public void attach(final LibvirtVMDef.InterfaceDef iface) {
        logger.info("vDPA attach: dev={}", iface.getVdpaDevPath());
    }

    @Override
    public void detach(final LibvirtVMDef.InterfaceDef iface) {
        unplug(iface, false);
    }

    @Override
    public void deleteBr(final NicTO nic) {
        // No bridge owned by this driver.
    }

    @Override
    public void createControlNetwork(final String privBrName) {
        // Control NIC (cloud0) uses a TAP/bridge driver, not vDPA.
    }

    @Override
    public boolean isExistingBridge(final String bridgeName) {
        return false;
    }

    /**
     * Given a VF PCI (e.g. {@code 0000:01:00.2}) resolve the real host-side
     * representor netdev name. Sysfs mapping:
     * <ul>
     *   <li>{@code /sys/bus/pci/devices/<vf>/physfn} -> PF PCI</li>
     *   <li>{@code /sys/bus/pci/devices/<pfPci>/net/<pfName>} -> PF netdev</li>
     *   <li>{@code /sys/bus/pci/devices/<pfPci>/virtfnN} (matching the VF PCI)
     *       -> VF index N</li>
     *   <li>Representor convention on these hosts: {@code <pfName>vf<N>}
     *       (renamed by udev; see project_a2_sriov_rolling_complete).</li>
     * </ul>
     * Returns {@code null} if anything along the way is unreadable.
     */
    static String resolveRepNameFromVfPci(final String vfPciAddress) {
        if (StringUtils.isBlank(vfPciAddress)) {
            return null;
        }
        try {
            final Path vfPhysfn = Paths.get("/sys/bus/pci/devices", vfPciAddress, "physfn");
            final String pfPci = Files.readSymbolicLink(vfPhysfn).getFileName().toString();
            // In switchdev mode /sys/bus/pci/devices/<pfPci>/net/ contains BOTH
            // the PF netdev AND every VF representor (they share the same PCI
            // parent). Filter to the real PF netdev — identified by an empty
            // or "pfN"-only phys_port_name (representors carry "pfNvfM").
            final Path pfNetDir = Paths.get("/sys/bus/pci/devices", pfPci, "net");
            String pfName = null;
            try (Stream<Path> s = Files.list(pfNetDir)) {
                for (Path nd : (Iterable<Path>) s::iterator) {
                    final String name = nd.getFileName().toString();
                    String ppn = "";
                    try {
                        ppn = new String(Files.readAllBytes(
                                Paths.get("/sys/class/net", name, "phys_port_name"))).trim();
                    } catch (Exception ignored) {}
                    if (ppn.isEmpty() || !ppn.contains("vf")) {
                        pfName = name;
                        break;
                    }
                }
            }
            if (pfName == null) {
                return null;
            }
            final Path pfDevice = Paths.get("/sys/bus/pci/devices", pfPci);
            try (Stream<Path> s = Files.list(pfDevice)) {
                final Integer idx = s
                        .filter(p -> p.getFileName().toString().startsWith("virtfn"))
                        .filter(p -> {
                            try {
                                return vfPciAddress.equals(Files.readSymbolicLink(p).getFileName().toString());
                            } catch (Exception ignored) {
                                return false;
                            }
                        })
                        .findFirst()
                        .map(p -> {
                            try {
                                return Integer.parseInt(p.getFileName().toString().substring("virtfn".length()));
                            } catch (NumberFormatException ex) {
                                return null;
                            }
                        })
                        .orElse(null);
                if (idx == null) {
                    return null;
                }
                return pfName + "vf" + idx;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Put the VF representor on {@code br-bond} with a {@code clsact} TC qdisc
     * so the host HW-offload pipeline can program flower rules for this NIC.
     */
    private void ensureRepresentorOnOvs(final String repName) {
        Script.runSimpleBashScript(String.format("ovs-vsctl --may-exist add-port %s %s", OVS_BRIDGE, repName));
        Script.runSimpleBashScript(String.format("tc qdisc add dev %s clsact 2>/dev/null", repName));
        logger.info("Ensured VF representor {} on OVS {} with clsact qdisc", repName, OVS_BRIDGE);
    }

    /**
     * Set the OVS access tag on a VF representor port so packets from the VM
     * exit OVS with the correct VLAN/VXLAN-folded tag. For {@code vlan://N}
     * (Public NIC) we apply the VLAN ID directly; for {@code vxlan://N} we
     * fold the VNI through {@link OvsVifDriver#toOvsAccessTag(int)} and also
     * trigger {@link com.cloud.hypervisor.kvm.resource.ovs.VxlanTunnelManager}
     * to build the inter-host tunnel mesh for this VNI.
     */
    private void attachVfRepToNetwork(final NicTO nic, final String repName) {
        final String uri = nic.getBroadcastUri() != null ? nic.getBroadcastUri().toString() : null;
        if (StringUtils.isBlank(uri)) {
            return;
        }
        int tag = -1;
        try {
            if (uri.startsWith("vlan://")) {
                tag = Integer.parseInt(uri.substring("vlan://".length()));
            } else if (uri.startsWith("vxlan://")) {
                final int vni = Integer.parseInt(uri.substring("vxlan://".length()));
                tag = OvsVifDriver.toOvsAccessTag(vni);
                ensureVxlanMesh(nic, vni);
            }
        } catch (NumberFormatException e) {
            logger.warn("attachVfRepToNetwork: unparseable broadcastUri '{}' for rep {}", uri, repName);
            return;
        }
        if (tag > 0) {
            Script.runSimpleBashScript(String.format("ovs-vsctl set port %s tag=%d", repName, tag));
            logger.info("Set OVS tag={} on VF rep {} (from broadcastUri={})", tag, repName, uri);
        }
    }

    private void ensureVxlanMesh(final NicTO nic, final int vni) {
        if (_libvirtComputingResource == null || _libvirtComputingResource.vxlanTunnelManager == null) {
            return;
        }
        try {
            final String raw = nic.getNicDetail("vxlan.peers");
            final java.util.Collection<String> peers = (raw == null || raw.isEmpty())
                    ? null
                    : java.util.Arrays.asList(raw.split("\\s*,\\s*"));
            final String vmName = nic.getNicDetail("vxlan.vm.name");
            _libvirtComputingResource.vxlanTunnelManager.ensureMeshForVni(vmName, vni, peers);
        } catch (RuntimeException e) {
            logger.warn("ensureVxlanMesh failed for vni={}: {}", vni, e.getMessage());
        }
    }
}
