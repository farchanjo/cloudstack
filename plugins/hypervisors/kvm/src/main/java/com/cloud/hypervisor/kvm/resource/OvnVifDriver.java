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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.hwoffload.VdpaPoolReconciler;
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

    /** sysfs PCI devices root used by FREE-VF residual reconcile. */
    private static final Path SYS_PCI_DEVICES = Paths.get("/sys/bus/pci/devices");

    /** Driver name that marks a VF as hostdev-passthrough ALLOCATED. */
    private static final String DRV_VFIO = "vfio-pci";

    /**
     * Free a VF representor for reuse: neutralize OVN binding
     * ({@code external_ids} including {@code iface-id}/{@code attached-mac}/
     * {@code iface-status}) then remove the port from whichever bridge it is
     * on.
     *
     * <p><b>Bridge-agnostic {@code del-port}</b>: callers historically issued
     * {@code ovs-vsctl --if-exists del-port &lt;bridge&gt; &lt;rep&gt;}. When
     * {@code bridge} was wrong (fleet {@code br-overlay} vs driver default
     * {@code br-int}), {@code --if-exists} silently no-op'd and left the
     * Interface row stamped {@code iface-id=lsp-... iface-status=active}
     * after destroy/expunge (Chaos B). Omitting the bridge argument deletes
     * the port from any bridge; clearing {@code external_ids} first still
     * drops the OVN binding even if del-port fails for another reason.
     *
     * <p>Idempotent — safe when the Interface/port is already gone.
     *
     * <p>Public so {@code HostVfPurgeOrphans} / startup residual reconcile can
     * share the exact unplug free path.
     */
    public static void freeRepresentorOnOvs(final Logger log, final String callerLabel, final String repName) {
        if (StringUtils.isBlank(repName)) {
            return;
        }
        final String bdf = resolveVfPciFromRepresentor(repName);
        if (StringUtils.isBlank(bdf)) {
            log.warn("{}: representor BDF is unknown; refusing unfenced CAS for {}", callerLabel, repName);
            return;
        }
        final java.util.concurrent.locks.ReentrantLock lock = VfHostLifecycleLock.forBdf(bdf);
        lock.lock();
        try {
        if (!OvsRepresentorCas.remove(OvnVifDriver::runOvsdb, "unix:/var/run/openvswitch/db.sock", repName, null)) {
            throw new IllegalStateException(callerLabel + ": OVS representor CAS failed for " + repName);
        }
        log.info("{}: freed OVS representor {} by UUID-bound CAS", callerLabel, repName);
        } finally {
            lock.unlock();
        }
    }

    /** Checked variant used when management requires positive cleanup evidence. */
    public static boolean freeRepresentorOnOvsChecked(final Logger log, final String callerLabel, final String repName) {
        if (StringUtils.isBlank(repName)) {
            return true;
        }
        final String bdf = resolveVfPciFromRepresentor(repName);
        if (StringUtils.isBlank(bdf)) {
            log.warn("{}: representor BDF is unknown; refusing unfenced CAS for {}", callerLabel, repName);
            return false;
        }
        final java.util.concurrent.locks.ReentrantLock lock = VfHostLifecycleLock.forBdf(bdf);
        lock.lock();
        try {
        if (!OvsRepresentorCas.remove(OvnVifDriver::runOvsdb, "unix:/var/run/openvswitch/db.sock", repName, null)) {
            log.warn("{}: failed to free OVS representor {} by CAS", callerLabel, repName);
            return false;
        }
        log.info("{}: freed OVS representor {} by UUID-bound CAS", callerLabel, repName);
        return true;
        } finally {
            lock.unlock();
        }
    }

    private static OvsRepresentorCas.Result runOvsdb(final String... argv) {
        try {
            final String output = Script.executeCommand(argv);
            return output == null ? new OvsRepresentorCas.Result(false, "", "no OVSDB output")
                    : new OvsRepresentorCas.Result(true, output, "");
        } catch (RuntimeException e) {
            return new OvsRepresentorCas.Result(false, "", e.getMessage());
        }
    }

    /** Matches {@code <mac address='..'/>} / {@code ".." } in virsh dumpxml. */
    private static final Pattern MAC_IN_DOMAIN_XML = Pattern.compile(
            "mac\\s+address=['\"]([0-9a-fA-F:]{17})['\"]", Pattern.CASE_INSENSITIVE);

    /**
     * Residual Chaos-B heal: free switchdev VF representors that still carry
     * {@code external_ids:iface-id} when no <em>running</em> libvirt domain
     * owns the representor's {@code attached-mac}.
     *
     * <p>Failed CKS/systemvm starts and domain-gone stops often leave the VF
     * on {@code vfio-pci} or with a leftover {@code vdpa-*} mgmtdev even though
     * the guest is dead. The old "kernel-FREE only" gate skipped those orphans
     * forever. Ownership is now decided by live domain MAC inventory:
     * <ul>
     *   <li>attached-mac present and <b>not</b> in any running domain → free
     *       OVS rep + clear VF identity + best-effort {@code vdpa dev del}
     *       (even if PCI still shows vfio/vDPA leftovers)</li>
     *   <li>attached-mac present and owned by a live domain → keep
     *       ({@code skippedAllocated})</li>
     *   <li>no attached-mac → legacy kernel-FREE gate only
     *       ({@link #isSafeToFreeStaleRep})</li>
     * </ul>
     *
     * <p>vnet / TAP interfaces (no {@code phys_port_name=pfNvfM}) are skipped
     * — they are live guest taps and must keep their iface-id.
     *
     * @param log caller logger
     * @param callerLabel log prefix (eg. {@code HostVfPurgeOrphans})
     * @param dryRun when true, report only — no ovs-vsctl mutations
     * @return summary counts + freed names (capped)
     */
    static FreeStaleOvsResult freeStaleFreeVfRepresentors(final Logger log, final String callerLabel,
                                                           final boolean dryRun) {
        final FreeStaleOvsResult result = new FreeStaleOvsResult();
        final String listCmd = "ovs-vsctl --no-headings --bare --columns=name find Interface "
                + "external_ids:iface-id!=[] 2>/dev/null";
        final String raw = Script.runSimpleBashScriptWithFullResult(listCmd, 30);
        final List<String> candidates = parseOvsIfaceNames(raw);
        result.scanned = candidates.size();
        if (candidates.isEmpty()) {
            return result;
        }

        // null = inventory incomplete (virsh failed) → refuse free-by-MAC so we
        // never del-port a live guest's rep because of a partial domain list.
        final Set<String> liveMacs = collectLiveDomainMacs();
        final Set<String> vdpaPci = listVdpaMgmtPciAddresses(log);
        if (log != null && log.isDebugEnabled()) {
            log.debug("{}: live domain MACs known={} size={} ({})",
                    callerLabel, liveMacs != null, liveMacs == null ? -1 : liveMacs.size(), liveMacs);
            log.debug("{}: vDPA mgmtdev PCI set size={} ({})", callerLabel, vdpaPci.size(), vdpaPci);
        }

        for (final String iface : candidates) {
            if (!isVfRepresentor(iface)) {
                result.skippedNonRep++;
                continue;
            }
            final String vfPci = resolveVfPciFromRepresentor(iface);
            if (StringUtils.isBlank(vfPci)) {
                log.debug("{}: cannot resolve VF PCI for rep={}; skipping", callerLabel, iface);
                result.skippedUnresolved++;
                continue;
            }
            final String driver = readPciDriver(vfPci);
            // lower-case: sysfs + parseVdpaDevShowPci normalize; virtfn can vary
            final boolean hasVdpa = vdpaPci.contains(vfPci.toLowerCase());
            final String attachedMac = readAttachedMac(iface);

            // Primary path: MAC ownership from running domains. Domain-dead
            // leftovers keep vfio/vDPA on the PCI BDF — still free them.
            // Only when live inventory is complete (non-null).
            if (StringUtils.isNotBlank(attachedMac) && liveMacs != null) {
                if (liveMacs.contains(attachedMac)) {
                    result.skippedAllocated++;
                    log.debug("{}: keep rep={} mac={} pci={} (live domain owns MAC)",
                            callerLabel, iface, attachedMac, vfPci);
                    continue;
                }
                if (dryRun) {
                    result.freed++;
                    if (result.freedNames.size() < 64) {
                        result.freedNames.add(iface);
                    }
                    continue;
                }
                try {
                    freeRepresentorOnOvs(log, callerLabel, iface);
                    clearVfIdentityForRepBestEffort(log, callerLabel, iface);
                    if (hasVdpa) {
                        deleteVdpaDevsForPciBestEffort(log, callerLabel, vfPci);
                    }
                    result.freed++;
                    if (result.freedNames.size() < 64) {
                        result.freedNames.add(iface);
                    }
                } catch (RuntimeException re) {
                    log.warn("{}: failed to free orphan rep={} mac={}: {}",
                            callerLabel, iface, attachedMac, re.getMessage());
                }
                continue;
            }

            // No attached-mac stamp, or live-domain inventory unknown: only free
            // when the VF is kernel-FREE (legacy safe path — never free vfio/vDPA).
            if (!isSafeToFreeStaleRep(driver, hasVdpa)) {
                result.skippedAllocated++;
                log.debug("{}: keep rep={} pci={} driver={} hasVdpa={} mac={} liveKnown={} (ALLOCATED/unknown)",
                        callerLabel, iface, vfPci, driver, hasVdpa, attachedMac, liveMacs != null);
                continue;
            }
            if (dryRun) {
                result.freed++;
                if (result.freedNames.size() < 64) {
                    result.freedNames.add(iface);
                }
                continue;
            }
            try {
                freeRepresentorOnOvs(log, callerLabel, iface);
                result.freed++;
                if (result.freedNames.size() < 64) {
                    result.freedNames.add(iface);
                }
            } catch (RuntimeException re) {
                log.warn("{}: failed to free stale FREE rep={}: {}", callerLabel, iface, re.getMessage());
            }
        }
        log.info("{}: freeStaleFreeVfRepresentors scanned={} freed={} skippedNonRep={} "
                        + "skippedAllocated={} skippedUnresolved={} dryRun={}",
                callerLabel, result.scanned, result.freed, result.skippedNonRep,
                result.skippedAllocated, result.skippedUnresolved, dryRun);
        return result;
    }

    /**
     * MACs of NICs on currently running libvirt domains
     * ({@code virsh list --state-running} + {@code dumpxml}). Lower-cased.
     *
     * <p>Returns:
     * <ul>
     *   <li>empty set — no running domains (safe to free all orphan MACs)</li>
     *   <li>non-empty set — complete inventory of live guest MACs</li>
     *   <li>{@code null} — inventory incomplete (virsh failed); caller must
     *       <b>not</b> free-by-MAC (would risk live guests)</li>
     * </ul>
     * Package-private for unit tests of the pure XML parser path.
     */
    static Set<String> collectLiveDomainMacs() {
        final String list;
        try {
            // FullResult: OneLineParser would drop every domain after the first.
            list = Script.runSimpleBashScriptWithFullResult(
                    "virsh list --name --state-running 2>/dev/null", 10);
        } catch (RuntimeException re) {
            return null;
        }
        final Set<String> macs = new HashSet<>();
        if (StringUtils.isBlank(list)) {
            return macs;
        }
        for (final String line : list.split("\\R")) {
            final String dom = line.trim();
            if (StringUtils.isBlank(dom)) {
                continue;
            }
            final String xml;
            try {
                xml = Script.runSimpleBashScriptWithFullResult(
                        "virsh dumpxml " + dom + " 2>/dev/null", 15);
            } catch (RuntimeException re) {
                // Incomplete inventory — refuse free-by-MAC this pass.
                return null;
            }
            if (xml == null) {
                return null;
            }
            macs.addAll(parseMacAddressesFromDomainXml(xml));
        }
        return macs;
    }

    /**
     * Extract guest MACs from libvirt domain XML. Package-private pure helper
     * for unit tests.
     */
    static Set<String> parseMacAddressesFromDomainXml(final String xml) {
        final Set<String> out = new HashSet<>();
        if (StringUtils.isBlank(xml)) {
            return out;
        }
        final Matcher m = MAC_IN_DOMAIN_XML.matcher(xml);
        while (m.find()) {
            out.add(m.group(1).toLowerCase());
        }
        return out;
    }

    /**
     * Read {@code external_ids:attached-mac} for an OVS Interface, or null when
     * missing / blank. Lower-cased for set membership against live domain MACs.
     */
    static String readAttachedMac(final String iface) {
        if (StringUtils.isBlank(iface)) {
            return null;
        }
        final String raw = Script.runSimpleBashScript(String.format(
                "ovs-vsctl --if-exists get Interface %s external_ids:attached-mac 2>/dev/null",
                iface));
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        final String mac = raw.trim().replaceAll("^\"|\"$", "");
        if (StringUtils.isBlank(mac) || "[]".equals(mac)) {
            return null;
        }
        return mac.toLowerCase();
    }

    /**
     * Best-effort {@code vdpa dev del} for every vDPA device whose mgmtdev is
     * {@code vfPci}.
     */
    static void deleteVdpaDevsForPciBestEffort(final Logger log, final String callerLabel,
                                               final String vfPci) {
        if (StringUtils.isBlank(vfPci)) {
            return;
        }
        for (final String name : listVdpaNamesForPci(vfPci)) {
            try {
                Script.runSimpleBashScript("vdpa dev del " + name + " 2>/dev/null");
                if (log != null) {
                    log.info("{}: deleted leftover vdpa {} for pci={}", callerLabel, name, vfPci);
                }
            } catch (RuntimeException re) {
                if (log != null) {
                    log.warn("{}: vdpa dev del {} for pci={} failed: {}",
                            callerLabel, name, vfPci, re.getMessage());
                }
            }
        }
    }

    /**
     * vDPA device names whose mgmtdev parent PCI is {@code vfPci}. Prefer sysfs
     * (no PATH / multi-line issues); fall back to multi-line {@code vdpa dev show}.
     */
    static Set<String> listVdpaNamesForPci(final String vfPci) {
        final Set<String> names = new HashSet<>();
        if (StringUtils.isBlank(vfPci)) {
            return names;
        }
        final String want = vfPci.toLowerCase();
        final File bus = new File("/sys/bus/vdpa/devices");
        final File[] entries = bus.listFiles();
        if (entries != null) {
            for (final File entry : entries) {
                try {
                    final File real = entry.getCanonicalFile();
                    final File parent = real.getParentFile();
                    if (parent != null && want.equals(parent.getName().toLowerCase())) {
                        names.add(entry.getName());
                    }
                } catch (IOException ignored) {
                    // skip unreadable entry
                }
            }
        }
        if (!names.isEmpty()) {
            return names;
        }
        try {
            final String raw = Script.runSimpleBashScriptWithFullResult("vdpa dev show 2>/dev/null", 5);
            if (StringUtils.isBlank(raw)) {
                return names;
            }
            for (final String line : raw.split("\\R")) {
                final int idx = line.indexOf("mgmtdev pci/");
                if (idx < 0) {
                    continue;
                }
                String rest = line.substring(idx + "mgmtdev pci/".length()).trim();
                final int sp = rest.indexOf(' ');
                if (sp > 0) {
                    rest = rest.substring(0, sp);
                }
                if (!want.equals(rest.toLowerCase())) {
                    continue;
                }
                final int colon = line.indexOf(':');
                final String name = colon > 0 ? line.substring(0, colon).trim() : null;
                if (StringUtils.isNotBlank(name)) {
                    names.add(name);
                }
            }
        } catch (RuntimeException ignored) {
            // CLI unavailable — empty set is fine (best-effort)
        }
        return names;
    }

    /**
     * Pure decision: a representor with iface-id is safe to free only when the
     * VF is kernel-FREE — not on {@code vfio-pci} and not hosting a vDPA
     * mgmtdev. Used only when the rep has no {@code attached-mac} stamp.
     * Package-private for unit tests.
     */
    static boolean isSafeToFreeStaleRep(final String pciDriver, final boolean hasVdpaOnPci) {
        if (hasVdpaOnPci) {
            return false;
        }
        return !DRV_VFIO.equals(pciDriver);
    }

    /**
     * Parse {@code ovs-vsctl --bare --columns=name find Interface …} output
     * into interface names (one per non-blank line, quotes stripped).
     * Package-private pure helper for unit tests.
     */
    static List<String> parseOvsIfaceNames(final String raw) {
        final List<String> out = new ArrayList<>();
        if (StringUtils.isBlank(raw)) {
            return out;
        }
        for (final String line : raw.split("\\R")) {
            final String name = line.trim().replaceAll("^\"|\"$", "");
            if (StringUtils.isNotBlank(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * True when {@code iface} is a switchdev VF representor
     * ({@code phys_port_name} matches {@code pfNvfM}).
     */
    static boolean isVfRepresentor(final String iface) {
        if (StringUtils.isBlank(iface)) {
            return false;
        }
        final String phys = VfPassthroughVifDriver.readPhysPortName(iface);
        return phys != null && REP_PHYS_PORT_PATTERN.matcher(phys).matches();
    }

    /**
     * Resolve the VF PCI BDF for a switchdev representor via phys_port_name
     * ({@code pfNvfM}) → parent PF PCI → {@code virtfnM}.
     */
    static String resolveVfPciFromRepresentor(final String repName) {
        final String phys = VfPassthroughVifDriver.readPhysPortName(repName);
        final Matcher matcher = phys == null ? null : REP_PHYS_PORT_PATTERN.matcher(phys);
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        final int vfId = Integer.parseInt(matcher.group(2));
        // Representor netdev's device symlink is the parent PF in switchdev.
        final File devLink = new File("/sys/class/net/" + repName + "/device");
        if (!devLink.exists()) {
            return null;
        }
        try {
            final String pfPci = devLink.getCanonicalFile().getName();
            final File virtfn = new File("/sys/bus/pci/devices/" + pfPci + "/virtfn" + vfId);
            if (!virtfn.exists()) {
                return null;
            }
            return virtfn.getCanonicalFile().getName();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Read the bound PCI driver basename for {@code bdf}, or {@code null}
     * when unbound / missing.
     */
    static String readPciDriver(final String bdf) {
        if (StringUtils.isBlank(bdf)) {
            return null;
        }
        final Path driverLink = SYS_PCI_DEVICES.resolve(bdf).resolve("driver");
        try {
            if (!Files.exists(driverLink)) {
                return null;
            }
            return driverLink.toRealPath().getFileName().toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * PCI BDFs currently hosting a vDPA mgmtdev.
     *
     * <p><b>Must use multi-line sources.</b> {@link Script#runSimpleBashScript}
     * only returns the <em>first</em> line (OneLineParser). Live CKS hosts often
     * have 2+ vDPA devices (e.g. salazar + snape); keeping only the first PCI
     * caused {@link #freeStaleFreeVfRepresentors} to treat the other live
     * representors as FREE and {@code del-port} them off {@code br-overlay}
     * (L2 blackhole → etcd/apiserver crashloop).
     *
     * <p>Sources (union):
     * <ol>
     *   <li>sysfs {@code /sys/bus/vdpa/devices/*} → parent PCI BDF (no PATH)</li>
     *   <li>{@code vdpa dev show} via {@link Script#runSimpleBashScriptWithFullResult}
     *       with absolute binary paths</li>
     * </ol>
     */
    static Set<String> listVdpaMgmtPciAddresses(final Logger log) {
        final Set<String> out = new HashSet<>();
        out.addAll(listVdpaMgmtPciFromSysfs());
        out.addAll(listVdpaMgmtPciFromCli(log));
        return out;
    }

    /**
     * Walk {@code /sys/bus/vdpa/devices/*}; each entry is a symlink under a
     * PCI device directory ({@code .../0000:01:01.0/vdpa-…}). Package-private
     * for unit tests via injectable path is overkill — pure directory walk.
     */
    static Set<String> listVdpaMgmtPciFromSysfs() {
        final Set<String> out = new HashSet<>();
        final File bus = new File("/sys/bus/vdpa/devices");
        final File[] entries = bus.listFiles();
        if (entries == null) {
            return out;
        }
        for (final File entry : entries) {
            try {
                // .../0000:bb:dd.f/vdpa-name  → parent name is the PCI BDF
                final File real = entry.getCanonicalFile();
                final File parent = real.getParentFile();
                if (parent == null) {
                    continue;
                }
                final String bdf = parent.getName();
                if (bdf.matches("[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-9a-fA-F]")) {
                    out.add(bdf.toLowerCase());
                }
            } catch (IOException ignored) {
                // skip unreadable entry
            }
        }
        return out;
    }

    /**
     * Full multi-line {@code vdpa dev show} parse. Never uses OneLineParser.
     */
    static Set<String> listVdpaMgmtPciFromCli(final Logger log) {
        final Set<String> out = new HashSet<>();
        for (final String bin : new String[] {"/usr/sbin/vdpa", "/sbin/vdpa", "/usr/local/sbin/vdpa", "vdpa"}) {
            try {
                 final String raw = Script.runSimpleBashScriptWithFullResult(bin + " dev show -j 2>/dev/null", 5);
                 for (final VdpaPoolReconciler.VdpaSf device
                         : VdpaPoolReconciler.parseHostSfs(raw).values()) {
                     out.add(device.getMgmtdevPci().toLowerCase());
                 }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (RuntimeException re) {
                if (log != null) {
                    log.debug("listVdpaMgmtPciFromCli: {} failed: {}", bin, re.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * Parse multi-line {@code vdpa dev show} into PCI BDFs.
     * Lines look like: {@code vdpa-XXXX: type network mgmtdev pci/0000:01:00.3 ...}
     * Package-private for unit tests.
     */
    static Set<String> parseVdpaDevShowPci(final String raw) {
        final Set<String> out = new HashSet<>();
        if (StringUtils.isBlank(raw)) {
            return out;
        }
        for (final String line : raw.split("\\R")) {
            final int idx = line.indexOf("mgmtdev pci/");
            if (idx < 0) {
                continue;
            }
            String rest = line.substring(idx + "mgmtdev pci/".length()).trim();
            final int sp = rest.indexOf(' ');
            if (sp > 0) {
                rest = rest.substring(0, sp);
            }
            if (StringUtils.isNotBlank(rest)) {
                out.add(rest.toLowerCase());
            }
        }
        return out;
    }

    /** Counters returned by {@link #freeStaleFreeVfRepresentors}. */
    public static final class FreeStaleOvsResult {
        public int scanned;
        public int freed;
        public int skippedNonRep;
        public int skippedAllocated;
        public int skippedUnresolved;
        public final List<String> freedNames = new ArrayList<>();
    }

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
     * <p>For every representor OVS returns, {@link #freeRepresentorOnOvs}
     * clears external_ids and removes the port (bridge-agnostic). A
     * best-effort attempt is also made to clear the VF identity (MAC + VLAN)
     * on the parent PF — see {@link #clearVfIdentityForRepBestEffort}.
     * Idempotent — safe when no representor carries the given
     * {@code attached-mac}.
     *
     * @param log the calling driver's own logger instance, so log lines carry
     *            that driver's class name.
     * @param callerLabel short label identifying the calling driver + method
     *                    (eg. {@code "OvnVdpaVifDriver.unplug"}), used as a
     *                    log-line prefix.
     * @param integrationBridge retained as a log hint only; free path no
     *                          longer scopes del-port to a single bridge.
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
        // FullResult: OneLineParser would drop every rep after the first when
        // multiple Interfaces share the same attached-mac (rare but real).
        final String found;
        try {
            found = Script.runSimpleBashScriptWithFullResult(findCmd, 10);
        } catch (RuntimeException re) {
            if (log != null) {
                log.debug("{}: attached-mac find failed for mac={}: {}",
                        callerLabel, mac, re.getMessage());
            }
            return;
        }
        if (StringUtils.isBlank(found)) {
            return;
        }
        for (final String raw : found.split("\\R")) {
            final String repName = raw.trim().replaceAll("^\"|\"$", "");
            if (StringUtils.isBlank(repName)) {
                continue;
            }
            freeRepresentorOnOvs(log, callerLabel, repName);
            log.info("{}: attached-mac fallback freed orphan rep={} (bridge hint={}, mac={})",
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
        final String vfPci = resolveVfPciFromRepresentor(repName);
        final String pfName = VfPassthroughVifDriver.lookupPfFromVf(vfPci);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(vfPci);
        if (pfName == null || vfId == null) {
            log.debug("{}: no exact parent PF/VF found for rep={} pci={}; skipping PF-side clear",
                    callerLabel, repName, vfPci);
            return;
        }
        Script.runSimpleBashScript(String.format(
            "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
        log.info("{}: cleared VF identity pf={} vf={} (derived from rep={})", callerLabel, pfName, vfId, repName);
    }
}
