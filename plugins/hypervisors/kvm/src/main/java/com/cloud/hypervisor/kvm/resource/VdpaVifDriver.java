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

import java.util.Map;

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

        final String repName = nic.getVfRepName();
        if (StringUtils.isNotBlank(repName)) {
            ensureRepresentorOnOvs(repName);
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
     * Put the VF representor on {@code br-bond} with a {@code clsact} TC qdisc
     * so the host HW-offload pipeline can program flower rules for this NIC.
     */
    private void ensureRepresentorOnOvs(final String repName) {
        Script.runSimpleBashScript(String.format("ovs-vsctl --may-exist add-port %s %s", OVS_BRIDGE, repName));
        Script.runSimpleBashScript(String.format("tc qdisc add dev %s clsact 2>/dev/null", repName));
        logger.info("Ensured VF representor {} on OVS {} with clsact qdisc", repName, OVS_BRIDGE);
    }
}
