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
package com.cloud.network.ovn.element;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Maintains the per-tier {@code DNS} row attached to the tier
 * {@code Logical_Switch}. OVN's distributed DNS responder answers the
 * recorded names directly from the integration bridge.
 *
 * <p>The {@code records} map is a single OVSDB column whose lifecycle is
 * authoritative. Since {@code update} wholesale-replaces the column, every
 * mutation reads the live row first and performs a read-modify-write; no
 * management-server JVM owns an authoritative snapshot.
 *
 * <p>Helper bean (single-Provider invariant); invoked from
 * {@link OvnNetworkElement}.
 */
@Component
public class OvnDnsService {

    private static final Logger LOGGER = LogManager.getLogger(OvnDnsService.class);

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;

    /**
     * In-memory snapshot of {@code records} per CloudStack network id. The
     * authoritative copy lives in OVN; the snapshot is a write-through cache
     * to avoid a round-trip read before every update.
     */

    /**
     * Add (or replace) a {@code <hostname, ip>} record for the VM nic. Keys
     * are lowercased per RFC 1035.
     */
    public boolean addDnsEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vm) {
        if (network == null || nic == null) {
            return false;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return false;
        }
        final String hostname = pickHostname(vm, nic);
        if (StringUtils.isBlank(hostname) || StringUtils.isBlank(nic.getIPv4Address())) {
            return false;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        try {
            final String dnsUuid = ensureDnsRow(nb, controller, network);
            nb.mutateDnsRecord(dnsUuid, hostname.toLowerCase(), nic.getIPv4Address());
            LOGGER.info("OvnDnsService: registered {} -> {} on DNS {} (network id={})",
                    hostname, nic.getIPv4Address(), dnsUuid, network.getId());
            return true;
        } catch (OvnException e) {
            LOGGER.error("OvnDnsService.addDnsEntry failed for nic id={}: {}", nic.getId(), e.getMessage());
            return false;
        }
    }

    /** Drops the record for the supplied NIC's hostname. */
    public boolean removeDnsEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vm) {
        if (network == null || nic == null) {
            return false;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return false;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.DNS_RECORDS, network.getId(), controller.getId());
        if (mapping == null) {
            return true;
        }
            try {
                final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
            final Map<String, String> records = nb.readDnsRecords(mapping.getOvnUuid());
            final java.util.List<String> keys = new java.util.ArrayList<>();
            final String hostname = pickHostname(vm, nic);
            if (StringUtils.isNotBlank(hostname)) {
                keys.add(hostname.toLowerCase());
            }
            if (StringUtils.isNotBlank(nic.getIPv4Address())) {
                records.forEach((key, value) -> {
                    if (nic.getIPv4Address().equals(value)) keys.add(key);
                });
            }
            nb.removeDnsRecordKeys(mapping.getOvnUuid(), keys);
            return true;
        } catch (OvnException e) {
            LOGGER.warn("OvnDnsService.removeDnsEntry failed for nic id={}: {}", nic.getId(), e.getMessage());
            return false;
        }
    }

    /** Drop the per-tier DNS row entirely — invoked on tier destroy. */
    public void removeTierDns(final Network network) {
        if (network == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.DNS_RECORDS, network.getId(), controller.getId());
        if (mapping != null) {
            final OvnLogicalIdMapVO lsMapping = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
            if (lsMapping == null) {
                // Tier LS is already gone (e.g. shutdown() ran the LS-delete
                // before this method, or a prior version left the mapping
                // dangling). DNS is a root OVN NB table — an unreferenced
                // row is never garbage-collected by ovsdb-server, so it MUST
                // be deleted directly rather than emptied.
                try {
                    nb.deleteDnsRowDirect(mapping.getOvnUuid());
                } catch (OvnException ignored) {
                    // best-effort
                }
                logicalIdMapDao.remove(mapping.getId());
            } else {
                try {
                    nb.deleteDnsRecords(lsMapping.getOvnUuid(), mapping.getOvnUuid());
                } catch (OvnException e) {
                    LOGGER.warn("OvnDnsService.removeTierDns failed (network id={}): {}", network.getId(), e.getMessage());
                } finally {
                    logicalIdMapDao.remove(mapping.getId());
                }
            }
        }
        // Orphan sweep — drop any DNS rows tagged with this network's cs_id
        // whose CS-side mapping was already wiped (earlier failed tx, prior
        // plugin version pre-stale-guard). DNS is a root table with no
        // garbage collection, so each orphan is deleted directly rather
        // than emptied.
        for (final String orphan : nb.findUuidsByExternalIds("DNS",
                OvnConstants.EXT_ID_ID, String.valueOf(network.getId()))) {
            try {
                nb.deleteDnsRowDirect(orphan);
                LOGGER.info("OvnDnsService.removeTierDns: orphan DNS {} deleted (network id={})",
                        orphan, network.getId());
            } catch (OvnException ignored) {
                // best-effort
            }
        }
    }

    private String ensureDnsRow(final OvnNbClient nb, final OvnControllerVO controller, final Network network) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.DNS_RECORDS, network.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when NB row was deleted out-of-band.
            if (nb.rowExistsByUuid("DNS", existing.getOvnUuid())) {
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnDnsService: DNS_RECORDS mapping net={} -> {} stale; recreating",
                    network.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final OvnLogicalIdMapVO lsMapping = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        if (lsMapping == null) {
            throw new OvnException("OvnDnsService: tier LS mapping missing for network id=" + network.getId());
        }
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.DNS_RECORDS.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(network.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(network.getDataCenterId()));
        final String dnsUuid = nb.createDnsRecords(lsMapping.getOvnUuid(), Map.of(), ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.DNS_RECORDS, network.getId(), controller.getId(),
                dnsUuid, "dns-" + network.getId()));
        return dnsUuid;
    }

    /**
     * Resolve a hostname for the DNS record. Preference: VM hostname →
     * VM internal name → mac-derived fallback.
     */
    private static String pickHostname(final VirtualMachineProfile vm, final NicProfile nic) {
        if (vm != null && vm.getVirtualMachine() != null) {
            final String h = vm.getVirtualMachine().getHostName();
            if (StringUtils.isNotBlank(h)) {
                return h;
            }
            final String n = vm.getVirtualMachine().getInstanceName();
            if (StringUtils.isNotBlank(n)) {
                return n;
            }
        }
        if (nic != null && StringUtils.isNotBlank(nic.getMacAddress())) {
            return "vm-" + nic.getMacAddress().replace(":", "");
        }
        return null;
    }
}
