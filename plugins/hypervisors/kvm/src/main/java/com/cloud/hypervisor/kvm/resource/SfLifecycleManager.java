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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.CreateSfAnswer;
import com.cloud.agent.api.CreateSfCommand;
import com.cloud.agent.api.DestroySfAnswer;
import com.cloud.agent.api.DestroySfCommand;
import com.cloud.agent.api.InitHostSriovAnswer;
import com.cloud.agent.api.InitHostSriovCommand;
import com.cloud.utils.script.Script;

/**
 * Agent-side lifecycle manager for Mellanox Sub-Functions (SF) and vDPA devices.
 *
 * <p>Handles the full SF lifecycle on the KVM host:
 * <ol>
 *   <li>Create SF via devlink port add</li>
 *   <li>Configure MAC and activate the SF</li>
 *   <li>Rebind from mlx5_core.sf to mlx5_vdpa.vnet driver</li>
 *   <li>Wire the SF representor into OVS br-bond</li>
 *   <li>Tear down (reverse of creation)</li>
 * </ol>
 *
 * <p>Also provides VF initialization for SR-IOV passthrough and SF capability probing.
 *
 * <p>Uses {@link com.cloud.utils.script.Script#runSimpleBashScript(String)} for all
 * host commands, consistent with the pattern in {@link VfPassthroughVifDriver}.
 */
public class SfLifecycleManager {

    private static final Logger LOGGER = LogManager.getLogger(SfLifecycleManager.class);

    private static final String SYS_CLASS_NET = "/sys/class/net";
    private static final String SYS_BUS_AUX_DEVICES = "/sys/bus/auxiliary/devices";
    private static final String SYS_BUS_PCI_DEVICES = "/sys/bus/pci/devices";
    private static final String AUX_DRIVER_SF = "/sys/bus/auxiliary/drivers/mlx5_core.sf";
    private static final String AUX_DRIVER_VDPA = "/sys/bus/auxiliary/drivers/mlx5_vdpa.vnet";
    private static final String OVS_BRIDGE = "br-bond";

    private static final int SF_POLL_INTERVAL_MS = 200;
    private static final int SF_POLL_MAX_WAIT_MS = 10000;
    private static final int VF_POLL_MAX_WAIT_MS = 10000;

    private static final Pattern DEVLINK_PORT_PATTERN =
            Pattern.compile("(pci/[0-9a-fA-F:.]+/\\d+)");

