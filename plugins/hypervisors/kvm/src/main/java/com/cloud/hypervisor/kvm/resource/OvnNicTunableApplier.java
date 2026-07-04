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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef.NicModel;
import com.cloud.utils.script.Script;

/**
 * Stateless utility that applies the OVN-managed NIC tunables resolved by
 * {@code HypervisorGuruBase.populateOvnTunables} (see
 * {@code com.cloud.network.ovn.config.OvnNicConfig} +
 * {@code com.cloud.network.ovn.config.OvnNicTunables}) onto the agent host.
 *
 * <p>Two surfaces:
 * <ul>
 *   <li><b>Host config</b> applied imperatively via shell helpers:
 *       {@code ip link set <pf> vf <N> trust|spoofchk|state|max_tx_rate|min_tx_rate|qos}
 *       and {@code ethtool -K <iface> tso|gso|gro|lro|tx|rx} for the kernel-tap path.</li>
 *   <li><b>Domain XML</b> mutations on a {@link InterfaceDef} (driver model,
 *       vhost driver name, vhost queues, tx/rx queue size, packed vq).</li>
 * </ul>
 *
 * <p>All methods are null-safe and idempotent: a {@code null} tunable on the
 * {@link NicTO} means "operator did not configure it; leave host/XML default".
 * This preserves wire compat with older mgmt servers that don't populate the
 * tunables and ensures the agent never regresses behavior on upgrade.
 *
 * <p>Failures from {@code ip link} / {@code ethtool} are logged at WARN but
 * never abort plug — the e-switch state may not yet be ready (e.g. PF in
 * legacy mode) and the operator can re-tune online.
 */
public final class OvnNicTunableApplier {

    private static final Logger LOGGER = LogManager.getLogger(OvnNicTunableApplier.class);

