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

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

/**
 * Maps CloudStack {@link PortForwardingRule}s onto OVN {@code Load_Balancer}
 * rows attached to the VPC tier's / isolated network's gateway
 * {@code Logical_Router}.
 *
 * <p>Rationale: OVN's {@code NAT} table does NOT translate destination
 * <em>ports</em> — {@code external_port_range} on a {@code dnat_and_snat} row
 * is SNAT-source-port scoped only, so a {@code dnat_and_snat} row emitted with
 * an external port delivers the packet with its destination port unchanged.
 * A port-forward such as {@code ext:2222 -> vm:22} therefore arrived at the VM
 * still addressed to port 2222 and was refused. Destination-port DNAT in OVN
 * is expressed exclusively through {@code Load_Balancer} VIP entries
 * ({@code vip_ip:vip_port -> backend_ip:backend_port}); this is the same shape
 * the plugin already uses for CloudStack LB rules
 * ({@link OvnLoadBalancerService}). One PF rule becomes one
 * {@code cs-pf-<ruleId>} load_balancer row with a single backend.
 *
 * <p>Full-IP 1:1 static NAT ({@code enableStaticNat}) legitimately stays on
 * {@code dnat_and_snat} (no port translation involved) and is owned by
 * {@link OvnStaticNatService} — untouched here.
 *
 * <p>Self-heal: a broken deployment whose PF rules still live as legacy
 * {@code dnat_and_snat}-with-port rows migrates transparently. On
 * {@code apply} a mapping pointing at a {@code NAT} row is dropped and the
 * rule is recreated as an LB; on {@code revoke} both the tracked row and any
 * untracked legacy {@code dnat_and_snat} matching
 * {@code external_ip + external_port_range + logical_ip} are removed.
 */
@Component
public class OvnPortForwardingService {

    private static final Logger LOGGER = LogManager.getLogger(OvnPortForwardingService.class);

    /**
     * OVN NAT type of the legacy (pre-Load_Balancer) PF shape. Retained only
     * for self-heal detection + cleanup; live PF rules now use Load_Balancer.
     */
    public static final String LEGACY_NAT_TYPE_DNAT_AND_SNAT = "dnat_and_snat";

    /** Load_Balancer name prefix for a port-forward rule ({@code cs-pf-<ruleId>}). */
    public static final String PF_LB_NAME_PREFIX = "cs-pf-";

