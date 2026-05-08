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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

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
 *       {@code selection_fields=[ip4_src, ip4_dst, tcp_src, tcp_dst]}.
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
    private static final List<String> SOURCE_HASH_FIELDS = List.of("ip_src", "ip_dst", "tcp_src", "tcp_dst");

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = buildCapabilities();

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private NetworkDao networkDao;

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
                applyOne(nb, controller, lrUuid, rule);
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
                          final LoadBalancingRule rule) {
        final FirewallRule.State state = rule.getState();
        if (state == FirewallRule.State.Revoke) {
            revokeOne(nb, controller, lrUuid, rule);
            return;
        }
        if (state != FirewallRule.State.Add && state != FirewallRule.State.Active) {
            LOGGER.debug("OvnLoadBalancerService: skipping rule id={} in state {}", rule.getId(), state);
            return;
        }
        final Map<String, String> vips = buildVipsMap(rule);
        if (vips.isEmpty()) {
            LOGGER.warn("OvnLoadBalancerService: rule id={} has no live destinations; LB row skipped",
                    rule.getId());
            return;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.LOAD_BALANCER, rule.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when LB row was deleted out-of-band.
            if (nb.rowExistsByUuid("Load_Balancer", existing.getOvnUuid())) {
                // Backend pool change → atomic vips re-write.
                nb.updateLoadBalancerBackends(existing.getOvnUuid(), vips);
                LOGGER.info("OvnLoadBalancerService: LB {} backends updated for rule id={}",
                        existing.getOvnUuid(), rule.getId());
                return;
            }
            LOGGER.warn("OvnLoadBalancerService: LOAD_BALANCER mapping rule={} -> {} stale; recreating",
                    rule.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final List<String> selectionFields = selectionFieldsFor(rule);
        final String protocol = protocolFor(rule);
        final String name = "cs-lb-" + rule.getId();
        final Map<String, String> ext = buildExternalIds(rule);
        final Map<String, String> options = buildLbOptions(rule);
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
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.LOAD_BALANCER, rule.getId(), controller.getId(), lbUuid, name));
        LOGGER.info("OvnLoadBalancerService: LB {} created (rule id={}, vips={}, algo={}, protocol={})",
                lbUuid, rule.getId(), vips, rule.getAlgorithm(), protocol);
        if (rule.getHealthCheckPolicies() != null && !rule.getHealthCheckPolicies().isEmpty()) {
            LOGGER.warn("OvnLoadBalancerService: rule id={} has CloudStack health-check policies; "
                    + "OVN L4 health-check mapping is not implemented in MVP", rule.getId());
        }
    }

    private void revokeOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                           final LoadBalancingRule rule) {
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.LOAD_BALANCER, rule.getId(), controller.getId());
        if (mapping == null) {
            LOGGER.debug("OvnLoadBalancerService: no OVN LB mapping for rule id={}; revoke is a no-op",
                    rule.getId());
            return;
        }
        try {
            nb.detachLoadBalancerFromLogicalRouter(lrUuid, mapping.getOvnUuid());
        } catch (final OvnException oe) {
            LOGGER.warn("OvnLoadBalancerService: detach LB {} from LR {} failed: {}",
                    mapping.getOvnUuid(), lrUuid, oe.getMessage());
        }
        try {
            nb.deleteLoadBalancer(mapping.getOvnUuid());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
        LOGGER.info("OvnLoadBalancerService: LB {} revoked (rule id={})", mapping.getOvnUuid(), rule.getId());
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
     * <p>Visible for testing.
     */
    public static Map<String, String> buildLbOptions(final LoadBalancingRule rule) {
        final Map<String, String> opts = new HashMap<>();
        if (rule.getSourceIp() != null) {
            final String vip = rule.getSourceIp().addr();
            if (vip != null && !vip.isEmpty()) {
                opts.put("hairpin_snat_ip", vip);
            }
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
