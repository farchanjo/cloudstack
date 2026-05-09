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
package com.cloud.network.ovn.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.offering.NetworkOffering;

/**
 * OVN NIC tunable contract — lives in the {@code cloud-api} module so both
 * the server (which orchestrates and resolves tunables) and the OVN plugin
 * (which registers the ConfigKey defaults) can reference the same canonical
 * key strings and the same {@link #resolve} implementation without forcing
 * a server -> OVN plugin dependency.
 *
 * <p>Resolution order (highest wins):
 * <ol>
 *   <li>VM detail (from {@code user_vm_details} / {@code vm_instance_details})</li>
 *   <li>Network detail (from {@code network_details})</li>
 *   <li>Network offering detail (the closed
 *       {@link NetworkOffering.Detail} enum bucket; matched case-insensitively
 *       against the canonical key string)</li>
 *   <li>Global default supplied by the caller (typically {@code ConfigKey.value()})</li>
 *   <li>Hardcoded fallback baked into the global ConfigKey</li>
 * </ol>
 *
 * <p>Each layer stores values as strings; type coercion (Boolean / Integer /
 * String) happens inside {@link #resolve}. Whitelisted enums are validated
 * against {@link #ALLOWED_VALUES}; an out-of-whitelist value is logged and
 * the next layer is consulted (so a typo never silently flips an unrelated
 * knob).
 *
 * <p>Companion class: {@code com.cloud.network.ovn.config.OvnNicConfig} in
 * the {@code cloud-plugin-network-ovn} module owns the {@code ConfigKey<?>}
 * defaults plus the {@code Configurable} contract; this file owns the
 * canonical string keys and the resolution algorithm so they stay in lock
 * step.
 */
public final class OvnNicTunables {

    private static final Logger LOGGER = LogManager.getLogger(OvnNicTunables.class);

    /* ---------- Canonical detail key strings ---------- */

    /* vDPA tunables */
    public static final String OVN_VDPA_MAX_VQS = "ovn.vdpa.max_vqs";
    public static final String OVN_VDPA_QUEUE_PAIRS = "ovn.vdpa.queue_pairs";
    public static final String OVN_VDPA_EVENT_IDX = "ovn.vdpa.event_idx";
    public static final String OVN_VDPA_INDIRECT_DESC = "ovn.vdpa.indirect_desc";
    public static final String OVN_VDPA_IOMMU = "ovn.vdpa.iommu";
    public static final String OVN_VDPA_PACKED = "ovn.vdpa.packed";

    /* SR-IOV VF tunables */
    public static final String OVN_VF_TRUST = "ovn.vf.trust";
    public static final String OVN_VF_SPOOFCHECK = "ovn.vf.spoofcheck";
    public static final String OVN_VF_LINK_STATE = "ovn.vf.link_state";
    public static final String OVN_VF_MAX_TX_RATE = "ovn.vf.max_tx_rate";
    public static final String OVN_VF_MIN_TX_RATE = "ovn.vf.min_tx_rate";
    public static final String OVN_VF_VLAN = "ovn.vf.vlan";
    public static final String OVN_VF_QOS = "ovn.vf.qos";

    /* vhost / multiqueue tunables */
    public static final String OVN_VHOST_QUEUES = "ovn.vhost.queues";
    public static final String OVN_VHOST_DRIVER = "ovn.vhost.driver";
    public static final String OVN_VHOST_TX_QUEUE_SIZE = "ovn.vhost.tx_queue_size";
    public static final String OVN_VHOST_RX_QUEUE_SIZE = "ovn.vhost.rx_queue_size";

    /* Generic NIC tunables */
    public static final String OVN_MTU = "ovn.mtu";
    public static final String OVN_TSO = "ovn.tso";
    public static final String OVN_GSO = "ovn.gso";
    public static final String OVN_GRO = "ovn.gro";
    public static final String OVN_LRO = "ovn.lro";
    public static final String OVN_CSUM_OFFLOAD = "ovn.csum_offload";
    public static final String OVN_DRIVER_MODEL = "ovn.driver_model";