    /**
     * Per-JVM latch — true once the agent has stamped the bridge-wide
     * {@code Open_vSwitch other_config:tc-policy} for this process. Avoids
     * issuing the same {@code ovs-vsctl set} on every NIC plug; ovs-vsctl
     * is idempotent but the latch keeps log noise down and skips a fork
     * per attach. Reset on agent restart, which is the right cadence
     * because the OVS DB persists the value across daemon restart and
     * only a process boot needs to re-assert it.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean TC_POLICY_LATCH =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private OvnNicTunableApplier() {
        // Utility class.
    }

    /**
     * Stamp {@code other_config:hairpin=true} (or {@code false}) on a freshly
     * attached br-int port. Required for VF&lt;-&gt;VF same-host hardware
     * offload via TC flower on mlx5 switchdev: without it the eswitch
     * refuses to short-circuit the VF-to-VF path through the representor
     * pair and the steady-state traffic falls back to software, collapsing
     * throughput by ~50x in our cluster.
     *
     * <p>Null-safe: when {@code hairpin} is {@code null} (older mgmt didn't
     * resolve the tunable, or operator explicitly opted out via VM detail)
     * the call is a no-op so wire compat is preserved.
     *
     * <p>Failures from {@code ovs-vsctl} (e.g. kernel datapath that does not
     * accept the flag, port disappeared between add-port and set Port) are
     * logged at WARN but never thrown — the plug must succeed even on a
     * vanilla CloudStack deployment without mlx5 / VF / hairpin support.
     *
     * @param portName OVS port name (the same name passed to add-port)
     * @param hairpin  resolved value; {@code null} = skip
     */
    public static void applyHairpin(final String portName, final Boolean hairpin) {
        if (StringUtils.isBlank(portName) || hairpin == null) {
            return;
        }
        final String cmd = buildHairpinCommand(portName, hairpin);
        try {
            Script.runSimpleBashScript(cmd);
            LOGGER.debug("OvnNicTunableApplier.applyHairpin: port={} hairpin={}", portName, hairpin);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNicTunableApplier.applyHairpin: ovs-vsctl set Port {} hairpin failed: {}",
                    portName, e.getMessage());
        }
    }

    /**
     * Build the {@code ovs-vsctl set Port ... other_config:hairpin=...}
     * invocation. Pure string construction so unit tests can pin the
     * exact command shape without forking a shell.
     */
    public static String buildHairpinCommand(final String portName, final boolean hairpin) {
        return String.format("ovs-vsctl --if-exists set Port %s other_config:hairpin=%s",
                portName, hairpin ? "true" : "false");
    }

    /**
     * Apply the bridge-wide {@code Open_vSwitch other_config:tc-policy}
     * setting once per JVM at the first OVN-aware plug. Subsequent calls
     * within the same JVM are no-ops (the OVSDB value already persisted on
     * disk; nothing to re-stamp).
     *
     * <p>The whitelist is enforced mgmt-side ({@link com.cloud.network.ovn.config.OvnNicTunables#ALLOWED_VALUES}),
     * so a non-whitelist value never reaches the agent. Defensive guard
     * here rejects blank input so a future regression in the resolver
     * cannot wipe the OVS-side value with an empty string.
     *
     * <p>{@code ovs-vsctl} failures (e.g. kernel datapath that ignores the
     * key) are logged at WARN but never thrown.
     *
     * @param tcPolicy resolved value; {@code null}/blank = skip
     */
    public static void applyTcPolicyOnce(final String tcPolicy) {
        if (StringUtils.isBlank(tcPolicy)) {
            return;
        }
        if (!TC_POLICY_LATCH.compareAndSet(false, true)) {
            return;
        }
        final String cmd = buildTcPolicyCommand(tcPolicy);
        try {
            Script.runSimpleBashScript(cmd);
            LOGGER.info("OvnNicTunableApplier.applyTcPolicyOnce: stamped tc-policy={} on Open_vSwitch", tcPolicy);
        } catch (RuntimeException e) {
            // Re-arm the latch so the next plug retries — failure here is
            // typically a transient OVSDB connect issue.
            TC_POLICY_LATCH.set(false);
            LOGGER.warn("OvnNicTunableApplier.applyTcPolicyOnce: ovs-vsctl set Open_vSwitch failed: {}",
                    e.getMessage());
        }
    }

    /**
     * Build the {@code ovs-vsctl set Open_vSwitch . other_config:tc-policy=...}
     * invocation. Pure string construction so unit tests can pin the exact
     * command shape without forking a shell.
     */
    public static String buildTcPolicyCommand(final String tcPolicy) {
        return String.format("ovs-vsctl set Open_vSwitch . other_config:tc-policy=%s", tcPolicy);
    }

    /**
     * Force re-stamp the bridge-wide tc-policy on {@code Open_vSwitch}
     * unconditionally, ignoring the per-JVM latch. Used by the
     * reconciler-driven drift sweep, which is the explicit
     * "operator wants the value re-asserted right now" path. Failures are
     * logged at WARN; never thrown.
     *
     * @param tcPolicy resolved value; {@code null}/blank = skip
     * @return {@code true} when the {@code ovs-vsctl} call succeeded.
     */
    public static boolean applyTcPolicyForce(final String tcPolicy) {
        if (StringUtils.isBlank(tcPolicy)) {
            return false;
        }
        final String cmd = buildTcPolicyCommand(tcPolicy);
        try {
            Script.runSimpleBashScript(cmd);
            // Latch the JVM so subsequent per-plug applies short-circuit.
            TC_POLICY_LATCH.set(true);
            LOGGER.info("OvnNicTunableApplier.applyTcPolicyForce: stamped tc-policy={}", tcPolicy);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNicTunableApplier.applyTcPolicyForce: ovs-vsctl set failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Read the current {@code other_config:hairpin} value from a port via
     * {@code ovs-vsctl get}. Returns {@code null} when the key is absent /
     * the port is gone / {@code ovs-vsctl} fails. Pure string conversion —
     * matches the literal {@code "true"} / {@code "false"} ovsdb returns
     * and treats anything else as missing.
     */
    public static Boolean readHairpin(final String portName) {
        if (StringUtils.isBlank(portName)) {
            return null;
        }
        final String cmd = String.format(
                "ovs-vsctl --if-exists get Port %s other_config:hairpin 2>/dev/null", portName);
        try {
            final String raw = Script.runSimpleBashScript(cmd);
            if (raw == null) {
                return null;
            }
            final String trimmed = raw.trim().replace("\"", "");
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
            return null;
        } catch (RuntimeException e) {
            LOGGER.debug("OvnNicTunableApplier.readHairpin: get Port {} failed: {}", portName, e.getMessage());
            return null;
        }
    }

    /**
     * List ports of an OVS bridge via {@code ovs-vsctl list-ports}. Returns
     * an empty list when the bridge is gone / {@code ovs-vsctl} fails.
     * Note: {@code list-ports} does not accept {@code --if-exists}; the
     * stderr from a missing bridge is suppressed via the redirect, and the
     * empty stdout naturally returns an empty list. Uses
     * {@link Script#runSimpleBashScriptWithFullResult} so every line of the
     * port list survives — the {@code OneLineParser} variant in
     * {@link Script#runSimpleBashScript} would silently return the first
     * line only.
     */
    public static java.util.List<String> listBridgePorts(final String bridge) {
        if (StringUtils.isBlank(bridge)) {
            return java.util.Collections.emptyList();
        }
        final String cmd = String.format("ovs-vsctl list-ports %s 2>/dev/null", bridge);
        try {
            final String raw = Script.runSimpleBashScriptWithFullResult(cmd, 10);
            if (raw == null || raw.trim().isEmpty()) {
                return java.util.Collections.emptyList();
            }
            final String[] lines = raw.split("\\r?\\n");
            final java.util.List<String> out = new java.util.ArrayList<>(lines.length);
            for (final String line : lines) {
                final String name = line.trim();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
            return out;
        } catch (RuntimeException e) {
            LOGGER.debug("OvnNicTunableApplier.listBridgePorts: list-ports {} failed: {}", bridge, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Resolve the OVN integration bridge from OVSDB
     * ({@code external_ids:ovn-bridge}) — the authoritative value ovn-controller
     * itself uses. Forks that rename the default {@code br-int} (this fleet runs
     * {@code br-overlay}) are handled automatically, so the reconciler sweep no
     * longer depends on a hardcoded or per-fleet-configured bridge name. Falls
     * back to {@code hint} (the bridge carried on the command) and finally
     * {@code "br-int"} when OVSDB has no explicit setting.
     *
     * @param hint bridge name carried on the command; used only as fallback
     * @return the integration bridge name; never blank
     */
    public static String resolveIntegrationBridge(final String hint) {
        final String fallback = StringUtils.defaultIfBlank(hint, "br-int");
        try {
            final String raw = Script.runSimpleBashScript(
                    "ovs-vsctl --if-exists get open_vswitch . external_ids:ovn-bridge 2>/dev/null");
            if (raw == null) {
                return fallback;
            }
            final String trimmed = raw.trim().replace("\"", "");
            return StringUtils.isBlank(trimmed) ? fallback : trimmed;
        } catch (RuntimeException e) {
            LOGGER.debug("OvnNicTunableApplier.resolveIntegrationBridge: lookup failed: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * Result of {@link #sweepHairpin}: counts of ports inspected, drifted,
     * and fixed. Pure value object — no state across calls.
     */
    public static final class SweepResult {
        public final int portsScanned;
        public final int hairpinDrifted;
        public final int hairpinFixed;

        public SweepResult(final int portsScanned, final int hairpinDrifted, final int hairpinFixed) {
            this.portsScanned = portsScanned;
            this.hairpinDrifted = hairpinDrifted;
            this.hairpinFixed = hairpinFixed;
        }
    }

    /**
     * Walk every port on {@code bridge} whose name matches {@code portRegex},
     * compare {@code other_config:hairpin} against {@code hairpinDefault},
     * and re-apply via {@link #applyHairpin} when they differ. Returns
     * counts so the caller can surface drift back to mgmt.
     *
     * <p>When {@code hairpinDefault} is {@code null}, the sweep is skipped
     * (returns zeroed counts) — caller decides explicitly whether to enforce
     * a value.
     *
     * <p>When {@code dryRun} is {@code true}, drift is counted but no
     * mutation is issued. {@code hairpinFixed} stays at zero.
     *
     * @param bridge          OVS bridge name (typically {@code br-int})
     * @param hairpinDefault  desired value; {@code null} = skip
     * @param portRegex       regex applied to port names; {@code null}/blank
     *                        = match every port
     * @param dryRun          true = report only; false = re-apply on drift
     * @return counts triple
     */
    public static SweepResult sweepHairpin(final String bridge, final Boolean hairpinDefault,
                                           final String portRegex, final boolean dryRun) {
        if (hairpinDefault == null) {
            return new SweepResult(0, 0, 0);
        }
        final java.util.regex.Pattern pattern = StringUtils.isBlank(portRegex)
                ? null : java.util.regex.Pattern.compile(portRegex);
        final java.util.List<String> ports = listBridgePorts(bridge);
        int scanned = 0;
        int drifted = 0;
        int fixed = 0;
        for (final String port : ports) {
            if (pattern != null && !pattern.matcher(port).matches()) {
                continue;
            }
            scanned++;
            final Boolean current = readHairpin(port);
            if (java.util.Objects.equals(current, hairpinDefault)) {
                continue;
            }
            drifted++;
            LOGGER.debug("OvnNicTunableApplier.sweepHairpin: drift bridge={} port={} current={} desired={} dryRun={}",
                    bridge, port, current, hairpinDefault, dryRun);
            if (dryRun) {
                continue;
            }
            applyHairpin(port, hairpinDefault);
            // Confirm the apply landed before counting it as fixed; readHairpin
            // returning null after apply means ovs-vsctl rejected the set.
            final Boolean post = readHairpin(port);
            if (java.util.Objects.equals(post, hairpinDefault)) {
                fixed++;
            }
        }
        if (drifted > 0) {
            LOGGER.info("OvnNicTunableApplier.sweepHairpin: bridge={} scanned={} drifted={} fixed={} dryRun={}",
                    bridge, scanned, drifted, fixed, dryRun);
        }
        return new SweepResult(scanned, drifted, fixed);
    }

    /**
     * Test-only seam: reset the per-JVM latch. Production code never calls
     * this — the latch lifetime is the JVM lifetime by design.
     */
    static void resetTcPolicyLatchForTesting() {
        TC_POLICY_LATCH.set(false);
    }

    /**
     * Apply every PF-side VF tunable carried on {@code nic} via
     * {@code ip link set}. {@code pfName} and {@code vfId} must be resolved
     * by the caller (sysfs scan); we only consume them.
     *
     * <p>Order is intentional: identity first (mac), then policy (trust /
     * spoofchk / link state), then rate caps (max/min), then 802.1Q (vlan
     * + qos). On switchdev the kernel rejects {@code vlan} and {@code qos}
     * — we still try and log; caller can detect via stderr.
     *
     * @param nic    populated NicTO (tunables may be null = skip)
     * @param pfName host PF netdev name (e.g. {@code dx6p0})
     * @param vfId   VF index inside the PF
     */
    public static void applyVfTunables(final NicTO nic, final String pfName, final Integer vfId) {
        if (nic == null || pfName == null || vfId == null) {
            return;
        }
        runIfSet("trust",   nic.getVfTrust(),      v -> ipLinkVf(pfName, vfId, "trust " + onOff(v)));
        runIfSet("spoof",   nic.getVfSpoofcheck(), v -> ipLinkVf(pfName, vfId, "spoofchk " + onOff(v)));
        runIfSet("state",   nic.getVfLinkState(),  v -> ipLinkVf(pfName, vfId, "state " + v));
        runIfSet("maxRate", nic.getVfMaxTxRate(),  v -> ipLinkVf(pfName, vfId, "max_tx_rate " + v));
        runIfSet("minRate", nic.getVfMinTxRate(),  v -> ipLinkVf(pfName, vfId, "min_tx_rate " + v));
        if (nic.getVfVlan() != null && nic.getVfVlan() > 0) {
            final int qos = nic.getVfQos() != null ? nic.getVfQos() : 0;
            ipLinkVf(pfName, vfId, "vlan " + nic.getVfVlan() + " qos " + qos);
        }
        LOGGER.debug("OvnNicTunableApplier.applyVfTunables: pf={} vf={} done", pfName, vfId);
    }

    /**
     * Apply ethtool-style generic NIC offload tunables (TSO/GSO/GRO/LRO/csum)
     * to a host-side netdev (typically the guest tap). Each knob is skipped
     * when the tunable is null on {@code nic}.
     *
     * <p>{@code csumOffload} flips both TX and RX checksum offload because
     * libvirt-managed virtio taps don't expose them separately.
     *
     * @param nic    populated NicTO (tunables may be null)
     * @param ifName host netdev to tune (e.g. {@code vnet42})
     */
    public static void applyEthtoolOffloads(final NicTO nic, final String ifName) {
        if (nic == null || StringUtils.isBlank(ifName)) {
            return;
        }
        runIfSet("tso", nic.getTso(),         v -> ethtoolKey(ifName, "tso", v));
        runIfSet("gso", nic.getGso(),         v -> ethtoolKey(ifName, "gso", v));
        runIfSet("gro", nic.getGro(),         v -> ethtoolKey(ifName, "gro", v));
        runIfSet("lro", nic.getLro(),         v -> ethtoolKey(ifName, "lro", v));
        if (nic.getCsumOffload() != null) {
            ethtoolKey(ifName, "tx", nic.getCsumOffload());
            ethtoolKey(ifName, "rx", nic.getCsumOffload());
        }
        if (nic.getMtu() != null && nic.getMtu() > 0) {
            Script.runSimpleBashScript(String.format("ip link set %s mtu %d", ifName, nic.getMtu()));
        }
    }

    /**
     * Stamp every {@link InterfaceDef}-level tunable from {@code nic} onto
     * {@code intf}: vhost driver name, vhost queues, tx/rx queue size,
     * packed virtqueues, plus the libvirt {@code <model type='...'/>} for
     * non-OVN paths that pick the model from the tunable. Each knob is
     * a no-op when the corresponding NicTO field is null.
     *
     * <p>The vhost driver name maps directly to libvirt's
     * {@code <driver name='vhost'|'qemu'/>} attribute. {@code vhost-net}
     * and {@code vhost-user} are normalized to {@code vhost} (libvirt's
     * canonical attribute value) because libvirt distinguishes these
     * via the interface {@code type} attribute, not the driver name.
     */
    public static void applyInterfaceDefTunables(final NicTO nic, final InterfaceDef intf) {
        if (nic == null || intf == null) {
            return;
        }
        if (StringUtils.isNotBlank(nic.getVhostDriver())) {
            intf.setVhostDriverName(normalizeVhostDriver(nic.getVhostDriver()));
        }
        if (nic.getVhostQueues() != null && nic.getVhostQueues() > 0) {
            intf.setMultiQueueNumber(nic.getVhostQueues());
        }
        if (nic.getVhostTxQueueSize() != null && nic.getVhostTxQueueSize() > 0) {
            intf.setTxQueueSize(nic.getVhostTxQueueSize());
        }
        if (nic.getVhostRxQueueSize() != null && nic.getVhostRxQueueSize() > 0) {
            intf.setRxQueueSize(nic.getVhostRxQueueSize());
        }
        if (nic.getVdpaPacked() != null) {
            intf.setPackedVirtQueues(nic.getVdpaPacked());
        }
        if (nic.getMtu() != null && nic.getMtu() > 0) {
            intf.setMtuSize(nic.getMtu());
        }
    }

    /**
     * Normalize the {@code ovn.vhost.driver} whitelist to libvirt's actual
     * {@code <driver name='...'/>} attribute. {@code vhost-net} and
     * {@code vhost-user} both map to the literal {@code "vhost"} that
     * libvirt expects on bridge/network interfaces; the vhost-user code
     * path uses {@code <interface type='vhostuser'>} which is selected
     * separately by NetworkOffering tagging.
     */
    public static String normalizeVhostDriver(final String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        final String trimmed = raw.trim().toLowerCase();
        if ("vhost-net".equals(trimmed) || "vhost-user".equals(trimmed) || "vhost".equals(trimmed)) {
            return "vhost";
        }
        if ("qemu".equals(trimmed)) {
            return "qemu";
        }
        return null;
    }

    /**
     * Resolve the libvirt {@link NicModel} from a tunable string. Returns
     * {@code null} (= caller keeps current model) on unknown values.
     */
    public static NicModel resolveDriverModel(final String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        final String trimmed = raw.trim().toLowerCase();
        switch (trimmed) {
            case "virtio":  return NicModel.VIRTIO;
            case "e1000":   return NicModel.E1000;
            case "rtl8139": return NicModel.RTL8139;
            case "vmxnet3": return NicModel.VMXNET3;
            case "ne2k_pci":return NicModel.NE2KPCI;
            default:        return null;
        }
    }

    /* ---------- private helpers ---------- */

    private static <T> void runIfSet(final String tag, final T value, final java.util.function.Consumer<T> body) {
        if (value == null) {
            return;
        }
        try {
            body.accept(value);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnNicTunableApplier: {} apply failed: {}", tag, e.getMessage());
        }
    }

    private static void ipLinkVf(final String pfName, final Integer vfId, final String suffix) {
        Script.runSimpleBashScript(String.format("ip link set %s vf %d %s", pfName, vfId, suffix));
    }

    private static void ethtoolKey(final String ifName, final String key, final boolean value) {
        Script.runSimpleBashScript(String.format("ethtool -K %s %s %s 2>/dev/null", ifName, key, onOff(value)));
    }

    private static String onOff(final Boolean value) {
        return Boolean.TRUE.equals(value) ? "on" : "off";
    }

    private static String onOff(final boolean value) {
        return value ? "on" : "off";
    }
}
