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
package com.cloud.hypervisor.kvm.resource.hwoffload;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Maps a Virtual Function PCI address to its switchdev representor netdev name.
 * Used by {@code TcRuleProgrammer} to find the correct rep on which to install
 * TC flower rules.
 *
 * <p>The mapping is built by scanning {@code /sys/class/net/<iface>/phys_port_name}
 * for entries matching {@code pf?vf*} and resolving the underlying VF PCI via
 * {@code /sys/bus/pci/devices/<pf>/virtfnN}.
 *
 * <p>Cached and rebuilt on demand (representors are stable across the lifetime
 * of the host between reboots, so we only rebuild on explicit refresh or cache miss).
 */
public class RepresentorMapper {

    private static final Logger LOGGER = LogManager.getLogger(RepresentorMapper.class);

    private final Map<String, String> pciToRep = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean initialized = false;

    /**
     * Look up the representor netdev name for a VF PCI address. Triggers a refresh
     * if the cache is empty or the PCI is unknown. Returns null if the VF has no
     * representor (e.g. host is not in switchdev mode for that NIC).
     */
    public String getRepresentor(String vfPciAddress) {
        if (vfPciAddress == null || vfPciAddress.isEmpty()) {
            return null;
        }
        lock.readLock().lock();
        try {
            if (initialized) {
                String rep = pciToRep.get(vfPciAddress);
                if (rep != null) {
                    return rep;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        // Cache miss or first call — refresh and try again.
        refresh();
        lock.readLock().lock();
        try {
            return pciToRep.get(vfPciAddress);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Force a re-scan of /sys/class/net (call after VF rename / SR-IOV reconfig). */
    public void refresh() {
        Map<String, String> fresh = scan();
        lock.writeLock().lock();
        try {
            pciToRep.clear();
            pciToRep.putAll(fresh);
            initialized = true;
        } finally {
            lock.writeLock().unlock();
        }
        LOGGER.info("RepresentorMapper refreshed: {} VF→rep entries", fresh.size());
    }

    private Map<String, String> scan() {
        Map<String, String> result = new HashMap<>();
        File netDir = new File("/sys/class/net");
        String[] ifaces = netDir.list();
        if (ifaces == null) {
            return result;
        }
        for (String iface : ifaces) {
            File ppnFile = new File("/sys/class/net/" + iface + "/phys_port_name");
            if (!ppnFile.isFile()) {
                continue;
            }
            String ppn = readSysfs(ppnFile).trim();
            if (!ppn.matches("pf[01]vf\\d+")) {
                continue;
            }
            int vfIdx;
            try {
                vfIdx = Integer.parseInt(ppn.substring(ppn.indexOf("vf") + 2));
            } catch (NumberFormatException e) {
                continue;
            }
            // Resolve the parent PF PCI from the rep's device symlink.
            String parentPci = resolveParentPci(iface);
            if (parentPci == null) {
                continue;
            }
            String vfPci = resolveVfPciByIndex(parentPci, vfIdx);
            if (vfPci != null) {
                result.put(vfPci, iface);
            }
        }
        return result;
    }

    private String resolveParentPci(String iface) {
        try {
            return new File("/sys/class/net/" + iface + "/device").getCanonicalFile().getName();
        } catch (IOException e) {
            return null;
        }
    }

    private String resolveVfPciByIndex(String pfPci, int vfIdx) {
        File virtfn = new File("/sys/bus/pci/devices/" + pfPci + "/virtfn" + vfIdx);
        if (!virtfn.exists()) {
            return null;
        }
        try {
            return virtfn.getCanonicalFile().getName();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readSysfs(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
