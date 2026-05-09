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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.offering.NetworkOffering;

/**
 * OVN NIC tunables: ConfigKey registry + hierarchical resolution chain.
 *
 * <p>Resolution order (highest wins):
 * <ol>
 *   <li>VM detail (from {@code user_vm_details} / {@code vm_instance_details})</li>
 *   <li>Network detail (from {@code network_details})</li>
 *   <li>Network offering detail (from {@code network_offering_details}, keyed by
 *       {@link NetworkOffering.Detail}; only the {@code Other} bucket is used here)</li>
 *   <li>Global {@link ConfigKey} default</li>
 *   <li>Hardcoded fallback baked into the ConfigKey</li>
 * </ol>
 *
 * <p>Each layer stores values as strings; type coercion (Boolean / Integer)
 * happens inside {@link #resolve}. Whitelisted enums are validated against
 * {@link #ALLOWED_VALUES}; an out-of-whitelist value is logged and the next
 * layer is consulted (so a typo never silently flips an unrelated knob).
 *
 * <p>This class is the single source of truth for both:
 * <ul>
 *   <li>The set of OVN knobs that can be configured at any of the four scopes;</li>
 *   <li>The dictionary mapping detail keys (the literal strings the operator
 *       passes to {@code cmk createNetworkOffering details=…}) to ConfigKey
 *       defaults.</li>
 * </ul>
 *
 * <p>Keep names stable: they appear in operator runbooks and in the schema
 * tables {@code user_vm_details} / {@code network_details} /
 * {@code network_offering_details}.
 */
@Component
public class OvnNicConfig implements Configurable {

    private static final Logger LOGGER = LogManager.getLogger(OvnNicConfig.class);

    private static final String CATEGORY = "Network";

    /* ---------- Detail key constants (used everywhere as the canonical name) ---------- */

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
    public static final String OVN_DPDK_ENABLED = "ovn.dpdk.enabled";

    /**
     * Per-port {@code ovs-vsctl set Port <port> other_config:hairpin=true}
     * stamp. Default-on. The setting is required for VF&lt;-&gt;VF same-host
     * hardware offload via TC flower on mlx5 switchdev: without it the
     * eswitch refuses to short-circuit the VF-to-VF path through the
     * representor pair on br-int and the steady-state traffic falls back
     * to software, collapsing throughput by ~50x in our cluster.
     */
    public static final String OVN_OVS_HAIRPIN = "ovn.ovs.hairpin";

    /**
     * Bridge-wide {@code ovs-vsctl set Open_vSwitch . other_config:tc-policy}
     * stamp. Default {@code none} (HW offload with SW fallback). Applied
     * once per agent at OVN setup.
     */
    public static final String OVN_OVS_TC_POLICY = "ovn.ovs.tc.policy";

    /* OVN binding / chassis */
    public static final String OVN_REQUESTED_CHASSIS = "ovn.requested_chassis";
    public static final String OVN_HA_CHASSIS_PRIORITY = "ovn.ha_chassis_priority";
    public static final String OVN_BFD_ENABLE = "ovn.bfd.enable";
    public static final String OVN_BFD_MIN_RX = "ovn.bfd.min_rx";
    public static final String OVN_BFD_MIN_TX = "ovn.bfd.min_tx";
    public static final String OVN_BFD_MULTIPLIER = "ovn.bfd.multiplier";

    /* Load_Balancer options */
    public static final String OVN_LB_AFFINITY_TIMEOUT = "ovn.lb.affinity_timeout";

    /* LSP binding options */
    public static final String OVN_LSP_ARP_PROXY = "ovn.lsp.arp_proxy";

    /* Conntrack timeouts */
    public static final String OVN_CT_SNAT_INACTIVE_TIMEOUT = "ovn.ct.snat_inactive_timeout";
    public static final String OVN_CT_TCP_INACTIVE_TIMEOUT = "ovn.ct.tcp_inactive_timeout";
    public static final String OVN_CT_UDP_INACTIVE_TIMEOUT = "ovn.ct.udp_inactive_timeout";
    public static final String OVN_CT_ICMP_INACTIVE_TIMEOUT = "ovn.ct.icmp_inactive_timeout";

    /* ---------- Whitelists for enum-typed knobs ---------- */

