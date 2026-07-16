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

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;

/**
 * Wires the per-tier {@code DHCP_Options} row and pins it on every plugged
 * LSP. OVN's distributed DHCP responder then answers requests on the
 * integration bridge — no dnsmasq / dhcpd needed inside the VR.
 *
 * <p>One {@code DHCP_Options} row per tier (keyed by tier CIDR) reused
 * across all NICs on that tier. The mapping is recorded under
 * {@link Kind#DHCP_OPTIONS} (and {@link Kind#DHCP_OPTIONS_V6} for IPv6) so
 * idempotent re-creates collapse to a no-op and tier-deletion drops the row.
 *
 * <p>This is a helper bean, not a CloudStack {@code NetworkElement}: it is
 * invoked from {@link OvnNetworkElement} to keep the plugin's single-Provider
 * registration invariant.
 */
@Component
public class OvnDhcpService {

    private static final Logger LOGGER = LogManager.getLogger(OvnDhcpService.class);

    /** Default DHCP lease (seconds). 24h matches CloudStack VR default. */
    public static final int DEFAULT_LEASE_SECS = 86_400;

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private DataCenterDao dataCenterDao;

    /**
     * Ensure the tier's DHCP_Options row exists and pin it on the LSP for
     * the supplied NIC. Safe to call repeatedly: existing rows are reused
     * and the LSP-side pin is replaced atomically.
     *
     * @return {@code true} when the row+pin land successfully; {@code false}
     *         when a precondition is missing (no controller / no LSP /
     *         missing CIDR) — caller decides whether to fall back.
     */
    public boolean ensureDhcpForNic(final Network network, final NicProfile nic) {
        if (nic == null || network == null) {
            return false;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            LOGGER.warn("OvnDhcpService: no OVN controller for zone {}", network.getDataCenterId());
            return false;
        }
        final OvnLogicalIdMapVO lspMapping = logicalIdMapDao.findByCsId(Kind.NIC, nic.getId(), controller.getId());
        if (lspMapping == null) {
            LOGGER.debug("OvnDhcpService: NIC id={} has no LSP yet; skipping DHCP pin", nic.getId());
            return false;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        try {
            if (StringUtils.isNotBlank(network.getCidr())) {
                final String dhcpUuid = ensureDhcpOptionsRow(nb, controller, network);
                nb.lspSetDhcpv4Options(lspMapping.getOvnUuid(), dhcpUuid);
                LOGGER.info("OvnDhcpService: pinned DHCPv4 {} on LSP {} (nic id={})",
                        dhcpUuid, lspMapping.getOvnUuid(), nic.getId());
            }
            if (StringUtils.isNotBlank(network.getIp6Cidr())) {
                final String dhcp6Uuid = ensureDhcpOptionsRowV6(nb, controller, network);
                nb.lspSetDhcpv6Options(lspMapping.getOvnUuid(), dhcp6Uuid);
                LOGGER.info("OvnDhcpService: pinned DHCPv6 {} on LSP {} (nic id={})",
                        dhcp6Uuid, lspMapping.getOvnUuid(), nic.getId());
            }
            return true;
        } catch (OvnException e) {
            LOGGER.error("OvnDhcpService: DHCP pin failed for nic id={}: {}", nic.getId(), e.getMessage());
            return false;
        }
    }

    /** Clear the LSP-side DHCP pin without touching the per-tier row. */
    public void clearDhcpForNic(final Network network, final NicProfile nic) {
        if (nic == null || network == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO lspMapping = logicalIdMapDao.findByCsId(Kind.NIC, nic.getId(), controller.getId());
        if (lspMapping == null) {
            return;
        }
        try {
            pluginManager.nbClient(network.getDataCenterId()).lspClearDhcpv4Options(lspMapping.getOvnUuid());
            pluginManager.nbClient(network.getDataCenterId()).lspClearDhcpv6Options(lspMapping.getOvnUuid());
        } catch (OvnException e) {
            LOGGER.warn("OvnDhcpService.clearDhcpForNic failed for nic id={}: {}", nic.getId(), e.getMessage());
        }
    }

    /** Drop the per-tier DHCP_Options row(s) — invoked from network destroy. */
    public void removeTierDhcp(final Network network) {
        if (network == null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        final OvnLogicalIdMapVO v4 = logicalIdMapDao.findByCsId(Kind.DHCP_OPTIONS, network.getId(), controller.getId());
        if (v4 != null) {
            try {
                nb.deleteDhcpOptions(v4.getOvnUuid());
            } catch (OvnException e) {
                LOGGER.warn("OvnDhcpService: DHCPv4 row {} delete failed: {}", v4.getOvnUuid(), e.getMessage());
            } finally {
                logicalIdMapDao.remove(v4.getId());
            }
        }
        // Orphan sweep: catch any DHCP_Options rows tagged with this network's
        // cs_id but whose CS-side mapping was already wiped (earlier failed
        // tx, manual cleanup, prior plugin version pre-stale-guard). Without
        // this NB rows accumulate forever — visible as growing
        // `ovn-nbctl list dhcp_options` between full VPC tear-downs.
        for (final String orphan : nb.findUuidsByExternalIds("DHCP_Options",
                OvnConstants.EXT_ID_ID, String.valueOf(network.getId()))) {
            try {
                nb.deleteDhcpOptions(orphan);
                LOGGER.info("OvnDhcpService.removeTierDhcp: orphan DHCP_Options {} swept (network id={})",
                        orphan, network.getId());
            } catch (OvnException ignored) {
                // best-effort; row may have just gone via cascade
            }
        }
        final OvnLogicalIdMapVO v6 = logicalIdMapDao.findByCsId(Kind.DHCP_OPTIONS_V6, network.getId(), controller.getId());
        if (v6 != null) {
            try {
                nb.deleteDhcpOptions(v6.getOvnUuid());
            } catch (OvnException e) {
                LOGGER.warn("OvnDhcpService: DHCPv6 row {} delete failed: {}", v6.getOvnUuid(), e.getMessage());
            } finally {
                logicalIdMapDao.remove(v6.getId());
            }
        }
    }

    private String ensureDhcpOptionsRow(final OvnNbClient nb, final OvnControllerVO controller, final Network network) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.DHCP_OPTIONS, network.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when NB row was deleted out-of-band.
            if (nb.rowExistsByUuid("DHCP_Options", existing.getOvnUuid())) {
                nb.updateDhcpOptions(existing.getOvnUuid(), buildDhcpv4Options(network));
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnDhcpService: DHCP_OPTIONS mapping net={} -> {} stale; recreating",
                    network.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final Map<String, String> options = buildDhcpv4Options(network);
        final Map<String, String> ext = buildExternalIds(network, Kind.DHCP_OPTIONS);
        final String uuid = nb.createDhcpOptions(network.getCidr(), options, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.DHCP_OPTIONS, network.getId(), controller.getId(), uuid,
                "dhcp4-" + network.getId()));
        return uuid;
    }

    private String ensureDhcpOptionsRowV6(final OvnNbClient nb, final OvnControllerVO controller, final Network network) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.DHCP_OPTIONS_V6, network.getId(), controller.getId());
        if (existing != null) {
            if (nb.rowExistsByUuid("DHCP_Options", existing.getOvnUuid())) {
                nb.updateDhcpOptions(existing.getOvnUuid(), buildDhcpv6Options(network));
                return existing.getOvnUuid();
            }
            LOGGER.warn("OvnDhcpService: DHCP_OPTIONS_V6 mapping net={} -> {} stale; recreating",
                    network.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final Map<String, String> options = buildDhcpv6Options(network);
        final Map<String, String> ext = buildExternalIds(network, Kind.DHCP_OPTIONS_V6);
        final String uuid = nb.createDhcpOptions(network.getIp6Cidr(), options, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.DHCP_OPTIONS_V6, network.getId(), controller.getId(), uuid,
                "dhcp6-" + network.getId()));
        return uuid;
    }

    /**
     * Build the OVN DHCPv4 options map. {@code server_id} doubles as the
     * gateway (CloudStack convention) so the VM sees one identity for both
     * the DHCP server and the default route.
     */
    private Map<String, String> buildDhcpv4Options(final Network network) {
        final Map<String, String> opts = new HashMap<>();
        final String gateway = StringUtils.trimToNull(network.getGateway());
        if (gateway != null) {
            opts.put("server_id", gateway);
        }
        opts.put("server_mac", deriveServerMac(gateway));
        opts.put("lease_time", String.valueOf(DEFAULT_LEASE_SECS));
        if (StringUtils.isNotBlank(gateway)) {
            opts.put("router", gateway);
        }
        // DNS: prefer the network's own dns1/dns2; fall back to the zone's
        // guest DNS when the network row carries none. CloudStack does NOT
        // persist the inherited zone DNS onto the guest network row (the
        // dns1/dns2 seen in the API response are synthesized at read-time),
        // so network.getDns1() is routinely null — without this fallback the
        // OVN DHCP responder would hand out no resolver at all.
        String dns1 = network.getDns1();
        String dns2 = network.getDns2();
        if (StringUtils.isBlank(dns1)) {
            final DataCenterVO zone = dataCenterDao.findById(network.getDataCenterId());
            if (zone != null) {
                dns1 = zone.getDns1();
                dns2 = zone.getDns2();
            }
        }
        if (StringUtils.isNotBlank(dns1)) {
            final StringBuilder dns = new StringBuilder("{").append(dns1);
            if (StringUtils.isNotBlank(dns2)) {
                dns.append(", ").append(dns2);
            }
            dns.append("}");
            opts.put("dns_server", dns.toString());
        }
        // MTU intentionally omitted: the Network base interface lacks a
        // getMtu() accessor in the targeted ACS branch; downstream overlays
        // pass the right MTU through agent-side virtio negotiation. Add via
        // a NIC detail (ovn.dhcp.mtu) in a follow-up if needed.
        return opts;
    }

    /**
     * Build the OVN DHCPv6 options map. {@code server_id} is the stable MAC
     * identity required by OVN's DHCPv6 responder.
     */
    private Map<String, String> buildDhcpv6Options(final Network network) {
        final Map<String, String> opts = new HashMap<>();
        opts.put("server_id", deriveServerMacV6(network.getIp6Gateway()));
        // Same zone-DNS fallback as v4 (see buildDhcpv4Options).
        String dns1 = network.getIp6Dns1();
        String dns2 = network.getIp6Dns2();
        if (StringUtils.isBlank(dns1)) {
            final DataCenterVO zone = dataCenterDao.findById(network.getDataCenterId());
            if (zone != null) {
                dns1 = zone.getIp6Dns1();
                dns2 = zone.getIp6Dns2();
            }
        }
        if (StringUtils.isNotBlank(dns1)) {
            final StringBuilder dns = new StringBuilder("{").append(dns1);
            if (StringUtils.isNotBlank(dns2)) {
                dns.append(", ").append(dns2);
            }
            dns.append("}");
            opts.put("dns_server", dns.toString());
        }
        return opts;
    }

    /**
     * Derive a deterministic MAC for OVN's DHCP responder from the gateway IP
     * (last 3 octets) so different tiers don't collide on the same chassis.
     * Matches the convention used by upstream OVN-Kubernetes.
     */
    private static String deriveServerMac(final String gatewayIp) {
        if (StringUtils.isBlank(gatewayIp) || !gatewayIp.contains(".")) {
            return "02:00:00:00:00:01";
        }
        final String[] octets = gatewayIp.trim().split("\\.");
        if (octets.length != 4) {
            return "02:00:00:00:00:01";
        }
        try {
            final int o1 = Integer.parseInt(octets[1]) & 0xff;
            final int o2 = Integer.parseInt(octets[2]) & 0xff;
            final int o3 = Integer.parseInt(octets[3]) & 0xff;
            return String.format("02:00:00:%02x:%02x:%02x", o1, o2, o3);
        } catch (NumberFormatException e) {
            return "02:00:00:00:00:01";
        }
    }

    private static String deriveServerMacV6(final String gw6) {
        if (StringUtils.isBlank(gw6)) {
            return "02:00:00:00:00:02";
        }
        final String hex = gw6.replace(":", "").replace("%", "");
        if (hex.length() < 6) {
            return "02:00:00:00:00:02";
        }
        try {
            final String tail = hex.substring(hex.length() - 6);
            return "02:00:" + tail.substring(0, 2) + ":" + tail.substring(2, 4) + ":" + tail.substring(4, 6) + ":02";
        } catch (RuntimeException e) {
            return "02:00:00:00:00:02";
        }
    }

    /** Stable external_ids for forensic + import flows. */
    private static Map<String, String> buildExternalIds(final Network network, final Kind kind) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, kind.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(network.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(network.getDataCenterId()));
        if (StringUtils.isNotBlank(network.getCidr())) {
            ext.put("cidr", network.getCidr());
        }
        // Touch NetUtils so the import is exercised; safe no-op signature check.
        if (NetUtils.isValidIp4(network.getGateway())) {
            ext.put("gw4", network.getGateway());
        }
        return ext;
    }
}
