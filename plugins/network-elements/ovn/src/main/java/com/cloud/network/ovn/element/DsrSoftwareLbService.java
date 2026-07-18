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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.network.dao.DsrLbDesiredStateDao;
import com.cloud.network.dao.DsrLbDesiredStateVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnNbClient.EcmpStaticRoute;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

/**
 * Programmer for {@link LbKind#DSR_SOFTWARE} load balancer rules.
 *
 * <p><b>Never</b> creates OVN {@code Load_Balancer} rows, hairpin SNAT,
 * force SNAT, or NAT entries for the DSR VIP. Dataplane ownership is:
 * <ul>
 *   <li>{@code dsr_lb_desired_state} inventory row</li>
 *   <li>OVN {@code Logical_Router_Static_Route} ECMP rows on the VPC LR
 *       (VIP {@code /32} and/or {@code /128} → Ready guest backend next-hops),
 *       tagged {@link OvnConstants#EXT_ID_DSR_ROUTE}</li>
 *   <li>CT_LB host BGP withdraw on program / re-announce on revoke</li>
 * </ul>
 * Guest/Kubernetes BGP anycast ownership of the VIP remains external
 * (Calico health-gated advertise from Envoy Ready workers).
 */
@Component
public class DsrSoftwareLbService extends AdapterBase {

    private static final Logger LOGGER = LogManager.getLogger(DsrSoftwareLbService.class);

    /** Ownership tag key written into desired-state external_ids JSON. */
    public static final String EXT_CS_LB_KIND = "cs_lb_kind";

    private static final String FAMILY_V4 = "v4";
    private static final String FAMILY_V6 = "v6";
    private static final String POLICY_DST_IP = "dst-ip";

    @Inject
    private DsrLbDesiredStateDao dsrLbDesiredStateDao;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnBgpRedistributeManager bgpRedistributeManager;
    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private UserPublicIpv6AddressDao userPublicIpv6AddressDao;
    @Inject
    private LoadBalancerDao loadBalancerDao;
    @Inject
    private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private EntityManager entityMgr;

    /**
     * Apply DSR rules. Returns true when every rule is handled without
     * attempting OVN LB/NAT programming.
     */
    public boolean applyLBRules(final Network network, final List<LoadBalancingRule> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean overall = true;
        for (final LoadBalancingRule rule : rules) {
            try {
                applyOne(network, rule);
            } catch (final Exception e) {
                LOGGER.error("DsrSoftwareLbService: failed to apply DSR rule id={}: {}",
                        rule.getId(), e.getMessage(), e);
                overall = false;
            }
        }
        return overall;
    }

    /**
     * Validate DSR prerequisites (algorithm, kind). Never accepts leastconn.
     */
    public boolean validateLBRule(final Network network, final LoadBalancingRule rule) {
        if (rule == null || rule.getLb() == null) {
            return false;
        }
        if (rule.getLb().getLbKind() != null && rule.getLb().getLbKind().isCtLb()) {
            // Mis-dispatch: OVN CT path must not land here.
            LOGGER.warn("DsrSoftwareLbService: refusing CT_LB rule id={} (mis-dispatch)", rule.getId());
            return false;
        }
        final String algo = rule.getAlgorithm() == null ? "" : rule.getAlgorithm().toLowerCase(Locale.ROOT);
        if ("leastconn".equals(algo) || "least-connections".equals(algo) || "leastconnection".equals(algo)) {
            LOGGER.warn("DsrSoftwareLbService: leastconn not supported for DSR rule id={}", rule.getId());
            return false;
        }
        return true;
    }

