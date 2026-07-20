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
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

/**
 * VifDriver for the OVN + SR-IOV hardware-offload path. Combines:
 *
 * <ul>
 *   <li>Guest data plane: PCI passthrough of the VF (libvirt
 *       {@code <interface type='hostdev' managed='yes'>}) — same as
 *       {@link VfPassthroughVifDriver}.
 *   <li>Control plane: the VF's representor netdev is dropped into the OVS
 *       integration bridge {@code br-int} with
 *       {@code external_ids:iface-id=<ovnLspName>}. {@code ovn-controller}
 *       on the chassis claims the matching {@code Port_Binding} row in
 *       OVN_Southbound, programs the OpenFlow pipeline, and the mlx5
 *       switchdev driver offloads accepted flows to the e-switch via TC
 *       flower so the steady-state traffic never hits the kernel.
 * </ul>
 *
 * <p>Differences vs {@link VfPassthroughVifDriver}:
 * <ul>
 *   <li>Representor goes to {@code br-int} instead of {@code br-bond} — OVN
 *       owns the integration bridge.
 *   <li>No PF-side VF VLAN tag (mlx5 switchdev rejects it; OVN inserts the
 *       Geneve VNI in its pipeline).
 *   <li>No OVS access tag on the representor — OVN's logical-flow stage
 *       handles tenant separation.
 *   <li>No {@code DvrManager} registration / FDB pin OF rules — OVN
 *       supplies its own ARP/ND, FDB and routing flows.
 * </ul>
 *
 * <p>Reuses the VF/PF/representor sysfs lookup statics from
 * {@link VfPassthroughVifDriver} so any topology helper improvement
 * applies to both code paths.
 */
public class OvnVfPassthroughVifDriver extends VifDriverBase {

