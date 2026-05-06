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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.utils.script.Script;

/**
 * VifDriver for the OVN + vDPA (vhost-vdpa) path. Combines the vDPA control
 * surface (a {@code vdpa dev add ...} on top of an SR-IOV VF, exposed as
 * {@code <interface type='vdpa'><source dev='/dev/vhost-vdpaN'/></interface>})
 * with OVN integration: the underlying VF representor is attached to the
 * integration bridge {@code br-int} and stamped with
 * {@code external_ids:iface-id=<ovnLspName>}, so {@code ovn-controller}
 * binds the OVN port and offloads programmed flows to the e-switch via
 * mlx5 switchdev TC flower.
 *
 * <p>Differences vs {@link VdpaVifDriver}:
 * <ul>
 *   <li>Representor goes to {@code br-int} (not {@code br-bond}).
 *   <li>No PF-side VF VLAN config (mlx5 switchdev rejects it; OVN owns
 *       Geneve segmentation).
 *   <li>No OVS access tag on the rep — OVN logical flows handle isolation.
 *   <li>No DvrManager hooks — OVN supplies its own L3/ARP/ND.
 * </ul>
 *
 * <p>The vDPA SF lifecycle (create on plug, destroy on unplug) is identical
 * to {@link VdpaVifDriver}; the helper sysfs/iproute2 logic is reused via
 * the static methods exposed by that class.
 */
public class OvnVdpaVifDriver extends VifDriverBase {

    /** Default integration bridge name; matches OVN upstream default. */
    public static final String DEFAULT_INTEGRATION_BRIDGE = OvnVifDriver.DEFAULT_INTEGRATION_BRIDGE;

    private String integrationBridge = DEFAULT_INTEGRATION_BRIDGE;

    @Override
    public void configure(final Map<String, Object> params) throws ConfigurationException {
        super.configure(params);
        final Object override = params == null ? null : params.get(OvnVifDriver.PROP_INTEGRATION_BRIDGE);
        if (override instanceof String && StringUtils.isNotBlank((String) override)) {
            this.integrationBridge = (String) override;
        }
    }

