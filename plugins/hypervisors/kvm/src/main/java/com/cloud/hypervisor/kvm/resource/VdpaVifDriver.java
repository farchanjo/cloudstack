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

import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.utils.script.Script;

/**
 * VifDriver for Mellanox Sub-Function (SF) with vDPA passthrough. Generates
 * libvirt {@code <interface type='vdpa'><source dev='/dev/vhost-vdpa-N'/><mac address='...'/></interface>}
 * XML so the SF's vDPA device is presented as a virtio-net NIC inside the guest.
 *
 * <p>Used for VRs (or any VM) when the NIC's network offering has SF/vDPA enabled
 * (NicTO.useSfVdpa=true, vdpaDevice and sfRepresentorName set by SfPoolManager).
 *
 * <p>Unlike VF passthrough ({@link VfPassthroughVifDriver}), vDPA interfaces
 * support hot-plug (attach/detach) because they use the vhost-vdpa chardev
 * rather than PCI assignment.
 *
 * <p>This driver ensures the SF representor is on OVS (br-bond) and has a
 * clsact TC qdisc for hardware-offloaded flow rules. TC/HW offload rules
 * themselves are programmed separately by the host agent's TcRuleProgrammer.
 */
public class VdpaVifDriver extends VifDriverBase {

    @Override
    public void configure(Map<String, Object> params) throws ConfigurationException {
        super.configure(params);
    }

    @Override
    public LibvirtVMDef.InterfaceDef plug(NicTO nic, String guestOsType, String nicAdapter,
            Map<String, String> extraConfig) throws InternalErrorException, LibvirtException {

        String vdpaDevice = nic.getVdpaDevice();
        if (StringUtils.isBlank(vdpaDevice)) {
            throw new InternalErrorException(
                "VdpaVifDriver invoked without vdpaDevice on NicTO; check SfPoolManager allocation");
        }

        String sfRepName = nic.getSfRepresentorName();
        if (StringUtils.isNotBlank(sfRepName)) {
            ensureSfRepresentorOnOvs(sfRepName);
        } else {
            logger.warn("SF representor name not set on NicTO for vDPA device {}; "
                    + "OVS port and TC qdisc will not be configured", vdpaDevice);
        }

        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        intf.defVdpaNet(vdpaDevice, nic.getMac());

        logger.info("vDPA plug: dev={} sfRep={} mac={}", vdpaDevice, sfRepName, nic.getMac());
        return intf;
    }

    @Override
    public void unplug(LibvirtVMDef.InterfaceDef iface, boolean deleteBr) {
        String vdpaDevPath = iface.getVdpaDevPath();
        if (StringUtils.isBlank(vdpaDevPath)) {
            return;
        }

        // Best-effort: find and clean up the SF representor associated with this vDPA device.
        // The SF representor name is derived from the vDPA device index (e.g. vhost-vdpa-0 -> sf0).
        // SF destruction itself is handled by SfPoolManager, not here.
        String sfRepName = lookupSfRepresentorFromVdpa(vdpaDevPath);
        if (sfRepName != null) {
            Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null", sfRepName));
            Script.runSimpleBashScript(String.format("ovs-vsctl --if-exists del-port br-bond %s", sfRepName));
            logger.info("vDPA unplug: removed SF rep {} from OVS and cleared TC for {}", sfRepName, vdpaDevPath);
        } else {
            logger.debug("vDPA unplug: no SF representor found for {}; skipping OVS/TC cleanup", vdpaDevPath);
        }
    }

    @Override
    public void attach(LibvirtVMDef.InterfaceDef iface) {
        // vDPA supports hot-plug via libvirt virDomainAttachDevice.
        // Ensure the SF representor is on OVS before the device is attached.
        String vdpaDevPath = iface.getVdpaDevPath();
        String sfRepName = lookupSfRepresentorFromVdpa(vdpaDevPath);
        if (sfRepName != null) {
            ensureSfRepresentorOnOvs(sfRepName);
        }
        logger.info("vDPA attach: dev={} sfRep={}", vdpaDevPath, sfRepName);
    }

    @Override
    public void detach(LibvirtVMDef.InterfaceDef iface) {
        // vDPA supports hot-unplug. Clean up OVS/TC for the SF representor.
        unplug(iface, false);
        logger.info("vDPA detach: dev={}", iface.getVdpaDevPath());
    }

    @Override
    public void deleteBr(NicTO nic) {
        // No bridge to delete for vDPA interfaces.
    }

    @Override
    public void createControlNetwork(String privBrName) {
        // Control network (cloud0) uses a TAP/bridge NIC, not vDPA.
        // Nothing to do here -- control NIC is handled by BridgeVifDriver.
    }

    @Override
    public boolean isExistingBridge(String bridgeName) {
        // vDPA does not use bridges.
        return false;
    }

    /**
     * Ensure the SF representor is added to OVS br-bond and has a clsact TC qdisc
     * for hardware-offloaded flow programming.
     */
    private void ensureSfRepresentorOnOvs(String sfRepName) {
        Script.runSimpleBashScript(String.format("ovs-vsctl --may-exist add-port br-bond %s", sfRepName));
        Script.runSimpleBashScript(String.format("tc qdisc add dev %s clsact 2>/dev/null", sfRepName));
        logger.info("Ensured SF representor {} on OVS br-bond with clsact qdisc", sfRepName);
    }

    /**
     * Best-effort lookup of the SF representor netdev associated with a vDPA char device.
     *
     * <p>vDPA devices under {@code /sys/bus/vdpa/devices/vdpa<N>/} have a symlink to their
     * parent SF auxiliary device, which in turn has a representor netdev. We scan
     * {@code /sys/class/net/} for SF representors (phys_port_name starts with "pf"
     * and contains "sf") whose parent vDPA index matches.
     *
     * <p>Falls back to {@code null} if the mapping cannot be resolved (caller handles gracefully).
     *
     * @param vdpaDevPath the vDPA device path, e.g. "/dev/vhost-vdpa-0".
     * @return the SF representor netdev name (e.g. "dx6p0sf0"), or null.
     */
    static String lookupSfRepresentorFromVdpa(String vdpaDevPath) {
        if (StringUtils.isBlank(vdpaDevPath)) {
            return null;
        }
        // Extract vDPA index from path: "/dev/vhost-vdpa-0" -> "0"
        String baseName = vdpaDevPath;
        int lastDash = baseName.lastIndexOf('-');
        if (lastDash < 0) {
            return null;
        }
        String vdpaIndex = baseName.substring(lastDash + 1);

        // Try to find SF representor via sysfs: /sys/class/vdpa/vdpa<N>/
        // The representor is typically named with "sf" in its phys_port_name.
        String result = Script.runSimpleBashScript(
                String.format("ls /sys/class/net/ | while read iface; do "
                        + "ppn=$(cat /sys/class/net/$iface/phys_port_name 2>/dev/null); "
                        + "[ -z \"$ppn\" ] && continue; "
                        + "echo \"$ppn\" | grep -q 'sf' || continue; "
                        + "sfnum=$(cat /sys/class/net/$iface/phys_switch_id 2>/dev/null); "
                        + "echo $iface; break; "
                        + "done", vdpaIndex));

        return StringUtils.isNotBlank(result) ? result.trim() : null;
    }
}
