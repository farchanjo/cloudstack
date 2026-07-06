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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.config.OvnNicConfig;
import com.cloud.network.ovn.config.OvnNicTunables;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import org.apache.commons.lang3.StringUtils;

/**
 * Maps CloudStack {@link LoadBalancingRule} rows onto OVN NB
 * {@code load_balancer} rows.
 *
 * <p>One CloudStack LB rule becomes one OVN load_balancer row attached to
 * the VPC's gateway Logical_Router (north-south LB). The rule id is
 * recorded in the load_balancer's {@code external_ids} so re-applies are
 * idempotent and a CloudStack {@code Revoke} cleanly resolves the matching
 * OVN UUID via {@code ovn_logical_id_map} (kind={@link Kind#LOAD_BALANCER}).
 *
 * <p>Algorithm mapping:
 * <ul>
 *   <li>{@code roundrobin} -> OVN default selection (no
 *       {@code selection_fields}).
 *   <li>{@code source} / {@code source-hash} ->
 *       {@code selection_fields=[ip_src, ip_dst, tp_src, tp_dst]}.
 *   <li>{@code leastconn} / {@code least-connections} -> not supported by
 *       OVN; the rule is rejected with a clear error so the operator can
 *       pick a supported algorithm.
 * </ul>
 *
 * <p>Health checks: the CloudStack TCP / HTTP probe primitives do not have
 * a 1:1 mapping in OVN's {@code Load_Balancer_Health_Check} table (which
 * is L4-only and ICMP/TCP based). The MVP records the gap in a debug log
 * line and proceeds without a health-check row; tests assert the warning
 * is emitted so the operator notices.
 */
/**
 * Helper bean. Not a CloudStack {@code NetworkElement}: the plugin federates
 * all per-service implementations through the single {@link OvnNetworkElement}
 * (CloudStack enforces a 1:1 Provider &lt;-&gt; NetworkElement registration). The
 * element {@code @Inject}s this helper and delegates the {@code
 * LoadBalancingServiceProvider} contract to it. {@code AdapterBase} is kept
 * so the bean retains a stable {@code name} for log-line attribution.
 */
@Component
public class OvnLoadBalancerService extends AdapterBase {

    private static final Logger LOGGER = LogManager.getLogger(OvnLoadBalancerService.class);

    /** OVN selection_fields entries that emulate CloudStack's source-hash. */
    private static final List<String> SOURCE_HASH_FIELDS = List.of("ip_src", "ip_dst", "tp_src", "tp_dst");

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = buildCapabilities();

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private OvnBgpRedistributeManager bgpRedistributeManager;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;
    @Inject
    private NetworkDetailsDao networkDetailsDao;
    @Inject
    private NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Inject
    private NicDao nicDao;

    public Map<Service, Map<Capability, String>> getCapabilities() {
        return CAPABILITIES;
    }

    public Provider getProvider() {
        return OvnNetworkProvider.OVN_PROVIDER;
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        return super.configure(name, params);
    }

