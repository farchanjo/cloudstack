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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef.NicModel;
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
        // Stamp the bridge-wide tc-policy on the very first OVN-aware plug
        // this JVM performs. Subsequent calls are no-ops via the per-JVM
        // latch inside the applier.
        OvnNicTunableApplier.applyTcPolicyOnce(nic.getOvsTcPolicy());
        logger.info("OvnVifDriver.plug: nic mac={} ip={} lsp={} ls={} bridge={}",
                nic.getMac(), nic.getIp(), nic.getOvnLspName(), nic.getOvnLsName(), integrationBridge);

        final InterfaceDef intf = new InterfaceDef();
        // bridge type + virtualport=openvswitch + interfaceid lets libvirt
        // run `ovs-vsctl add-port br-int <vnet> -- set Interface <vnet>
        // external_ids:iface-id=<...>` on attach. We do NOT call
        // defBridgeNet on a non-OVS bridge because that path emits
        // <virtualport type='openvswitch'> implicitly only when the bridge
        // is OVS-managed; we set it explicitly to keep the intent obvious.
        //
        // libvirt requires `interfaceid` to be a well-formed UUID — passing
        // {@code ovnLspName} (which carries the {@code lsp-} prefix) makes
        // libvirt reject the domain XML with
        //   "XML error: cannot parse interfaceid parameter as a uuid"
        // Use the NIC UUID here so libvirt's XML validator accepts it; libvirt
        // then runs `ovs-vsctl add-port br-int <vnet> -- set Interface <vnet>
        // external_ids:iface-id=<nic-uuid>` during domain start, which leaves
        // the OVS Port row with the WRONG binding key for ovn-controller (NB
        // {@code Logical_Switch_Port.name} is {@code lsp-<nic-uuid>}, not the
        // raw UUID). The OVS row is rewritten with the correct prefixed value
        // in {@link #applyPostPlugTunables} below — that callback fires after
        // libvirt has spawned the tap, mirroring the contract used by
        // {@link OvnVdpaVifDriver} and {@link OvnVfPassthroughVifDriver}.
        intf.setVirtualPortType("openvswitch");
        intf.setVirtualPortInterfaceId(nic.getUuid());

        final Integer rateKBps = getNetworkRateKbps(nic);
        // OVN tunable ovn.driver_model overrides the legacy guestOs/nicAdapter
        // resolution. Defaults to virtio when the operator did not set it.
        final NicModel tunableModel = OvnNicTunableApplier.resolveDriverModel(nic.getDriverModel());
        final NicModel model = tunableModel != null ? tunableModel : getGuestNicModel(guestOsType, nicAdapter);
        intf.defBridgeNet(integrationBridge, null, nic.getMac(), model, rateKBps);
        // No VLAN tag: OVN owns segmentation via Geneve VNI. Setting a tag
        // here would cause OVS to strip/insert .1Q on the access port and
        // collide with the OVN-injected metadata in the pipeline.

        // Stamp libvirt-XML tunables resolved by mgmt: vhost queues,
        // tx/rx queue size, vhost driver name, packed virtqueues.
        OvnNicTunableApplier.applyInterfaceDefTunables(nic, intf);
        return intf;
    }

    /**
     * Apply OVS external_ids stamp + ethtool offload toggles on the
     * freshly-created tap once libvirt has spawned it.
     *
     * <p>Called by {@link
     * com.cloud.hypervisor.kvm.resource.LibvirtComputingResource#applyOvnPostPlugTunables}
     * after the domain is running and the actual tap dev name ({@code vnetN})
     * is known from the live domain XML. This is the only correct place to
     * override the libvirt-emitted {@code external_ids:iface-id} (which uses
     * the raw NIC UUID — required by libvirt XML schema) with the OVN
     * logical-switch-port name ({@code lsp-<uuid>}) that ovn-controller
     * exact-matches against {@code Logical_Switch_Port.name} to claim
     * the {@code Port_Binding} row.
     */
    @Override
    public void applyPostPlugTunables(final NicTO nic, final String hostNetdev) {
        if (nic == null || StringUtils.isBlank(hostNetdev)) {
            return;
        }
        // Override the libvirt-emitted iface-id (raw NIC UUID — required by
        // libvirt XML schema; see plug() above) with the OVN logical-switch-
        // port name. ovn-controller exact-matches OVS
        // {@code external_ids:iface-id} against NB
        // {@code Logical_Switch_Port.name}, which is {@code lsp-<nic-uuid>};
        // without this stamp the Port_Binding is never claimed, no datapath
        // flows are programmed, and DHCP / tenant traffic fails. Mirrors the
        // pattern used by {@link OvnVdpaVifDriver} and
        // {@link OvnVfPassthroughVifDriver}.
        if (StringUtils.isNotBlank(nic.getOvnLspName())) {
            // Stamp iface-id together with ovn-installed=true and iface-status=active
            // in a single ovs-vsctl call. The ovn-installed flag is the per-Interface
            // contract ovn-controller normally sets after claiming the Port_Binding
            // for a freshly-plugged port; the destination of a live-migration retains
            // the same Port_Binding chassis and ovn-controller therefore does NOT
            // re-trigger the bind sequence — only the timestamp is updated. Without
            // ovn-installed=true the OpenFlow ingress action does not direct traffic
            // into the logical port and forwarding silently breaks (Bug 26).
            // Stamping it agent-side here is idempotent on the fresh-plug path
            // (ovn-controller would set it anyway) and load-bearing on the
            // post-migrate stamp path.
            final String stamp = String.format(
                    "ovs-vsctl --if-exists set Interface %s "
                            + "external_ids:iface-id=%s "
                            + "external_ids:iface-status=active "
                            + "external_ids:ovn-installed=true",
                    hostNetdev, nic.getOvnLspName());
            try {
                Script.runSimpleBashScript(stamp);
                logger.info("OvnVifDriver.applyPostPlugTunables: stamped dev={} lsp={} status=active ovn-installed=true",
                        hostNetdev, nic.getOvnLspName());
            } catch (RuntimeException e) {
                logger.warn("OvnVifDriver.applyPostPlugTunables: stamp failed dev={} lsp={}: {}",
                        hostNetdev, nic.getOvnLspName(), e.getMessage());
            }
        }
        OvnNicTunableApplier.applyEthtoolOffloads(nic, hostNetdev);
        // Stamp hairpin on the OVS Port now that libvirt has spawned the
        // tap and added it to br-int. Required for VF<->VF same-host
        // hardware offload via TC flower; harmless on kernel datapaths
        // that ignore the flag.
        OvnNicTunableApplier.applyHairpin(hostNetdev, nic.getOvsHairpin());
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

    /** Matches a VF representor's {@code phys_port_name} (eg. {@code pf0vf4}). */
    private static final Pattern REP_PHYS_PORT_PATTERN = Pattern.compile("pf(\\d+)vf(\\d+)");

    /**
     * Fallback representor teardown shared by {@link OvnVfPassthroughVifDriver#unplug}
     * and {@link OvnVdpaVifDriver#unplug} for when their VF-PCI reverse lookup
     * by guest MAC fails. libvirt zeroes the VF MAC during managed hostdev
     * detach / domain destroy BEFORE {@code unplug} runs, so that lookup
     * routinely returns null on the VM-expunge path and the primary rep
     * teardown is skipped — leaving the representor attached to the
     * integration bridge with its stale {@code external_ids}.
     *
     * <p>Both drivers stamp {@code external_ids:attached-mac=<guest mac>} on
     * the representor at plug time (see {@code attachRepresentorToBrInt} in
     * each class); that stamp lives in OVSDB, not on the netdev, so it
     * survives the MAC zeroing and is used here as the fallback lookup key.
     *
     * <p>For every representor OVS returns, the port is removed from
     * {@code integrationBridge} and a best-effort attempt is made to also
     * clear the VF identity (MAC + VLAN) on the parent PF — see
     * {@link #clearVfIdentityForRepBestEffort}. Idempotent — safe to call
     * when no representor carries the given {@code attached-mac}.
     *
     * @param log the calling driver's own logger instance, so log lines carry
     *            that driver's class name.
     * @param callerLabel short label identifying the calling driver + method
     *                    (eg. {@code "OvnVdpaVifDriver.unplug"}), used as a
     *                    log-line prefix.
     * @param integrationBridge the OVS bridge the orphan representor(s) are
     *                          attached to.
     * @param mac the guest MAC stamped as {@code attached-mac} at plug time.
     */
    static void clearOrphanRepsByAttachedMac(final Logger log, final String callerLabel,
                                              final String integrationBridge, final String mac) {
        if (StringUtils.isBlank(mac)) {
            return;
        }
        final String findCmd = String.format(
            "ovs-vsctl --no-headings --columns=name find Interface external_ids:attached-mac=%s 2>/dev/null",
            mac);
        final String found = Script.runSimpleBashScript(findCmd);
        if (StringUtils.isBlank(found)) {
            return;
        }
        for (final String raw : found.split("\\R")) {
            final String repName = raw.trim().replaceAll("^\"|\"$", "");
            if (StringUtils.isBlank(repName)) {
                continue;
            }
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl --if-exists del-port %s %s", integrationBridge, repName));
            log.info("{}: attached-mac fallback removed orphan rep={} from {} (mac={})",
                    callerLabel, repName, integrationBridge, mac);
            clearVfIdentityForRepBestEffort(log, callerLabel, repName);
        }
    }

    /**
     * Best-effort VF identity clear (MAC zero + VLAN 0) on the parent PF of a
     * representor that was just removed from the bridge, derived solely from
     * the representor's own netdev name — the PCI-scoped lookup path is
     * exactly what was unavailable when {@link #clearOrphanRepsByAttachedMac}
     * fired. Any resolution miss (missing/unexpected {@code phys_port_name},
     * PF netdev not found) is logged at DEBUG and silently skipped; the rep
     * removal already performed by the caller is the load-bearing half of
     * this cleanup and must not be undone by a failure here.
     */
    private static void clearVfIdentityForRepBestEffort(final Logger log, final String callerLabel, final String repName) {
        final String physPort = VfPassthroughVifDriver.readPhysPortName(repName);
        final Matcher matcher = physPort == null ? null : REP_PHYS_PORT_PATTERN.matcher(physPort);
        if (matcher == null || !matcher.matches()) {
            log.debug("{}: cannot derive VF identity for rep={} (phys_port_name={}); skipping PF-side clear",
                    callerLabel, repName, physPort);
            return;
        }
        final int pfIdx = Integer.parseInt(matcher.group(1));
        final int vfId = Integer.parseInt(matcher.group(2));
        final String pfName = VfPassthroughVifDriver.findPfByPhysPortIndex(pfIdx);
        if (pfName == null) {
            log.debug("{}: no PF netdev found for phys_port_name index p{}; skipping PF-side clear for rep={}",
                    callerLabel, pfIdx, repName);
            return;
        }
        Script.runSimpleBashScript(String.format(
            "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
        log.info("{}: cleared VF identity pf={} vf={} (derived from rep={})", callerLabel, pfName, vfId, repName);
    }
}
