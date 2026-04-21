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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.CreateVdpaAnswer;
import com.cloud.agent.api.CreateVdpaCommand;
import com.cloud.agent.api.DestroyVdpaAnswer;
import com.cloud.agent.api.DestroyVdpaCommand;
import com.cloud.utils.script.Script;

/**
 * Agent-side lifecycle for VF+vDPA on ConnectX-6 Dx (and any Mellanox adapter
 * that exposes each SR-IOV VF as a {@code mlx5_vdpa} auxiliary device).
 *
 * <p>CX-6 Dx does NOT support SF+vDPA — the {@code VIRTIO_NET_EMULATION_ENABLE}
 * firmware parameter is unavailable on the adapter. The VF-based alternative
 * is sufficient: {@code vdpa dev add name vdpa-&lt;N&gt; mgmtdev pci/&lt;vf-bdf&gt;}
 * binds one pre-provisioned VF as a {@code /dev/vhost-vdpa-&lt;K&gt;} chardev which
 * libvirt consumes via {@code &lt;interface type='vdpa'&gt;}.
 *
 * <p>Notes:
 * <ul>
 *   <li>{@code max_vqs=3} is a hardcoded mlx5_vdpa limit on CX-6 Dx (1 RX + 1 TX
 *       + 1 ctrl). Acceptable for the VR control plane — the data plane runs
 *       on the host OVS pipeline compiled to TC flower on the VF representor.</li>
 *   <li>Teardown rebinds the VF auxiliary device back to {@code mlx5_core}
 *       automatically; no PCI hot-unplug is required.</li>
 *   <li>OVS representor wiring is done by {@link VdpaVifDriver} during
 *       {@code plug()}; this manager is concerned only with the host-side
 *       {@code /dev/vhost-vdpa-*} lifecycle and VF MAC programming.</li>
 * </ul>
 */
public class VfVdpaLifecycleManager {

    private static final Logger LOGGER = LogManager.getLogger(VfVdpaLifecycleManager.class);

    private static final int POLL_INTERVAL_MS = 200;
    private static final int POLL_MAX_WAIT_MS = 10_000;