    /**
     * Upper bound on the number of ports a single PF rule may expand into
     * (one Load_Balancer VIP entry per port). Guards against a fat range
     * exploding the {@code vips} column.
     */
    static final int MAX_PF_RANGE_PORTS = 64;

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
    private com.cloud.network.ovn.manager.OvnBgpRedistributeManager bgpRedistributeManager;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Apply every supplied PF rule. Idempotent: an existing mapping to a live
     * {@code Load_Balancer} re-syncs its backends; a mapping to a legacy
     * {@code NAT} row migrates to an LB; rules in {@code Revoke} state drop
     * the LB (or legacy NAT) row and the mapping.
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
        // Public IP comes from FirewallRule.sourceIpAddressId (the floating IP
        // allocated to the rule); private IP is the VM-side target carried on
        // PortForwardingRule.destinationIpAddress.
        final IPAddressVO publicIpRow = ipAddressDao.findById(rule.getSourceIpAddressId());
        final String publicIp = publicIpRow == null || publicIpRow.getAddress() == null
                ? null : publicIpRow.getAddress().addr();
        final String privateIp = lookupVmIp(rule);
        if (StringUtils.isBlank(publicIp) || StringUtils.isBlank(privateIp)) {
            LOGGER.warn("OvnPortForwardingService: rule id={} missing public/private IP (pub={} priv={}); skipping",
                    rule.getId(), publicIp, privateIp);
            return;
        }
        final Map<String, String> vips = buildPfVips(publicIp, privateIp, rule);
        final String protocol = protocolFor(rule);
        final Map<String, String> ext = buildExternalIds(network, rule);
        if (existing != null && reconcileExisting(nb, existing, rule, vips)) {
            return;
        }
        createAndAttachLb(nb, controller, lrUuid, network, rule, vips, protocol, ext, publicIpRow);
    }

    /**
     * Handle a pre-existing PORT_FORWARDING mapping. Returns {@code true} when
     * the rule is fully reconciled (mapping points at a live
     * {@code Load_Balancer}, whose backends are re-synced idempotently).
     * Returns {@code false} — after dropping the row + mapping — when the
     * caller must create a fresh LB: a legacy {@code dnat_and_snat} NAT-PF row
     * (migrated to LB) or a stale mapping.
     */
    private boolean reconcileExisting(final OvnNbClient nb, final OvnLogicalIdMapVO existing,
                                      final PortForwardingRule rule, final Map<String, String> vips) {
        if (nb.rowExistsByUuid("Load_Balancer", existing.getOvnUuid())) {
            nb.updateLoadBalancerBackends(existing.getOvnUuid(), vips);
            LOGGER.debug("OvnPortForwardingService: rule id={} LB {} backends re-synced",
                    rule.getId(), existing.getOvnUuid());
            return true;
        }
        if (nb.rowExistsByUuid("NAT", existing.getOvnUuid())) {
            LOGGER.info("OvnPortForwardingService: migrating legacy dnat_and_snat PF rule id={} (nat={}) to LB",
                    rule.getId(), existing.getOvnUuid());
            nb.deleteNatRule(existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
            return false;
        }
        LOGGER.warn("OvnPortForwardingService: PORT_FORWARDING mapping rule={} -> {} stale; recreating",
                rule.getId(), existing.getOvnUuid());
        logicalIdMapDao.remove(existing.getId());
        return false;
    }

    private void createAndAttachLb(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                                   final Network network, final PortForwardingRule rule,
                                   final Map<String, String> vips, final String protocol,
                                   final Map<String, String> ext, final IPAddressVO publicIpRow) {
        final String name = PF_LB_NAME_PREFIX + rule.getId();
        final String lbUuid = nb.createLoadBalancer(name, vips, protocol, Collections.emptyList(), ext, null);
        try {
            nb.attachLoadBalancerToLogicalRouter(lrUuid, lbUuid);
        } catch (final OvnException oe) {
            // Best-effort cleanup so a failed attach does not leave an orphan row.
            try {
                nb.deleteLoadBalancer(lbUuid);
            } catch (final OvnException ignored) {
                // Already swallowed.
            }
            throw oe;
        }
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.PORT_FORWARDING, rule.getId(), controller.getId(), lbUuid, name));
        LOGGER.info("OvnPortForwardingService: PF rule id={} -> LB {} (protocol={}, vips={})",
                rule.getId(), lbUuid, protocol, vips);
        // Announce /32 for the public IP backing this PF rule. Idempotent —
        // a public IP with multiple PF rules announces once (keyed by IP id).
        announce(network, publicIpRow);
    }

    private void revokeOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                           final OvnLogicalIdMapVO mapping, final Network network, final PortForwardingRule rule) {
        // Belt-and-suspenders self-heal: drop any untracked legacy
        // dnat_and_snat PF row for this rule (fat-jar drift can desync the
        // mapping from the NB DB). Runs regardless of mapping presence.
        cleanupLegacyNat(nb, rule);
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
            deleteTrackedRow(nb, lrUuid, mapping);
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.PORT_FORWARDING.name());
        } catch (OvnException e) {
            LOGGER.warn("OvnPortForwardingService: revoke {} failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
        }
        withdraw(network, rule);
    }

    /**
     * Delete the NB row a mapping points at. New PF rules track a
     * {@code Load_Balancer} (detach from the LR, then delete); a legacy
     * mapping may still track a {@code NAT} row (delete directly). A row that
     * is already gone is a no-op.
     */
    private void deleteTrackedRow(final OvnNbClient nb, final String lrUuid, final OvnLogicalIdMapVO mapping) {
        if (nb.rowExistsByUuid("Load_Balancer", mapping.getOvnUuid())) {
            try {
                nb.detachLoadBalancerFromLogicalRouter(lrUuid, mapping.getOvnUuid());
            } catch (OvnException ignored) {
                // Best-effort; the LB may already be detached.
            }
            nb.deleteLoadBalancer(mapping.getOvnUuid());
        } else if (nb.rowExistsByUuid("NAT", mapping.getOvnUuid())) {
            nb.deleteNatRule(mapping.getOvnUuid());
        } else {
            LOGGER.debug("OvnPortForwardingService: revoke {} no-op (row already gone)", mapping.getOvnUuid());
        }
    }

    /**
     * Remove any legacy {@code dnat_and_snat} PF row for this rule by matching
     * {@code external_ip + external_port_range + logical_ip}. The mandatory
     * non-empty {@code external_port_range} match keeps full-IP static-NAT
     * rows (which never set it) untouched. Best-effort.
     */
    private void cleanupLegacyNat(final OvnNbClient nb, final PortForwardingRule rule) {
        if (rule == null) {
            return;
        }
        final IPAddressVO publicIpRow = ipAddressDao.findById(rule.getSourceIpAddressId());
        final String publicIp = publicIpRow == null || publicIpRow.getAddress() == null
                ? null : publicIpRow.getAddress().addr();
        final String privateIp = lookupVmIp(rule);
        if (StringUtils.isBlank(publicIp) || StringUtils.isBlank(privateIp)) {
            return;
        }
        try {
            final int removed = nb.deleteNatByMatch(LEGACY_NAT_TYPE_DNAT_AND_SNAT, publicIp,
                    buildExternalPortRange(rule), privateIp);
            if (removed > 0) {
                LOGGER.info("OvnPortForwardingService: self-healed {} legacy dnat_and_snat PF row(s) for rule id={} "
                        + "({}:{} -> {})", removed, rule.getId(), publicIp, buildExternalPortRange(rule), privateIp);
            }
        } catch (OvnException e) {
            LOGGER.warn("OvnPortForwardingService: legacy NAT self-heal for rule id={} failed: {}",
                    rule.getId(), e.getMessage());
        }
    }

    /**
     * Sweep this network's {@code cs-pf-*} {@code Load_Balancer} rows on
     * network delete. Fires only for a network that owns an exclusive LR
     * (isolated Phase B, {@link Kind#NETWORK_LR}): its LR is exclusive, so
     * every PF LB attached to it belongs to this network. VPC tiers share the
     * VPC LR — their PF LBs are cleaned per-rule on revoke and by the
     * VPC-delete cascade, so this is a no-op there (sweeping a shared LR would
     * nuke sibling tiers). PF LB rows are weak refs and do NOT cascade with
     * the LR, so detach + delete them explicitly before the LR is dropped.
     * Idempotent + best-effort.
     */
    public void removeTierPortForwarding(final Network network) {
        if (network == null || network.getVpcId() != null) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(network.getDataCenterId());
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO lrMap = logicalIdMapDao.findByCsId(Kind.NETWORK_LR, network.getId(), controller.getId());
        if (lrMap == null) {
            return;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        final Set<String> onLr = new HashSet<>(nb.listLoadBalancersOnLogicalRouter(lrMap.getOvnUuid()));
        int removed = 0;
        for (final OvnLogicalIdMapVO row : logicalIdMapDao.listByKind(Kind.PORT_FORWARDING, controller.getId())) {
            if (onLr.contains(row.getOvnUuid()) && sweepPfLb(nb, lrMap.getOvnUuid(), row)) {
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("OvnPortForwardingService.removeTierPortForwarding: swept {} cs-pf LB(s) (network id={})",
                    removed, network.getId());
        }
    }

    private boolean sweepPfLb(final OvnNbClient nb, final String lrUuid, final OvnLogicalIdMapVO row) {
        try {
            nb.detachLoadBalancerFromLogicalRouter(lrUuid, row.getOvnUuid());
            nb.deleteLoadBalancer(row.getOvnUuid());
        } catch (final OvnException e) {
            LOGGER.warn("OvnPortForwardingService.removeTierPortForwarding: sweep LB {} failed: {}",
                    row.getOvnUuid(), e.getMessage());
            return false;
        }
        logicalIdMapDao.remove(row.getId());
        return true;
    }

    private void announce(final Network network, final IPAddressVO publicIpRow) {
        if (network.getVpcId() == null || publicIpRow == null || publicIpRow.getAddress() == null) {
            return;
        }
        bgpRedistributeManager.announce(publicIpRow.getAddress().addr(), publicIpRow.getId(),
                network.getVpcId(), network.getDataCenterId());
    }

    private void withdraw(final Network network, final PortForwardingRule rule) {
        if (network == null || rule == null || network.getVpcId() == null) {
            return;
        }
        final IPAddressVO publicIpRow = ipAddressDao.findById(rule.getSourceIpAddressId());
        if (publicIpRow == null || publicIpRow.getAddress() == null) {
            return;
        }
        // Best-effort withdraw — if another PF rule shares the IP the announce
        // path re-announces on its next applyOne() touch.
        bgpRedistributeManager.withdraw(publicIpRow.getAddress().addr(), publicIpRow.getId(),
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
     * PortForwardingRule carries the destination IP via
     * {@code getDestinationIpAddress()} (set by the orchestrator before the
     * rule is applied). Returns {@code null} when unresolved.
     */
    private static String lookupVmIp(final PortForwardingRule rule) {
        if (rule.getDestinationIpAddress() != null) {
            return rule.getDestinationIpAddress().addr();
        }
        return null;
    }

    /**
     * Expand a PF rule into OVN {@code Load_Balancer} VIP entries:
     * {@code publicIp:extPort -> privateIp:privPort}. CloudStack "source"
     * ports are the public/external ports; "destination" ports are the
     * VM-side ports. A single-port rule yields one entry; a port range yields
     * one offset-mapped entry per external port (all mapping to the single
     * private port when the private side is not itself a range). Fails with a
     * clear error when the range exceeds {@link #MAX_PF_RANGE_PORTS}.
     */
    private Map<String, String> buildPfVips(final String publicIp, final String privateIp,
                                            final PortForwardingRule rule) {
        final int extStart = orZero(rule.getSourcePortStart());
        final int extEndRaw = orZero(rule.getSourcePortEnd());
        final int extEnd = extEndRaw < extStart ? extStart : extEndRaw;
        final int privStart = rule.getDestinationPortStart();
        final int privEndRaw = rule.getDestinationPortEnd();
        final boolean privIsRange = privEndRaw > privStart;
        final int count = extEnd - extStart + 1;
        if (count > MAX_PF_RANGE_PORTS) {
            throw new OvnException(String.format(Locale.ROOT,
                    "OvnPortForwardingService: PF rule id=%d external port range %d-%d spans %d ports; max %d per rule",
                    rule.getId(), extStart, extEnd, count, MAX_PF_RANGE_PORTS));
        }
        final Map<String, String> vips = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            final int privPort = privIsRange ? privStart + i : privStart;
            vips.put(publicIp + ":" + (extStart + i), privateIp + ":" + privPort);
        }
        return vips;
    }

    private static int orZero(final Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * OVN {@code Load_Balancer} VIPs carrying an L4 port require a protocol.
     * CloudStack PF rules are tcp/udp; unknown/blank defaults to tcp.
     */
    private static String protocolFor(final PortForwardingRule rule) {
        final String proto = rule.getProtocol() == null ? "" : rule.getProtocol().toLowerCase(Locale.ROOT);
        if ("udp".equals(proto)) {
            return OvnNbClient.LB_PROTOCOL_UDP;
        }
        if ("sctp".equals(proto)) {
            return OvnNbClient.LB_PROTOCOL_SCTP;
        }
        return OvnNbClient.LB_PROTOCOL_TCP;
    }

    private static Map<String, String> buildExternalIds(final Network network, final PortForwardingRule rule) {
        final Map<String, String> ext = new LinkedHashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.PORT_FORWARDING.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
        ext.put(OvnConstants.EXT_ID_ZONE, String.valueOf(network.getDataCenterId()));
        return ext;
    }

    /**
     * Render the legacy NAT row's {@code external_port_range} value (single
     * port, or {@code start-end}) so a self-heal can match + delete it.
     */
    private static String buildExternalPortRange(final PortForwardingRule rule) {
        final int start = orZero(rule.getSourcePortStart());
        final int end = orZero(rule.getSourcePortEnd());
        if (end <= 0 || end == start) {
            return Integer.toString(start);
        }
        return start + "-" + end;
    }
}