    /** Default OVS integration bridge name (matches OVN upstream default). */
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
            throw new InternalErrorException("OvnVfPassthroughVifDriver invoked for nic without useOvn flag: " + nic);
        }
        if (StringUtils.isBlank(nic.getOvnLspName())) {
            throw new InternalErrorException("OvnVfPassthroughVifDriver: NicTO is missing ovnLspName (mac=" + nic.getMac() + ")");
        }
        final String pciAddress = nic.getVfPciAddress();
        if (StringUtils.isBlank(pciAddress)) {
            throw new InternalErrorException(
                "OvnVfPassthroughVifDriver invoked without vfPciAddress on NicTO; check VfPoolManager allocation");
        }
        final String pfName = StringUtils.isNotBlank(nic.getVfPfName())
                ? nic.getVfPfName()
                : VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);

        // Stamp the bridge-wide tc-policy on the first OVN-aware plug per
        // JVM (idempotent latch).
        OvnNicTunableApplier.applyTcPolicyOnce(nic.getOvsTcPolicy());
        // Switchdev mlx5 PF refuses `ip link set vf vlan N` — OVN inserts
        // the Geneve VNI in its pipeline instead. Pass null to skip the
        // PF-side VLAN config and avoid "Operation not supported".
        final Deque<Runnable> rollback = new ArrayDeque<>();
        final String preLockRepName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
        if (preLockRepName != null) {
            clearOrphanRepsForLspName(nic.getOvnLspName(), preLockRepName);
        }
        final java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(pciAddress);
        lifecycleLock.lock();
        try {
            configureVfOnPfNoVlan(pfName, vfId, nic.getMac());
            // Apply operator-resolved VF tunables (trust / spoofchk /
            // link state / max/min tx_rate / qos) on top of the bare
            // configureVfOnPfNoVlan baseline. Tunables that are null on
            // NicTO are skipped — preserving the historical defaults.
            OvnNicTunableApplier.applyVfTunables(nic, pfName, vfId);
            final String pfFinal = pfName;
            final Integer vfIdFinal = vfId;
            rollback.push(() -> {
                if (pfFinal != null && vfIdFinal != null) {
                    Script.runSimpleBashScript(String.format(
                        "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfFinal, vfIdFinal));
                }
            });

            final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName == null) {
                throw new InternalErrorException(String.format(
                    "OvnVfPassthroughVifDriver: representor not found for VF %s; is mlx5 in switchdev mode?",
                    pciAddress));
            }
            attachRepresentorToBrInt(repName, nic.getOvnLspName(), nic.getMac(), nic.getOvsHairpin(), pciAddress);
            final String repFinal = repName;
            rollback.push(() -> OvnVifDriver.freeRepresentorOnOvsLocked(logger,
                    "OvnVfPassthroughVifDriver.rollback", repFinal, pciAddress, nic.getOvnLspName()));

            final InterfaceDef intf = new InterfaceDef();
            // xmlVlanTag=0 → no <vlan> element in domain XML; OVN handles
            // tenant separation in its pipeline.
            intf.defHostdevNet(pciAddress, nic.getMac(), 0);
            intf.setLinkStateUp(nic.isEnabled());

            logger.info("OvnVfPassthroughVifDriver.plug: pci={} pf={} mac={} rep={} lsp={} bridge={}",
                    pciAddress, pfName, nic.getMac(), repName, nic.getOvnLspName(), integrationBridge);
            return intf;
        } catch (RuntimeException | InternalErrorException ex) {
            drainRollback(rollback);
            throw ex;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void unplug(final InterfaceDef iface, final boolean delete) {
        // Stop-path fan-out calls every VifDriver for every InterfaceDef
        // (getAllVifDrivers); only act on the hostdev NICs this driver owns.
        if (iface == null || iface.getNetType() != InterfaceDef.GuestNetType.HOSTDEV) {
            return;
        }
        String pciAddress = iface.getPciAddress();
        final String mac = iface.getMacAddress();
        if (StringUtils.isNotBlank(pciAddress)) {
            final java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(pciAddress);
            lifecycleLock.lock();
            try {
            final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName != null) {
                OvnVifDriver.freeRepresentorOnOvsLocked(logger, "OvnVfPassthroughVifDriver.unplug",
                        repName, pciAddress);
            } else {
                logger.warn("OvnVfPassthroughVifDriver.unplug: exact representor not found for pci={} mac={}; skipping OVS cleanup",
                        pciAddress, mac);
            }
            final String pfName = VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
            final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
            if (pfName != null && vfId != null) {
                Script.runSimpleBashScript(String.format(
                    "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
            }
            } finally {
                lifecycleLock.unlock();
            }
        } else {
            logger.warn("OvnVfPassthroughVifDriver.unplug: no explicit VF PCI for mac={}; attempting exact attached-mac stale-representor cleanup",
                    mac);
            OvnVifDriver.clearOrphanRepsByAttachedMac(logger, "OvnVfPassthroughVifDriver.unplug",
                    integrationBridge, mac);
        }
    }

    @Override
    public void attach(final InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-attach is not supported for OVN VF passthrough");
    }

    @Override
    public void detach(final InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-detach is not supported for OVN VF passthrough");
    }

    @Override
    public void deleteBr(final NicTO nic) {
        // Integration bridge is shared infra — never auto-deleted.
    }

    @Override
    public void createControlNetwork(final String privBrName) {
        // Control NIC uses tap/bridge; OVN VF path never synthesizes one.
    }

    @Override
    public boolean isExistingBridge(final String bridgeName) {
        return integrationBridge.equals(bridgeName);
    }

    /**
     * MAC + trust + spoofchk on the VF, no VLAN. The PF-side VLAN op is
     * intentionally omitted: in mlx5 switchdev mode the e-switch rejects
     * it and OVN owns segmentation via the Geneve overlay anyway.
     */
    private void configureVfOnPfNoVlan(final String pfName, final Integer vfId, final String macAddr) {
        if (pfName == null || vfId == null) {
            logger.warn("OvnVfPassthroughVifDriver: cannot configure VF on PF: pf={} vfId={}", pfName, vfId);
            return;
        }
        if (StringUtils.isNotBlank(macAddr)) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d mac %s", pfName, vfId, macAddr));
        }
        Script.runSimpleBashScript(String.format("ip link set %s vf %d trust on", pfName, vfId));
        Script.runSimpleBashScript(String.format("ip link set %s vf %d spoofchk off", pfName, vfId));
    }

    /**
     * Add the VF representor to {@code br-int} and stamp the OVN binding
     * external_ids on it. {@code iface-id} is the contract OVN consults to
     * bind the {@code Port_Binding} row; {@code attached-mac} +
     * {@code iface-status=active} surface the port to OVN diagnostics.
     */
    private void attachRepresentorToBrInt(final String repName, final String lspName, final String mac) {
        attachRepresentorToBrInt(repName, lspName, mac, null);
    }

    /**
     * Add the VF representor to {@code br-int}, stamp the OVN binding
     * external_ids and (when non-null) the per-port hairpin flag. Hairpin
     * is gated to the resolved value for this NIC (default {@code true}
     * via {@link com.cloud.network.ovn.config.OvnNicConfig#OvsHairpin});
     * a {@code null} skips the stamp for wire compat with older mgmt.
     *
     * <p>Before adding the port, two guards are applied to prevent duplicate
     * {@code iface-id} conflicts in ovn-controller:
     * <ol>
     *   <li><b>Cross-rep guard</b> ({@link #clearOrphanRepsForLspName}): a
     *       different representor is removed only when it is explicitly
     *       destination-owned and inactive; active or unproven ownership
     *       fails closed.</li>
     *   <li><b>Same-rep guard (DEF-1)</b>: {@code external_ids} on the target
     *       representor itself are cleared before stamping the new iface-id.
     *       This handles the case where the same representor was previously used
     *       by a different VM and still holds a stale iface-id from that prior
     *       session — a stale that {@link #clearOrphanRepsForLspName} would not
     *       find because the old lspName differs from the new one.</li>
     * </ol>
     * Without these guards an unclean shutdown (libvirt destroy that zeroed
     * the VF MAC before unplug could resolve the rep) triggers:
     * <pre>
     *   binding|WARN|Invalid configuration: iface-id is configured on
     *   interfaces: [dx6p1vf6] and [dx6p0vf4]. Ignoring the configuration
     *   on interface [dx6p0vf4]
     * </pre>
     * which pushes one of the two reps out of the OVN dataplane.
     */
    private void attachRepresentorToBrInt(final String repName, final String lspName, final String mac,
                                           final Boolean hairpin) {
        attachRepresentorToBrInt(repName, lspName, mac, hairpin, null);
    }

    private void attachRepresentorToBrInt(final String repName, final String lspName, final String mac,
                                          final Boolean hairpin, final String bdf) {
        if (StringUtils.isBlank(bdf)) {
            clearOrphanRepsForLspName(lspName, repName);
        }
        // DEF-1: pre-clear any stale external_ids on this specific representor
        // before stamping the new iface-id. clearOrphanRepsForLspName only
        // removes reps carrying the same lspName; if this rep was previously
        // used by a different VM it may still hold an old iface-id that would
        // create a duplicate-iface-id conflict in ovn-controller.
        if (StringUtils.isBlank(bdf)) {
            OvnVifDriver.prepareRepresentorForAttach(logger, "OvnVfPassthroughVifDriver.attach",
                    repName, lspName);
        } else {
            OvnVifDriver.prepareRepresentorForAttachLocked(logger, "OvnVfPassthroughVifDriver.attach",
                    repName, bdf, lspName);
        }
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl --may-exist add-port %s %s", integrationBridge, repName));
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl set Interface %s external_ids:iface-id=%s", repName, lspName));
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl set Interface %s external_ids:migration-owner=destination", repName));
        if (StringUtils.isNotBlank(mac)) {
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl set Interface %s external_ids:attached-mac=%s", repName, mac));
        }
        // Stamp iface-status=active AND ovn-installed=true together. The
        // ovn-installed flag is the contract ovn-controller normally sets after
        // it claims the Port_Binding for a freshly-plugged port; on live-
        // migration the chassis owns the same binding and ovn-controller does
        // NOT re-fire the bind path, so without explicit ovn-installed=true
        // the OpenFlow ingress action stays unset and forwarding silently
        // breaks (Bug 26). Stamping agent-side is idempotent on fresh plug.
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl set Interface %s "
                    + "external_ids:iface-status=active "
                    + "external_ids:ovn-installed=true",
            repName));
        OvnNicTunableApplier.applyHairpin(repName, hairpin);
        logger.info("OvnVfPassthroughVifDriver: attached rep={} to {} with iface-id={} status=active "
                        + "ovn-installed=true hairpin={}",
                repName, integrationBridge, lspName, hairpin);
    }

    /**
     * Clear any orphan OVS interface that already carries the target
     * {@code iface-id} before we stamp it on {@code keepRepName}. Without
     * this, a previous unclean shutdown that left a stale {@code iface-id}
     * on a different VF representor would race with this stamp and trigger
     * the {@code "iface-id is configured on interfaces"} WARN in
     * {@code ovn-controller}, pushing one of the two reps out of the
     * dataplane.
     *
     * <p>When {@code keepRepName} is {@code null} every interface carrying
     * {@code lspName} is removed — useful for cleanup callers that do not
     * own a specific target representor.
     *
     * <p>Idempotent — safe to call when no orphan exists.
     */
    private void clearOrphanRepsForLspName(final String lspName, final String keepRepName) {
        if (StringUtils.isBlank(lspName)) {
            return;
        }
        final String findCmd = String.format(
            "ovs-vsctl --no-headings --columns=name find Interface external_ids:iface-id=%s 2>/dev/null",
            lspName);
        final String found = Script.runSimpleBashScript(findCmd);
        if (StringUtils.isBlank(found)) {
            return;
        }
        for (final String raw : found.split("\\R")) {
            final String name = raw.trim().replaceAll("^\"|\"$", "");
            if (StringUtils.isBlank(name) || name.equals(keepRepName)) {
                continue;
            }
            final String ids = Script.runSimpleBashScript(String.format(
                "ovs-vsctl get Interface %s external_ids 2>/dev/null", name));
            final boolean destinationOwned = ids.contains("migration-owner=destination")
                    || ids.contains("migration-owner=\"destination\"");
            final boolean inactive = ids.contains("iface-status=inactive")
                    || ids.contains("iface-status=\"inactive\"");
            if (!destinationOwned || !inactive) {
                throw new CloudRuntimeException("refusing to remove representor with unproven ownership: " + name);
            }
            OvnVifDriver.freeRepresentorOnOvs(logger, "OvnVfPassthroughVifDriver.orphan", name);
            logger.info("OvnVfPassthroughVifDriver: cleared orphan rep={} carrying stale iface-id={} (kept rep={})",
                    name, lspName, keepRepName);
        }
    }

    /**
     * Cleanup stale OVS representors whose {@code external_ids:iface-id}
     * matches the given OVN logical switch port name. Useful for cleanup
     * paths where the VF MAC has already been zeroed by libvirt and the
     * standard {@link #unplug} cannot resolve the rep via PCI/MAC reverse
     * lookup.
     *
     * <p>Idempotent — safe to call when no orphan exists.
     */
    public void cleanupStaleRepsByLspName(final String lspName) {
        clearOrphanRepsForLspName(lspName, null);
    }

    private void drainRollback(final Deque<Runnable> rollback) {
        while (!rollback.isEmpty()) {
            try {
                rollback.pop().run();
            } catch (RuntimeException e) {
                logger.warn("OvnVfPassthroughVifDriver rollback step failed: {}", e.getMessage());
            }
        }
    }
}
