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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.to.NicTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.network.Networks;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.hypervisor.kvm.resource.hwoffload.VdpaPoolReconciler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * VifDriver for the vDPA path: a vhost-vdpa management device sits on top of
 * an SR-IOV VF (created by {@code vdpa dev add ... mgmtdev pci/<vfPci>}) and
 * is exposed to the guest via libvirt {@code <interface type='vdpa'>}. The
 * underlying VF representor still lives on {@code br-bond} so the HW offload
 * pipeline (TC flower rules driven by {@link com.cloud.hypervisor.kvm.resource.hwoffload.IntentReconciler})
 * runs on the same representor — vDPA does NOT bypass HW offload.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code plug}: program the VF MAC + VLAN on the PF (same as
 *       {@link VfPassthroughVifDriver}), add the VF rep to br-bond with the
 *       OVS access tag, then run
 *       {@code vdpa dev add name vdpa-<vmId> mgmtdev pci/<vfPci> mac <mac> max_vqs <N>}.
 *       Parse {@code vdpa dev show -j} to find which {@code /dev/vhost-vdpa-N}
 *       index the kernel assigned to the new SF, emit
 *       {@code <interface type='vdpa'><source dev='/dev/vhost-vdpa-N'/>...</interface>}.
 *   <li>{@code unplug}: {@code vdpa dev del vdpa-<vmId>}, drop the rep from
 *       br-bond, clear PF-side VF MAC/VLAN. Idempotent — repeated unplugs are
 *       safe and the VifDriver tolerates missing PCI/MAC info.
 * </ul>
 *
 * <p>Reuses {@link VfPassthroughVifDriver} static helpers ({@code lookupPfFromVf},
 * {@code lookupVfIdFromPci}, {@code lookupRepresentor}) so the rep-on-bridge
 * plumbing stays identical between the two paths and any sysfs scan
 * improvement applies to both.
 */
public class VdpaVifDriver extends VifDriverBase {