    /* OVS / TC offload */
    public static final String OVN_TC_OFFLOAD = "ovn.tc.offload";

    /**
     * OVS Port other_config:hairpin. Required for VF&lt;-&gt;VF same-host hardware
     * offload via TC flower / mlx5 eswitch. Applied per port on every br-int
     * attach the OVN plugin performs (VF representor, vhost-vdpa OVS port,
     * virtio tap).
     */
    public static final String OVN_OVS_HAIRPIN = "ovn.ovs.hairpin";

    /**
     * Open_vSwitch other_config:tc-policy. Bridge-wide knob applied once per
     * agent at OVN-aware setup. Whitelist: {@code none|skip_sw|skip_hw}.
     * {@code none} keeps software fallback when hardware offload misses;
     * {@code skip_sw} drops on hardware-miss; {@code skip_hw} forces
     * software-only datapath.
     */
    public static final String OVN_OVS_TC_POLICY = "ovn.ovs.tc.policy";

    /* OVN binding / chassis */
    public static final String OVN_REQUESTED_CHASSIS = "ovn.requested_chassis";
    public static final String OVN_HA_CHASSIS_PRIORITY = "ovn.ha_chassis_priority";
    public static final String OVN_BFD_ENABLE = "ovn.bfd.enable";
    public static final String OVN_BFD_MIN_RX = "ovn.bfd.min_rx";
    public static final String OVN_BFD_MIN_TX = "ovn.bfd.min_tx";
    public static final String OVN_BFD_MULTIPLIER = "ovn.bfd.multiplier";

    /* Conntrack timeouts */
    public static final String OVN_CT_SNAT_INACTIVE_TIMEOUT = "ovn.ct.snat_inactive_timeout";
    public static final String OVN_CT_TCP_INACTIVE_TIMEOUT = "ovn.ct.tcp_inactive_timeout";
    public static final String OVN_CT_UDP_INACTIVE_TIMEOUT = "ovn.ct.udp_inactive_timeout";
    public static final String OVN_CT_ICMP_INACTIVE_TIMEOUT = "ovn.ct.icmp_inactive_timeout";

    /* Load_Balancer options */
    /**
     * OVN {@code Load_Balancer.options:affinity_timeout} (seconds). 0 disables
     * affinity (OVN default). When positive, OVN persists the client-to-backend
     * mapping for at least this many seconds after the last packet, emulating
     * session persistence without a conntrack entry.
     */
    public static final String OVN_LB_AFFINITY_TIMEOUT = "ovn.lb.affinity_timeout";

    /* LSP binding options */
    /**
     * OVN {@code Logical_Switch_Port.options:arp_proxy}. When true and the LSP
     * has a bound IPv4 (and optionally IPv6) address, the plugin writes
     * {@code options:arp_proxy=<ip>} on the LSP so OVN's pipeline answers ARP
     * queries for that IP on behalf of the port, preventing ARP flooding on the
     * logical segment.
     */
    public static final String OVN_LSP_ARP_PROXY = "ovn.lsp.arp_proxy";

    /* ---------- Whitelists for enum-typed knobs ---------- */

    public static final Map<String, Set<String>> ALLOWED_VALUES = Collections.unmodifiableMap(
            Map.of(
                    OVN_VF_LINK_STATE, new HashSet<>(Arrays.asList("auto", "enable", "disable")),
                    OVN_VHOST_DRIVER, new HashSet<>(Arrays.asList("vhost-net", "vhost-user")),
                    OVN_DRIVER_MODEL, new HashSet<>(Arrays.asList("virtio", "e1000", "rtl8139", "vmxnet3")),
                    OVN_OVS_TC_POLICY, new HashSet<>(Arrays.asList("none", "skip_sw", "skip_hw"))
            ));

    /* ---------- Resolution chain ---------- */

