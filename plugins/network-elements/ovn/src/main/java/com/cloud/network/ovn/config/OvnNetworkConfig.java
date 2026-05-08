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

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.springframework.stereotype.Component;

/**
 * Network-wide OVN tunables: zone / VPC / global knobs that govern how the
 * plugin attaches the per-zone public Logical_Switch to the host underlay
 * (VLAN tagging on the localnet port) and whether the plugin announces a
 * /32 host route per allocated public IP through the host's existing FRR
 * daemon.
 *
 * <p>Distinct from {@link OvnNicConfig}: that class collects per-NIC /
 * per-tier tunables resolved through the VM detail / network detail /
 * offering detail / global chain. The keys here apply to the public-network
 * <em>plumbing</em>, not to a guest NIC, so the resolution chain is simpler:
 * VPC detail (free-form {@code vpc_details} row) -&gt; global ConfigKey -&gt;
 * hardcoded default.
 *
 * <p>All five keys are dynamically reconfigurable
 * ({@link ConfigKey#isDynamic()} = {@code true}); operators can flip them at
 * runtime via {@code cmk update configuration} without restarting the
 * management server.
 */
@Component
public class OvnNetworkConfig implements Configurable {

    private static final String CATEGORY = "Network";

    /* ---------- Detail keys (also used as VPC detail names for per-VPC overrides) ---------- */

    /** Toggle public-localnet VLAN auto-detection. */
    public static final String OVN_PUBLIC_VLAN_AUTO = "ovn.public.vlan.auto";

    /** Forced VLAN tag for the public localnet (overrides auto-detect). */
    public static final String OVN_PUBLIC_VLAN_OVERRIDE = "ovn.public.vlan.override";

    /** Opt-in toggle for /32 BGP redistribute per allocated public IP. */
    public static final String OVN_BGP_REDISTRIBUTE_PUBLIC_IPS = "ovn.bgp.redistribute.public_ips";

    /** Path to vtysh binary on KVM hosts (passed to agent command wrapper). */
    public static final String OVN_BGP_FRR_VTYSH_PATH = "ovn.bgp.frr.vtysh.path";

    /** BGP ASN for the {@code router bgp <asn>} block. 0 means auto-detect. */
    public static final String OVN_BGP_FRR_ASN = "ovn.bgp.frr.asn";

    /** Reconcile interval (seconds) for gateway-chassis drift detection. */
    public static final String OVN_BGP_RECONCILE_INTERVAL = "ovn.bgp.reconcile.interval.seconds";

    /** Audit-trail label written to {@code ovn_logical_id_map.ovn_name} so the
     *  reconciler can tell plugin-managed announce rows apart from operator
     *  hand-written ones in FRR. */
    public static final String OVN_BGP_FRR_INSTANCE_TAG = "ovn.bgp.frr.instance_tag";

    /** When true, the reconciler leaves operator-added /32 announce rows in
     *  FRR alone (audit-friendly); when false, the plugin owns the namespace
     *  and prunes any /32 it did not announce. The plugin's own bookkeeping
     *  table {@code ovn_logical_id_map} gives the safe set; operators can
     *  flip this to false once they migrate every existing /32 into the
     *  plugin namespace. */
    public static final String OVN_BGP_RESPECT_MANUAL = "ovn.bgp.respect_manual";

    /** Per-VPC detail name (mirrors {@link #OVN_BGP_REDISTRIBUTE_PUBLIC_IPS}). */
    public static final String VPC_DETAIL_BGP_REDISTRIBUTE = "ovn.bgp.redistribute";

    /** Per-VPC detail name (mirrors {@link #OVN_PUBLIC_VLAN_OVERRIDE}). */
    public static final String VPC_DETAIL_PUBLIC_VLAN = "ovn.public.vlan";

    /* ---------- ConfigKeys ---------- */

    public static final ConfigKey<Boolean> PublicVlanAuto = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_PUBLIC_VLAN_AUTO, "true",
            "Auto-detect VLAN from CloudStack Public network and set the tag on the OVN public "
                    + "localnet port. Disable for manual control.",
            true);

    public static final ConfigKey<Integer> PublicVlanOverride = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_PUBLIC_VLAN_OVERRIDE, "0",
            "Force a specific VLAN tag on the public localnet. 0 = use auto-detect; non-zero "
                    + "overrides whatever auto-detect would derive.",
            true);

    public static final ConfigKey<Boolean> BgpRedistributePublicIps = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_BGP_REDISTRIBUTE_PUBLIC_IPS, "false",
            "Opt-in: announce /32 per allocated public IP (sourceNAT / StaticNAT / PortForward) "
                    + "via host-side FRR vtysh on the OVN gateway-chassis. Default off so VRRP / "
                    + "static setups stay non-disruptive.",
            true);

    public static final ConfigKey<String> BgpFrrVtyshPath = new ConfigKey<>(CATEGORY, String.class,
            OVN_BGP_FRR_VTYSH_PATH, "/usr/bin/vtysh",
            "Path to vtysh binary on KVM hosts.",
            true);

    public static final ConfigKey<Integer> BgpFrrAsn = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_BGP_FRR_ASN, "0",
            "BGP ASN for the `router bgp <asn>` block on each KVM host. 0 = auto-detect from "
                    + "running FRR via `vtysh -c 'show ip bgp summary'`.",
            true);

    public static final ConfigKey<Integer> BgpReconcileIntervalSeconds = new ConfigKey<>(CATEGORY, Integer.class,
            OVN_BGP_RECONCILE_INTERVAL, "60",
            "How often the plugin re-checks gateway-chassis assignment and re-announces /32 "
                    + "host routes on failover.",
            true);

    public static final ConfigKey<String> BgpFrrInstanceTag = new ConfigKey<>(CATEGORY, String.class,
            OVN_BGP_FRR_INSTANCE_TAG, "BGP-AUTO",
            "Audit-trail label written to ovn_logical_id_map.ovn_name (suffix) so the BGP /32 "
                    + "reconciler can tell plugin-managed announce rows apart from operator "
                    + "hand-written ones in FRR.",
            true);

    public static final ConfigKey<Boolean> BgpRespectManual = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_BGP_RESPECT_MANUAL, "true",
            "When true, the reconciler ignores /32 announce rows that are not present in "
                    + "ovn_logical_id_map (operator-managed entries). When false, the plugin "
                    + "owns the /32 namespace and prunes any /32 it did not announce.",
            true);

    /* ---------- Configurable contract ---------- */

    @Override
    public String getConfigComponentName() {
        return OvnNetworkConfig.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                PublicVlanAuto,
                PublicVlanOverride,
                BgpRedistributePublicIps,
                BgpFrrVtyshPath,
                BgpFrrAsn,
                BgpReconcileIntervalSeconds,
                BgpFrrInstanceTag,
                BgpRespectManual
        };
    }

    /**
     * Spring-instantiated bean: declared {@code public} so the
     * {@code @Component} scan can build a single shared instance for
     * ConfigKey registration. The class has no instance state — the static
     * API is the real surface.
     */
    public OvnNetworkConfig() {
        // No-op.
    }
}