    /**
     * Create a Mellanox Sub-Function, bind it to vDPA, and wire its representor into OVS.
     *
     * @param pfPciAddress PF PCI address, e.g. "0000:01:00.0"
     * @param pfIndex      PF index (0 or 1)
     * @param sfIndex      SF number for devlink (sfnum)
     * @param macAddress   MAC address to assign to the SF
     * @return CreateSfAnswer with all discovered identifiers
     */
    public CreateSfAnswer createSf(final CreateSfCommand cmd, final String pfPciAddress,
                                   final int pfIndex, final int sfIndex,
                                   final String macAddress) {
        String devlinkHandle = null;
        try {
            devlinkHandle = createDevlinkPort(pfPciAddress, pfIndex, sfIndex);
            configureAndActivateSf(devlinkHandle, macAddress);
            String auxDevice = waitForAuxDevice(pfPciAddress, sfIndex);
            String sfNetdev = findSfNetdev(pfIndex, sfIndex);
            bringUpNetdev(sfNetdev);
            rebindToVdpa(auxDevice);
            String vdpaDevice = waitForVdpaDevice();
            String repName = findSfNetdev(pfIndex, sfIndex);
            addRepresentorToOvs(repName);

            LOGGER.info("SF created: handle={}, netdev={}, rep={}, vdpa={}",
                    devlinkHandle, sfNetdev, repName, vdpaDevice);

            return new CreateSfAnswer(cmd, devlinkHandle, sfNetdev, repName, vdpaDevice);
        } catch (Exception e) {
            LOGGER.error("Failed to create SF pf={} pfIdx={} sfIdx={}: {}",
                    pfPciAddress, pfIndex, sfIndex, e.getMessage(), e);
            rollbackPartialSf(devlinkHandle);
            return new CreateSfAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Destroy a previously created Sub-Function: remove rep from OVS, deactivate, and delete.
     *
     * @param cmd             the destroy command
     * @param devlinkHandle   devlink port handle, e.g. "pci/0000:01:00.0/32768"
     * @param vdpaDevice      vhost-vdpa device path (informational, cleaned by kernel)
     * @param repName         representor netdev name
     * @return DestroySfAnswer
     */
    public DestroySfAnswer destroySf(final DestroySfCommand cmd, final String devlinkHandle,
                                     final String vdpaDevice, final String repName) {
        try {
            removeRepresentorFromOvs(repName);
            clearTcQdisc(repName);
            deactivateSf(devlinkHandle);
            deleteDevlinkPort(devlinkHandle);

            LOGGER.info("SF destroyed: handle={}, rep={}, vdpa={}", devlinkHandle, repName, vdpaDevice);
            return new DestroySfAnswer(cmd, true, null);
        } catch (Exception e) {
            LOGGER.error("Failed to destroy SF handle={}: {}", devlinkHandle, e.getMessage(), e);
            return new DestroySfAnswer(cmd, false, e.getMessage());
        }
    }

    /**
     * Initialize SR-IOV VFs on all Mellanox PFs and probe SF capability.
     *
     * @param cmd         the init command
     * @param numVfsPerPf number of VFs to create per PF
     * @return InitHostSriovAnswer with VF PCI list and sfCapable flag
     */
    public InitHostSriovAnswer initVfs(final InitHostSriovCommand cmd, final int numVfsPerPf) {
        List<String> allVfPciAddresses = new ArrayList<>();
        try {
            List<String> pfNames = discoverMellanoxPfs();
            if (pfNames.isEmpty()) {
                return new InitHostSriovAnswer(cmd, false, "No Mellanox PFs found on host");
            }

            for (String pfName : pfNames) {
                String pfPci = readPfPciAddress(pfName);
                if (pfPci == null) {
                    LOGGER.warn("Could not determine PCI address for PF {}, skipping", pfName);
                    continue;
                }
                enableVfs(pfPci, numVfsPerPf);
            }

            waitForVfDevices(SF_POLL_MAX_WAIT_MS);

            for (String pfName : pfNames) {
                String pfPci = readPfPciAddress(pfName);
                if (pfPci == null) {
                    continue;
                }
                List<String> vfAddrs = collectVfPciAddresses(pfPci);
                allVfPciAddresses.addAll(vfAddrs);
            }

            boolean sfCapable = probeSfCapability(pfNames);

            LOGGER.info("SR-IOV init complete: {} VFs discovered across {} PFs, sfCapable={}",
                    allVfPciAddresses.size(), pfNames.size(), sfCapable);

            return new InitHostSriovAnswer(cmd, allVfPciAddresses, sfCapable);
        } catch (Exception e) {
            LOGGER.error("Failed to init SR-IOV VFs: {}", e.getMessage(), e);
            return new InitHostSriovAnswer(cmd, false, e.getMessage());
        }
    }

    // ---- devlink port operations ----

    private String createDevlinkPort(final String pfPci, final int pfIndex, final int sfIndex) {
        String addCmd = String.format(
                "devlink port add pci/%s flavour pcisf pfnum %d sfnum %d",
                pfPci, pfIndex, sfIndex);
        String output = Script.runSimpleBashScript(addCmd);

        String showCmd = String.format(
                "devlink port show pci/%s/%d 2>/dev/null || devlink port show | grep 'sfnum %d'",
                pfPci, 32768 + sfIndex, sfIndex);
        String showOutput = Script.runSimpleBashScript(showCmd);

        String handle = parseDevlinkHandle(showOutput);
        if (handle == null) {
            handle = parseDevlinkHandle(output);
        }
        if (handle == null) {
            handle = String.format("pci/%s/%d", pfPci, 32768 + sfIndex);
            LOGGER.debug("Using computed devlink handle: {}", handle);
        }

        LOGGER.debug("Devlink port created: {} (raw output: {})", handle, output);
        return handle;
    }

    private void configureAndActivateSf(final String handle, final String macAddress) {
        String macCmd = String.format("devlink port function set %s hw_addr %s", handle, macAddress);
        Script.runSimpleBashScript(macCmd);
        LOGGER.debug("SF MAC set to {} on {}", macAddress, handle);

        String activateCmd = String.format("devlink port function set %s state active", handle);
        Script.runSimpleBashScript(activateCmd);
        LOGGER.debug("SF activated: {}", handle);
    }

    private void deactivateSf(final String handle) {
        String cmd = String.format("devlink port function set %s state inactive", handle);
        Script.runSimpleBashScript(cmd);
        LOGGER.debug("SF deactivated: {}", handle);
    }

    private void deleteDevlinkPort(final String handle) {
        String cmd = String.format("devlink port del %s", handle);
        Script.runSimpleBashScript(cmd);
        LOGGER.debug("Devlink port deleted: {}", handle);
    }

    // ---- auxiliary device and driver binding ----

    private String waitForAuxDevice(final String pfPci, final int sfIndex) throws InterruptedException {
        long deadline = System.currentTimeMillis() + SF_POLL_MAX_WAIT_MS;
        String expectedSuffix = String.format("sf.%d", sfIndex);

        while (System.currentTimeMillis() < deadline) {
            File auxDir = new File(SYS_BUS_AUX_DEVICES);
            String[] entries = auxDir.list();
            if (entries != null) {
                for (String entry : entries) {
                    if (entry.startsWith("mlx5_core.") && entry.contains("sf")) {
                        String sfnumOutput = readSysfsFile(
                                SYS_BUS_AUX_DEVICES + "/" + entry + "/sfnum");
                        if (sfnumOutput != null && sfnumOutput.trim().equals(String.valueOf(sfIndex))) {
                            LOGGER.debug("Found auxiliary device {} for sfnum {}", entry, sfIndex);
                            return entry;
                        }
                    }
                }
            }
            Thread.sleep(SF_POLL_INTERVAL_MS);
        }
        throw new RuntimeException(String.format(
                "Timeout waiting for auxiliary device with sfnum %d under %s", sfIndex, SYS_BUS_AUX_DEVICES));
    }

    private void rebindToVdpa(final String auxDevice) {
        File sfDriverBound = new File(AUX_DRIVER_SF + "/" + auxDevice);
        if (sfDriverBound.exists()) {
            String unbindCmd = String.format("echo %s > %s/unbind", auxDevice, AUX_DRIVER_SF);
            Script.runSimpleBashScript(unbindCmd);
            LOGGER.debug("Unbound {} from mlx5_core.sf", auxDevice);
        }

        String bindCmd = String.format("echo %s > %s/bind", auxDevice, AUX_DRIVER_VDPA);
        Script.runSimpleBashScript(bindCmd);
        LOGGER.debug("Bound {} to mlx5_vdpa.vnet", auxDevice);
    }

    private String waitForVdpaDevice() throws InterruptedException {
        long deadline = System.currentTimeMillis() + SF_POLL_MAX_WAIT_MS;

        String[] before = listVhostVdpaDevices();

        while (System.currentTimeMillis() < deadline) {
            String[] current = listVhostVdpaDevices();
            if (current.length > before.length) {
                for (String dev : current) {
                    if (!containsDevice(before, dev)) {
                        String path = "/dev/" + dev;
                        LOGGER.debug("New vhost-vdpa device detected: {}", path);
                        return path;
                    }
                }
            }
            Thread.sleep(SF_POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timeout waiting for vhost-vdpa device under /dev/");
    }

    // ---- netdev discovery ----

    private String findSfNetdev(final int pfIndex, final int sfIndex) {
        String expectedPhysPort = String.format("pf%dsf%d", pfIndex, sfIndex);
        File netDir = new File(SYS_CLASS_NET);
        String[] ifaces = netDir.list();
        if (ifaces == null) {
            throw new RuntimeException("Cannot list " + SYS_CLASS_NET);
        }
        for (String iface : ifaces) {
            String physPort = readSysfsFile(SYS_CLASS_NET + "/" + iface + "/phys_port_name");
            if (physPort != null && physPort.trim().equals(expectedPhysPort)) {
                LOGGER.debug("Found SF netdev {} with phys_port_name {}", iface, expectedPhysPort);
                return iface;
            }
        }
        throw new RuntimeException(String.format(
                "SF netdev with phys_port_name %s not found", expectedPhysPort));
    }

    private void bringUpNetdev(final String netdev) {
        String cmd = String.format("ip link set %s up", netdev);
        Script.runSimpleBashScript(cmd);
        LOGGER.debug("Brought up netdev {}", netdev);
    }

    // ---- OVS and TC operations ----

    private void addRepresentorToOvs(final String repName) {
        Script.runSimpleBashScript(
                String.format("ovs-vsctl --may-exist add-port %s %s", OVS_BRIDGE, repName));
        Script.runSimpleBashScript(
                String.format("tc qdisc add dev %s clsact 2>/dev/null", repName));
        LOGGER.info("Added SF representor {} to OVS {} with clsact qdisc", repName, OVS_BRIDGE);
    }

    private void removeRepresentorFromOvs(final String repName) {
        Script.runSimpleBashScript(
                String.format("ovs-vsctl --if-exists del-port %s %s", OVS_BRIDGE, repName));
        LOGGER.debug("Removed representor {} from OVS {}", repName, OVS_BRIDGE);
    }

    private void clearTcQdisc(final String repName) {
        Script.runSimpleBashScript(
                String.format("tc qdisc del dev %s clsact 2>/dev/null", repName));
        LOGGER.debug("Cleared clsact qdisc on {}", repName);
    }

    // ---- VF initialization ----

    private List<String> discoverMellanoxPfs() {
        List<String> pfNames = new ArrayList<>();
        File netDir = new File(SYS_CLASS_NET);
        String[] ifaces = netDir.list();
        if (ifaces == null) {
            return pfNames;
        }
        for (String iface : ifaces) {
            String physPortName = readSysfsFile(SYS_CLASS_NET + "/" + iface + "/phys_port_name");
            if (physPortName == null) {
                continue;
            }
            String trimmed = physPortName.trim();
            if ("p0".equals(trimmed) || "p1".equals(trimmed)) {
                pfNames.add(iface);
                LOGGER.debug("Discovered Mellanox PF: {} (phys_port_name={})", iface, trimmed);
            }
        }
        return pfNames;
    }

    private String readPfPciAddress(final String pfName) {
        try {
            Path deviceLink = Paths.get(SYS_CLASS_NET, pfName, "device");
            if (!Files.exists(deviceLink)) {
                return null;
            }
            Path realPath = deviceLink.toRealPath();
            return realPath.getFileName().toString();
        } catch (IOException e) {
            LOGGER.warn("Failed to read PCI address for PF {}: {}", pfName, e.getMessage());
            return null;
        }
    }

    private void enableVfs(final String pfPci, final int numVfs) {
        String sysPath = String.format("%s/%s/sriov_numvfs", SYS_BUS_PCI_DEVICES, pfPci);
        String cmd = String.format("echo %d > %s", numVfs, sysPath);
        Script.runSimpleBashScript(cmd);
        LOGGER.info("Enabled {} VFs on PF {}", numVfs, pfPci);
    }

    private void waitForVfDevices(final int maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            String output = Script.runSimpleBashScript("ls " + SYS_BUS_PCI_DEVICES + "/*/virtfn0 2>/dev/null");
            if (output != null && !output.trim().isEmpty()) {
                LOGGER.debug("VF devices appeared in sysfs");
                return;
            }
            Thread.sleep(SF_POLL_INTERVAL_MS);
        }
        LOGGER.warn("Timeout waiting for VF devices, proceeding with collection");
    }

    private List<String> collectVfPciAddresses(final String pfPci) {
        List<String> addresses = new ArrayList<>();
        File pfDir = new File(SYS_BUS_PCI_DEVICES + "/" + pfPci);
        String[] entries = pfDir.list();
        if (entries == null) {
            return addresses;
        }
        for (String entry : entries) {
            if (!entry.startsWith("virtfn")) {
                continue;
            }
            try {
                Path vfLink = Paths.get(SYS_BUS_PCI_DEVICES, pfPci, entry);
                Path realPath = vfLink.toRealPath();
                String vfPci = realPath.getFileName().toString();
                addresses.add(vfPci);
                LOGGER.debug("Discovered VF: {} -> {}", entry, vfPci);
            } catch (IOException e) {
                LOGGER.warn("Failed to resolve VF link {}/{}: {}", pfPci, entry, e.getMessage());
            }
        }
        return addresses;
    }

    private boolean probeSfCapability(final List<String> pfNames) {
        if (pfNames.isEmpty()) {
            return false;
        }
        String pfPci = readPfPciAddress(pfNames.get(0));
        if (pfPci == null) {
            return false;
        }
        String probeCmd = String.format(
                "devlink port add pci/%s flavour pcisf pfnum 0 sfnum 9999 2>&1", pfPci);
        String output = Script.runSimpleBashScript(probeCmd);

        if (output != null && !output.contains("not supported") && !output.contains("Operation not supported")) {
            Script.runSimpleBashScript(String.format(
                    "devlink port del pci/%s/42767 2>/dev/null", pfPci));
            LOGGER.info("SF capability confirmed on PF {} (pci/{})", pfNames.get(0), pfPci);
            return true;
        }
        LOGGER.info("SF capability NOT available on PF {} (pci/{}): {}", pfNames.get(0), pfPci, output);
        return false;
    }

    // ---- utility methods ----

    private String parseDevlinkHandle(final String output) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        Matcher m = DEVLINK_PORT_PATTERN.matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String[] listVhostVdpaDevices() {
        File devDir = new File("/dev");
        String[] entries = devDir.list();
        if (entries == null) {
            return new String[0];
        }
        List<String> vdpa = new ArrayList<>();
        for (String entry : entries) {
            if (entry.startsWith("vhost-vdpa-")) {
                vdpa.add(entry);
            }
        }
        return vdpa.toArray(new String[0]);
    }

    private boolean containsDevice(final String[] arr, final String name) {
        for (String s : arr) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private String readSysfsFile(final String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                return null;
            }
            return new String(Files.readAllBytes(p)).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void rollbackPartialSf(final String devlinkHandle) {
        if (devlinkHandle == null) {
            return;
        }
        try {
            LOGGER.warn("Rolling back partial SF creation: {}", devlinkHandle);
            Script.runSimpleBashScript(
                    String.format("devlink port function set %s state inactive 2>/dev/null", devlinkHandle));
            Script.runSimpleBashScript(
                    String.format("devlink port del %s 2>/dev/null", devlinkHandle));
        } catch (Exception rollbackEx) {
            LOGGER.error("Rollback of SF {} also failed: {}", devlinkHandle, rollbackEx.getMessage());
        }
    }
}