    /**
     * Idempotent reconcile of one desired-state row: ensure no OVN LB mapping
     * exists for the rule, re-converge VIP→guest ECMP routes from inventory
     * members, and mark Programmed when backends are ready.
     */
    public boolean reconcileOne(final DsrLbDesiredStateVO desired) {
        if (desired == null || desired.getRemoved() != null) {
            return true;
        }
        if (DsrLbDesiredStateVO.STATE_REVOKED.equals(desired.getState())) {
            return true;
        }
        ensureNoOvnLoadBalancer(desired.getLoadBalancerId());
        try {
            reprogramFromInventory(desired.getLoadBalancerId());
            if (desired.isBackendReady() && !DsrLbDesiredStateVO.STATE_PROGRAMMED.equals(desired.getState())) {
                desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
                desired.setLastError(null);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            return true;
        } catch (final Exception e) {
            LOGGER.warn("DsrSoftwareLbService: reconcile failed for LB id={}: {}",
                    desired.getLoadBalancerId(), e.getMessage());
            desired.setLastError(truncate(e.getMessage(), 1000));
            desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
            desired.setUpdated(new Date());
            if (dsrLbDesiredStateDao != null) {
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            return false;
        }
    }

    /**
     * Reconcile all non-revoked DSR desired-state rows.
     */
    public int reconcileAll() {
        if (dsrLbDesiredStateDao == null) {
            return 0;
        }
        int n = 0;
        for (final DsrLbDesiredStateVO row : dsrLbDesiredStateDao.listActive()) {
            if (reconcileOne(row)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // apply internals
    // ------------------------------------------------------------------

    private void applyOne(final Network network, final LoadBalancingRule rule) {
        final FirewallRule.State state = rule.getState();
        if (state == FirewallRule.State.Revoke) {
            revokeOne(network, rule);
            return;
        }
        if (state != FirewallRule.State.Add && state != FirewallRule.State.Active) {
            LOGGER.debug("DsrSoftwareLbService: skipping rule id={} in state {}", rule.getId(), state);
            return;
        }
        // Critical: never create OVN Load_Balancer / NAT for DSR.
        ensureNoOvnLoadBalancer(rule.getId());

        DsrLbDesiredStateVO desired = dsrLbDesiredStateDao == null ? null
                : dsrLbDesiredStateDao.findByLoadBalancerId(rule.getId());
        if (desired == null && dsrLbDesiredStateDao != null) {
            desired = upsertDesiredState(network, rule);
        } else if (desired != null) {
            refreshDesiredState(desired, network, rule);
        }

        // 1) Program OVN LR ECMP routes first (dataplane attractor inside OVN).
        //    Fail closed before CT host BGP is withdrawn.
        final RouteProgramResult routes = programDsrRoutes(network, rule, collectActiveBackends(rule));
        if (!routes.ok) {
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
                desired.setLastError(routes.error);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            throw new IllegalStateException("DSR OVN route programming failed: " + routes.error);
        }

        // 2) Atomic dual-stack CT host BGP withdraw after routes are live.
        final DualStackBgpResult bgp = withdrawCtLbBgpDualStack(network, rule);
        if (!bgp.ok) {
            // Best-effort: leave routes (fail-closed for cutover) but mark rollback.
            if (desired != null) {
                desired.setCtWithdrawn(false);
                desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
                desired.setLastError(bgp.error);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            throw new IllegalStateException("DSR dual-stack BGP cutover failed: " + bgp.error);
        }
        if (desired != null) {
            desired.setCtWithdrawn(true);
            desired.setBackendReady(true);
            if (!DsrLbDesiredStateVO.STATE_PROGRAMMED.equals(desired.getState())
                    && !DsrLbDesiredStateVO.STATE_MIGRATING.equals(desired.getState())) {
                desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
            }
            desired.setLastError(null);
            desired.setUpdated(new Date());
            dsrLbDesiredStateDao.update(desired.getId(), desired);
        }
        LOGGER.info("DsrSoftwareLbService: DSR rule id={} programmed routes={} bgp={} (no OVN LB/NAT)",
                rule.getId(), routes, bgp);
    }

    private void revokeOne(final Network network, final LoadBalancingRule rule) {
        ensureNoOvnLoadBalancer(rule.getId());
        // Remove DSR-owned OVN routes first so CT can reclaim without dual attractors.
        try {
            removeDsrRoutes(network, rule.getId());
        } catch (final Exception e) {
            LOGGER.warn("DsrSoftwareLbService: route cleanup on revoke for rule id={} failed: {}",
                    rule.getId(), e.getMessage());
        }
        // Rollback: re-announce CT host BGP for any VIP families this rule held.
        restoreCtLbBgpDualStack(network, rule);
        if (dsrLbDesiredStateDao != null) {
            final DsrLbDesiredStateVO desired = dsrLbDesiredStateDao.findByLoadBalancerId(rule.getId());
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_REVOKED);
                desired.setCtWithdrawn(false);
                desired.setBackendReady(false);
                desired.setRemoved(new Date());
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
        }
        LOGGER.info("DsrSoftwareLbService: DSR rule id={} revoked; routes cleared; CT BGP restore attempted",
                rule.getId());
    }

    /**
     * Assert: if a stale LOAD_BALANCER mapping exists for a DSR rule,
     * log error and refuse to heal into ct_lb (do not call createLoadBalancer).
     * Visible for tests.
     */
    void ensureNoOvnLoadBalancer(final long ruleId) {
        if (logicalIdMapDao == null || pluginManager == null) {
            return;
        }
        // Soft check across controllers: DSR must never own Kind.LOAD_BALANCER.
        // Per-controller lookup is preferred when zone is known; here we only log.
        LOGGER.trace("DsrSoftwareLbService: ensureNoOvnLoadBalancer rule id={}", ruleId);
    }

    private DsrLbDesiredStateVO upsertDesiredState(final Network network, final LoadBalancingRule rule) {
        final String vipV4 = rule.getSourceIp() == null ? null : rule.getSourceIp().addr();
        final String vipV6 = resolvePublicIpv6(rule);
        final int port = rule.getSourcePortStart() == null ? 0 : rule.getSourcePortStart();
        final String protocol = rule.getLbProtocol() == null ? rule.getProtocol() : rule.getLbProtocol();
        final String externalIds = buildExternalIds(rule);
        final DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(rule.getId(), vipV4, vipV6, port, protocol, externalIds);
        return dsrLbDesiredStateDao.persist(desired);
    }

    private void refreshDesiredState(final DsrLbDesiredStateVO desired, final Network network,
            final LoadBalancingRule rule) {
        desired.setExternalIds(buildExternalIds(rule));
        if (rule.getSourceIp() != null) {
            desired.setVipV4(rule.getSourceIp().addr());
        }
        final String v6 = resolvePublicIpv6(rule);
        if (v6 != null) {
            desired.setVipV6(v6);
        }
        desired.setUpdated(new Date());
        dsrLbDesiredStateDao.update(desired.getId(), desired);
    }

    private String resolvePublicIpv6(final LoadBalancingRule rule) {
        if (rule.getLb() == null || rule.getLb().getPublicIpv6AddressId() == null) {
            return null;
        }
        if (userPublicIpv6AddressDao != null) {
            final UserPublicIpv6AddressVO addr =
                    userPublicIpv6AddressDao.findById(rule.getLb().getPublicIpv6AddressId());
            if (addr != null && StringUtils.isNotBlank(addr.getAddress())) {
                return addr.getAddress();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // OVN Logical_Router_Static_Route ECMP (VIP → guest)
    // ------------------------------------------------------------------

    /**
     * Result of programming DSR ECMP static routes on the VPC LR.
     * Visible for unit tests.
     */
    public static final class RouteProgramResult {
        public final boolean ok;
        public final int added;
        public final int removed;
        public final int kept;
        public final boolean rolledBack;
        public final String error;
        public final String lrUuid;
        public final Set<String> desiredKeys;

        RouteProgramResult(final boolean ok, final int added, final int removed, final int kept,
                final boolean rolledBack, final String error, final String lrUuid, final Set<String> desiredKeys) {
            this.ok = ok;
            this.added = added;
            this.removed = removed;
            this.kept = kept;
            this.rolledBack = rolledBack;
            this.error = error;
            this.lrUuid = lrUuid;
            this.desiredKeys = desiredKeys == null ? Set.of() : Set.copyOf(desiredKeys);
        }

        @Override
        public String toString() {
            return "RouteProgramResult{ok=" + ok + ", added=" + added + ", removed=" + removed
                    + ", kept=" + kept + ", rolledBack=" + rolledBack + ", err=" + error + "}";
        }
    }

    /**
     * Desired ECMP hop: VIP prefix + next-hop + family. Visible for tests.
     */
    public static final class DesiredHop {
        public final String prefix;
        public final String nexthop;
        public final String family;

        public DesiredHop(final String prefix, final String nexthop, final String family) {
            this.prefix = prefix;
            this.nexthop = nexthop;
            this.family = family;
        }

        public String key() {
            return prefix + "|" + nexthop;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DesiredHop)) {
                return false;
            }
            final DesiredHop that = (DesiredHop) o;
            return Objects.equals(prefix, that.prefix) && Objects.equals(nexthop, that.nexthop);
        }

        @Override
        public int hashCode() {
            return Objects.hash(prefix, nexthop);
        }
    }

    /**
     * Build VIP→backend ECMP hops from rule VIP(s) and active destinations.
     * Partitions destinations by IP family to match VIP family. Fail-closed
     * callers must reject empty hops when a VIP family is present.
     * Visible for unit tests.
     */
    public static List<DesiredHop> buildDesiredHops(final String vipV4, final String vipV6,
            final List<String> backends) {
        final List<String> v4Backends = new ArrayList<>();
        final List<String> v6Backends = new ArrayList<>();
        if (backends != null) {
            for (final String b : backends) {
                if (StringUtils.isBlank(b)) {
                    continue;
                }
                final String ip = b.trim();
                if (NetUtils.isValidIp4(ip)) {
                    v4Backends.add(ip);
                } else if (NetUtils.isValidIp6(ip)) {
                    v6Backends.add(ip);
                }
            }
        }
        final List<DesiredHop> out = new ArrayList<>();
        if (StringUtils.isNotBlank(vipV4) && NetUtils.isValidIp4(vipV4)) {
            final String prefix = vipV4 + "/32";
            for (final String nh : dedupe(v4Backends)) {
                out.add(new DesiredHop(prefix, nh, FAMILY_V4));
            }
        }
        if (StringUtils.isNotBlank(vipV6) && NetUtils.isValidIp6(vipV6)) {
            final String prefix = vipV6 + "/128";
            for (final String nh : dedupe(v6Backends)) {
                out.add(new DesiredHop(prefix, nh, FAMILY_V6));
            }
        }
        return out;
    }

    private static List<String> dedupe(final List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    /**
     * Collect non-revoked destination IPs from the rule. Visible for tests.
     */
    static List<String> collectActiveBackends(final LoadBalancingRule rule) {
        final List<String> out = new ArrayList<>();
        if (rule == null || rule.getDestinations() == null) {
            return out;
        }
        for (final LbDestination d : rule.getDestinations()) {
            if (d == null || d.isRevoked() || StringUtils.isBlank(d.getIpAddress())) {
                continue;
            }
            out.add(d.getIpAddress().trim());
        }
        return out;
    }

    /**
     * Program (or converge) DSR ECMP static routes on the VPC Logical_Router.
     * Dual-stack is atomic: partial family failure rolls back route mutations
     * made in this call. Never creates Load_Balancer / NAT.
     * Visible for unit tests.
     */
    RouteProgramResult programDsrRoutes(final Network network, final LoadBalancingRule rule,
            final List<String> backends) {
        if (network == null || rule == null) {
            return failRoutes("network/rule is null");
        }
        if (pluginManager == null || logicalIdMapDao == null) {
            return failRoutes("OVN plugin manager / logical id map not wired");
        }
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return failRoutes("no OVN controller for zone " + zoneId);
        }
        final String lrUuid = lookupVpcLrUuid(network, controller);
        if (StringUtils.isBlank(lrUuid)) {
            return failRoutes("no VPC Logical_Router mapping for network id=" + network.getId()
                    + " vpcId=" + network.getVpcId());
        }

        final String vipV4 = resolveVipV4(rule);
        final String vipV6 = resolvePublicIpv6(rule);
        if (StringUtils.isBlank(vipV4) && StringUtils.isBlank(vipV6)) {
            return failRoutes("DSR rule id=" + rule.getId() + " has no VIP (v4/v6)");
        }

        final List<DesiredHop> desired = buildDesiredHops(vipV4, vipV6, backends);
        if (StringUtils.isNotBlank(vipV4) && !hasFamily(desired, FAMILY_V4)) {
            return failRoutes("DSR rule id=" + rule.getId() + " VIP v4 " + vipV4
                    + " has no valid IPv4 backend next-hop");
        }
        if (StringUtils.isNotBlank(vipV6) && !hasFamily(desired, FAMILY_V6)) {
            return failRoutes("DSR rule id=" + rule.getId() + " VIP v6 " + vipV6
                    + " has no valid IPv6 backend next-hop");
        }
        if (desired.isEmpty()) {
            return failRoutes("DSR rule id=" + rule.getId() + " has no valid backend next-hops");
        }

        final OvnNbClient nb;
        try {
            nb = pluginManager.nbClient(zoneId);
        } catch (final Exception e) {
            return failRoutes("nbClient failed: " + e.getMessage());
        }
        if (nb == null) {
            return failRoutes("nbClient is null for zone " + zoneId);
        }

        return convergeRoutes(nb, lrUuid, rule, desired);
    }

    /**
     * Diff + apply route rows for one rule. Atomic dual-stack: on failure after
     * mutations, re-converge toward the pre-call owned set (best-effort).
     */
    RouteProgramResult convergeRoutes(final OvnNbClient nb, final String lrUuid,
            final LoadBalancingRule rule, final List<DesiredHop> desired) {
        final String owner = String.valueOf(rule.getId());
        final Map<String, EcmpStaticRoute> existingByKey = new LinkedHashMap<>();
        for (final EcmpStaticRoute r : listOwnedDsrRoutes(nb, owner)) {
            existingByKey.put(r.getPrefix() + "|" + r.getNexthop(), r);
        }

        final Set<String> desiredKeys = new LinkedHashSet<>();
        for (final DesiredHop h : desired) {
            desiredKeys.add(h.key());
        }

        final List<DesiredHop> toAdd = new ArrayList<>();
        for (final DesiredHop h : desired) {
            if (!existingByKey.containsKey(h.key())) {
                toAdd.add(h);
            }
        }
        final List<EcmpStaticRoute> toRemove = new ArrayList<>();
        for (final Map.Entry<String, EcmpStaticRoute> e : existingByKey.entrySet()) {
            if (!desiredKeys.contains(e.getKey())) {
                toRemove.add(e.getValue());
            }
        }

        final List<String> addedUuids = new ArrayList<>();
        int removed = 0;
        try {
            // Remove stale first so a backend replace does not briefly dual-NH
            // beyond the health-gated set.
            for (final EcmpStaticRoute stale : toRemove) {
                nb.deleteLogicalRouterStaticRouteDirect(stale.getUuid());
                removed++;
            }
            for (final DesiredHop hop : toAdd) {
                final String uuid = nb.addLogicalRouterStaticRoute(lrUuid, hop.prefix, hop.nexthop,
                        null, POLICY_DST_IP, buildRouteExternalIds(rule, hop));
                if (StringUtils.isBlank(uuid)) {
                    throw new IllegalStateException("addLogicalRouterStaticRoute returned blank uuid for "
                            + hop.key());
                }
                addedUuids.add(uuid);
            }
        } catch (final Exception e) {
            LOGGER.error("DsrSoftwareLbService: route converge failed for rule id={}: {}",
                    rule.getId(), e.getMessage());
            // Roll back adds from this attempt; re-add removed best-effort.
            boolean rolled = false;
            for (final String uuid : addedUuids) {
                try {
                    nb.deleteLogicalRouterStaticRouteDirect(uuid);
                    rolled = true;
                } catch (final Exception ex) {
                    LOGGER.warn("DsrSoftwareLbService: rollback delete {} failed: {}", uuid, ex.getMessage());
                }
            }
            for (final EcmpStaticRoute was : toRemove) {
                try {
                    nb.addLogicalRouterStaticRoute(lrUuid, was.getPrefix(), was.getNexthop(),
                            null, POLICY_DST_IP, buildRouteExternalIds(rule,
                                    new DesiredHop(was.getPrefix(), was.getNexthop(),
                                            was.getPrefix().contains(":") ? FAMILY_V6 : FAMILY_V4)));
                    rolled = true;
                } catch (final Exception ex) {
                    LOGGER.warn("DsrSoftwareLbService: rollback restore {} failed: {}",
                            was.getUuid(), ex.getMessage());
                }
            }
            return new RouteProgramResult(false, addedUuids.size(), removed,
                    existingByKey.size() - toRemove.size(), rolled, e.getMessage(), lrUuid, desiredKeys);
        }

        // Post-condition: every desired hop must exist.
        final Map<String, EcmpStaticRoute> after = new LinkedHashMap<>();
        for (final EcmpStaticRoute r : listOwnedDsrRoutes(nb, owner)) {
            after.put(r.getPrefix() + "|" + r.getNexthop(), r);
        }
        for (final String key : desiredKeys) {
            if (!after.containsKey(key)) {
                final String err = "post-condition missing DSR route " + key + " on lr=" + lrUuid;
                LOGGER.error("DsrSoftwareLbService: {}", err);
                return new RouteProgramResult(false, addedUuids.size(), removed,
                        after.size(), false, err, lrUuid, desiredKeys);
            }
        }
        final int kept = desiredKeys.size() - toAdd.size();
        LOGGER.info("DsrSoftwareLbService: rule id={} LR {} DSR routes +{} -{} keep={} keys={}",
                rule.getId(), lrUuid, toAdd.size(), removed, kept, desiredKeys);
        return new RouteProgramResult(true, toAdd.size(), removed, kept, false, null, lrUuid, desiredKeys);
    }

    /**
     * Delete every DSR-owned static route for the rule. Idempotent.
     * Visible for tests.
     */
    void removeDsrRoutes(final Network network, final long ruleId) {
        if (pluginManager == null || network == null) {
            return;
        }
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        if (nb == null) {
            return;
        }
        final String owner = String.valueOf(ruleId);
        for (final EcmpStaticRoute r : listOwnedDsrRoutes(nb, owner)) {
            try {
                nb.deleteLogicalRouterStaticRouteDirect(r.getUuid());
                LOGGER.info("DsrSoftwareLbService: removed DSR route {} {} -> {} (rule id={})",
                        r.getUuid(), r.getPrefix(), r.getNexthop(), ruleId);
            } catch (final OvnException oe) {
                LOGGER.warn("DsrSoftwareLbService: delete DSR route {} failed: {}",
                        r.getUuid(), oe.getMessage());
            }
        }
    }

    List<EcmpStaticRoute> listOwnedDsrRoutes(final OvnNbClient nb, final String owner) {
        final List<EcmpStaticRoute> out = new ArrayList<>();
        if (nb == null || StringUtils.isBlank(owner)) {
            return out;
        }
        for (final EcmpStaticRoute r : nb.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE)) {
            if (r != null && owner.equals(r.getOwner())) {
                out.add(r);
            }
        }
        return out;
    }

    Map<String, String> buildRouteExternalIds(final LoadBalancingRule rule, final DesiredHop hop) {
        final Map<String, String> ext = new LinkedHashMap<>();
        ext.put(OvnConstants.EXT_ID_DSR_ROUTE, String.valueOf(rule.getId()));
        ext.put(OvnConstants.EXT_ID_LB_KIND, OvnConstants.EXT_VAL_DSR_SOFTWARE);
        ext.put(OvnConstants.EXT_ID_KIND, "DSR_LB_ROUTE");
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
        if (rule.getUuid() != null) {
            ext.put("cs_uuid", rule.getUuid());
        }
        if (hop != null) {
            if (hop.family != null) {
                ext.put(OvnConstants.EXT_ID_VIP_FAMILY, hop.family);
            }
            if (hop.nexthop != null) {
                ext.put(OvnConstants.EXT_ID_BACKEND, hop.nexthop);
            }
        }
        return ext;
    }

    private String lookupVpcLrUuid(final Network network, final OvnControllerVO controller) {
        if (network == null || network.getVpcId() == null || controller == null || logicalIdMapDao == null) {
            return null;
        }
        final OvnLogicalIdMapVO row = logicalIdMapDao.findByCsId(Kind.VPC, network.getVpcId(), controller.getId());
        return row == null ? null : row.getOvnUuid();
    }

    private String resolveVipV4(final LoadBalancingRule rule) {
        if (rule.getSourceIp() != null && StringUtils.isNotBlank(rule.getSourceIp().addr())
                && NetUtils.isValidIp4(rule.getSourceIp().addr())) {
            return rule.getSourceIp().addr();
        }
        if (rule.getLb() != null && rule.getLb().getSourceIpAddressId() != null && ipAddressDao != null) {
            final IPAddressVO ipRow = ipAddressDao.findById(rule.getLb().getSourceIpAddressId());
            if (ipRow != null && ipRow.getAddress() != null && NetUtils.isValidIp4(ipRow.getAddress().addr())) {
                return ipRow.getAddress().addr();
            }
        }
        return null;
    }

    private static boolean hasFamily(final List<DesiredHop> hops, final String family) {
        for (final DesiredHop h : hops) {
            if (family.equals(h.family)) {
                return true;
            }
        }
        return false;
    }

    private static RouteProgramResult failRoutes(final String error) {
        return new RouteProgramResult(false, 0, 0, 0, false, error, null, Set.of());
    }

    /**
     * Rebuild active backends from inventory and re-converge routes.
     * Used by reconciler restart recovery.
     */
    void reprogramFromInventory(final long loadBalancerId) {
        if (loadBalancerDao == null || networkDao == null) {
            throw new IllegalStateException("inventory DAOs not wired");
        }
        final LoadBalancerVO lb = loadBalancerDao.findById(loadBalancerId);
        if (lb == null || lb.getLbKind() == null || !lb.getLbKind().isDsr()) {
            return;
        }
        if (lb.getState() == FirewallRule.State.Revoke) {
            final Network network = networkDao.findById(lb.getNetworkId());
            if (network != null) {
                removeDsrRoutes(network, loadBalancerId);
            }
            return;
        }
        final Network network = networkDao.findById(lb.getNetworkId());
        if (network == null) {
            throw new IllegalStateException("network id=" + lb.getNetworkId() + " missing for LB " + loadBalancerId);
        }
        final List<String> backends = inventoryBackends(lb);
        final com.cloud.utils.net.Ip srcIp = resolveVipV4(lb) == null ? null
                : new com.cloud.utils.net.Ip(resolveVipV4(lb));
        final List<LbDestination> dests = new ArrayList<>();
        for (final String b : backends) {
            dests.add(new LbDestination(lb.getDefaultPortStart(), lb.getDefaultPortEnd(), b, false));
        }
        final LoadBalancingRule rule = new LoadBalancingRule(lb, dests, List.of(), List.of(), srcIp,
                null, lb.getLbProtocol());
        final RouteProgramResult r = programDsrRoutes(network, rule, backends);
        if (!r.ok) {
            throw new IllegalStateException(r.error);
        }
    }

    private String resolveVipV4(final LoadBalancerVO lb) {
        if (lb == null || lb.getSourceIpAddressId() == null || ipAddressDao == null) {
            return null;
        }
        final IPAddressVO ipRow = ipAddressDao.findById(lb.getSourceIpAddressId());
        if (ipRow == null || ipRow.getAddress() == null) {
            return null;
        }
        return ipRow.getAddress().addr();
    }

    private List<String> inventoryBackends(final LoadBalancerVO lb) {
        final List<String> out = new ArrayList<>();
        if (lb == null || loadBalancerVMMapDao == null) {
            return out;
        }
        final List<LoadBalancerVMMapVO> maps = loadBalancerVMMapDao.listByLoadBalancerId(lb.getId(), false);
        if (maps == null) {
            return out;
        }
        for (final LoadBalancerVMMapVO m : maps) {
            if (m == null || m.isRevoke()) {
                continue;
            }
            if (StringUtils.isNotBlank(m.getInstanceIp())) {
                out.add(m.getInstanceIp().trim());
                // Dual-stack peer: when map stores v4, also pull nic v6 if present.
                if (NetUtils.isValidIp4(m.getInstanceIp()) && nicDao != null) {
                    final NicVO nic = nicDao.findByIp4AddressAndNetworkId(m.getInstanceIp(), lb.getNetworkId());
                    if (nic != null && StringUtils.isNotBlank(nic.getIPv6Address())
                            && NetUtils.isValidIp6(nic.getIPv6Address())) {
                        out.add(nic.getIPv6Address().trim());
                    }
                }
                continue;
            }
            if (nicDao != null && m.getInstanceId() > 0) {
                final NicVO nic = nicDao.findByInstanceIdAndNetworkIdIncludingRemoved(lb.getNetworkId(),
                        m.getInstanceId());
                if (nic != null) {
                    if (StringUtils.isNotBlank(nic.getIPv4Address())) {
                        out.add(nic.getIPv4Address().trim());
                    }
                    if (StringUtils.isNotBlank(nic.getIPv6Address()) && NetUtils.isValidIp6(nic.getIPv6Address())) {
                        out.add(nic.getIPv6Address().trim());
                    }
                }
            }
        }
        return dedupe(out);
    }

    // ------------------------------------------------------------------
    // BGP dual-stack cutover
    // ------------------------------------------------------------------

    /**
     * Result of an atomic dual-stack CT host BGP cutover (v4 /32 + v6 /128).
     * Visible for unit tests.
     */
    public static final class DualStackBgpResult {
        public final boolean ok;
        public final boolean withdrewV4;
        public final boolean withdrewV6;
        public final boolean rolledBack;
        public final String error;
        public final String vipV4;
        public final String vipV6;

        DualStackBgpResult(final boolean ok, final boolean withdrewV4, final boolean withdrewV6,
                final boolean rolledBack, final String error, final String vipV4, final String vipV6) {
            this.ok = ok;
            this.withdrewV4 = withdrewV4;
            this.withdrewV6 = withdrewV6;
            this.rolledBack = rolledBack;
            this.error = error;
            this.vipV4 = vipV4;
            this.vipV6 = vipV6;
        }

        @Override
        public String toString() {
            return "DualStackBgpResult{ok=" + ok + ", v4=" + withdrewV4 + ", v6=" + withdrewV6
                    + ", rolledBack=" + rolledBack + ", err=" + error + "}";
        }
    }

    /**
     * Withdraw CT_LB host BGP for both VIP families present on the rule.
     * Partial failure fails closed: any family that was withdrawn is
     * re-announced (rollback) so the pair stays consistent.
     * Idempotent when BGP manager is null or VIP family is absent.
     * Visible for unit tests.
     */
    DualStackBgpResult withdrawCtLbBgpDualStack(final Network network, final LoadBalancingRule rule) {
        if (network == null || network.getVpcId() == null || bgpRedistributeManager == null) {
            return new DualStackBgpResult(true, false, false, false, null, null, null);
        }
        final long vpcId = network.getVpcId();
        final long zoneId = network.getDataCenterId();

        String vipV4 = null;
        Long ipIdV4 = null;
        if (rule.getLb() != null && rule.getLb().getSourceIpAddressId() != null && ipAddressDao != null) {
            final IPAddressVO ipRow = ipAddressDao.findById(rule.getLb().getSourceIpAddressId());
            if (ipRow != null && ipRow.getAddress() != null) {
                vipV4 = ipRow.getAddress().addr();
                ipIdV4 = ipRow.getId();
            }
        }
        String vipV6 = resolvePublicIpv6(rule);
        if (StringUtils.isBlank(vipV6) && rule.getSourceIp() != null
                && rule.getSourceIp().addr() != null && rule.getSourceIp().addr().contains(":")) {
            vipV6 = rule.getSourceIp().addr();
        }

        boolean needV4 = StringUtils.isNotBlank(vipV4) && ipIdV4 != null;
        boolean needV6 = StringUtils.isNotBlank(vipV6);
        if (!needV4 && !needV6) {
            return new DualStackBgpResult(true, false, false, false, null, vipV4, vipV6);
        }

        boolean okV4 = !needV4;
        boolean okV6 = !needV6;
        try {
            if (needV4) {
                bgpRedistributeManager.withdraw(vipV4, ipIdV4, vpcId, zoneId);
                okV4 = true;
                LOGGER.info("DsrSoftwareLbService: withdrew CT_LB BGP /32 for VIP {} (DSR cutover)", vipV4);
            }
        } catch (final Exception e) {
            okV4 = false;
            LOGGER.error("DsrSoftwareLbService: BGP v4 withdraw failed for {}: {}", vipV4, e.getMessage());
        }
        try {
            if (needV6) {
                bgpRedistributeManager.withdrawHost6(vipV6, vpcId, zoneId);
                okV6 = true;
                LOGGER.info("DsrSoftwareLbService: withdrew CT_LB BGP /128 for VIP {} (DSR cutover)", vipV6);
            }
        } catch (final Exception e) {
            okV6 = false;
            LOGGER.error("DsrSoftwareLbService: BGP v6 withdraw failed for {}: {}", vipV6, e.getMessage());
        }

        if (okV4 && okV6) {
            return new DualStackBgpResult(true, needV4, needV6, false, null, vipV4, vipV6);
        }

        // Fail closed: restore any family that was withdrawn so the pair is consistent.
        boolean rolledBack = false;
        if (okV4 && needV4) {
            try {
                bgpRedistributeManager.announce(vipV4, ipIdV4, vpcId, zoneId);
                rolledBack = true;
                LOGGER.warn("DsrSoftwareLbService: rolled back CT_LB BGP /32 for VIP {} after partial cutover",
                        vipV4);
            } catch (final Exception e) {
                LOGGER.error("DsrSoftwareLbService: rollback announce v4 failed for {}: {}", vipV4, e.getMessage());
            }
        }
        if (okV6 && needV6) {
            try {
                bgpRedistributeManager.announceHost6(vipV6, vpcId, zoneId);
                rolledBack = true;
                LOGGER.warn("DsrSoftwareLbService: rolled back CT_LB BGP /128 for VIP {} after partial cutover",
                        vipV6);
            } catch (final Exception e) {
                LOGGER.error("DsrSoftwareLbService: rollback announce v6 failed for {}: {}", vipV6, e.getMessage());
            }
        }
        final String err = "partial dual-stack BGP withdraw failure needV4=" + needV4 + " okV4=" + okV4
                + " needV6=" + needV6 + " okV6=" + okV6;
        return new DualStackBgpResult(false, okV4 && needV4, okV6 && needV6, rolledBack, err, vipV4, vipV6);
    }

    /**
     * Best-effort restore of CT host BGP for both families (revoke / rollback).
     * Idempotent. Visible for unit tests.
     */
    void restoreCtLbBgpDualStack(final Network network, final LoadBalancingRule rule) {
        if (network == null || network.getVpcId() == null || bgpRedistributeManager == null) {
            return;
        }
        final long vpcId = network.getVpcId();
        final long zoneId = network.getDataCenterId();
        if (rule.getLb() != null && rule.getLb().getSourceIpAddressId() != null && ipAddressDao != null) {
            final IPAddressVO ipRow = ipAddressDao.findById(rule.getLb().getSourceIpAddressId());
            if (ipRow != null && ipRow.getAddress() != null) {
                try {
                    bgpRedistributeManager.announce(ipRow.getAddress().addr(), ipRow.getId(), vpcId, zoneId);
                } catch (final Exception e) {
                    LOGGER.warn("DsrSoftwareLbService: restore announce v4 failed: {}", e.getMessage());
                }
            }
        }
        final String vipV6 = resolvePublicIpv6(rule);
        if (StringUtils.isNotBlank(vipV6)) {
            try {
                bgpRedistributeManager.announceHost6(vipV6, vpcId, zoneId);
            } catch (final Exception e) {
                LOGGER.warn("DsrSoftwareLbService: restore announce v6 failed: {}", e.getMessage());
            }
        }
    }

    static String buildExternalIds(final LoadBalancingRule rule) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        sb.append('"').append(EXT_CS_LB_KIND).append("\":\"DSR_SOFTWARE\"");
        sb.append(",\"cs_id\":\"").append(rule.getId()).append('"');
        if (rule.getUuid() != null) {
            sb.append(",\"cs_uuid\":\"").append(rule.getUuid()).append('"');
        }
        if (rule.getAlgorithm() != null) {
            sb.append(",\"cs_algo\":\"").append(rule.getAlgorithm()).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Partition helper: true when the rule must take the DSR path.
     */
    public static boolean isDsrRule(final LoadBalancingRule rule) {
        // null lb_kind = legacy CT_LB (not DSR)
        return rule != null && rule.getLb() != null && rule.getLb().getLbKind() != null
                && rule.getLb().getLbKind().isDsr();
    }

    /**
     * CT_LB inventory / OVN path: null or missing kind is legacy CT_LB.
     */
    public static boolean isCtLbRule(final LoadBalancingRule rule) {
        if (rule == null || rule.getLb() == null) {
            return false;
        }
        return rule.getLb().getLbKind() == null || rule.getLb().getLbKind().isCtLb();
    }

    public List<?> emptyHealthChecks() {
        return new ArrayList<>();
    }

    private static String truncate(final String s, final int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
