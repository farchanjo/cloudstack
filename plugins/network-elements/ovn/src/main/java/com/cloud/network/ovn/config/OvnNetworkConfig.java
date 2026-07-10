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
    public static final String OVN_BGP_REDISTRIBUTE_ROUTED_TIERS = "ovn.bgp.redistribute.routed_tiers";

    /** PARSEL-V6: opt-in toggle for announcing the IPv6 CIDR of dual-stack OVN
     *  tiers to the fabric route reflectors (native routing, no v6 NAT). */
    public static final String OVN_BGP_REDISTRIBUTE_TIER_IPV6 = "ovn.bgp.redistribute.tier.ipv6";

    /** PARSEL-V6: routed public IPv6 /64 the VPC public LRP takes a GUA from
     *  (e.g. {@code 2a13:8740:0:7::/64}). Blank disables the whole v6 public
     *  transport path (v4-only, byte-identical behaviour). */
    public static final String OVN_PUBLIC_IPV6_PREFIX = "ovn.public.ipv6.prefix";

    /** PARSEL-V6: the v6 fabric gateway the gateway-chassis pub-anchor answers
     *  NDP for (e.g. {@code 2a13:8740:0:7::1}); the {@code ::/0} next-hop of the
     *  VPC public LRP. Blank disables the v6 public transport path. */
    public static final String OVN_PUBLIC_IPV6_GATEWAY = "ovn.public.ipv6.gateway";

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

    /** Master gate for the gateway-chassis public-IP datapath anchor: a single
     *  on-link IP that makes the VPC public LRP next-hop ARP-resolvable so the
     *  /32 route delivers inbound N-S into OVN. The anchor address itself is NOT
     *  configured — it is DERIVED at runtime as the first address of the public
     *  segment that sits outside CloudStack's allocation pool ({@code ip4_range})
     *  and is not the subnet gateway. Devops governs it purely through the
     *  existing public IP range in CloudStack (change the range -&gt; the anchor
     *  follows); this key only turns the behaviour on/off. Default off. */
    public static final String OVN_BGP_PUBLIC_ANCHOR_ENABLED = "ovn.bgp.public.anchor.enabled";

    /** Per-VPC detail name (mirrors {@link #OVN_BGP_REDISTRIBUTE_PUBLIC_IPS}). */
    public static final String VPC_DETAIL_BGP_REDISTRIBUTE = "ovn.bgp.redistribute";

    /** Per-VPC detail name (mirrors {@link #OVN_PUBLIC_VLAN_OVERRIDE}). */
    public static final String VPC_DETAIL_PUBLIC_VLAN = "ovn.public.vlan";

    /** Per-network (tier) detail: force-enable/disable BGP subnet advertise
     *  for a routed tier, overriding the plugin's per-tier decision. Absent =>
     *  the routed-tier's /24 announce follows the global routed-tiers toggle.
     *  Set via {@code cmk create/update network ... details[0].key=ovn.tier.advertise
     *  details[0].value=true}. */
    public static final String NETWORK_DETAIL_TIER_ADVERTISE = "ovn.tier.advertise";

    /** Comma-separated destination IPv4 addresses exempted from the VPC-wide
     *  source-NAT rule (e.g. BGP route reflectors a guest must peer with
     *  using its real address instead of the VPC's SNAT IP). Empty disables
     *  the exemption; existing exemptions on NAT rows are left untouched
     *  when disabled — see {@code OvnSourceNatService.applySnatDestinationExemption}. */
    public static final String OVN_SNAT_EXEMPTED_DESTINATIONS = "ovn.snat.exempted.destinations";

    /** Per-network extra CIDRs appended to every guest-NIC
     *  {@code Logical_Switch_Port}'s {@code addresses} and {@code port_security}
     *  columns, so raw pod / LB-VIP frames (e.g. Calico {@code ipipMode: Never},
     *  cross-node dual-stack pod v6) are not dropped by OVN's L3 port-security
     *  spoof guard. Map syntax:
     *  {@code <network-uuid>=cidr1,cidr2;<network-uuid>=cidr3,...}. A network
     *  absent from the map keeps EXACTLY the legacy behaviour (MAC + NIC IPs).
     *  See {@link com.cloud.network.ovn.config.OvnLspAddresses}. */
    public static final String OVN_LSP_EXTRA_PORT_SECURITY_CIDRS = "ovn.lsp.extra.port.security.cidrs";

    /** Per-network ECMP static routes programmed on the VPC
     *  {@code Logical_Router} that owns each network. One
     *  {@code Logical_Router_Static_Route} row is created per next-hop for the
     *  same destination prefix (OVN native ECMP), so k8s LB VIP ranges route
     *  from the OVN gateway to the CKS worker nodes. Map syntax:
     *  {@code <network-uuid>=<prefix>-><nh1>|<nh2>|<nh3>;...}. Multi-stanza
     *  entries may reuse the same network UUID so dual-stack can declare an
     *  IPv4 and an IPv6 VIP prefix independently (same-prefix stanzas merge
     *  next-hops; different prefixes append). Every managed row is tagged
     *  {@code external_ids:cs-ecmp-route=<network-uuid>} so the reconciler
     *  touches ONLY plugin-owned routes. A network absent from the map has no
     *  route managed on its LR (zero regression).
     *  See {@link com.cloud.network.ovn.config.OvnEcmpRoutes}. */
    public static final String OVN_LR_ECMP_STATIC_ROUTES = "ovn.lr.ecmp.static.routes";

    /** Per-network public IPv6 Load_Balancer rows on the VPC Logical_Router
     *  (and tier LS). Map syntax:
     *  {@code <network-uuid>=[vip]:vport->[be]:p|[be]:p;...}. IPv6 VIP/backends
     *  MUST use brackets when ports are present. Every managed row is tagged
     *  {@code external_ids:cs-pub6-lb=<network-uuid>|<vip>|<port>} so the
     *  reconciler touches ONLY plugin-owned LBs. Empty disables the feature
     *  (owned rows are removed). See {@link com.cloud.network.ovn.config.OvnPublicIpv6Lb}. */
    public static final String OVN_LR_PUBLIC_IPV6_LB = "ovn.lr.public.ipv6.lb";

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
            "Opt-in: announce /32 per allocated public IP (sourceNAT / StaticNAT / PortForward / "
                    + "LoadBalancer) via host-side FRR vtysh on the OVN gateway-chassis. Default off "
                    + "so VRRP / static setups stay non-disruptive.",
            true);

    public static final ConfigKey<Boolean> BgpRedistributeRoutedTiers = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_BGP_REDISTRIBUTE_ROUTED_TIERS, "true",
            "Announce the subnet (tier CIDR) of every ROUTED-mode OVN VPC tier via host-side FRR "
                    + "vtysh on the gateway-chassis, so the route reflectors learn it. RFC1918 tiers "
                    + "stay internal via the fabric EBGP egress deny; a PUBLIC-block tier reaches "
                    + "upstream. Default on (routed mode implies advertising the tier). Kill-switch.",
            true);

    public static final ConfigKey<Boolean> BgpRedistributeTierIpv6 = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_BGP_REDISTRIBUTE_TIER_IPV6, "false",
            "PARSEL-V6: announce the IPv6 CIDR (getIp6Cidr) of every dual-stack OVN tier via host-side "
                    + "FRR vtysh into the fabric's IPv6 unicast address-family on the gateway-chassis, so the "
                    + "route reflectors learn the tier /64. Independent of the tier's IPv4 network mode — v6 is "
                    + "natively routed (never NATed), so this fires for NAT-mode (CKS) tiers too. Gated additionally "
                    + "on the tier carrying an IPv6 gateway/cidr. Default off.",
            true);

    public static final ConfigKey<String> PublicIpv6Prefix = new ConfigKey<>(CATEGORY, String.class,
            OVN_PUBLIC_IPV6_PREFIX, "",
            "PARSEL-V6: routed public IPv6 /64 the VPC public LRP allocates a per-VPC GUA from (e.g. "
                    + "'2a13:8740:0:7::/64'). The GUA host id is DERIVED from the last octet of the VPC's IPv4 "
                    + "public LRP address (217.179.89.34 -> 2a13:8740:0:7::34), so it is collision-free and never "
                    + "hardcoded. Blank disables the entire IPv6 public transport path (v4-only, zero regression).",
            true);

    public static final ConfigKey<String> PublicIpv6Gateway = new ConfigKey<>(CATEGORY, String.class,
            OVN_PUBLIC_IPV6_GATEWAY, "",
            "PARSEL-V6: the IPv6 fabric gateway the gateway-chassis pub-anchor answers NDP for (e.g. "
                    + "'2a13:8740:0:7::1') and the '::/0' next-hop programmed on the VPC public LRP. In the "
                    + "BGP-to-host model there is no physical device on this address — the anchor holds it. Blank "
                    + "disables the IPv6 public transport path.",
            true);

    public static final ConfigKey<String> BgpFrrVtyshPath = new ConfigKey<>(CATEGORY, String.class,
            OVN_BGP_FRR_VTYSH_PATH, "/usr/bin/vtysh",
            "Path to vtysh binary on KVM hosts.",
            true);

    public static final ConfigKey<Long> BgpFrrAsn = new ConfigKey<>(CATEGORY, Long.class,
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

    public static final ConfigKey<Boolean> BgpPublicAnchorEnabled = new ConfigKey<>(CATEGORY, Boolean.class,
            OVN_BGP_PUBLIC_ANCHOR_ENABLED, "false",
            "Opt-in: on the OVN gateway-chassis, put a single on-link anchor IP on a dedicated "
                    + "pub-anchor OVS internal port of the provider-localnet bridge so the VPC public "
                    + "LRP next-hop is ARP-resolvable and the /32 datapath route delivers inbound N-S "
                    + "into OVN. The anchor address is DERIVED (first address of the public segment "
                    + "outside the CloudStack ip4_range allocation pool, not the gateway) — never "
                    + "hardcoded; it follows the public range devops manages. Default off (advertise-/"
                    + "route-only until enabled).",
            true);

    public static final ConfigKey<String> SnatExemptedDestinations = new ConfigKey<>(CATEGORY, String.class,
            OVN_SNAT_EXEMPTED_DESTINATIONS, "",
            "Comma-separated list of destination IPv4 addresses exempted from VPC source NAT "
                    + "(e.g. BGP route reflectors guests must peer with using their real IPs). "
                    + "Empty disables the exemption.",
            true);

    public static final ConfigKey<String> LspExtraPortSecurityCidrs = new ConfigKey<>(CATEGORY, String.class,
            OVN_LSP_EXTRA_PORT_SECURITY_CIDRS, "",
            "Per-network extra CIDRs appended to every guest-NIC Logical_Switch_Port addresses and "
                    + "port_security columns so raw pod / LB-VIP frames survive OVN's spoof guard "
                    + "(e.g. Calico ipipMode Never, cross-node dual-stack pod v6). Syntax: "
                    + "'<network-uuid>=cidr1,cidr2;<network-uuid>=cidr3,...'. Networks absent from the "
                    + "map keep the legacy MAC+NIC-IP behaviour. Malformed CIDRs are logged and skipped. "
                    + "Empty disables the feature entirely.",
            true);

    public static final ConfigKey<String> LrEcmpStaticRoutes = new ConfigKey<>(CATEGORY, String.class,
            OVN_LR_ECMP_STATIC_ROUTES, "",
            "Per-network ECMP static routes programmed on the VPC Logical_Router that owns each "
                    + "network. One Logical_Router_Static_Route row is created per next-hop for the same "
                    + "destination prefix (OVN native ECMP), tagged external_ids:cs-ecmp-route=<network-uuid> "
                    + "so the reconciler manages ONLY plugin-owned routes. Syntax: "
                    + "'<network-uuid>=<prefix>-><nexthop1>|<nexthop2>|...;...'. Multi-stanza same UUID is "
                    + "supported for dual-stack (IPv4 + IPv6 VIP prefixes on one network); same-prefix "
                    + "stanzas merge next-hops, different prefixes append. Next-hops must be valid IPs "
                    + "inside the network's matching-family CIDR (IPv4 cidr / IPv6 ip6cidr); out-of-range "
                    + "next-hops and malformed entries are logged and skipped. Empty disables the feature "
                    + "entirely (no route is ever touched). Used to route k8s LB VIP ranges from the OVN "
                    + "gateway to CKS workers.",
            true);

    public static final ConfigKey<String> LrPublicIpv6Lb = new ConfigKey<>(CATEGORY, String.class,
            OVN_LR_PUBLIC_IPV6_LB, "",
            "Per-network public IPv6 OVN Load_Balancer rows on the VPC Logical_Router (and tier LS). "
                    + "Each entry is one VIP:port with one or more IPv6 backends; tagged "
                    + "external_ids:cs-pub6-lb=<network-uuid>|<vip>|<port> so the reconciler manages ONLY "
                    + "plugin-owned LBs. Syntax: "
                    + "'<network-uuid>=[vip]:vport->[be]:p|[be]:p;...'. IPv6 VIP and backends MUST use "
                    + "brackets when ports are present. Backends outside the tier ip6Cidr and malformed "
                    + "entries are logged and skipped. Empty disables the feature (owned rows removed). "
                    + "Also announces each VIP as a BGP /128 on the gateway-chassis. Prefer VIP host ids "
                    + "::100+ in the public IPv6 /64; avoid transport GUAs (::1, per-VPC LRP ids).",
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
                BgpRedistributeRoutedTiers,
                BgpRedistributeTierIpv6,
                PublicIpv6Prefix,
                PublicIpv6Gateway,
                BgpFrrVtyshPath,
                BgpFrrAsn,
                BgpReconcileIntervalSeconds,
                BgpFrrInstanceTag,
                BgpRespectManual,
                BgpPublicAnchorEnabled,
                SnatExemptedDestinations,
                LspExtraPortSecurityCidrs,
                LrEcmpStaticRoutes,
                LrPublicIpv6Lb
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