    public boolean applyLBRules(final Network network, final List<LoadBalancingRule> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.warn("OvnLoadBalancerService: no OVN controller for zone {} — skipping {} rule(s)",
                    zoneId, rules.size());
            return false;
        }
        final String lrUuid = lookupVpcLrUuid(network, controller);
        if (lrUuid == null) {
            LOGGER.warn("OvnLoadBalancerService: tier id={} has no parent VPC LR mapping; LB skipped",
                    network.getId());
            return false;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        boolean overall = true;
        for (final LoadBalancingRule rule : rules) {
            try {
                applyOne(nb, controller, lrUuid, network, rule);
            } catch (final OvnException oe) {
                LOGGER.error("OvnLoadBalancerService: failed to apply LB rule id={}: {}", rule.getId(), oe.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    public boolean validateLBRule(final Network network, final LoadBalancingRule rule) {
        // Reject algorithms OVN cannot represent before the rule is committed.
        final String algo = rule.getAlgorithm() == null ? "" : rule.getAlgorithm().toLowerCase(Locale.ROOT);
        if ("leastconn".equals(algo) || "least-connections".equals(algo) || "leastconnection".equals(algo)) {
            LOGGER.warn("OvnLoadBalancerService: leastconn LB algorithm is not supported by OVN; rule id={} rejected",
                    rule.getId());
            return false;
        }
        return true;
    }

    public List<LoadBalancerTO> updateHealthChecks(final Network network, final List<LoadBalancingRule> lbrules) {
        // OVN's L4 health-check table cannot represent CloudStack's HTTP /
        // TCP probe primitives 1:1; the MVP defers and relies on CloudStack
        // to drive backend rotation through applyLBRules + the Destination
        // .isRevoked() path.
        return new ArrayList<>();
    }

    public boolean handlesOnlyRulesInTransitionState() {
        return false;
    }

    public IpDeployer getIpDeployer(final Network network) {
        // OVN handles IP deployment via the VPC LR LRP gateway IP / NAT
        // rules already programmed by OvnVpcElement + OvnSourceNatService.
        return null;
    }

    private void applyOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                          final Network network, final LoadBalancingRule rule) {
        final FirewallRule.State state = rule.getState();
        if (state == FirewallRule.State.Revoke) {
            revokeOne(nb, controller, lrUuid, network, rule);
            return;
        }
        if (state != FirewallRule.State.Add && state != FirewallRule.State.Active) {
            LOGGER.debug("OvnLoadBalancerService: skipping rule id={} in state {}", rule.getId(), state);
            return;
        }
        final Map<String, String> vips = buildVipsMap(rule);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.LOAD_BALANCER, rule.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when LB row was deleted out-of-band.
            if (nb.rowExistsByUuid("Load_Balancer", existing.getOvnUuid())) {
                updateExistingLbRow(nb, controller, network, existing.getOvnUuid(), rule, vips);
                return;
            }
            LOGGER.warn("OvnLoadBalancerService: LOAD_BALANCER mapping rule={} -> {} stale; recreating",
                    rule.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        if (vips.isEmpty()) {
            LOGGER.warn("OvnLoadBalancerService: rule id={} has no live destinations; LB row skipped",
                    rule.getId());
            return;
        }
        final List<String> selectionFields = selectionFieldsFor(rule);
        final String protocol = protocolFor(rule);
        final String name = "cs-lb-" + rule.getId();
        final Map<String, String> ext = buildExternalIds(rule);
        final Map<String, String> options = buildLbOptions(rule, network);
        final String lbUuid = nb.createLoadBalancer(name, vips, protocol, selectionFields, ext, options);
        try {
            nb.attachLoadBalancerToLogicalRouter(lrUuid, lbUuid);
        } catch (final OvnException oe) {
            // Best-effort cleanup if attach fails so we do not leave an orphan row.
            try {
                nb.deleteLoadBalancer(lbUuid);
            } catch (final OvnException ignored) {
                // Already swallowed.
            }
            throw oe;
        }
        attachToTierLs(nb, controller, network, lbUuid);
        configureHealthCheck(nb, network, rule, lbUuid);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.LOAD_BALANCER, rule.getId(), controller.getId(), lbUuid, name));
        LOGGER.info("OvnLoadBalancerService: LB {} created (rule id={}, vips={}, algo={}, protocol={})",
                lbUuid, rule.getId(), vips, rule.getAlgorithm(), protocol);
        // Announce /32 for the LB VIP. The manager is idempotent — multiple
        // LB rules sharing the same source IP collapse to a single
        // BGP_ANNOUNCE row (keyed by ip address id, not rule id).
        announceLbVip(network, rule);
    }

    /**
     * Also attach the LB to the rule's tier Logical_Switch so guests on the
     * tier reach the VIP east-west ({@code ct_lb} runs on the source chassis,
     * fully symmetric); the LR attachment keeps serving north-south. Scope:
     * the rule's own tier only — sibling tiers of the VPC are deliberately
     * out (no cheap enumeration + teardown coupling). Best-effort.
     */
    private void attachToTierLs(final OvnNbClient nb, final OvnControllerVO controller,
                                final Network network, final String lbUuid) {
        final String lsUuid = lookupTierLsUuid(network, controller);
        if (lsUuid == null) {
            LOGGER.debug("OvnLoadBalancerService: no LS mapping for network; LB {} stays LR-only", lbUuid);
            return;
        }
        try {
            nb.attachLoadBalancerToLogicalSwitch(lsUuid, lbUuid);
        } catch (final OvnException e) {
            LOGGER.warn("OvnLoadBalancerService: LS attach of LB {} failed (east-west VIP degraded): {}",
                    lbUuid, e.getMessage());
        }
    }

    private String lookupTierLsUuid(final Network network, final OvnControllerVO controller) {
        if (network == null || controller == null || logicalIdMapDao == null) {
            return null;
        }
        final OvnLogicalIdMapVO ls = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        return ls == null ? null : ls.getOvnUuid();
    }

    /** OVN service-monitor probe cadence: 5s interval, 3s timeout, up 1, down 3. */
    private static final Map<String, String> HC_OPTIONS = Map.of(
            "interval", "5", "timeout", "3", "success_count", "1", "failure_count", "3");

    /**
     * Configure an OVN L4 health check so dead backends drop out of rotation
     * instead of blackholing new connections (critical while multi-backend
     * VIPs — e.g. the CKS API LB — bootstrap with only one live backend).
     * Best-effort: any failure leaves the LB functional without probes.
     * Skipped when a health check already exists (idempotent re-apply).
     */
    private void configureHealthCheck(final OvnNbClient nb, final Network network,
                                      final LoadBalancingRule rule, final String lbUuid) {
        try {
            if (rule.getSourceIp() == null || nb.loadBalancerHasHealthCheck(lbUuid)) {
                return;
            }
            final Map<String, String> mappings = buildIpPortMappings(network, rule);
            if (mappings.isEmpty()) {
                LOGGER.debug("OvnLoadBalancerService: no ip_port_mappings resolvable for rule id={}; "
                        + "health check skipped", rule.getId());
                return;
            }
            final String vip = rule.getSourceIp().addr() + ":" + rule.getSourcePortStart();
            nb.configureLoadBalancerHealthCheck(lbUuid, vip, mappings, HC_OPTIONS, buildExternalIds(rule));
            LOGGER.info("OvnLoadBalancerService: health check configured on LB {} (rule id={}, {} backend(s))",
                    lbUuid, rule.getId(), mappings.size());
        } catch (final Exception e) {
            LOGGER.warn("OvnLoadBalancerService: health-check config for LB {} failed (LB stays probe-less): {}",
                    lbUuid, e.getMessage());
        }
    }

    /**
     * Backend IP -> {@code "<lsp-name>:<probe-source-ip>"} for every live
     * destination. All-or-nothing: a backend whose NIC cannot be resolved
     * aborts the mapping (a partial map would leave unprobed backends
     * permanently "online" per ovn-nb(5) semantics).
     */
    private Map<String, String> buildIpPortMappings(final Network network, final LoadBalancingRule rule) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (nicDao == null || network == null || rule.getDestinations() == null) {
            return out;
        }
        final String sourceIp = healthCheckSourceIp(network);
        if (sourceIp == null) {
            return out;
        }
        for (final LbDestination d : rule.getDestinations()) {
            if (d.isRevoked()) {
                continue;
            }
            final NicVO nic = nicDao.findByIp4AddressAndNetworkId(d.getIpAddress(), network.getId());
            if (nic == null || StringUtils.isBlank(nic.getUuid())) {
                LOGGER.debug("OvnLoadBalancerService: no NIC for backend {} on network id={}; "
                        + "skipping health check for rule id={}", d.getIpAddress(), network.getId(), rule.getId());
                return new LinkedHashMap<>();
            }
            out.put(d.getIpAddress(), "lsp-" + nic.getUuid() + ":" + sourceIp);
        }
        return out;
    }

    /**
     * Probe source IP: OVN claims (svc_monitor_mac, source-ip) on the LS, so
     * the address must be unused. Walk down from the subnet's last usable
     * host IP until an unallocated one is found (bounded). Follow-up: reserve
     * the address through IPAM instead of probing allocation state.
     */
    private String healthCheckSourceIp(final Network network) {
        final String cidr = network.getCidr();
        if (StringUtils.isBlank(cidr) || !cidr.contains("/")) {
            return null;
        }
        final String[] parts = cidr.split("/");
        long candidate = NetUtils.ip2Long(NetUtils.getIpRangeEndIpFromCidr(parts[0], Long.parseLong(parts[1])));
        for (int i = 0; i < 8; i++, candidate--) {
            final String ip = NetUtils.long2Ip(candidate);
            if (nicDao.findByIp4AddressAndNetworkId(ip, network.getId()) == null) {
                return ip;
            }
        }
        LOGGER.warn("OvnLoadBalancerService: no free probe source IP near the top of {} (network id={})",
                cidr, network.getId());
        return null;
    }

    /**
     * Updates an OVN {@code Load_Balancer} row that already exists in the NB DB.
     *
     * <p>Two operations are issued atomically against the same OVSDB row:
     * <ol>
     *   <li>If {@code vips} is non-empty, rewrite the {@code vips} column via
     *       {@link OvnNbClient#updateLoadBalancerBackends}.</li>
     *   <li>Always rewrite {@code selection_fields} and {@code external_ids} via
     *       {@link OvnNbClient#updateLoadBalancerProperties} so that an operator
     *       algorithm change is reflected immediately, even when no backends are
     *       live at the time of the update.</li>
     * </ol>
     *
     * <p><b>Side-effect</b>: rewriting {@code selection_fields} flushes OVN
     * conntrack state for the VIP, rescheduling in-flight connections.
     * This is the intended behaviour for an admin-driven algorithm change.
     */
    private void updateExistingLbRow(final OvnNbClient nb, final OvnControllerVO controller, final Network network,
                                     final String lbUuid, final LoadBalancingRule rule, final Map<String, String> vips) {
        if (!vips.isEmpty()) {
            nb.updateLoadBalancerBackends(lbUuid, vips);
            LOGGER.info("OvnLoadBalancerService: LB {} backends updated for rule id={}", lbUuid, rule.getId());
        } else {
            LOGGER.info("OvnLoadBalancerService: LB {} has no live backends; skipping vips write for rule id={}",
                    lbUuid, rule.getId());
        }
        nb.updateLoadBalancerProperties(lbUuid, selectionFieldsFor(rule), buildExternalIds(rule));
        LOGGER.info("OvnLoadBalancerService: LB {} properties re-synced (algo={}) for rule id={}",
                lbUuid, rule.getAlgorithm(), rule.getId());
        // Re-assert the east-west LS attachment (idempotent set insert) and
        // the health check (skipped when one already exists) so pre-existing
        // LB rows converge to the new shape on their next apply touch.
        attachToTierLs(nb, controller, network, lbUuid);
        configureHealthCheck(nb, network, rule, lbUuid);
    }

    private void revokeOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                           final Network network, final LoadBalancingRule rule) {
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.LOAD_BALANCER, rule.getId(), controller.getId());
        if (mapping == null) {
            LOGGER.debug("OvnLoadBalancerService: no OVN LB mapping for rule id={}; revoke is a no-op",
                    rule.getId());
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        if (!pendingDeletionDao.isPendingByOvnUuid(mapping.getOvnUuid(), Kind.LOAD_BALANCER.name())) {
            pendingDeletionDao.persist(new OvnPendingDeletionVO(
                    UUID.randomUUID().toString(), controller.getId(), network.getDataCenterId(),
                    Kind.LOAD_BALANCER, mapping.getOvnUuid(), rule.getId()));
            LOGGER.info("OvnLoadBalancerService: enqueued pending deletion kind=LOAD_BALANCER ovn_uuid={} cs_id={}",
                    mapping.getOvnUuid(), rule.getId());
        }
        try {
            nb.detachLoadBalancerFromLogicalRouter(lrUuid, mapping.getOvnUuid());
        } catch (final OvnException oe) {
            LOGGER.warn("OvnLoadBalancerService: detach LB {} from LR {} failed: {}",
                    mapping.getOvnUuid(), lrUuid, oe.getMessage());
        }
        final String lsUuid = lookupTierLsUuid(network, controller);
        if (lsUuid != null) {
            try {
                nb.detachLoadBalancerFromLogicalSwitch(lsUuid, mapping.getOvnUuid());
            } catch (final OvnException oe) {
                // Best-effort; set delete of an absent element is a no-op anyway.
                LOGGER.warn("OvnLoadBalancerService: detach LB {} from LS {} failed: {}",
                        mapping.getOvnUuid(), lsUuid, oe.getMessage());
            }
        }
        try {
            nb.deleteLoadBalancer(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.LOAD_BALANCER.name());
        } catch (final OvnException oe) {
            // Mapping survives so reconciler + processor can retry.
            LOGGER.warn("OvnLoadBalancerService: LB {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), oe.getMessage());
            throw oe;
        }
        LOGGER.info("OvnLoadBalancerService: LB {} revoked (rule id={})", mapping.getOvnUuid(), rule.getId());
        // Best-effort withdraw — if another LB or NAT rule shares the public
        // IP, the announce path on its next applyOne() touch re-creates the
        // BGP_ANNOUNCE row.
        withdrawLbVip(network, rule);
    }

    /**
     * Resolve the public IP backing the LB rule's source IP and announce a
     * {@code /32} via the gateway-chassis FRR. Best-effort: a missing
     * IPAddress row, missing VPC binding, or operator-disabled redistribute
     * collapses to a no-op.
     */
    private void announceLbVip(final Network network, final LoadBalancingRule rule) {
        if (network == null || rule == null || network.getVpcId() == null) {
            return;
        }
        final IPAddressVO ipRow = lookupSourceIpRow(rule);
        if (ipRow == null) {
            return;
        }
        final String publicIp = ipRow.getAddress() == null ? null : ipRow.getAddress().addr();
        if (publicIp == null || publicIp.isEmpty()) {
            return;
        }
        bgpRedistributeManager.announce(publicIp, ipRow.getId(),
                network.getVpcId(), network.getDataCenterId());
    }

    private void withdrawLbVip(final Network network, final LoadBalancingRule rule) {
        if (network == null || rule == null || network.getVpcId() == null) {
            return;
        }
        final IPAddressVO ipRow = lookupSourceIpRow(rule);
        if (ipRow == null) {
            return;
        }
        final String publicIp = ipRow.getAddress() == null ? null : ipRow.getAddress().addr();
        if (publicIp == null || publicIp.isEmpty()) {
            return;
        }
        bgpRedistributeManager.withdraw(publicIp, ipRow.getId(),
                network.getVpcId(), network.getDataCenterId());
    }

    private IPAddressVO lookupSourceIpRow(final LoadBalancingRule rule) {
        if (rule == null) {
            return null;
        }
        // LoadBalancer extends FirewallRule -> getSourceIpAddressId().
        final Long ipId = rule.getLb() == null ? null : rule.getLb().getSourceIpAddressId();
        if (ipId == null) {
            return null;
        }
        return ipAddressDao == null ? null : ipAddressDao.findById(ipId);
    }

    private String lookupVpcLrUuid(final Network network, final OvnControllerVO controller) {
        if (network.getVpcId() == null) {
            return null;
        }
        // The tier may have been resolved via NetworkDao; we already have it.
        final Long vpcId = network.getVpcId();
        final OvnLogicalIdMapVO row = logicalIdMapDao.findByCsId(Kind.VPC, vpcId, controller.getId());
        return row == null ? null : row.getOvnUuid();
    }

    /** Visible for testing. */
    public static Map<String, String> buildVipsMap(final LoadBalancingRule rule) {
        final Map<String, String> out = new HashMap<>();
        if (rule.getSourceIp() == null) {
            return out;
        }
        final String vipKey = rule.getSourceIp().addr() + ":" + rule.getSourcePortStart();
        final List<? extends LbDestination> dests = rule.getDestinations();
        if (dests == null || dests.isEmpty()) {
            return out;
        }
        final List<String> backends = new ArrayList<>();
        for (final LbDestination d : dests) {
            if (d.isRevoked()) {
                continue;
            }
            backends.add(d.getIpAddress() + ":" + d.getDestinationPortStart());
        }
        if (backends.isEmpty()) {
            return out;
        }
        out.put(vipKey, String.join(",", backends));
        return out;
    }

    /** Visible for testing. */
    public static List<String> selectionFieldsFor(final LoadBalancingRule rule) {
        final String algo = rule.getAlgorithm() == null ? "" : rule.getAlgorithm().toLowerCase(Locale.ROOT);
        switch (algo) {
            case "source":
            case "source-hash":
            case "sourcehash":
                return SOURCE_HASH_FIELDS;
            case "roundrobin":
            case "round-robin":
            case "rr":
            case "":
                return Collections.emptyList();
            default:
                LOGGER.debug("OvnLoadBalancerService: unknown algorithm '{}' — falling back to OVN default", algo);
                return Collections.emptyList();
        }
    }

    /** Visible for testing. */
    public static String protocolFor(final LoadBalancingRule rule) {
        final String proto = rule.getProtocol() == null ? "" : rule.getProtocol().toLowerCase(Locale.ROOT);
        if ("tcp".equals(proto) || "http".equals(proto) || "https".equals(proto) || "ssl".equals(proto)) {
            return OvnNbClient.LB_PROTOCOL_TCP;
        }
        if ("udp".equals(proto)) {
            return OvnNbClient.LB_PROTOCOL_UDP;
        }
        if ("sctp".equals(proto)) {
            return OvnNbClient.LB_PROTOCOL_SCTP;
        }
        return null;
    }

    private static Map<String, String> buildExternalIds(final LoadBalancingRule rule) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.LOAD_BALANCER.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
        if (rule.getUuid() != null) {
            ext.put("cs_uuid", rule.getUuid());
        }
        if (rule.getAlgorithm() != null) {
            ext.put("cs_algo", rule.getAlgorithm());
        }
        return ext;
    }

