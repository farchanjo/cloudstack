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
package com.cloud.hypervisor.kvm.resource.ovs;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;

import com.cloud.utils.script.Script;

/**
 * Sweep OVS bridges for {@code vnet*} ports whose libvirt-side tap interface
 * is gone, and remove them.
 *
 * <p>Background: when a VM stops or migrates, libvirt destroys the kernel tap
 * device but the OVS port reference (added in {@code OvsVifDriver.attach})
 * stays in {@code conf.db} unless {@code OvsVifDriver.unplug} ran the explicit
 * {@code ovs-vsctl del-port}. The lingering port has {@code ofport=-1}, no
 * link, and serves only to confuse FDB/flooding paths and survive across
 * reboots through OVSDB persistence.
 *
 * <p>This reconciler is invoked once on agent startup, after {@code VxlanTunnelManager}
 * and {@code DvrManager} have replayed their state. It is intentionally a
 * one-shot operation — periodic sweeps are unnecessary because the unplug-side
 * fix in {@code OvsVifDriver} keeps the bridges clean during normal VM
 * lifecycle, and a startup pass catches everything that survived a crash.
 */
public final class OvsPortReconciler {

    private static final Logger LOGGER = LogManager.getLogger(OvsPortReconciler.class);
    private static final List<String> BRIDGES = Arrays.asList("br-bond", "cloud0");
    private static final int OVS_TIMEOUT_MS = 10_000;
    private static final String VNET_PREFIX = "vnet";

    private OvsPortReconciler() {
    }

    /**
     * Walk every configured OVS bridge, list its {@code vnet*} ports, and
     * delete those that do not appear in the live libvirt domain XMLs.
     *
     * @param connect active libvirt connection; if {@code null} the reconciler
     *                logs a warning and returns 0 (treats all ports as live to
     *                avoid false positives during connection blips).
     * @return number of ghost ports actually removed across all bridges.
     */
    public static synchronized int reconcileGhostVnetPorts(Connect connect) {
        if (connect == null) {
            LOGGER.warn("OvsPortReconciler: libvirt Connect is null, skipping reconcile");
            return 0;
        }
        Set<String> liveTaps = collectLiveTaps(connect);
        int removed = 0;
        for (String bridge : BRIDGES) {
            for (String port : listOvsVnetPorts(bridge)) {
                if (!liveTaps.contains(port)) {
                    if (deletePort(bridge, port)) {
                        removed++;
                        LOGGER.info("OvsPortReconciler: removed ghost port {}/{}", bridge, port);
                    }
                }
            }
        }
        if (removed == 0) {
            LOGGER.info("OvsPortReconciler: no ghost ports across {} (live taps={})", BRIDGES, liveTaps.size());
        } else {
            LOGGER.info("OvsPortReconciler: removed {} ghost vnet port(s); live taps={}", removed, liveTaps.size());
        }
        return removed;
    }

    private static Set<String> collectLiveTaps(Connect connect) {
        Set<String> taps = new LinkedHashSet<>();
        try {
            int[] ids = connect.listDomains();
            if (ids != null) {
                for (int id : ids) {
                    try {
                        String xml = connect.domainLookupByID(id).getXMLDesc(0);
                        extractTaps(xml, taps);
                    } catch (LibvirtException le) {
                        LOGGER.debug("OvsPortReconciler: dump xml for domain id={} failed: {}", id, le.getMessage());
                    }
                }
            }
        } catch (LibvirtException e) {
            LOGGER.warn("OvsPortReconciler: connect.listDomains failed: {}", e.getMessage());
        }
        return taps;
    }

    private static void extractTaps(String xml, Set<String> out) {
        if (StringUtils.isBlank(xml)) {
            return;
        }
        // Cheap forward-scan for tokens of the form: target dev='vnetN'
        // Avoids dragging in an XML parser for a hot startup path.
        final String marker = "target dev='";
        int idx = 0;
        while ((idx = xml.indexOf(marker, idx)) >= 0) {
            int start = idx + marker.length();
            int end = xml.indexOf('\'', start);
            if (end <= start) {
                break;
            }
            String tap = xml.substring(start, end);
            if (tap.startsWith(VNET_PREFIX)) {
                out.add(tap);
            }
            idx = end + 1;
        }
    }

    private static Set<String> listOvsVnetPorts(String bridge) {
        Set<String> out = new LinkedHashSet<>();
        String cmd = String.format("ovs-vsctl --if-exists list-ports %s", bridge);
        try {
            String result = Script.runSimpleBashScript(cmd, OVS_TIMEOUT_MS);
            if (StringUtils.isNotBlank(result)) {
                for (String line : result.split("\\s+")) {
                    String s = line.trim();
                    if (s.startsWith(VNET_PREFIX)) {
                        out.add(s);
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("OvsPortReconciler: list-ports {}: {}", bridge, e.getMessage());
        }
        return out;
    }

    private static boolean deletePort(String bridge, String port) {
        String cmd = String.format("ovs-vsctl --if-exists del-port %s %s", bridge, port);
        try {
            Script.runSimpleBashScript(cmd, OVS_TIMEOUT_MS);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("OvsPortReconciler: del-port {}/{}: {}", bridge, port, e.getMessage());
            return false;
        }
    }
}