    /** Recognised vdpa-name prefix. Mirrors {@code SriovVfPoolDaoImpl.buildVdpaName}. */
    static final String VDPA_NAME_PREFIX = "vdpa-";

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
                "VdpaVifDriver invoked without vfPciAddress on NicTO; check VfPoolManager.allocateForVdpa");
        }
        String mac = nic.getMac();
        if (StringUtils.isBlank(mac)) {
            throw new InternalErrorException("VdpaVifDriver requires a MAC on the NicTO");
        }
        java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(pciAddress);
        lifecycleLock.lock();

        String pfName = nic.getVfPfName();
        Integer vlanTag = extractVlanTag(nic);
        int maxVqs = nic.getVdpaMaxVqs() != null ? nic.getVdpaMaxVqs() : 33;

        // Phase H.1: every step that mutates host state pushes its inverse
        // onto rollback. If a later step throws, rollback runs LIFO and the
        // original exception is re-thrown.
        Deque<Runnable> rollback = new ArrayDeque<>();
        try {
            String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            VfPassthroughVifDriver.requireExactVfTopology(pciAddress, pfName, null);
            boolean switchdev = repName != null;
            Integer pfVlanTag = switchdev ? null : vlanTag;
            configureVfOnPf(pfName, pciAddress, mac, pfVlanTag);
            final String pfNameFinal = pfName != null
                    ? pfName : VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
            final Integer vfIdFinal = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
            rollback.push(() -> {
                if (pfNameFinal != null && vfIdFinal != null) {
                    Script.runSimpleBashScript(String.format(
                        "ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfNameFinal, vfIdFinal));
                }
            });

            String vdpaName = buildVdpaName(nic);
            // `vdpa dev add` is idempotent only by name; pre-clean so a stale
            // entry from a previous boot does not pin /dev/vhost-vdpa-N to the
            // wrong VF.
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            // iproute2 vdpa CLI expects {@code max_vqp} (queue PAIRS), not
            // {@code max_vqs} (total VQs); see {@link OvnVdpaVifDriver} for
            // the rationale. Convert: 33 total VQs -> 16 queue pairs.
            int maxVqp = Math.max(1, (maxVqs - 1) / 2);
            String addCmd = String.format(
                "vdpa dev add name %s mgmtdev pci/%s mac %s max_vqp %d",
                vdpaName, pciAddress, mac, maxVqp);
            Script.runSimpleBashScript(addCmd, 5000);
            final String vdpaNameFinal = vdpaName;
            rollback.push(() -> Script.runSimpleBashScript(
                    String.format("vdpa dev del %s 2>/dev/null", vdpaNameFinal)));

            String vhostDev = resolveVhostVdpaDevice(vdpaName);
            if (StringUtils.isBlank(vhostDev)) {
                throw new InternalErrorException(String.format(
                    "VdpaVifDriver could not resolve /dev/vhost-vdpa-N for vdpa name %s; aborting plug", vdpaName));
            }

            if (repName != null) {
                addRepresentorToOvs(repName, vlanTag);
                final String repNameFinal = repName;
                rollback.push(() -> {
                    Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null", repNameFinal));
                    if (!OvnVifDriver.freeRepresentorOnOvsLocked(logger, "VdpaVifDriver.plug-rollback",
                            repNameFinal, pciAddress)) {
                        throw new CloudRuntimeException("representor CAS failed for " + repNameFinal);
                    }
                });
            }

            // Capture the host /dev path on the NicTO so the mgmt server can store it
            // in nics.vdpa_device for state queries / live-migrate handoff.
            nic.setVdpaDevice(vhostDev);

            LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
            // queues = max_vqs / 2 (TX+RX pair count). 33 max_vqs (16 RX + 16 TX +
            // 1 control) → 16 queue pairs.
            Integer queues = maxVqs > 1 ? maxVqs / 2 : null;
            intf.defVdpaNet(vhostDev, mac, queues);
            intf.setLinkStateUp(nic.isEnabled());

            logger.info("vDPA plug: name={} pci={} pf={} mac={} vlan={} rep={} maxVqs={} vhost={}",
                    vdpaName, pciAddress, pfName, mac, vlanTag, repName, maxVqs, vhostDev);
            return intf;
        } catch (RuntimeException | InternalErrorException ex) {
            drainRollback(rollback);
            throw ex;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Run every queued rollback step in LIFO order. Each step's failure is
     * logged but does not abort the others — best-effort cleanup so the host
     * is left as close to pre-plug as possible.
     */
    private void drainRollback(Deque<Runnable> rollback) {
        while (!rollback.isEmpty()) {
            Runnable step = rollback.pop();
            try {
                step.run();
            } catch (RuntimeException e) {
                logger.warn("VdpaVifDriver.plug rollback step failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void unplug(LibvirtVMDef.InterfaceDef iface, boolean delete) {
        // Stop-path fan-out calls every VifDriver for every InterfaceDef
        // (getAllVifDrivers); only act on the vDPA NICs this driver owns.
        if (iface == null || iface.getNetType() != LibvirtVMDef.InterfaceDef.GuestNetType.VDPA) {
            return;
        }
        // For vDPA the source is the /dev/vhost-vdpa-N path — there's no
        // straightforward way to derive vdpaName from it without `vdpa dev
        // show`. Use the name pattern stored on the iface's MAC.
        String mac = iface.getMacAddress();
        String vhostDev = iface.getBrName(); // _sourceName for VDPA
        logger.info("VdpaVifDriver.unplug ENTRY: vhost={} mac={} netType={} delete={}",
                vhostDev, mac, iface.getNetType(), delete);

        // Best-effort lookup of vdpaName via `vdpa dev show -j` parsed against
        // the dev path; falls through to MAC if path lookup fails.
        String vdpaName = lookupVdpaNameByVhostDev(vhostDev);
        if (StringUtils.isBlank(vdpaName)) {
            vdpaName = lookupVdpaNameByMac(mac);
        }
        final String pciAddress = StringUtils.isNotBlank(vdpaName) ? lookupVdpaPciByName(vdpaName) : null;
        if (StringUtils.isBlank(vdpaName) || StringUtils.isBlank(pciAddress)) {
            logger.warn("vDPA unplug: name={} did not resolve to an exact VF BDF; fail-closed", vdpaName);
            return;
        }
        final java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(pciAddress);
        lifecycleLock.lock();
        try {
            VfPassthroughVifDriver.requireExactVfTopology(pciAddress, null, null);
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            if (VdpaPoolReconciler.parseHostSfs(
                    Script.runSimpleBashScript("vdpa dev show -j 2>/dev/null", 5000)).containsKey(vdpaName)) {
                throw new IllegalStateException("vDPA device remained after deletion: " + vdpaName);
            }
            logger.info("vDPA unplug: deleted {} (vhost={} mac={})", vdpaName, vhostDev, mac);

        // Drop the VF representor from br-bond and clear PF-side VF VLAN/MAC,
        // mirroring VfPassthroughVifDriver. The pciAddress is not stored on
        // the VDPA InterfaceDef — recover it through the same lookupVfPciByMac
        // path the passthrough driver uses, falling back to skip on miss.
            String repName = VfPassthroughVifDriver.lookupRepresentor(pciAddress);
            if (repName != null) {
                Script.runSimpleBashScript(String.format("tc qdisc del dev %s clsact 2>/dev/null", repName));
                if (!OvnVifDriver.freeRepresentorOnOvsLocked(logger, "VdpaVifDriver.unplug", repName, pciAddress)) {
                    throw new CloudRuntimeException("representor CAS failed for " + repName);
                }
                logger.info("vDPA unplug: removed rep {} from OVS and cleared TC", repName);
            }
            String pfName = VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
            Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
            if (pfName != null && vfId != null) {
                Script.runSimpleBashScript(String.format("ip link set %s vf %d mac 00:00:00:00:00:00 vlan 0", pfName, vfId));
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void attach(LibvirtVMDef.InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-attach is not supported for vDPA interfaces");
    }

    @Override
    public void detach(LibvirtVMDef.InterfaceDef iface) {
        throw new UnsupportedOperationException("Hot-detach is not supported for vDPA interfaces");
    }

    @Override
    public void deleteBr(NicTO nic) {
        // No bridge to delete for vDPA.
    }

    @Override
    public void createControlNetwork(String privBrName) {
        // Control network (cloud0) uses TAP/bridge; nothing here.
    }

    @Override
    public boolean isExistingBridge(String bridgeName) {
        return false;
    }

    /**
     * Build the canonical vdpa-name for the NIC. Preference order:
     * (1) {@code vdpa-<nicUuid first 8 chars>}, (2) {@code vdpa-<mac with-no-colons>}.
     * The mgmt-side allocator stamps {@code vdpa-<nicId>} on {@code sriov_vf_pool.vdpa_name};
     * the agent does not have the nic id at plug time, so MAC is used as the
     * collision-free local fallback. Either format starts with {@link #VDPA_NAME_PREFIX}
     * so {@link #lookupVdpaNameByVhostDev} can identify our own SFs.
     */
    static String buildVdpaName(NicTO nic) {
        String mac = nic.getMac();
        if (StringUtils.isNotBlank(mac)) {
            return VDPA_NAME_PREFIX + mac.replace(":", "").toLowerCase();
        }
        return VDPA_NAME_PREFIX + System.nanoTime();
    }

    /**
     * Run {@code vdpa dev show -j} and find the {@code /dev/vhost-vdpa-N} path
     * that matches the given vdpa-name. Returns null when the command fails
     * or the name is not present in the output.
     */
    static String resolveVhostVdpaDevice(String vdpaName) {
        if (StringUtils.isBlank(vdpaName)) {
            return null;
        }
        String json = Script.runSimpleBashScript("vdpa dev show -j 2>/dev/null", 5000);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        return parseVhostVdpaFromShow(json, vdpaName);
    }

    /**
     * Parse {@code vdpa dev show -j} output and return the {@code /dev/vhost-vdpa-N}
     * path for the SF whose name matches {@code vdpaName}. Robust against the two
     * shapes iproute2 has emitted historically:
     * <pre>
     * {"dev":{"vdpa-vmA2":{"type":"network","mgmtdev":"pci/0000:01:00.3","vendor_id":...,"max_vqs":33}}}
     * </pre>
     * The {@code /dev/vhost-vdpa-N} index is read from the kernel via
     * {@code /sys/bus/vdpa/devices/<vdpaName>/vhost-vdpa-N} (sysfs presents
     * a symlink with the index encoded in its name).
     */
    static String parseVhostVdpaFromShow(String json, String vdpaName) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject dev = root.getAsJsonObject().getAsJsonObject("dev");
            if (dev == null || !dev.has(vdpaName)) {
                return null;
            }
            // The shape only confirms the SF exists; the actual cdev index is
            // read from sysfs because iproute2 does not surface it.
            return readVhostDevFromSysfs(vdpaName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Walk {@code /sys/bus/vdpa/devices/<vdpaName>} and return the
     * {@code /dev/vhost-vdpa-N} path matching the index encoded in the
     * directory's child {@code vhost-vdpa-N} entry (kernel-allocated).
     * Returns null when no such entry exists.
     *
     * <p>Kernel sysfs emits the child as {@code vhost-vdpa-<digits>}
     * (with hyphen). The legacy implementation matched
     * {@code vhost-vdpaN} (no hyphen) and rejected the real kernel
     * shape via the {@code \\d+} regex, leaving every plug aborted with
     * "could not resolve /dev/vhost-vdpa-N". Strip both prefixes
     * ({@code vhost-vdpa-} and {@code vhost-vdpa}) so the resolver
     * works against any iproute2/kernel combination.
     */
    static String readVhostDevFromSysfs(String vdpaName) {
        File devDir = new File("/sys/bus/vdpa/devices/" + vdpaName);
        if (!devDir.isDirectory()) {
            return null;
        }
        String[] entries = devDir.list();
        if (entries == null) {
            return null;
        }
        for (String name : entries) {
            if (!name.startsWith("vhost-vdpa")) {
                continue;
            }
            String idx = name.substring("vhost-vdpa".length());
            if (idx.startsWith("-")) {
                idx = idx.substring(1);
            }
            if (idx.matches("\\d+")) {
                // Kernel cdev path is {@code /dev/vhost-vdpa-<idx>} (with
                // hyphen). Older fork code emitted {@code /dev/vhost-vdpaN}
                // (no hyphen) and libvirt would reject it with
                //   "Unable to open '/dev/vhost-vdpaN' for vdpa device:
                //    No such file or directory"
                // even though the SF was correctly created.
                return "/dev/vhost-vdpa-" + idx;
            }
        }
        return null;
    }

    /**
     * Resolve the vdpa-name whose backing {@code /dev/vhost-vdpa-N} matches
     * the given path. Used at unplug when libvirt only stores the path on the
     * iface (not the name).
     */
    static String lookupVdpaNameByVhostDev(String vhostDev) {
        if (StringUtils.isBlank(vhostDev)) {
            return null;
        }
        // Cheap reverse-lookup via sysfs: every vdpa SF directory has a child
        // entry of the form vhost-vdpaN — find the one whose N matches.
        File base = new File("/sys/bus/vdpa/devices");
        String[] devs = base.list();
        if (devs == null) {
            return null;
        }
        String wanted = vhostDev.startsWith("/dev/") ? vhostDev.substring("/dev/".length()) : vhostDev;
        for (String dev : devs) {
            String[] children = new File(base, dev).list();
            if (children == null) {
                continue;
            }
            for (String child : children) {
                if (child.equals(wanted)) {
                    return dev;
                }
            }
        }
        return null;
    }

    /** Resolve one vDPA device name to its exact management PCI BDF via sysfs. */
    static String lookupVdpaPciByName(String vdpaName) {
        if (StringUtils.isBlank(vdpaName)) {
            return null;
        }
        try {
            final File real = new File("/sys/bus/vdpa/devices/" + vdpaName).getCanonicalFile();
            final File parent = real.getParentFile();
            if (parent != null && parent.getName().matches(
                    "[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-9a-fA-F]")) {
                return parent.getName().toLowerCase(java.util.Locale.ROOT);
            }
        } catch (IOException ignored) {
            // Fail closed: caller skips destructive cleanup without an exact BDF.
        }
        return null;
    }

    /**
     * Resolve the vdpa-name whose MAC matches the given iface MAC. Best-effort
     * fallback when {@link #lookupVdpaNameByVhostDev} returns null. Iterates
     * {@code vdpa dev config show} for every present SF and matches by MAC.
     */
    private String lookupVdpaNameByMac(String mac) {
        if (StringUtils.isBlank(mac)) {
            return null;
        }
        File base = new File("/sys/bus/vdpa/devices");
        String[] devs = base.list();
        if (devs == null) {
            return null;
        }
        String norm = mac.trim().toLowerCase();
        for (String dev : devs) {
            String json = Script.runSimpleBashScript(
                String.format("vdpa dev config show %s -j 2>/dev/null", dev), 5000);
            if (StringUtils.isBlank(json)) {
                continue;
            }
            try {
                JsonElement root = JsonParser.parseString(json);
                if (!root.isJsonObject()) {
                    continue;
                }
                JsonObject config = root.getAsJsonObject().getAsJsonObject("config");
                if (config == null || !config.has(dev)) {
                    continue;
                }
                JsonObject perDev = config.getAsJsonObject(dev);
                if (perDev != null && perDev.has("mac")
                        && norm.equalsIgnoreCase(perDev.get("mac").getAsString())) {
                    return dev;
                }
            } catch (Exception ignore) {
                // Tolerant of older iproute2 schema variations; next dev.
            }
        }
        return null;
    }

    /** Same VF lookup pattern as VfPassthroughVifDriver — see that class for sysfs scan rationale. */
    private String lookupVfPciByMac(String mac) {
        // Delegating saves us re-implementing the cached PF scan; the
        // passthrough driver instance is per-host and re-instantiated by
        // LibvirtComputingResource so a clean VfPassthroughVifDriver is fine.
        VfPassthroughVifDriver helper = new VfPassthroughVifDriver();
        try {
            java.lang.reflect.Method m = VfPassthroughVifDriver.class
                .getDeclaredMethod("lookupVfPciByMac", String.class);
            m.setAccessible(true);
            return (String) m.invoke(helper, mac);
        } catch (Exception e) {
            logger.debug("lookupVfPciByMac reflective dispatch failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Configure the VF on its parent PF — same as VfPassthroughVifDriver.
     * Kept inline here so vDPA does not depend on the passthrough driver's
     * private method visibility.
     */
    private void configureVfOnPf(String pfName, String pciAddress, String macAddr, Integer vlanTag) {
        if (StringUtils.isBlank(pfName)) {
            pfName = VfPassthroughVifDriver.lookupPfFromVf(pciAddress);
        }
        Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(pciAddress);
        if (pfName == null || vfId == null) {
            throw new CloudRuntimeException(String.format("Cannot configure VF on PF: pf=%s pci=%s", pfName, pciAddress));
        }
        if (StringUtils.isNotBlank(macAddr)) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d mac %s", pfName, vfId, macAddr));
        }
        Script.runSimpleBashScript(String.format("ip link set %s vf %d trust on", pfName, vfId));
        Script.runSimpleBashScript(String.format("ip link set %s vf %d spoofchk off", pfName, vfId));
        if (vlanTag == null) {
            return;
        }
        if (vlanTag > 0 && vlanTag < 4095) {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d vlan %d", pfName, vfId, vlanTag));
        } else {
            Script.runSimpleBashScript(String.format("ip link set %s vf %d vlan 0", pfName, vfId));
        }
    }

    /** Add the VF representor to br-bond with the OVS access tag. */
    private void addRepresentorToOvs(String repName, Integer vlanTag) {
        Script.runSimpleBashScript(String.format(
            "ovs-vsctl --may-exist add-port br-bond %s", repName));
        if (vlanTag != null && vlanTag > 0) {
            int ovsTag = VfPassthroughVifDriver.toOvsAccessTag(vlanTag);
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl set port %s tag=%d", repName, ovsTag));
        } else {
            Script.runSimpleBashScript(String.format(
                "ovs-vsctl clear port %s tag", repName));
        }
        Script.runSimpleBashScript(String.format("tc qdisc add dev %s clsact 2>/dev/null", repName));
        logger.info("Added VF representor {} to OVS br-bond (segment={}) with clsact qdisc", repName, vlanTag);
    }

    /** Same broadcast-URI parsing as VfPassthroughVifDriver. */
    private Integer extractVlanTag(NicTO nic) {
        if (nic.getBroadcastUri() == null) {
            return null;
        }
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

    /** Helper used only by the bind-side toString of {@link JsonArray} (kept to surface the import). */
    @SuppressWarnings("unused")
    private static String unused(JsonArray a) {
        return a == null ? "" : a.toString();
    }
}