    /**
     * Resolve {@code key} across the four configuration scopes in priority
     * order. Each scope may be {@code null} (skipped). Type coercion is
     * driven by {@code type} — only {@link Boolean}, {@link Integer} and
     * {@link String} are accepted.
     *
     * @param key             canonical detail name (e.g. {@link #OVN_MTU})
     * @param vmDetails       VM-level detail map
     * @param netDetails      network-level detail map
     * @param offeringDetails offering-level detail map
     * @param globalDefault   fallback when no detail layer wins
     * @param type            target Java type (Boolean.class, Integer.class, String.class)
     * @param <T>             target Java type parameter
     * @return resolved value; falls back to {@code globalDefault} when no layer parses
     */
    public static <T> T resolve(final String key,
                                final Map<String, String> vmDetails,
                                final Map<String, String> netDetails,
                                final Map<NetworkOffering.Detail, String> offeringDetails,
                                final T globalDefault,
                                final Class<T> type) {
        if (key == null || type == null) {
            return globalDefault;
        }
        T resolved = pickFromMap(key, vmDetails, type);
        if (resolved != null) {
            LOGGER.debug("OvnNicTunables.resolve: key={} -> from VM detail", key);
            return resolved;
        }
        resolved = pickFromMap(key, netDetails, type);
        if (resolved != null) {
            LOGGER.debug("OvnNicTunables.resolve: key={} -> from network detail", key);
            return resolved;
        }
        resolved = pickFromOfferingDetails(key, offeringDetails, type);
        if (resolved != null) {
            LOGGER.debug("OvnNicTunables.resolve: key={} -> from offering detail", key);
            return resolved;
        }
        return globalDefault;
    }

    private static <T> T pickFromMap(final String key, final Map<String, String> source, final Class<T> type) {
        if (source == null) {
            return null;
        }
        final String raw = source.get(key);
        return coerce(key, raw, type);
    }

    private static <T> T pickFromOfferingDetails(final String key,
                                                 final Map<NetworkOffering.Detail, String> offeringDetails,
                                                 final Class<T> type) {
        if (offeringDetails == null || offeringDetails.isEmpty()) {
            return null;
        }
        for (Map.Entry<NetworkOffering.Detail, String> e : offeringDetails.entrySet()) {
            if (e.getKey() != null && key.equalsIgnoreCase(e.getKey().toString())) {
                return coerce(key, e.getValue(), type);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T coerce(final String key, final String raw, final Class<T> type) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        final String trimmed = raw.trim();
        try {
            if (type == Boolean.class) {
                return (T) parseBoolean(trimmed);
            }
            if (type == Integer.class) {
                return (T) Integer.valueOf(trimmed);
            }
            if (type == String.class) {
                final Set<String> allowed = ALLOWED_VALUES.get(key);
                if (allowed != null && !allowed.contains(trimmed)) {
                    LOGGER.warn("OvnNicTunables.resolve: key={} value='{}' not in whitelist {}; rejecting",
                            key, trimmed, allowed);
                    return null;
                }
                return (T) trimmed;
            }
        } catch (NumberFormatException nfe) {
            LOGGER.warn("OvnNicTunables.resolve: key={} value='{}' not parseable as {}: {}",
                    key, trimmed, type.getSimpleName(), nfe.getMessage());
            return null;
        }
        LOGGER.warn("OvnNicTunables.resolve: key={} unsupported type {}", key, type.getName());
        return null;
    }

    private static Boolean parseBoolean(final String raw) {
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /* ---------- Range guards ---------- */

    public static boolean isValidVlan(final Integer vlan) {
        return vlan != null && vlan >= 0 && vlan <= 4094;
    }

    public static boolean isValidQos(final Integer qos) {
        return qos != null && qos >= 0 && qos <= 7;
    }

    public static boolean isValidRate(final Integer rate) {
        return rate != null && rate >= 0;
    }

    public static boolean isValidQueueCount(final Integer queues) {
        return queues != null && queues > 0;
    }

    private OvnNicTunables() {
        // Static-only helper.
    }
}
