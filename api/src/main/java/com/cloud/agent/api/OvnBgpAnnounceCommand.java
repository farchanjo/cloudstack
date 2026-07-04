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
package com.cloud.agent.api;

/**
 * Agent command that asks the receiving KVM host to announce or withdraw a
 * /32 host route via its local FRR daemon (host-side {@code vtysh}).
 *
 * <p>Driven by {@code OvnBgpRedistributeManager} on the management server
 * when a public IP is allocated to a VPC whose gateway-chassis lives on the
 * receiving host. The announcement carries only the public IPv4; the prefix
 * length is implicit ({@code /32}).
 *
 * <p>Operations:
 * <ul>
 *   <li>{@link #OP_ANNOUNCE} — write {@code router bgp <asn> ; network <ip>/32}
 *       in vtysh configure mode.</li>
 *   <li>{@link #OP_WITHDRAW} — write {@code no network <ip>/32}.</li>
 * </ul>
 *
 * <p>Wire-compat note: agents that predate the matching wrapper return an
 * {@code Unsupported command} answer; the management caller treats that as
 * a non-fatal warning and continues, leaving the underlying ECMP-without-/32
 * behaviour as documented in the operator runbook.
 */
public class OvnBgpAnnounceCommand extends Command {

    /** Announce {@code <publicIp>/32} on the host's FRR. */
    public static final String OP_ANNOUNCE = "announce";

    /** Withdraw {@code <publicIp>/32} from the host's FRR. */
    public static final String OP_WITHDRAW = "withdraw";

    private String publicIp;
    private String operation;
    private String vtyshPath;
    private Long asn;
    private String gatewayIp;
    private String anchorCidr;
    private String vlan;
    private String networkGatewayIp;

    /** No-arg constructor for serialization frameworks. */
    public OvnBgpAnnounceCommand() {
        // No-op.
    }

    /**
     * Advertise-only constructor (no datapath route). Kept for wire / test
     * compatibility with callers predating the {@code gatewayIp} datapath hook;
     * delegates with a {@code null} gateway so the wrapper only writes the BGP
     * {@code network} statement.
     *
     * @param publicIp   bare IPv4 address (no prefix length)
     * @param operation  {@link #OP_ANNOUNCE} or {@link #OP_WITHDRAW}
     * @param vtyshPath  absolute path to vtysh on the agent host (typically
     *                   {@code /usr/bin/vtysh}); when null the wrapper falls
     *                   back to the host's PATH-resolved binary.
     * @param asn        BGP ASN to use in {@code router bgp <asn>}; pass
     *                   {@code null} or {@code 0} to ask the wrapper to
     *                   auto-detect via {@code show ip bgp summary}.
     */
    public OvnBgpAnnounceCommand(final String publicIp, final String operation,
                                 final String vtyshPath, final Long asn) {
        this(publicIp, operation, vtyshPath, asn, null);
    }

    /**
     * @param gatewayIp  OVN LR public-port IP (bare, e.g. {@code 217.179.89.34})
     *                   used as the next-hop of the {@code <publicIp>/32} kernel
     *                   route the wrapper installs on the gateway chassis, so
     *                   inbound N-S traffic is delivered into OVN (and the /32
     *                   is seeded into zebra's RIB so the BGP {@code network}
     *                   statement actually originates). Pass {@code null} on
     *                   withdraw, or when the VPC's public LRP IP is unknown, to
     *                   fall back to advertise-only (no datapath route).
     */
    public OvnBgpAnnounceCommand(final String publicIp, final String operation,
                                 final String vtyshPath, final Long asn,
                                 final String gatewayIp) {
        this(publicIp, operation, vtyshPath, asn, gatewayIp, null);
    }

    /**
     * Full constructor adding the datapath on-link anchor.
     *
     * @param anchorCidr reserved out-of-pool host address WITH prefix in the
     *                   public segment (e.g. {@code 217.179.89.2/24}) that the
     *                   wrapper puts on-link on a dedicated {@code pub-anchor}
     *                   OVS internal port of the provider-localnet bridge on the
     *                   gateway chassis. This makes the LRP next-hop
     *                   ({@code gatewayIp}) ARP-resolvable so the {@code /32}
     *                   route can be installed and inbound N-S actually enters
     *                   OVN. A single anchor per public segment; it lives only on
     *                   the current gateway chassis. Pass {@code null} on
     *                   withdraw or when the anchor is not managed here (falls
     *                   back to the pre-anchor advertise-/route-only behaviour).
     */
    public OvnBgpAnnounceCommand(final String publicIp, final String operation,
                                 final String vtyshPath, final Long asn,
                                 final String gatewayIp, final String anchorCidr) {
        this(publicIp, operation, vtyshPath, asn, gatewayIp, anchorCidr, null, null);
    }

    /**
     * Full constructor adding the provider-localnet VLAN tag and the public
     * network gateway IP, both needed to correctly provision the gateway-chassis
     * {@code pub-anchor} port.
     *
     * @param vlan             bare 802.1Q VLAN id of the public segment (e.g.
     *                         {@code "2988"}); the anchor is created as an ACCESS
     *                         port on it so host frames match the OVN localnet
     *                         ingress ({@code dl_vlan=<vlan>}). {@code null}
     *                         keeps the anchor untagged (pre-fix behaviour).
     * @param networkGatewayIp public network gateway IP (bare, e.g.
     *                         {@code 217.179.89.1}) — the LR's egress next-hop.
     *                         The wrapper also holds it on {@code pub-anchor} so
     *                         the gateway chassis answers ARP for it and VM
     *                         egress is forwarded upstream. {@code null} skips it.
     */
    public OvnBgpAnnounceCommand(final String publicIp, final String operation,
                                 final String vtyshPath, final Long asn,
                                 final String gatewayIp, final String anchorCidr,
                                 final String vlan, final String networkGatewayIp) {
        this.publicIp = publicIp;
        this.operation = operation;
        this.vtyshPath = vtyshPath;
        this.asn = asn;
        this.gatewayIp = gatewayIp;
        this.anchorCidr = anchorCidr;
        this.vlan = vlan;
        this.networkGatewayIp = networkGatewayIp;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public String getOperation() {
        return operation;
    }

    public String getVtyshPath() {
        return vtyshPath;
    }

    public Long getAsn() {
        return asn;
    }

    public String getGatewayIp() {
        return gatewayIp;
    }

    public String getAnchorCidr() {
        return anchorCidr;
    }

    public String getVlan() {
        return vlan;
    }

    public String getNetworkGatewayIp() {
        return networkGatewayIp;
    }
}
