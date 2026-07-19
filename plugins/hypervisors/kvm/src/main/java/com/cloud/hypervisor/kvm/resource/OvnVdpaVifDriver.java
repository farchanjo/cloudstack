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
        // Stamp the bridge-wide tc-policy on the first OVN-aware plug per
        // JVM (idempotent latch).
        OvnNicTunableApplier.applyTcPolicyOnce(nic.getOvsTcPolicy());

        final String pfName = StringUtils.isNotBlank(nic.getVfPfName())
                ? nic.getVfPfName()
                : VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
        final int maxVqs = nic.getVdpaMaxVqs() != null ? nic.getVdpaMaxVqs() : 33;

        final Deque<Runnable> rollback = new ArrayDeque<>();
        final java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(pciAddress);
        lifecycleLock.lock();
        try {
            // (1) PF-side VF identity: MAC + trust + spoofchk, NO VLAN.
            //     Switchdev mlx5 rejects PF-side VLAN; OVN owns segmentation.
            configureVfOnPfNoVlan(pfName, vfId, mac);
            // Apply operator-resolved VF tunables (trust / spoofchk /
            // link state / max_tx_rate / min_tx_rate / qos). On switchdev
            // most kernels reject vlan/qos here; the helper logs and
            // continues, so plug never aborts on operator typos.
            OvnNicTunableApplier.applyVfTunables(nic, pfName, vfId);
            final String pfFinal = pfName;
            final Integer vfIdFinal = vfId;
            rollback.push(() -> {
                if (pfFinal != null && vfIdFinal != null) {
                    Script.runSimpleBashScript(String.format(
                        "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfFinal, vfIdFinal));
                }
            });

            // (2) Create the vDPA SF on top of the VF. Append optional
            //     vDPA feature flags resolved from OvnNicConfig: event_idx,
            //     indirect_desc, iommu, packed. mlx5 accepts these as
            //     {@code <feature> on/off} suffixes; older kernels ignore
            //     unknown flags.
            final String vdpaName = VdpaVifDriver.buildVdpaName(nic);
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            final String vdpaAddCmd = buildVdpaAddCommand(vdpaName, pciAddress, mac, maxVqs, nic);
            Script.runSimpleBashScript(vdpaAddCmd);
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
            //     iface-status is set inactive here; the post-start hook in
            //     LibvirtComputingResource.applyOvnPostPlugTunables cycles it
            //     to active after vhost-vdpa queue negotiation completes, which
            //     prevents the mlx5 TC chain-0/chain-1 race (Bug 16 + 17).
            final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName == null) {
                throw new InternalErrorException(String.format(
                    "OvnVdpaVifDriver: representor not found for VF %s; mlx5 switchdev required",
                    pciAddress));
            }
            // Cache rep name on the NicTO so applyOvnPostPlugTunables can
            // retrieve it without re-deriving from PCI after VM start.
            nic.setVfRepName(repName);
            attachRepresentorToBrInt(repName, nic.getOvnLspName(), mac, nic.getOvsHairpin());
            final String repFinal = repName;
            rollback.push(() -> OvnVifDriver.freeRepresentorOnOvs(
                    logger, "OvnVdpaVifDriver.plug-rollback", repFinal));

            // (4) Domain XML <interface type='vdpa'>. queues defaults to
            //     max_vqs / 2 (TX+RX pair count); operator can override
            //     with ovn.vdpa.queue_pairs. tx_queue_size / rx_queue_size
            //     map to the libvirt <driver/> attributes.
            final InterfaceDef intf = new InterfaceDef();
            final Integer queues = resolveQueuePairs(nic, maxVqs);
            intf.defVdpaNet(vhostDev, mac, queues);
            intf.setLinkStateUp(nic.isEnabled());
            // Stamp queue depth + packed vq + driver name on the InterfaceDef.
            OvnNicTunableApplier.applyInterfaceDefTunables(nic, intf);

            logger.info("OvnVdpaVifDriver.plug: name={} pci={} pf={} mac={} rep={} lsp={} vhost={} maxVqs={} queues={} bridge={}",
                    vdpaName, pciAddress, pfName, mac, repName, nic.getOvnLspName(),
                    vhostDev, maxVqs, queues, integrationBridge);
            return intf;
        } catch (RuntimeException | InternalErrorException ex) {
            drainRollback(rollback);
            throw ex;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Build the {@code vdpa dev add} command line, appending optional
     * feature flags (event_idx, indirect_desc, iommu, packed) when the
     * caller provided non-null tunables on {@link NicTO}.
     *
     * <p>Linux 6.x mlx5_vdpa accepts these flags inline; older kernels
     * may reject unknown tokens — caller is responsible for matching the
     * tunable surface to deployed kernel support.
     */
    private static String buildVdpaAddCommand(final String vdpaName, final String pciAddress,
                                              final String mac, final int maxVqs, final NicTO nic) {
        // iproute2 vdpa CLI expects {@code max_vqp} (max queue PAIRS), NOT
        // {@code max_vqs} (total virtqueues). Older fork code passed the
        // raw total, which iproute2 rejects with
        //   "Unknown option \"max_vqs\""
        // and aborts the whole {@code vdpa dev add} call. Convert the
        // canonical 33 (16 TX + 16 RX + 1 ctrl) to 16 queue pairs by
        // dropping the ctrl_vq and halving. Cap at 1 so legacy configs
        // (max_vqs=1 or 2) still produce a valid command.
        final int maxVqp = Math.max(1, (maxVqs - 1) / 2);
        final StringBuilder cmd = new StringBuilder()
                .append("vdpa dev add name ").append(vdpaName)
                .append(" mgmtdev pci/").append(pciAddress)
                .append(" mac ").append(mac)
                .append(" max_vqp ").append(maxVqp);
        appendIfSet(cmd, "event_idx",     nic.getVdpaEventIdx());
        appendIfSet(cmd, "indirect_desc", nic.getVdpaIndirectDesc());
        appendIfSet(cmd, "iommu",         nic.getVdpaIommu());
        appendIfSet(cmd, "packed",        nic.getVdpaPacked());
        return cmd.toString();
    }

    private static void appendIfSet(final StringBuilder sb, final String token, final Boolean value) {
        if (value == null) {
            return;
        }
        sb.append(' ').append(token).append(' ').append(Boolean.TRUE.equals(value) ? "on" : "off");
    }

    /**
     * Resolve the queue-pair count for the libvirt {@code <driver queues='N'/>}
     * attribute. Operator override {@code ovn.vdpa.queue_pairs} wins; otherwise
     * we default to {@code maxVqs / 2} (the historical CloudStack default).
     */
    private static Integer resolveQueuePairs(final NicTO nic, final int maxVqs) {
        if (nic.getVdpaQueuePairs() != null && nic.getVdpaQueuePairs() > 0) {
            return nic.getVdpaQueuePairs();
        }
        return maxVqs > 1 ? maxVqs / 2 : null;
    }

    @Override
    public void unplug(final InterfaceDef iface, final boolean delete) {
        // Stop-path fan-out calls every VifDriver for every InterfaceDef
        // (getAllVifDrivers); only act on the vDPA NICs this driver owns.
        if (iface == null || iface.getNetType() != InterfaceDef.GuestNetType.VDPA) {
            return;
        }
        final String mac = iface.getMacAddress();
        final String vhostDev = iface.getBrName(); // _sourceName carries /dev/vhost-vdpaN
        logger.info("OvnVdpaVifDriver.unplug ENTRY: vhost={} mac={} netType={} delete={}",
                vhostDev, mac, iface.getNetType(), delete);

        // (1) vdpa dev del — match by /dev path first, fall back to vdpa-name
        //     scan via VdpaVifDriver helpers.
        String vdpaName = VdpaVifDriver.lookupVdpaNameByVhostDev(vhostDev);
        final String pciAddress = VdpaVifDriver.lookupVdpaPciByName(vdpaName);
        if (StringUtils.isBlank(pciAddress)) {
            logger.warn("OvnVdpaVifDriver.unplug: vDPA name {} did not resolve to an exact VF BDF; fail-closed",
                    vdpaName);
            return;
        }
        final java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(pciAddress);
        lifecycleLock.lock();
        try {
        if (StringUtils.isNotBlank(vdpaName)) {
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            logger.info("OvnVdpaVifDriver.unplug: deleted vdpa dev {}", vdpaName);
        } else {
            logger.warn("OvnVdpaVifDriver.unplug: could not resolve vdpa name from vhost={}; skipping vdpa dev del", vhostDev);
        }

        // (2) Free the representor for OVN reuse: clear external_ids (iface-id /
        //     attached-mac / iface-status) and del-port in a bridge-agnostic way
        //     so a wrong integration-bridge name cannot leave a live OVN binding
        //     after destroy/expunge (Chaos B). Cleanup is fail-closed unless
        //     the vhost device resolves to one exact vDPA management BDF.
        final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName != null) {
                OvnVifDriver.freeRepresentorOnOvs(logger, "OvnVdpaVifDriver.unplug", repName);
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

    /** Delegates to the hairpin-aware overload with {@code hairpin=null}. */
    private void attachRepresentorToBrInt(final String repName, final String lspName, final String mac) {
        attachRepresentorToBrInt(repName, lspName, mac, null);
    }

    /**
     * Attach the VF representor to the OVN integration bridge and stamp its
     * OVN binding external_ids. {@code hairpin=null} keeps the port untouched.
     *
     * <p><b>TC offload chain-0/chain-1 race (Bug 16 + Bug 17)</b>: mlx5
     * switchdev programs TC offload in two phases — chain&nbsp;0 (conntrack
     * entry, NAT pipe + goto chain&nbsp;1) fires as soon as
     * {@code iface-status=active} is observed by ovn-controller; chain&nbsp;1
     * (packet-forward rules) fires only after the vhost-vdpa queues complete
     * kernel-space negotiation (visible as {@code VF_VPORT_METADATA_ACTIVE}
     * in the mlx5 e-switch). If {@code iface-status} is stamped active at
     * {@code plug()} time (before the domain reaches running state), chain&nbsp;0
     * installs successfully but chain&nbsp;1 stays empty — every
     * TCP/UDP packet hits {@code goto chain 1} → hardware drop. Only ICMP
     * escapes because it is not matched by chain&nbsp;0. DHCP broadcasts are
     * also dropped (Bug&nbsp;16); TCP never establishes (Bug&nbsp;17).
     *
     * <p>The fix: stamp {@code iface-status=inactive} here and let
     * {@link com.cloud.hypervisor.kvm.resource.LibvirtComputingResource#applyOvnPostPlugTunables}
     * cycle it to {@code active} after the VM reaches the running state,
     * mirroring the proven Bug-14 TAP pattern in {@link OvnVifDriver}.
     * The representor name is cached on the NicTO ({@code setVfRepName}) by
     * the caller so the post-start hook can retrieve it without a PCI scan.
     */
    private void attachRepresentorToBrInt(final String repName, final String lspName, final String mac,
                                           final Boolean hairpin) {
        clearOrphanRepsForLspName(lspName, repName);
        // DEF-1: a representor can retain an iface-id from a previous VM even
        // when that stale value does not match the current LSP. Clear the
        // complete external_ids map before stamping the new identity.
        Script.runSimpleBashScript(String.format(
                "ovs-vsctl --if-exists clear Interface %s external_ids", repName));
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
        // Deferred-active pattern: set inactive at plug time; post-start hook
        // cycles to active after vhost-vdpa queue negotiation completes.
        // This prevents mlx5 TC chain-0/chain-1 offload race (Bug 16 + 17).
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl set Interface %s external_ids:iface-status=inactive", repName));
        logger.info("OvnVdpaVifDriver.attachRepresentorToBrInt: rep={} lsp={} stamped inactive"
                + " (deferred-active: post-start hook will cycle to active after vDPA queue negotiation)",
                repName, lspName);
        OvnNicTunableApplier.applyHairpin(repName, hairpin);
    }

    private void clearOrphanRepsForLspName(final String lspName, final String keepRepName) {
        if (StringUtils.isBlank(lspName)) {
            return;
        }
        final String found = Script.runSimpleBashScript(String.format(
                "ovs-vsctl --no-headings --columns=name find Interface external_ids:iface-id=%s 2>/dev/null",
                lspName));
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
                throw new CloudRuntimeException(String.format(
                        "duplicate active/source representor claim cannot be proven orphaned: rep=%s iface-id=%s target=%s",
                        name, lspName, keepRepName));
            }
            OvnVifDriver.freeRepresentorOnOvs(logger, "OvnVdpaVifDriver.orphan", name);
            logger.warn("Removed provably orphaned inactive destination representor {} for LSP {}", name, lspName);
        }
    }

    /**
     * Release the destination VF allocation that was established during a
     * {@code PrepareForMigration} call that subsequently failed (migration
     * aborted before or during transfer).
     *
     * <p>Mirrors the cleanup in {@link #unplug} but operates from a NicTO
     * alone, without requiring a live {@link InterfaceDef}: the vdpa-device
     * name is re-derived from the NIC MAC (same algorithm as
     * {@link VdpaVifDriver#buildVdpaName(NicTO)}), the vhost-vdpa cdev index
     * is resolved via sysfs, the representor is removed from {@code br-int},
     * and the PF-side VF identity is cleared.
     *
     * @param nic the NicTO whose VF was allocated on this host during prepare.
     */
    public void releaseVdpaOnRollback(final NicTO nic) {
        if (nic == null || !nic.isUseVdpa()) {
            return;
        }
        RuntimeException cleanupFailure = null;
        final String vdpaName = VdpaVifDriver.buildVdpaName(nic);
        logger.info("OvnVdpaVifDriver.releaseVdpaOnRollback: releasing vdpa={} mac={}", vdpaName, nic.getMac());
        try {
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
        } catch (RuntimeException e) {
            cleanupFailure = e;
        }

        final String pciAddress = nic.getVfPciAddress();
        if (StringUtils.isNotBlank(pciAddress)) {
            try {
                removeRepresentorAndClearVf(pciAddress);
            } catch (RuntimeException e) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, e);
            }
        } else {
            try {
                removeDestinationOwnedRepresentor(nic);
            } catch (RuntimeException e) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, e);
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private void removeDestinationOwnedRepresentor(final NicTO nic) {
        if (StringUtils.isBlank(nic.getUuid())) {
            throw new CloudRuntimeException("cannot identify destination representor during vDPA rollback");
        }
        final String lsp = "lsp-" + nic.getUuid();
        final String found = Script.runSimpleBashScript(String.format(
                "ovs-vsctl --no-headings --columns=name find Interface external_ids:iface-id=%s 2>/dev/null", lsp));
        for (final String raw : found.split("\\R")) {
            final String rep = raw.trim().replaceAll("^\"|\"$", "");
            if (StringUtils.isBlank(rep)) {
                continue;
            }
            final String ids = Script.runSimpleBashScript(String.format(
                    "ovs-vsctl get Interface %s external_ids 2>/dev/null", rep));
            final boolean owned = ids.contains("migration-owner=destination")
                    || ids.contains("migration-owner=\"destination\"");
            final boolean inactive = ids.contains("iface-status=inactive")
                    || ids.contains("iface-status=\"inactive\"");
            if (!owned || !inactive) {
                throw new CloudRuntimeException("refusing to remove non-destination-owned representor " + rep);
            }
            OvnVifDriver.freeRepresentorOnOvs(logger, "OvnVdpaVifDriver.rollback", rep);
        }
    }

    private RuntimeException appendCleanupFailure(final RuntimeException primary, final RuntimeException next) {
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    private void removeRepresentorAndClearVf(final String pciAddress) {
        final String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
        if (repName != null) {
            OvnVifDriver.freeRepresentorOnOvs(logger, "OvnVdpaVifDriver.releaseVdpaOnRollback", repName);
        }
        final String pfName = VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
        if (pfName != null && vfId != null) {
            Script.runSimpleBashScript(String.format(
                "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
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
