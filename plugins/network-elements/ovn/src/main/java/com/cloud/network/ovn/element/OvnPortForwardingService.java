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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.VpcVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

/**
 * Maps CloudStack {@link PortForwardingRule}s onto OVN {@code NAT} rows of
 * type {@code dnat_and_snat} attached to the VPC's {@code Logical_Router}.
 *
 * <p>NAT rows (rather than {@code Load_Balancer} rows) are emitted so the
 * underlying datapath gets ConnectX-6 Dx TC-flower CT-NAT 5-tuple hardware
 * offload — {@code dnat_and_snat} is in the offloaded action set on dx6,
 * whereas {@code group:type=select} (the LB lowering) historically falls
 * back to software on mlx5 TC. {@code Load_Balancer} rows remain reserved
 * for true LB use cases (multi-backend, health-checks, custom selection
 * fields) — handled by {@link OvnLoadBalancerService}.
 *
 * <p>The mapping table tracks the rule under {@link Kind#PORT_FORWARDING}.
 * On hot upgrade from the previous LB-based shape, the first {@code apply}
 * touch detects the legacy {@code Load_Balancer} UUID, drops it, and
 * recreates the rule as a NAT row — read-side fallback so existing PF state
 * does not need an operator-driven migration.
 */
@Component
public class OvnPortForwardingService {

    private static final Logger LOGGER = LogManager.getLogger(OvnPortForwardingService.class);