    /**
     * Build the OVN {@code Load_Balancer.options} column. The MVP sets
     * {@code hairpin_snat_ip} so that a backend VM hitting its own VIP gets
     * SNAT'd back to the VIP, preventing the kernel-loopback short-circuit
     * and forcing the reflected packet through the LR datapath. Without
     * this, a backend client hitting its own VIP would see the request
     * arrive with src=its-own-IP, dst=its-own-IP and drop it as a martian.
     *
     * <p>{@code affinity_timeout} is resolved through the 4-scope chain
     * (vm_details not applicable here — LB is network-scoped):
     * network_details &gt; network_offering_details &gt; global ConfigKey.
     *
     * <p>Visible for testing.
     */
    public Map<String, String> buildLbOptions(final LoadBalancingRule rule, final Network network) {
        final Map<String, String> opts = new HashMap<>();
        if (rule.getSourceIp() != null) {
            final String vip = rule.getSourceIp().addr();
            if (vip != null && !vip.isEmpty()) {
                opts.put("hairpin_snat_ip", vip);
            }
        }
        // OVN client-to-backend affinity: resolved via 4-scope chain so that
        // per-network and per-network-offering overrides are honoured.
        // LB is network-scoped (no per-VM context here) — vmDetails is null.
        // Value 0 (default) omits the key — OVN's default is no affinity.
        final Map<String, String> netDetails = (networkDetailsDao != null && network != null)
                ? networkDetailsDao.listDetailsKeyPairs(network.getId())
                : null;
        final Map<NetworkOffering.Detail, String> offeringDetails =
                (networkOfferingDetailsDao != null && network != null)
                ? networkOfferingDetailsDao.getNtwkOffDetails(network.getNetworkOfferingId())
                : null;
        final Integer affinityTimeout = OvnNicTunables.resolve(
                OvnNicTunables.OVN_LB_AFFINITY_TIMEOUT,
                /* vmDetails */ null,
                netDetails,
                offeringDetails,
                OvnNicConfig.LbAffinityTimeout.value(),
                Integer.class);
        if (affinityTimeout != null && affinityTimeout > 0) {
            opts.put("affinity_timeout", String.valueOf(affinityTimeout));
        }
        return opts;
    }