    /**
     * Bind a pre-provisioned VF as a vhost-vdpa chardev.
     *
     * @param cmd the originating agent command (echoed back into the answer)
     * @return answer with the {@code /dev/vhost-vdpa-*} path and the vDPA
     *         device name, or a failure answer with diagnostic details.
     */
    public CreateVdpaAnswer createVdpa(final CreateVdpaCommand cmd) {
        final String vfPciAddress = cmd.getVfPciAddress();
        final String pfName = cmd.getPfName();
        final String mac = cmd.getMac();

        if (StringUtils.isAnyBlank(vfPciAddress, pfName, mac)) {
            return new CreateVdpaAnswer(cmd, false,
                    "CreateVdpaCommand missing required fields (pci/pf/mac)");
        }

        final String vdpaName = buildVdpaName(pfName, vfPciAddress);

        try {
            removeStaleVdpaDevice(vdpaName);
            Script.runSimpleBashScript(String.format(
                    "vdpa dev add name %s mgmtdev pci/%s", vdpaName, vfPciAddress));
            Script.runSimpleBashScript("modprobe vhost_vdpa");

            final String vdpaDevice = waitForVhostVdpaDevice(vdpaName);
            if (vdpaDevice == null) {
                rollbackVdpa(vdpaName);
                return new CreateVdpaAnswer(cmd, false,
                        "Timed out waiting for /dev/vhost-vdpa-* to appear for " + vdpaName);
            }

            final int vfIndex = resolveVfIndex(pfName, vfPciAddress);
            if (vfIndex < 0) {
                rollbackVdpa(vdpaName);
                return new CreateVdpaAnswer(cmd, false,
                        "Failed to resolve VF index for " + vfPciAddress + " on PF " + pfName);
            }

            final String realPfName = resolvePfNetdevFromVfPci(vfPciAddress, pfName);
            Script.runSimpleBashScript(String.format(
                    "ip link set dev %s vf %d mac %s", realPfName, vfIndex, mac));

            LOGGER.info("VF+vDPA created: pfHint={} realPf={} vf={} pci={} vdpaName={} vdpaDev={} mac={}",
                    pfName, realPfName, vfIndex, vfPciAddress, vdpaName, vdpaDevice, mac);
            return new CreateVdpaAnswer(cmd, vdpaDevice, vdpaName);
        } catch (Exception e) {
            LOGGER.error("Failed to create vDPA device for VF {}: {}", vfPciAddress, e.getMessage(), e);
            rollbackVdpa(vdpaName);
            return new CreateVdpaAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Tear down a previously created vDPA device. Idempotent: returns success
     * if the device is already gone. The VF auxiliary device rebinds to
     * {@code mlx5_core} automatically.
     */
    public DestroyVdpaAnswer destroyVdpa(final DestroyVdpaCommand cmd) {
        final String vdpaName = cmd.getVdpaName();
        if (StringUtils.isBlank(vdpaName)) {
            return new DestroyVdpaAnswer(cmd, false, "DestroyVdpaCommand missing vdpaName");
        }
        try {
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
            LOGGER.info("VF+vDPA destroyed: vdpaName={}", vdpaName);
            return new DestroyVdpaAnswer(cmd, true, null);
        } catch (Exception e) {
            LOGGER.error("Failed to destroy vDPA device {}: {}", vdpaName, e.getMessage(), e);
            return new DestroyVdpaAnswer(cmd, false, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Derive a deterministic vDPA device name. The netlink name must be unique
     * across the host and ideally tied to the VF so we can recover cleanup
     * context if state is lost (e.g. agent restart).
     *
     * <p>Format: {@code vdpa-<pfName>vf<index-from-pci>}. Falls back to the
     * PCI BDF stripped of special characters if the PF / index resolution
     * fails (never rejects the request based on naming alone).
     */
    static String buildVdpaName(final String pfName, final String vfPciAddress) {
        int vfIndex = resolveVfIndex(pfName, vfPciAddress);
        if (vfIndex >= 0 && StringUtils.isNotBlank(pfName)) {
            return "vdpa-" + pfName + "vf" + vfIndex;
        }
        return "vdpa-" + vfPciAddress.replace(':', '_').replace('.', '_');
    }

    /**
     * Resolve the PF netdev (e.g. {@code dx6p0}) that owns a given VF PCI
     * by following the {@code physfn} sysfs symlink, regardless of any
     * possibly-stale {@code pfName} hint from the management-side pool.
     */
    private static String resolvePfNetdevFromVfPci(final String vfPciAddress, final String pfNameHint) {
        try {
            final Path vfPhysfn = Paths.get("/sys/bus/pci/devices", vfPciAddress, "physfn");
            final String pfPci = Files.readSymbolicLink(vfPhysfn).getFileName().toString();
            final Path pfNetDir = Paths.get("/sys/bus/pci/devices", pfPci, "net");
            if (Files.isDirectory(pfNetDir)) {
                try (Stream<Path> s = Files.list(pfNetDir)) {
                    for (Path nd : (Iterable<Path>) s::iterator) {
                        final String name = nd.getFileName().toString();
                        String ppn = "";
                        try {
                            ppn = new String(Files.readAllBytes(
                                    Paths.get("/sys/class/net", name, "phys_port_name"))).trim();
                        } catch (Exception ignored) {}
                        if (ppn.isEmpty() || !ppn.contains("vf")) {
                            return name;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return pfNameHint;
    }

    /**
     * Resolve the SR-IOV VF index for {@code vfPciAddress}. Walks
     * {@code /sys/bus/pci/devices/<vf>/physfn} to discover the owning PF
     * regardless of what {@code pfName} is passed — the management-side
     * {@code sriov_vf_pool} has been observed to mis-tag which PF owns a
     * given VF, and trusting that value causes {@code vdpa dev add} to
     * fail with "Failed to resolve VF index". The canonical mapping lives
     * in sysfs, so we derive both the PF and the index from it.
     *
     * @return the VF index (0..N), or {@code -1} if it could not be resolved.
     */
    static int resolveVfIndex(final String pfName, final String vfPciAddress) {
        if (StringUtils.isBlank(vfPciAddress)) {
            return -1;
        }
        // Discover the real PF PCI BDF via /sys/bus/pci/devices/<vf>/physfn.
        final Path vfPhysfn = Paths.get("/sys/bus/pci/devices", vfPciAddress, "physfn");
        String pfPci = null;
        try {
            pfPci = Files.readSymbolicLink(vfPhysfn).getFileName().toString();
        } catch (Exception ignored) {
            // fall through to pfName-based lookup below
        }
        if (pfPci != null) {
            final int idx = resolveVfIndexFromPfPci(pfPci, vfPciAddress);
            if (idx >= 0) {
                return idx;
            }
        }
        // Fallback: try the hint pfName (legacy path for hosts where physfn
        // symlink is not readable).
        return resolveVfIndexFromPfName(pfName, vfPciAddress);
    }

    private static int resolveVfIndexFromPfPci(final String pfPci, final String vfPciAddress) {
        final Path pfDevice = Paths.get("/sys/bus/pci/devices", pfPci);
        if (!Files.isDirectory(pfDevice)) {
            return -1;
        }
        try (Stream<Path> stream = Files.list(pfDevice)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("virtfn"))
                    .filter(p -> {
                        try {
                            return vfPciAddress.equals(Files.readSymbolicLink(p).getFileName().toString());
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .map(p -> {
                        try {
                            return Integer.parseInt(p.getFileName().toString().substring("virtfn".length()));
                        } catch (NumberFormatException ex) {
                            return -1;
                        }
                    })
                    .orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int resolveVfIndexFromPfName(final String pfName, final String vfPciAddress) {
        if (StringUtils.isBlank(pfName)) {
            return -1;
        }
        final Path pfDevice = Paths.get("/sys/class/net", pfName, "device");
        if (!Files.isDirectory(pfDevice)) {
            return -1;
        }
        try (Stream<Path> stream = Files.list(pfDevice)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("virtfn"))
                    .filter(p -> {
                        try {
                            return vfPciAddress.equals(Files.readSymbolicLink(p).getFileName().toString());
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .findFirst()
                    .map(p -> {
                        try {
                            return Integer.parseInt(p.getFileName().toString().substring("virtfn".length()));
                        } catch (NumberFormatException ex) {
                            return -1;
                        }
                    })
                    .orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Wait for a {@code /dev/vhost-vdpa-*} chardev to appear for {@code vdpaName}.
     * Polls sysfs {@code /sys/class/vdpa/<vdpaName>/} for the linked vhost-vdpa
     * chardev index, then resolves to {@code /dev/vhost-vdpa-<K>}.
     */
    String waitForVhostVdpaDevice(final String vdpaName) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + POLL_MAX_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            final String devicePath = lookupVhostVdpaDevice(vdpaName);
            if (devicePath != null) {
                return devicePath;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Reverse map {@code /dev/vhost-vdpa-N} back to the underlying SR-IOV VF
     * PCI BDF by walking sysfs:
     * <ul>
     *   <li>{@code /sys/bus/vdpa/devices/<name>/vhost-vdpa-N/} identifies
     *       which vDPA netlink device owns the chardev.</li>
     *   <li>{@code /sys/bus/vdpa/drivers/vhost_vdpa/<name>} is a symlink whose
     *       target contains the parent VF PCI (e.g.
     *       {@code .../devices/pci0000:00/.../0000:01:00.2/<name>}).</li>
     * </ul>
     *
     * <p>Returns {@code null} if the path cannot be resolved (caller falls back
     * to hostdev detection or skip).
     */
    public static String resolveVfPciFromVdpaDev(final String vdpaDevPath) {
        if (StringUtils.isBlank(vdpaDevPath)) {
            return null;
        }
        final String fileName = Paths.get(vdpaDevPath).getFileName().toString(); // vhost-vdpa-0
        final File devicesDir = new File("/sys/bus/vdpa/devices");
        final File[] vdpaDevs = devicesDir.listFiles();
        if (vdpaDevs == null) {
            return null;
        }
        String vdpaName = null;
        for (File d : vdpaDevs) {
            if (new File(d, fileName).exists()) {
                vdpaName = d.getName();
                break;
            }
        }
        if (vdpaName == null) {
            return null;
        }
        final Path symlink = Paths.get("/sys/bus/vdpa/drivers/vhost_vdpa/" + vdpaName);
        try {
            final String target = Files.readSymbolicLink(symlink).toString();
            // target like: ../../../../devices/pci0000:00/.../0000:01:00.2/<vdpaName>
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-9a-f])").matcher(target);
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return last;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String lookupVhostVdpaDevice(final String vdpaName) {
        // On current kernels the vdpa class symlink lives under
        // /sys/bus/vdpa/devices/<name>/, not /sys/class/vdpa/. The child
        // directory 'vhost-vdpa-K/' maps 1:1 to the /dev/vhost-vdpa-K chardev.
        final File sysDev = new File("/sys/bus/vdpa/devices/" + vdpaName);
        if (!sysDev.isDirectory()) {
            return null;
        }
        final File[] entries = sysDev.listFiles((dir, name) -> name.startsWith("vhost-vdpa-"));
        if (entries == null || entries.length == 0) {
            return null;
        }
        final String devNode = "/dev/" + entries[0].getName();
        return new File(devNode).exists() ? devNode : null;
    }

    private void removeStaleVdpaDevice(final String vdpaName) {
        final File sysDev = new File("/sys/bus/vdpa/devices/" + vdpaName);
        if (sysDev.exists()) {
            LOGGER.warn("Removing stale vDPA device {} before re-create", vdpaName);
            Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
        }
    }

    private void rollbackVdpa(final String vdpaName) {
        if (StringUtils.isBlank(vdpaName)) {
            return;
        }
        Script.runSimpleBashScript(String.format("vdpa dev del %s 2>/dev/null", vdpaName));
    }
}