    @Override
    public InterfaceDef plug(final NicTO nic, final String guestOsType, final String nicAdapter,
                             final Map<String, String> extraConfig) throws InternalErrorException, LibvirtException {
        if (!nic.isUseOvn()) {
            throw new InternalErrorException("OvnVdpaVifDriver invoked for nic without useOvn flag: " + nic);
        }
        if (StringUtils.isBlank(nic.getOvnLspName())) {
            throw new InternalErrorException("OvnVdpaVifDriver: NicTO is missing ovnLspName (mac=" + nic.getMac() + ")");
        }
        final String pciAddress = nic.getVfPciAddress();
        if (StringUtils.isBlank(pciAddress)) {
            throw new InternalErrorException(
                "OvnVdpaVifDriver invoked without vfPciAddress on NicTO; check VfPoolManager.allocateForVdpa");
        }
        final String mac = nic.getMac();
        if (StringUtils.isBlank(mac)) {
            throw new InternalErrorException("OvnVdpaVifDriver requires a MAC on the NicTO");
        }

        final String pfName = StringUtils.isNotBlank(nic.getVfPfName())
                ? nic.getVfPfName()
                : VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
        final int maxVqs = nic.getVdpaMaxVqs() != null ? nic.getVdpaMaxVqs() : 33;

        final Deque<Runnable> rollback = new ArrayDeque<>();
        try {
            // (1) PF-side VF identity: MAC + trust + spoofchk, NO VLAN.
            //     Switchdev mlx5 rejects PF-side VLAN; OVN owns segmentation.
            configureVfOnPfNoVlan(pfName, vfId, mac);
            final String pfFinal = pfName;
            final Integer vfIdFinal = vfId;
            rollback.push(() -> {
                if (pfFinal != null && vfIdFinal != null) {
                    Script.runSimpleBashScript(String.format(
                        "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfFinal, vfIdFinal));
                }
            });

            // (2) Create the vDPA SF on top of the VF.
            final String vdpaName = VdpaVifDriver.buildVdpaName(nic);
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            Script.runSimpleBashScript(String.format(
                "vdpa dev add name %s mgmtdev pci/%s mac %s max_vqs %d",
                vdpaName, pciAddress, mac, maxVqs), 5000);
            rollback.push(() -> Script.runSimpleBashScript(
                    String.format("vdpa dev del %s 2>/dev/null", vdpaName)));

            final String vhostDev = VdpaVifDriver.resolveVhostVdpaDevice(vdpaName);
            if (StringUtils.isBlank(vhostDev)) {
                throw new InternalErrorException(String.format(
                    "OvnVdpaVifDriver could not resolve /dev/vhost-vdpa-N for vdpa name %s", vdpaName));
            }
            nic.setVdpaDevice(vhostDev);

            // (3) Attach the VF representor to br-int with the OVN binding.
            //     ovn-controller picks it up and programs offloaded flows.
            final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName == null) {
                throw new InternalErrorException(String.format(
                    "OvnVdpaVifDriver: representor not found for VF %s; mlx5 switchdev required",
                    pciAddress));
            }
            attachRepresentorToBrInt(repName, nic.getOvnLspName(), mac);
            final String repFinal = repName;
            rollback.push(() -> Script.runSimpleBashScript(String.format(
                "ovs-vsctl --if-exists del-port %s %s", integrationBridge, repFinal)));

            // (4) Domain XML <interface type='vdpa'>; queues = max_vqs / 2.
            final InterfaceDef intf = new InterfaceDef();
            final Integer queues = maxVqs > 1 ? maxVqs / 2 : null;
            intf.defVdpaNet(vhostDev, mac, queues);
            intf.setLinkStateUp(nic.isEnabled());

            logger.info("OvnVdpaVifDriver.plug: name={} pci={} pf={} mac={} rep={} lsp={} vhost={} maxVqs={} bridge={}",
                    vdpaName, pciAddress, pfName, mac, repName, nic.getOvnLspName(),
                    vhostDev, maxVqs, integrationBridge);
            return intf;
        } catch (RuntimeException | InternalErrorException ex) {
            drainRollback(rollback);
            throw ex;
        }
    }

    @Override
    public void unplug(final InterfaceDef iface, final boolean delete) {
        if (iface == null) {
            return;
        }
        final String mac = iface.getMacAddress();
        final String vhostDev = iface.getBrName(); // _sourceName carries /dev/vhost-vdpaN
        logger.info("OvnVdpaVifDriver.unplug ENTRY: vhost={} mac={} netType={} delete={}",
                vhostDev, mac, iface.getNetType(), delete);

        // (1) vdpa dev del — match by /dev path first, fall back to vdpa-name
        //     scan via VdpaVifDriver helpers.
        String vdpaName = VdpaVifDriver.lookupVdpaNameByVhostDev(vhostDev);
        if (StringUtils.isNotBlank(vdpaName)) {
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            logger.info("OvnVdpaVifDriver.unplug: deleted vdpa dev {}", vdpaName);
        } else {
            logger.warn("OvnVdpaVifDriver.unplug: could not resolve vdpa name from vhost={}; skipping vdpa dev del", vhostDev);
        }

        // (2) Drop representor from br-int and clear PF-side VF identity.
        final String pciAddress = lookupVfPciByMac(mac);
        if (StringUtils.isNotBlank(pciAddress)) {
            final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName != null) {
                Script.runSimpleBashScript(String.format(
                    "ovs-vsctl --if-exists del-port %s %s", integrationBridge, repName));
                logger.info("OvnVdpaVifDriver.unplug: removed rep {} from {}", repName, integrationBridge);
            }
            final String pfName = VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
            final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
            if (pfName != null && vfId != null) {
                Script.runSimpleBashScript(String.format(
                    "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
            }
        } else {
            logger.warn("OvnVdpaVifDriver.unplug: VF MAC reverse lookup failed for mac={}", mac);
        }
    }

    @Override
    public void attach(final InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-attach is not supported for OVN vDPA");
    }

    @Override
    public void detach(final InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-detach is not supported for OVN vDPA");
    }

    @Override
    public void deleteBr(final NicTO nic) {
        // Integration bridge is shared infra — never auto-deleted.
    }

    @Override
    public void createControlNetwork(final String privBrName) {
        // Control NIC uses tap/bridge; OVN vDPA path never synthesizes one.
    }

    @Override
    public boolean isExistingBridge(final String bridgeName) {
        return integrationBridge.equals(bridgeName);
    }

    /** MAC + trust + spoofchk on the VF; never set VLAN (OVN handles it). */
    private void configureVfOnPfNoVlan(final String pfName, final Integer vfId, final String macAddr) {
        if (pfName == null || vfId == null) {
            logger.warn("OvnVdpaVifDriver: cannot configure VF on PF: pf={} vfId={}", pfName, vfId);
            return;
        }
        if (StringUtils.isNotBlank(macAddr)) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d mac %s", pfName, vfId, macAddr));
        }
        Script.runSimpleBashScript(String.format("ip link set %s vf %d trust on", pfName, vfId));
        Script.runSimpleBashScript(String.format("ip link set %s vf %d spoofchk off", pfName, vfId));
    }

    /** Attach VF rep to br-int with OVN binding external_ids — same contract as B2. */
    private void attachRepresentorToBrInt(final String repName, final String lspName, final String mac) {
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl --may-exist add-port %s %s", integrationBridge, repName));
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl set Interface %s external_ids:iface-id=%s", repName, lspName));
        if (StringUtils.isNotBlank(mac)) {
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl set Interface %s external_ids:attached-mac=%s", repName, mac));
        }
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl set Interface %s external_ids:iface-status=active", repName));
    }

    /**
     * Resolve VF PCI BDF by guest MAC. Reflectively reuses the cached PF
     * scan in {@link VfPassthroughVifDriver}; reflection cost is paid once
     * per unplug, dwarfed by the surrounding sysfs scans.
     */
    private String lookupVfPciByMac(final String mac) {
        if (StringUtils.isBlank(mac)) {
            return null;
        }
        try {
            final java.lang.reflect.Method m = VfPassthroughVifDriver.class
                    .getDeclaredMethod("lookupVfPciByMac", String.class);
            m.setAccessible(true);
            return (String) m.invoke(new VfPassthroughVifDriver(), mac);
        } catch (ReflectiveOperationException e) {
            logger.debug("OvnVdpaVifDriver.lookupVfPciByMac reflective dispatch failed: {}", e.getMessage());
            return null;
        }
    }

    private void drainRollback(final Deque<Runnable> rollback) {
        while (!rollback.isEmpty()) {
            try {
                rollback.pop().run();
            } catch (RuntimeException e) {
                logger.warn("OvnVdpaVifDriver rollback step failed: {}", e.getMessage());
            }
        }
    }
}