    private static final Map<String, Set<String>> ALLOWED_VALUES = Map.of(
            OVN_VF_LINK_STATE, new HashSet<>(Arrays.asList("auto", "enable", "disable")),
            OVN_VHOST_DRIVER, new HashSet<>(Arrays.asList("vhost-net", "vhost-user")),
            OVN_DRIVER_MODEL, new HashSet<>(Arrays.asList("virtio", "e1000", "rtl8139", "vmxnet3")),
            OVN_OVS_TC_POLICY, new HashSet<>(Arrays.asList("none", "skip_sw", "skip_hw"))
    );

    /* ---------- ConfigKeys (global defaults) ---------- */

    public static final ConfigKey<Integer> VdpaMaxVqs = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VDPA_MAX_VQS, "33",
            "vDPA virtqueue count requested from `vdpa dev add ... max_vqs <N>`. "
                    + "Default 33 covers 16 RX + 16 TX + 1 control (ConnectX-6 Dx default).",
            true);

    public static final ConfigKey<Integer> VdpaQueuePairs = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VDPA_QUEUE_PAIRS, "0",
            "vDPA queue pair count for the libvirt <driver queues='N'/> directive. "
                    + "0 (default) auto-derives from max_vqs/2.",
            true);

    public static final ConfigKey<Boolean> VdpaEventIdx = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_VDPA_EVENT_IDX, "true",
            "Enable virtio-net VIRTIO_RING_F_EVENT_IDX optimization (reduces interrupts).",
            true);

    public static final ConfigKey<Boolean> VdpaIndirectDesc = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_VDPA_INDIRECT_DESC, "true",
            "Enable virtio-net VIRTIO_RING_F_INDIRECT_DESC (bigger packets per descriptor).",
            true);

    public static final ConfigKey<Boolean> VdpaIommu = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_VDPA_IOMMU, "true",
            "Enable virtio-net VIRTIO_F_IOMMU_PLATFORM (IOMMU translation in guest).",
            true);

    public static final ConfigKey<Boolean> VdpaPacked = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_VDPA_PACKED, "false",
            "Enable virtio-net VIRTIO_F_RING_PACKED (packed virtqueues, faster on DOCA).",
            true);

    public static final ConfigKey<Boolean> VfTrust = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_VF_TRUST, "false",
            "SR-IOV VF trust mode (`ip link set <pf> vf <N> trust on/off`).",
            true);

    public static final ConfigKey<Boolean> VfSpoofcheck = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_VF_SPOOFCHECK, "true",
            "SR-IOV VF spoofcheck (`ip link set <pf> vf <N> spoofchk on/off`).",
            true);

    public static final ConfigKey<String> VfLinkState = new ConfigKey<>(CATEGORY, String.class,
            OVN_VF_LINK_STATE, "auto",
            "SR-IOV VF link state. Whitelist: auto | enable | disable.",
            true);

    public static final ConfigKey<Integer> VfMaxTxRate = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VF_MAX_TX_RATE, "0",
            "SR-IOV VF max TX rate in Mbps (0 = unlimited).",
            true);

    public static final ConfigKey<Integer> VfMinTxRate = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VF_MIN_TX_RATE, "0",
            "SR-IOV VF min TX rate in Mbps (0 = unconstrained).",
            true);

    public static final ConfigKey<Integer> VfVlan = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VF_VLAN, "0",
            "SR-IOV VF 802.1Q VLAN tag (0-4094, 0 = untagged). "
                    + "Note: switchdev mode rejects PF-side VLAN — only useful in legacy mode.",
            true);

    public static final ConfigKey<Integer> VfQos = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VF_QOS, "0",
            "SR-IOV VF 802.1p priority (0-7).",
            true);

    public static final ConfigKey<Integer> VhostQueues = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VHOST_QUEUES, "0",
            "vhost-net multiqueue count (0 = auto, equals VM vCPU count).",
            true);

    public static final ConfigKey<String> VhostDriver = new ConfigKey<>(CATEGORY, String.class,
            OVN_VHOST_DRIVER, "vhost-net",
            "vhost backend driver. Whitelist: vhost-net | vhost-user.",
            true);

    public static final ConfigKey<Integer> VhostTxQueueSize = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VHOST_TX_QUEUE_SIZE, "256",
            "vhost TX virtqueue size (descriptors). Power of 2; libvirt accepts 256/512/1024.",
            true);

    public static final ConfigKey<Integer> VhostRxQueueSize = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_VHOST_RX_QUEUE_SIZE, "256",
            "vhost RX virtqueue size (descriptors). Power of 2; libvirt accepts 256/512/1024.",
            true);

    public static final ConfigKey<Integer> Mtu = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_MTU, "1500",
            "Guest NIC MTU (bytes). Match the OVN/Geneve underlay MTU minus 58 (IPv4) or 78 (IPv6).",
            true);

    public static final ConfigKey<Boolean> Tso = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_TSO, "true",
            "TCP segmentation offload (ethtool tso on/off).",
            true);

    public static final ConfigKey<Boolean> Gso = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_GSO, "true",
            "Generic segmentation offload (ethtool gso on/off).",
            true);

    public static final ConfigKey<Boolean> Gro = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_GRO, "true",
            "Generic receive offload (ethtool gro on/off).",
            true);

    public static final ConfigKey<Boolean> Lro = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_LRO, "true",
            "Large receive offload (ethtool lro on/off).",
            true);

    public static final ConfigKey<Boolean> CsumOffload = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_CSUM_OFFLOAD, "true",
            "Checksum offload TX/RX (ethtool tx/rx on/off).",
            true);

    public static final ConfigKey<String> DriverModel = new ConfigKey<>(CATEGORY, String.class,
            OVN_DRIVER_MODEL, "virtio",
            "libvirt <model type='...'/> for guest NIC. Whitelist: virtio | e1000 | rtl8139 | vmxnet3.",
            true);

    public static final ConfigKey<Boolean> TcOffload = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_TC_OFFLOAD, "true",
            "Enable OVS hw-offload (TC flower) for the integration bridge.",
            true);

    public static final ConfigKey<Boolean> DpdkEnabled = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_DPDK_ENABLED, "false",
            "Enable OVS-DPDK userspace datapath (mutually exclusive with TC offload kernel datapath).",
            true);

    public static final ConfigKey<Boolean> OvsHairpin = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_OVS_HAIRPIN, "true",
            "Apply OVS Port other_config:hairpin=true to every port (VF rep, vDPA, virtio tap) "
                    + "the plugin attaches to br-int. Required for VF<->VF same-host hw-offload via "
                    + "TC flower / mlx5 eswitch.",
            true);

    public static final ConfigKey<String> OvsTcPolicy = new ConfigKey<>(CATEGORY, String.class,
            OVN_OVS_TC_POLICY, "none",
            "Open_vSwitch other_config:tc-policy. Whitelist: none|skip_sw|skip_hw. "
                    + "`none` = HW offload with SW fallback (recommended). "
                    + "`skip_sw` = HW only, drop on miss. "
                    + "`skip_hw` = SW only.",
            true);

    public static final ConfigKey<String> RequestedChassis = new ConfigKey<>(CATEGORY, String.class,
            OVN_REQUESTED_CHASSIS, "",
            "OVN logical-port `requested-chassis` (pin port to a specific chassis). Empty = any.",
            true);

    public static final ConfigKey<Integer> HaChassisPriority = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_HA_CHASSIS_PRIORITY, "0",
            "OVN HA chassis priority for distributed gateways (higher = preferred).",
            true);

    public static final ConfigKey<Boolean> BfdEnable = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_BFD_ENABLE, "false",
            "Enable BFD on this OVN port for liveness detection.",
            true);

    public static final ConfigKey<Integer> BfdMinRx = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_BFD_MIN_RX, "200",
            "BFD min RX interval (ms).",
            true);

    public static final ConfigKey<Integer> BfdMinTx = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_BFD_MIN_TX, "200",
            "BFD min TX interval (ms).",
            true);

    public static final ConfigKey<Integer> BfdMultiplier = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_BFD_MULTIPLIER, "5",
            "BFD detection multiplier (intervals before declaring down).",
            true);

    public static final ConfigKey<Integer> CtSnatInactiveTimeout = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_CT_SNAT_INACTIVE_TIMEOUT, "7440",
            "Conntrack SNAT inactive entry timeout (seconds). Default mirrors RFC 5382 TCP timeout.",
            true);

    public static final ConfigKey<Integer> CtTcpInactiveTimeout = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_CT_TCP_INACTIVE_TIMEOUT, "86400",
            "Conntrack TCP established inactive timeout (seconds).",
            true);

    public static final ConfigKey<Integer> CtUdpInactiveTimeout = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_CT_UDP_INACTIVE_TIMEOUT, "60",
            "Conntrack UDP inactive timeout (seconds).",
            true);

    public static final ConfigKey<Integer> CtIcmpInactiveTimeout = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_CT_ICMP_INACTIVE_TIMEOUT, "30",
            "Conntrack ICMP inactive timeout (seconds).",
            true);

    /**
     * OVN {@code Load_Balancer.options:affinity_timeout} (seconds). 0 (default)
     * disables client-to-backend affinity. When positive, OVN retains the
     * client → backend mapping for at least this many seconds after the last
     * packet, emulating session persistence without a conntrack entry.
     */
    public static final ConfigKey<Integer> LbAffinityTimeout = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_LB_AFFINITY_TIMEOUT, "0",
            "OVN Load_Balancer affinity_timeout (seconds). 0 = disabled (OVN default).",
            true);

    /**
     * OVN {@code Logical_Switch_Port.options:arp_proxy}. When true and the LSP
     * has a bound IPv4 (and optionally IPv6) address, the plugin writes
     * {@code options:arp_proxy=<ip>} so OVN's pipeline answers ARP queries on
     * behalf of the port, preventing ARP flooding on the logical segment.
     */
    public static final ConfigKey<Boolean> LspArpProxy = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_LSP_ARP_PROXY, "false",
            "Enable OVN LSP arp_proxy option (ARP suppression per port). Default false.",
            true);

    /* ---------- ConfigKey lookup table ---------- */

    private static final Map<String, ConfigKey<?>> KEY_BY_NAME;

    static {
        final Map<String, ConfigKey<?>> map = new HashMap<>();
        for (ConfigKey<?> k : allKeys()) {
            map.put(k.key(), k);
        }
        KEY_BY_NAME = Collections.unmodifiableMap(map);
    }

    private static ConfigKey<?>[] allKeys() {
        return new ConfigKey<?>[] {
                VdpaMaxVqs, VdpaQueuePairs, VdpaEventIdx, VdpaIndirectDesc, VdpaIommu, VdpaPacked,
                VfTrust, VfSpoofcheck, VfLinkState, VfMaxTxRate, VfMinTxRate, VfVlan, VfQos,
                VhostQueues, VhostDriver, VhostTxQueueSize, VhostRxQueueSize,
                Mtu, Tso, Gso, Gro, Lro, CsumOffload, DriverModel,
                TcOffload, DpdkEnabled, OvsHairpin, OvsTcPolicy,
                RequestedChassis, HaChassisPriority,
                BfdEnable, BfdMinRx, BfdMinTx, BfdMultiplier,
                CtSnatInactiveTimeout, CtTcpInactiveTimeout, CtUdpInactiveTimeout, CtIcmpInactiveTimeout,
                LbAffinityTimeout, LspArpProxy
        };
    }

    /* ---------- Configurable contract ---------- */

    @Override
    public String getConfigComponentName() {
        return OvnNicConfig.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return allKeys();
    }

    /**
     * Lookup a ConfigKey by its detail-key string. Returns {@code null} when
     * the name is not registered. Used by callers that already know the
     * literal key string and want the typed default.
     */
    public static ConfigKey<?> findKey(final String name) {
        return name == null ? null : KEY_BY_NAME.get(name);
    }

    /* ---------- Resolution chain ---------- */

    /**
     * Resolve {@code key} across the four configuration scopes in priority
     * order. Each scope may be {@code null} (skipped). Type coercion is
     * driven by {@code type} — only {@link Boolean}, {@link Integer} and
     * {@link String} are accepted.
     *
     * @param key             canonical detail name (e.g. {@link #OVN_MTU})
     * @param vmDetails       VM-level detail map ({@code user_vm_details})
     * @param netDetails      network-level detail map ({@code network_details})
     * @param offeringDetails offering-level detail map (only the {@code Other}
     *                        bucket is consulted; CloudStack stores arbitrary
     *                        keys there because the {@link NetworkOffering.Detail}
     *                        enum is closed)
     * @param globalDefault   value when no detail layer wins (typically
     *                        {@code ConfigKey.value()})
     * @param type            target Java type (Boolean.class, Integer.class, String.class)
     * @param <T>             target Java type parameter
     * @return resolved value; never {@code null} unless {@code globalDefault}
     *         is null AND no scope provides a parseable value
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
            LOGGER.debug("OvnNicConfig.resolve: key={} -> from VM detail", key);
            return resolved;
        }
        resolved = pickFromMap(key, netDetails, type);
        if (resolved != null) {
            LOGGER.debug("OvnNicConfig.resolve: key={} -> from network detail", key);
            return resolved;
        }
        resolved = pickFromOfferingDetails(key, offeringDetails, type);
        if (resolved != null) {
            LOGGER.debug("OvnNicConfig.resolve: key={} -> from offering detail", key);
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
        // The closed NetworkOffering.Detail enum doesn't define our keys
        // directly; CloudStack stores arbitrary detail rows by raw string,
        // and getNtwkOffDetails populates the enum-keyed map only for the
        // known enum values. Since we cannot bend the enum here, we look up
        // a free-form key by walking the toString() of each enum entry —
        // this matches the lookup convention CloudStack uses for the
        // PromiscuousMode etc. entries.
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
                    LOGGER.warn("OvnNicConfig.resolve: key={} value='{}' not in whitelist {}; rejecting",
                            key, trimmed, allowed);
                    return null;
                }
                return (T) trimmed;
            }
        } catch (NumberFormatException nfe) {
            LOGGER.warn("OvnNicConfig.resolve: key={} value='{}' not parseable as {}: {}",
                    key, trimmed, type.getSimpleName(), nfe.getMessage());
            return null;
        }
        LOGGER.warn("OvnNicConfig.resolve: key={} unsupported type {}", key, type.getName());
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

    /* ---------- Range guards (caller validates explicitly) ---------- */

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

    /**
     * Resolve {@link #OVN_OVS_HAIRPIN} through the standard four-scope
     * chain (VM &gt; network &gt; offering &gt; global). Falls back to the
     * registered {@link #OvsHairpin} ConfigKey default ({@code true}) when
     * no scope provides a value.
     *
     * @param vmDetails       VM-level detail map (may be {@code null})
     * @param netDetails      network-level detail map (may be {@code null})
     * @param offeringDetails offering-level detail map (may be {@code null})
     * @return resolved boolean; never {@code null}
     */
    public static Boolean resolveHairpin(final Map<String, String> vmDetails,
                                         final Map<String, String> netDetails,
                                         final Map<NetworkOffering.Detail, String> offeringDetails) {
        return resolve(OVN_OVS_HAIRPIN, vmDetails, netDetails, offeringDetails,
                OvsHairpin.value(), Boolean.class);
    }

    /**
     * Resolve {@link #OVN_OVS_TC_POLICY} through the standard four-scope
     * chain (VM &gt; network &gt; offering &gt; global). Falls back to the
     * registered {@link #OvsTcPolicy} ConfigKey default ({@code none}) when
     * no scope provides a parseable value (an out-of-whitelist string is
     * rejected by the resolver and the next layer is consulted).
     *
     * @param vmDetails       VM-level detail map (may be {@code null})
     * @param netDetails      network-level detail map (may be {@code null})
     * @param offeringDetails offering-level detail map (may be {@code null})
     * @return resolved policy string; one of {@code none|skip_sw|skip_hw}
     */
    public static String resolveTcPolicy(final Map<String, String> vmDetails,
                                         final Map<String, String> netDetails,
                                         final Map<NetworkOffering.Detail, String> offeringDetails) {
        return resolve(OVN_OVS_TC_POLICY, vmDetails, netDetails, offeringDetails,
                OvsTcPolicy.value(), String.class);
    }

    /**
     * Spring-instantiated bean: declared {@code public} so the {@code @Component}
     * scan can build a single shared instance for ConfigKey registration.
     * The class has no instance state — the static API is the real surface.
     */
    public OvnNicConfig() {
        // No-op.
    }
}
