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
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.utils.script.Script;

/**
 * VifDriver for OVN-managed tiers. Plugs the guest tap into the OVS
 * integration bridge ({@code br-int}) with the libvirt
 * {@code <virtualport type='openvswitch'><parameters interfaceid='&lt;ovnLspName&gt;'/></virtualport>}
 * directive — libvirt forwards the {@code interfaceid} as
 * {@code external_ids:iface-id} on the OVS port, which is the contract
 * {@code ovn-controller} consults to claim the matching
 * {@code Port_Binding} row in OVN_Southbound and program the OpenFlow
 * pipeline (datapath flows, conntrack, ARP/ND, ACLs).
 *
 * <p>Default integration bridge is {@code br-int}; operators can override
 * via {@code ovn.integration.bridge} agent property.
 *
 * <p>This driver is selected when {@link NicTO#isUseOvn()} returns {@code true}
 * and neither {@link NicTO#isUseHwOffload()} nor {@link NicTO#isUseVdpa()} is
 * set; the offload variants live in {@link OvnVfPassthroughVifDriver} and
 * {@link OvnVdpaVifDriver}.
 */
public class OvnVifDriver extends VifDriverBase {

    /** Default OVS integration bridge name (matches OVN upstream default). */
    public static final String DEFAULT_INTEGRATION_BRIDGE = "br-int";

    /** Agent property override for the integration bridge name. */
    public static final String PROP_INTEGRATION_BRIDGE = "ovn.integration.bridge";

    private String integrationBridge = DEFAULT_INTEGRATION_BRIDGE;

    @Override
    public void configure(final Map<String, Object> params) throws ConfigurationException {
        super.configure(params);
        // Allow operators to point at a non-default integration bridge
        // without touching code (rare — OVN upstream pins br-int).
        final Object override = params == null ? null : params.get(PROP_INTEGRATION_BRIDGE);
        if (override instanceof String && StringUtils.isNotBlank((String) override)) {
            this.integrationBridge = (String) override;
        }
    }

    @Override
    public InterfaceDef plug(final NicTO nic, final String guestOsType, final String nicAdapter,
                             final Map<String, String> extraConfig) throws InternalErrorException, LibvirtException {
        if (!nic.isUseOvn()) {
            // Defensive — should never happen because LibvirtComputingResource
            // dispatches by isUseOvn(). Fall back to a hard error so the
            // missing dispatch is visible at agent boot rather than as a
            // silent legacy plug.
            throw new InternalErrorException("OvnVifDriver invoked for nic without useOvn flag: " + nic);
        }
        if (StringUtils.isBlank(nic.getOvnLspName())) {
            throw new InternalErrorException("OvnVifDriver: NicTO is missing ovnLspName (mac=" + nic.getMac() + ")");
        }
        logger.info("OvnVifDriver.plug: nic mac={} ip={} lsp={} ls={} bridge={}",
                nic.getMac(), nic.getIp(), nic.getOvnLspName(), nic.getOvnLsName(), integrationBridge);

        final InterfaceDef intf = new InterfaceDef();
        // bridge type + virtualport=openvswitch + interfaceid lets libvirt
        // run `ovs-vsctl add-port br-int <vnet> -- set Interface <vnet>
        // external_ids:iface-id=<lspName>` on attach. We do NOT call
        // defBridgeNet on a non-OVS bridge because that path emits
        // <virtualport type='openvswitch'> implicitly only when the bridge
        // is OVS-managed; we set it explicitly to keep the intent obvious.
        intf.setVirtualPortType("openvswitch");
        intf.setVirtualPortInterfaceId(nic.getOvnLspName());

        final Integer rateKBps = getNetworkRateKbps(nic);
        intf.defBridgeNet(integrationBridge, null, nic.getMac(),
                getGuestNicModel(guestOsType, nicAdapter), rateKBps);
        // No VLAN tag: OVN owns segmentation via Geneve VNI. Setting a tag
        // here would cause OVS to strip/insert .1Q on the access port and
        // collide with the OVN-injected metadata in the pipeline.
        return intf;
    }

    @Override
    public void unplug(final InterfaceDef iface, final boolean deleteBr) {
        // libvirt removes the kernel netdev at domain stop, but the OVSDB
        // Port row (added when libvirt called add-port) survives with
        // ofport=-1 and external_ids:iface-id intact — a ghost port.
        // ovn-controller eventually GCs it, but explicit del-port avoids
        // the race window where ovn-controller re-binds a stale row to
        // a different domain that recycles the same vnet name. Mirrors
        // OvsVifDriver.unplug.
        final String dev = iface == null ? null : iface.getDevName();
        final String br = iface == null ? null : iface.getBrName();
        if (StringUtils.isBlank(dev) || StringUtils.isBlank(br)) {
            return;
        }
        try {
            final String cmd = String.format("ovs-vsctl --if-exists del-port %s %s", br, dev);
            Script.runSimpleBashScript(cmd);
            logger.info("OvnVifDriver.unplug: del-port br={} dev={}", br, dev);
        } catch (RuntimeException e) {
            logger.warn("OvnVifDriver.unplug: del-port {}/{} failed: {}", br, dev, e.getMessage());
        }
    }

    @Override
    public void attach(final InterfaceDef iface) {
        // libvirt 8+ already runs add-port via the bridge type, so this is
        // a defensive no-op. Kept for parity with the base contract.
    }

    @Override
    public void detach(final InterfaceDef iface) {
        unplug(iface, false);
    }

    @Override
    public void deleteBr(final NicTO nic) {
        // OVN integration bridge is shared infra — never auto-deleted.
    }

    @Override
    public void createControlNetwork(final String privBrName) {
        // Control network on link-local has its own driver / bridge; OVN
        // path doesn't synthesize one. No-op.
    }

    @Override
    public boolean isExistingBridge(final String bridgeName) {
        return integrationBridge.equals(bridgeName);
    }

    /** Returns the integration bridge name currently in effect (test hook). */
    public String getIntegrationBridge() {
        return integrationBridge;
    }
}
