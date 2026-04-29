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

/**
 * Backend-agnostic programming interface used by {@link IntentReconciler}.
 *
 * <p>Two concrete implementations:
 * <ul>
 *   <li>{@link TcRuleProgrammer} — emits {@code tc filter} commands on switchdev
 *       VF representors. Default backend, validated 4.24.1.x release train.</li>
 *   <li>{@link OfFlowProgrammer} — emits {@code ovs-ofctl add-flow} commands on
 *       OVS-DPDK userspace bridges. Used when {@code hwoffload.programmer=of}.</li>
 * </ul>
 *
 * <p>Selection is made at agent boot via {@code AgentProperties.HWOFFLOAD_PROGRAMMER}
 * and is host-local; cluster can run heterogeneous backends during migration.
 *
 * <p>Method semantics, parameters, ordering, idempotency and side-effects MUST
 * match exactly between implementations. Behavioural drift is a regression.
 */
public interface RuleProgrammer {

    /** Idempotent: ensure clsact qdisc / OF table seed exists on representor. */
    void initRepresentor(String repName);

    /** Install chain-0 dispatch ({@code ct zone N pipe} + goto chain 1). */
    void installChain0Dispatch(String repName, int zone);

    /** Established/related forward (chain 1, simple). */
    void installEstablishedForward(String repName, String outRep, boolean replyOnly);

    /** Established/related forward with optional VLAN push on egress (public-side). */
    void installEstablishedForward(String repName, String outRep, boolean replyOnly, Integer pushVlanId);

    /** Convenience overload, both directions, no push_vlan. */
    void installEstablishedForward(String repName, String outRep);

    /** Catch-all pass at the given priority (chain 1 tail). */
    void installCatchAllPass(String repName, int prio);

    /**
     * Per-tuple NAT rule (SNAT or DNAT) installed in chain 1 with explicit zone.
     * Used by SourceNat reply path and StaticNat egress.
     */
    void installNatRule(String repName, String outRep, int zone, TcRuleProgrammer.NatDirection dir,
                        String matchAddr, int matchPort, String translateAddr, String ipProto, int prio);

    /** Stateless ACL rule (no ct). */
    void installAclRule(String repName, String matchSrcIp, String matchDstIp,
                        int matchPort, String ipProto, TcRuleProgrammer.Action action, int prio);

    /** Stateful ACL rule (ct zone). */
    void installStatefulAclRule(String repName, int zone, String matchSrcIp, String matchDstIp,
                                int matchPort, String ipProto, TcRuleProgrammer.Action action, int prio);

    /** Wipe everything programmed on the representor. */
    void resetRepresentor(String repName);

    /**
     * Intra-LAN bypass (DHCPOFFER / dst_ip in tier_cidr / 255.255.255.255 broadcast)
     * installed BEFORE NAT rules in chain 1.
     */
    void installIntraLanBypass(String repName, String tierCidr);

    /** Wipe chain 1 only (used on VR Stop / removeIntent). */
    void clearChain1(String repName);

    /** Diagnostic snapshot of current programming on representor (human-readable). */
    String snapshot(String repName);

    /** PFW inbound DNAT installed on the public-side ingress block. */
    void installPfwInboundDnat(int blockId, int vlanId, String ipProto,
                               String publicIp, int publicPort,
                               int ctZone, String internalIp, int internalPort,
                               String publicVfRep, int prio);

    /** Wipe a PFW block. */
    void clearPfwBlock(int blockId);

    /** Static NAT inbound DNAT (1:1). */
    void installStaticNatInboundDnat(int blockId, int vlanId,
                                     String publicIp, int ctZone,
                                     String internalIp,
                                     String publicVfRep, int prio);

    /** Wipe a Static NAT block. */
    void clearStaticNatBlock(int blockId);

    /** Resolve the ingress block id for the uplink netdev. */
    int resolveIngressBlock(String netdev);

    /** Diagnostic: is this rep reporting its programming as offloaded by HW. */
    boolean isOffloaded(String repName);
}