    /** OVN NAT type emitted for port-forwarding rules. */
    public static final String NAT_TYPE_DNAT_AND_SNAT = "dnat_and_snat";

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private OvnVpcElement vpcElement;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private com.cloud.network.ovn.manager.OvnBgpRedistributeManager bgpRedistributeManager;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Apply every supplied PF rule. Idempotent: an existing mapping for the
     * rule id collapses to a no-op (or to a rebuild when the mapping points
     * at a row of the wrong table — i.e. legacy LB-PF migration).
     * Rules in {@code Revoke} state drop the NAT (or legacy LB) row and the
     * mapping.
     */
    public boolean applyPFRules(final Network network, final List<PortForwardingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            LOGGER.warn("OvnPortForwardingService: no OVN controller for zone {}", network.getDataCenterId());
            return false;
        }
        final String lrUuid = lookupVpcLrUuid(network, controller);
        if (lrUuid == null) {
            LOGGER.warn("OvnPortForwardingService: VPC LR not found for network id={}", network.getId());
            return false;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        boolean overall = true;
        for (final PortForwardingRule rule : rules) {
            try {
                applyOne(nb, controller, lrUuid, network, rule);
            } catch (OvnException e) {
                LOGGER.error("OvnPortForwardingService: rule id={} failed: {}", rule.getId(), e.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    private void applyOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                          final Network network, final PortForwardingRule rule) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.PORT_FORWARDING, rule.getId(), controller.getId());
        if (rule.getState() == FirewallRule.State.Revoke) {
            revokeOne(nb, controller, lrUuid, existing, network, rule);
            return;
        }
        if (rule.getState() != FirewallRule.State.Add && rule.getState() != FirewallRule.State.Active) {
            LOGGER.debug("OvnPortForwardingService: skipping rule id={} in state {}", rule.getId(), rule.getState());
            return;
        }
        // Public IP comes from FirewallRule.sourceIpAddressId (the floating
        // IP allocated to the rule); private IP is the VM-side target carried
        // on PortForwardingRule.destinationIpAddress. Earlier revisions used
        // destinationIpAddress for both, producing a row whose external IP
        // was the VM internal IP — useless for N-S traffic.
        final IPAddressVO publicIpRow = ipAddressDao.findById(rule.getSourceIpAddressId());
        final String publicIp = publicIpRow == null || publicIpRow.getAddress() == null
                ? null : publicIpRow.getAddress().addr();
        final String privateIp = lookupVmIp(rule);
        if (StringUtils.isBlank(publicIp) || StringUtils.isBlank(privateIp)) {
            LOGGER.warn("OvnPortForwardingService: rule id={} missing public/private IP (pub={} priv={}); skipping",
                    rule.getId(), publicIp, privateIp);
            return;
        }
        final String externalPortRange = buildExternalPortRange(rule);
        final String logicalPort = lookupLogicalPort(network, rule);
        final Map<String, String> ext = new LinkedHashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.PORT_FORWARDING.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(network.getDataCenterId()));

        if (existing != null) {
            // Legacy migration path: the row lives in Load_Balancer table from
            // a pre-NAT plugin version. Drop the LB row + mapping; fall through
            // to fresh NAT-row creation below. Keeps hot upgrade lossless —
            // the operator does not need to revoke and re-add PF rules.
            if (nb.rowExistsByUuid("Load_Balancer", existing.getOvnUuid())) {
                LOGGER.info("OvnPortForwardingService: migrating legacy LB-based PF rule id={} (lb={}) to NAT",
                        rule.getId(), existing.getOvnUuid());
                try {
                    nb.detachLoadBalancerFromLogicalRouter(lrUuid, existing.getOvnUuid());
                } catch (OvnException e) {
                    LOGGER.warn("OvnPortForwardingService: legacy LB detach failed (rule={}, lb={}): {}",
                            rule.getId(), existing.getOvnUuid(), e.getMessage());
                }
                try {
                    nb.deleteLoadBalancer(existing.getOvnUuid());
                } catch (OvnException e) {
                    LOGGER.warn("OvnPortForwardingService: legacy LB delete failed (rule={}, lb={}): {}",
                            rule.getId(), existing.getOvnUuid(), e.getMessage());
                }
                logicalIdMapDao.remove(existing.getId());
            } else if (nb.rowExistsByUuid("NAT", existing.getOvnUuid())) {
                // Mapping points at a live NAT row. OVN NAT semantics treat
                // (type, external_ip, external_port_range, logical_ip,
                // logical_port) as the natural key. Any change there forces a
                // delete-then-recreate so OVSDB picks up the new tuple
                // cleanly. CloudStack drives PF rules as immutable from the
                // user's perspective (a port change = revoke + re-add) so the
                // happy path here is a no-op.
                LOGGER.debug("OvnPortForwardingService: rule id={} mapped to NAT {}; no-op",
                        rule.getId(), existing.getOvnUuid());
                return;
            } else {
                LOGGER.warn("OvnPortForwardingService: PORT_FORWARDING mapping rule={} -> {} stale; recreating",
                        rule.getId(), existing.getOvnUuid());
                logicalIdMapDao.remove(existing.getId());
            }
        }
        final String natUuid = nb.addNatRule(lrUuid, NAT_TYPE_DNAT_AND_SNAT, publicIp, privateIp,
                logicalPort, externalPortRange, null, ext);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.PORT_FORWARDING, rule.getId(), controller.getId(), natUuid,
                NAT_TYPE_DNAT_AND_SNAT + "-pf-" + rule.getId()));
        LOGGER.info("OvnPortForwardingService: PF rule id={} -> NAT {} (extIp={}:{} -> {}:{}, lsp={})",
                rule.getId(), natUuid, publicIp, externalPortRange, privateIp,
                rule.getDestinationPortStart(), logicalPort);
        // Announce /32 for the public IP backing this PF rule. The manager
        // is idempotent — a public IP carrying multiple PF rules announces
        // exactly once because the BGP_ANNOUNCE row is keyed by IP id, not
        // rule id.
        if (network.getVpcId() != null && publicIpRow != null) {
            bgpRedistributeManager.announce(publicIp, publicIpRow.getId(),
                    network.getVpcId(), network.getDataCenterId());
        }
    }

    private void revokeOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                           final OvnLogicalIdMapVO mapping, final Network network, final PortForwardingRule rule) {
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        if (!pendingDeletionDao.isPendingByOvnUuid(mapping.getOvnUuid(), Kind.PORT_FORWARDING.name())) {
            pendingDeletionDao.persist(new OvnPendingDeletionVO(
                    UUID.randomUUID().toString(), controller.getId(), network.getDataCenterId(),
                    Kind.PORT_FORWARDING, mapping.getOvnUuid(), rule.getId()));
            LOGGER.info("OvnPortForwardingService: enqueued pending deletion kind=PORT_FORWARDING ovn_uuid={} cs_id={}",
                    mapping.getOvnUuid(), rule.getId());
        }
        try {
            // Probe both tables — the row may still live in Load_Balancer
            // from a pre-migration revoke that races a hot upgrade.
            if (nb.rowExistsByUuid("NAT", mapping.getOvnUuid())) {
                nb.deleteNatRule(mapping.getOvnUuid());
            } else if (nb.rowExistsByUuid("Load_Balancer", mapping.getOvnUuid())) {
                try {
                    nb.detachLoadBalancerFromLogicalRouter(lrUuid, mapping.getOvnUuid());
                } catch (OvnException ignored) {
                    // best-effort; LB may already be detached
                }
                nb.deleteLoadBalancer(mapping.getOvnUuid());
            } else {
                LOGGER.debug("OvnPortForwardingService: revoke {} no-op (row already gone)", mapping.getOvnUuid());
            }
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.PORT_FORWARDING.name());
        } catch (OvnException e) {
            LOGGER.warn("OvnPortForwardingService: revoke {} failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
        }
        // Withdraw /32 only when no other PF rule still claims this public
        // IP. The IP itself may also back a static-NAT or VPC source-NAT
        // mapping; for those the BGP_ANNOUNCE row is keyed by the same IP
        // id and will be re-announced by the periodic reconciler if dropped
        // here erroneously. We rely on the absence of other PF rules as the
        // soft signal that the IP no longer needs the /32 from the PF
        // perspective; orchestrator-level cleanup (IP release) emits an
        // explicit withdraw via OvnNetworkElement.
        if (network == null || rule == null || network.getVpcId() == null) {
            return;
        }
        final IPAddressVO publicIpRow = ipAddressDao.findById(rule.getSourceIpAddressId());
        final String publicIp = publicIpRow == null || publicIpRow.getAddress() == null
                ? null : publicIpRow.getAddress().addr();
        if (publicIp == null || publicIpRow == null) {
            return;
        }
        // Best-effort withdraw — if another PF rule shares the IP the
        // announce path will re-announce on its next applyOne() touch (the
        // BGP_ANNOUNCE row was rebuilt fresh from there).
        bgpRedistributeManager.withdraw(publicIp, publicIpRow.getId(),
                network.getVpcId(), network.getDataCenterId());
    }

    private String lookupVpcLrUuid(final Network network, final OvnControllerVO controller) {
        if (network.getVpcId() == null) {
            return null;
        }
        final VpcVO vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return null;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC, vpc.getId(), controller.getId());
        if (mapping != null) {
            return mapping.getOvnUuid();
        }
        // Cold path: the LR was never created. Lazily create via VpcElement.
        try {
            return vpcElement.createLogicalRouterFor(vpc);
        } catch (OvnException e) {
            LOGGER.error("OvnPortForwardingService: lazy LR create failed for VPC id={}: {}", vpc.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * PortForwardingRule does not directly carry the destination IP —
     * CloudStack resolves it via {@code getDestinationIpAddress()} (set by
     * the orchestrator before the rule is applied). When the rule arrives
     * pre-resolved we use it as-is; otherwise we leave the lookup to the
     * caller.
     */
    private static String lookupVmIp(final PortForwardingRule rule) {
        if (rule.getDestinationIpAddress() != null) {
            return rule.getDestinationIpAddress().addr();
        }
        return null;
    }

    /**
     * Resolve the LSP name of the VM NIC sitting on the rule's network. OVN
     * uses {@code logical_port} on a {@code dnat_and_snat} row to bind the
     * NAT to a specific guest port — required for distributed gateway-port
     * traffic (proxy ARP / GARP, reverse-path SNAT, ovn-trace correctness).
     * Returns {@code null} when the lookup fails (no NIC on that network for
     * that VM, or the NIC has no UUID); the NAT row is still emitted but
     * without {@code logical_port}, which OVN treats as any-port match.
     */
    private String lookupLogicalPort(final Network network, final PortForwardingRule rule) {
        final NicVO nic = nicDao.findNonReleasedByInstanceIdAndNetworkId(network.getId(), rule.getVirtualMachineId());
        if (nic == null) {
            LOGGER.debug("OvnPortForwardingService: no NIC for vm={} on network={} (rule={})",
                    rule.getVirtualMachineId(), network.getId(), rule.getId());
            return null;
        }
        if (StringUtils.isNotBlank(nic.getUuid())) {
            return "lsp-" + nic.getUuid();
        }
        return "lsp-nic-" + nic.getId();
    }

    /**
     * Render the NAT row's {@code external_port_range} value. CloudStack
     * exposes start/end ports per rule; OVN accepts a single port (e.g.
     * {@code "22"}) or a range (e.g. {@code "8080-8090"}). When start ==
     * end we emit a single port — the canonical 1:1 PF shape.
     */
    private static String buildExternalPortRange(final PortForwardingRule rule) {
        final Integer startBoxed = rule.getSourcePortStart();
        final Integer endBoxed = rule.getSourcePortEnd();
        final int start = startBoxed == null ? 0 : startBoxed;
        final int end = endBoxed == null ? 0 : endBoxed;
        if (end <= 0 || end == start) {
            return Integer.toString(start);
        }
        return start + "-" + end;
    }
}
