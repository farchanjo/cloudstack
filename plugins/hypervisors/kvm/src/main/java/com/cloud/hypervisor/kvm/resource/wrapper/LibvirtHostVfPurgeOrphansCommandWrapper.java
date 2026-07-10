//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.OvnVifDriver;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

/**
 * Purge orphan host-level NIC bindings: delete stranded vdpa-net devs,
 * rebind VFs stuck on {@code vfio-pci} back to {@code mlx5_core}, and free
 * residual OVS external_ids on FREE VF representors (Chaos B heal).
 *
 * <p>Three paths run inside one round-trip — see
 * {@link HostVfPurgeOrphansCommand} javadoc for the failure modes each
 * path is built to clear.
 *
 * <p>Cap: agent processes at most {@value #MAX_OPS_PER_PATH} ops per path
 * per call, returning a partial answer when more exist (operator runs the
 * command again). Prevents one runaway request from churning forever on
 * a host with thousands of stale entries.
 */
@ResourceWrapper(handles = HostVfPurgeOrphansCommand.class)
public final class LibvirtHostVfPurgeOrphansCommandWrapper extends
        CommandWrapper<HostVfPurgeOrphansCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOGGER = LogManager.getLogger(LibvirtHostVfPurgeOrphansCommandWrapper.class);

    /** Hard cap on operations per path — see class javadoc. */
    private static final int MAX_OPS_PER_PATH = 256;

    /** {@code vdpa} CLI binary path / name. */
    private static final String VDPA_BIN = "/usr/sbin/vdpa";

    /** Driver names interesting to this wrapper. */
    private static final String DRV_VFIO = "vfio-pci";
    private static final String DRV_MLX5 = "mlx5_core";

    /** sysfs roots used for VF rebind. */
    private static final Path SYS_PCI_DEVICES = Paths.get("/sys/bus/pci/devices");
    private static final Path SYS_DRV_MLX5 = Paths.get("/sys/bus/pci/drivers", DRV_MLX5);
    private static final Path SYS_DRV_VFIO = Paths.get("/sys/bus/pci/drivers", DRV_VFIO);

    @Override
    public Answer execute(final HostVfPurgeOrphansCommand cmd, final LibvirtComputingResource resource) {
        final Set<String> keepVdpa = cmd.getKeepVdpaNames() == null ? new HashSet<>() : cmd.getKeepVdpaNames();
        final Set<String> keepBdfs = cmd.getKeepPciBdfs() == null ? new HashSet<>() : cmd.getKeepPciBdfs();
        final boolean dryRun = cmd.isDryRun();

        final HostVfPurgeOrphansAnswer answer = new HostVfPurgeOrphansAnswer(cmd, true, "ok");

        if (cmd.isPurgeVdpa()) {
            try {
                purgeVdpa(keepVdpa, dryRun, answer);
            } catch (RuntimeException re) {
                LOGGER.warn("HostVfPurgeOrphans: vdpa purge failed: {}", re.getMessage());
            }
        }

        if (cmd.isRebindPassthroughVfs()) {
            try {
                rebindPassthroughVfs(keepBdfs, dryRun, answer);
            } catch (RuntimeException re) {
                LOGGER.warn("HostVfPurgeOrphans: vfio-pci rebind failed: {}", re.getMessage());
            }
        }

        // Step 3 runs AFTER vdpa delete + vfio rebind so force-release (empty
        // keep) has already unbound every previously-ALLOCATED VF; the FREE
        // heuristic then frees their residual OVS external_ids too. Periodic
        // sweep (vdpa/vfio flags off) only heals already-FREE residual leaks
        // and never touches live ALLOCATED bindings.
        if (cmd.isPurgeStaleOvsReps()) {
            try {
                purgeStaleOvsReps(dryRun, answer);
            } catch (RuntimeException re) {
                LOGGER.warn("HostVfPurgeOrphans: stale OVS FREE-rep purge failed: {}", re.getMessage());
            }
        }

        LOGGER.info("HostVfPurgeOrphans: vdpa[found={} kept={} deleted={}] vfio[scanned={} bound={} kept={} rebound={}] "
                        + "ovs[scanned={} freed={}] dryRun={}",
                answer.getVdpaFound(), answer.getVdpaKept(), answer.getVdpaDeleted(),
                answer.getVfsScanned(), answer.getVfsBoundVfio(), answer.getVfsKept(), answer.getVfsRebound(),
                answer.getOvsRepsScanned(), answer.getOvsRepsFreed(),
                dryRun);
        return answer;
    }

    /**
     * Step 3: free residual Chaos-B OVS bindings on FREE VF representors.
     * See {@link OvnVifDriver#freeStaleFreeVfRepresentors}.
     */
    private void purgeStaleOvsReps(final boolean dryRun, final HostVfPurgeOrphansAnswer answer) {
        final OvnVifDriver.FreeStaleOvsResult r =
                OvnVifDriver.freeStaleFreeVfRepresentors(LOGGER, "HostVfPurgeOrphans", dryRun);
        answer.setOvsRepsScanned(r.scanned);
        answer.setOvsRepsFreed(r.freed);
        answer.setOvsRepsFreedNames(r.freedNames);
    }

    // -------------------------------------------------------------------- vDPA

    /**
     * Step 1: {@code vdpa dev show} → delete every entry not in keep-set.
     */
    private void purgeVdpa(final Set<String> keep, final boolean dryRun, final HostVfPurgeOrphansAnswer answer) {
        final List<String> all = listVdpaDevs();
        answer.setVdpaFound(all.size());

        int kept = 0;
        int deleted = 0;
        final List<String> deletedNames = new ArrayList<>();

        for (final String name : all) {
            if (keep.contains(name)) {
                kept++;
                continue;
            }
            if (deleted >= MAX_OPS_PER_PATH) {
                LOGGER.warn("HostVfPurgeOrphans: vdpa cap {} reached", MAX_OPS_PER_PATH);
                break;
            }
            if (dryRun) {
                deleted++;
                if (deletedNames.size() < 64) {
                    deletedNames.add(name);
                }
                continue;
            }
            try {
                deleteVdpaDev(name);
                deleted++;
                if (deletedNames.size() < 64) {
                    deletedNames.add(name);
                }
            } catch (RuntimeException re) {
                LOGGER.warn("HostVfPurgeOrphans: failed to delete vdpa {}: {}", name, re.getMessage());
            }
        }

        answer.setVdpaKept(kept);
        answer.setVdpaDeleted(deleted);
        answer.setVdpaDeletedNames(deletedNames);
    }

    /**
     * {@code vdpa dev show} parsed into a list of device names. Returns an
     * empty list when the binary is absent (legacy host w/o vdpa support).
     */
    static List<String> listVdpaDevs() {
        final OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        final Script script = new Script(VDPA_BIN, 5_000, LOGGER);
        script.add("dev");
        script.add("show");
        final String err = script.execute(parser);
        if (err != null) {
            LOGGER.debug("HostVfPurgeOrphans: vdpa list returned non-zero: {}", err);
            return new ArrayList<>();
        }
        final String output = parser.getLines();
        if (StringUtils.isBlank(output)) {
            return new ArrayList<>();
        }
        final List<String> names = new ArrayList<>();
        for (final String line : Arrays.asList(output.split("\\R"))) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            // "vdpa-XXXX: type network mgmtdev pci/0000:01:00.3 ..."
            final int colon = line.indexOf(':');
            final String name = colon > 0 ? line.substring(0, colon).trim() : line.trim();
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }
        return names;
    }

    static void deleteVdpaDev(final String name) {
        final Script script = new Script(VDPA_BIN, 5_000, LOGGER);
        script.add("dev");
        script.add("del");
        script.add(name);
        final String err = script.execute();
        if (err != null) {
            throw new RuntimeException("vdpa dev del " + name + " failed: " + err);
        }
    }

    // ----------------------------------------------------------- VF passthrough

    /**
     * Step 2: walk every BDF currently bound to {@code vfio-pci}, unbind +
     * clear driver_override + bind to {@code mlx5_core}. Skip BDFs in
     * keep-set. Caller's keep-set protects active hostdev passthrough
     * bindings still in use by running guests.
     */
    private void rebindPassthroughVfs(final Set<String> keepBdfs, final boolean dryRun,
                                      final HostVfPurgeOrphansAnswer answer) {
        final List<String> vfioBdfs = listBdfsBoundTo(SYS_DRV_VFIO);
        // Total VFs scanned = number of devices currently visible under either
        // mlx5_core or vfio-pci (the only two drivers we touch). Useful as a
        // reality check for the operator.
        final int total = vfioBdfs.size() + listBdfsBoundTo(SYS_DRV_MLX5).size();
        answer.setVfsScanned(total);
        answer.setVfsBoundVfio(vfioBdfs.size());

        int kept = 0;
        int rebound = 0;
        final List<String> reboundBdfs = new ArrayList<>();

        for (final String bdf : vfioBdfs) {
            if (keepBdfs.contains(bdf)) {
                kept++;
                continue;
            }
            if (rebound >= MAX_OPS_PER_PATH) {
                LOGGER.warn("HostVfPurgeOrphans: vfio rebind cap {} reached", MAX_OPS_PER_PATH);
                break;
            }
            if (dryRun) {
                rebound++;
                if (reboundBdfs.size() < 64) {
                    reboundBdfs.add(bdf);
                }
                continue;
            }
            try {
                rebindOne(bdf);
                rebound++;
                if (reboundBdfs.size() < 64) {
                    reboundBdfs.add(bdf);
                }
            } catch (RuntimeException re) {
                LOGGER.warn("HostVfPurgeOrphans: failed to rebind {}: {}", bdf, re.getMessage());
            }
        }

        answer.setVfsKept(kept);
        answer.setVfsRebound(rebound);
        answer.setVfsReboundBdfs(reboundBdfs);
    }

    /**
     * List PCI BDFs (e.g. {@code 0000:01:04.3}) currently bound to the
     * driver at {@code driverDir}. Each driver dir contains symlinks named
     * after each bound BDF.
     */
    static List<String> listBdfsBoundTo(final Path driverDir) {
        final List<String> out = new ArrayList<>();
        if (!Files.isDirectory(driverDir)) {
            return out;
        }
        final File[] entries = driverDir.toFile().listFiles();
        if (entries == null) {
            return out;
        }
        for (final File f : entries) {
            // Bound devices appear as symlinks; non-symlinks (uevent, bind,
            // unbind) are skipped via the BDF regex.
            final String name = f.getName();
            if (name.matches("[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-9a-f]")) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Unbind from {@code vfio-pci}, clear driver_override, bind to
     * {@code mlx5_core}. Idempotent: skips steps that are already in the
     * desired state.
     */
    static void rebindOne(final String bdf) {
        final Path devPath = SYS_PCI_DEVICES.resolve(bdf);
        if (!Files.isDirectory(devPath)) {
            throw new RuntimeException("device " + bdf + " not found in sysfs");
        }
        final Path driverLink = devPath.resolve("driver");
        if (Files.exists(driverLink) && DRV_VFIO.equals(currentDriverOf(driverLink))) {
            writeSysfs(SYS_DRV_VFIO.resolve("unbind"), bdf);
        }
        // Clear driver_override so the next bind call hits the matching
        // driver via id-table rather than the override.
        final Path override = devPath.resolve("driver_override");
        if (Files.exists(override)) {
            writeSysfs(override, "\n");
        }
        // bind to mlx5_core (only if not already there).
        if (!DRV_MLX5.equals(currentDriverOf(driverLink))) {
            writeSysfs(SYS_DRV_MLX5.resolve("bind"), bdf);
        }
    }

    /**
     * Resolve the symlink {@code /sys/bus/pci/devices/<bdf>/driver} to the
     * basename of the driver dir (e.g. {@code mlx5_core} or
     * {@code vfio-pci}). Returns {@code null} when no driver is bound.
     */
    static String currentDriverOf(final Path driverLink) {
        try {
            if (!Files.exists(driverLink)) {
                return null;
            }
            final Path target = driverLink.toRealPath();
            return target.getFileName().toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Write a string to a sysfs node. Wrapped so the rebind helper has a
     * single I/O surface and {@link Files#write} surfaces translate into
     * unchecked errors the loop can catch + log.
     */
    static void writeSysfs(final Path target, final String value) {
        try {
            Files.write(target, value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("write to " + target + " failed: " + e.getMessage(), e);
        }
    }
}