    /**
     * Optional helper for callers that want to reach the underlying tier
     * Network when only an LB rule is in scope. Hands off to {@link NetworkDao}.
     */
    NetworkVO resolveNetworkForRule(final long networkId) {
        return networkDao.findById(networkId);
    }

    private static Map<Service, Map<Capability, String>> buildCapabilities() {
        final Map<Service, Map<Capability, String>> caps = new HashMap<>();
        caps.put(Service.Lb, null);
        return caps;
    }

    // ------------------------------------------------------------------
    // NetworkElement boilerplate.
    // ------------------------------------------------------------------

    public boolean implement(final Network network, final NetworkOffering offering, final DeployDestination dest,
                             final ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        return true;
    }

    public boolean prepare(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                           final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        return true;
    }

    public boolean release(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                           final ReservationContext context) {
        return true;
    }

    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) {
        return true;
    }

    public boolean destroy(final Network network, final ReservationContext context) {
        return true;
    }

    public boolean isReady(final PhysicalNetworkServiceProvider provider) {
        return true;
    }

    public boolean shutdownProviderInstances(final PhysicalNetworkServiceProvider provider,
                                             final ReservationContext context) {
        return true;
    }

    public boolean canEnableIndividualServices() {
        return true;
    }

    public boolean verifyServicesCombination(final Set<Service> services) {
        return services.contains(Service.Lb);
    }
}
