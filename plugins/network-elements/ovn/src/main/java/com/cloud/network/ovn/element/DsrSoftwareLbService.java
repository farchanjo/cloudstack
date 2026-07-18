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
import com.cloud.network.ovn.client.OvnNbClient.OwnedLoadBalancer;
import com.cloud.network.ovn.client.OvnNbClient.OwnedNat;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Programmer for {@link LbKind#DSR_SOFTWARE} load balancer rules.
 *
 * <p><b>Never</b> creates OVN {@code Load_Balancer} rows, hairpin SNAT,
 * force SNAT, or NAT entries for the DSR VIP. Dataplane ownership is:
 * <ul>
 *   <li>{@code dsr_lb_desired_state} inventory row (per LB rule)</li>
 *   <li>OVN {@code Logical_Router_Static_Route} ECMP on the VPC LR,
 *       VIP-scoped ({@code cs-dsr-route=<vpcId>|<prefix>}) so ports 80+443
 *       on the same VIP share one ECMP set (union of eligible members)</li>
 *   <li>CT_LB host BGP withdraw/restore with sibling refcount per VIP family</li>
 * </ul>
 *
 * <p>Operational order (cutover): delete CT rules → prove NB clean → enable
 * gate → create DSR → prove {@code cs-dsr-route} → enable fabric enforcer.
 * Rollback: disable fabric → revoke last DSR sibling (routes cleared, CT BGP
 * restore) → recreate CT rules/members via API (this service never recreates
 * OVN Load_Balancer). Envoy Ready is an external preflight; CloudStack only
 * asserts inventory eligibility (Running VM + NIC present).
 */
@Component
public class DsrSoftwareLbService extends AdapterBase {

    private static final Logger LOGGER = LogManager.getLogger(DsrSoftwareLbService.class);

    public static final String EXT_CS_LB_KIND = "cs_lb_kind";

    /** external_ids key listing contributing DSR rule ids (comma-separated). */
    public static final String EXT_ID_DSR_RULES = "cs_dsr_rules";

    private static final String FAMILY_V4 = "v4";
    private static final String FAMILY_V6 = "v6";
    private static final String POLICY_DST_IP = "dst-ip";

    /** DB-backed multi-MS lock timeout for VPC+VIP DSR programmer. */
    public static final int DSR_VIP_LOCK_TIMEOUT_SECS = 60;

    /**
     * When false, {@link #withVipLock} runs the body without acquiring a DB
     * GlobalLock (unit tests without a data source). Production leaves true.
     */
    volatile boolean dsrDistributedLockEnabled = true;

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
    private VMInstanceDao vmInstanceDao;
    @Inject
    private EntityManager entityMgr;

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

    public boolean validateLBRule(final Network network, final LoadBalancingRule rule) {
        if (rule == null || rule.getLb() == null) {
            return false;
        }
        if (rule.getLb().getLbKind() != null && rule.getLb().getLbKind().isCtLb()) {
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

    public boolean reconcileOne(final DsrLbDesiredStateVO desired) {
        if (desired == null || desired.getRemoved() != null) {
            return true;
        }
        if (DsrLbDesiredStateVO.STATE_REVOKED.equals(desired.getState())) {
            return true;
        }
        try {
            reprogramFromInventory(desired.getLoadBalancerId());
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
    // apply / revoke
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
        if (network == null || network.getVpcId() == null) {
            throw new IllegalStateException("DSR requires a VPC network");
        }

        final String vipV4 = canonicalizeVip(resolveVipV4(rule));
        final String vipV6 = canonicalizeVip(resolvePublicIpv6(rule));
        withVipLock(network.getVpcId(), vipV4, vipV6, () -> {
            applyOneLocked(network, rule, vipV4, vipV6);
            return null;
        });
    }

    private void applyOneLocked(final Network network, final LoadBalancingRule rule,
            final String vipV4, final String vipV6) {
        DsrLbDesiredStateVO desired = dsrLbDesiredStateDao == null ? null
                : dsrLbDesiredStateDao.findByLoadBalancerId(rule.getId());
        if (desired == null && dsrLbDesiredStateDao != null) {
            desired = upsertDesiredState(network, rule, vipV4, vipV6);
        } else if (desired != null) {
            refreshDesiredState(desired, rule, vipV4, vipV6);
        }

        // B4: refuse PROGRAMMED while residual CT LB/NAT owns VIP.
        final CtResidual residual = findResidualCtOnVip(network, rule, vipV4, vipV6);
        if (residual != null) {
            final String err = "residual CT LB/NAT/pub6 on VIP:port " + residual;
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
                desired.setLastError(truncate(err, 1000));
                desired.setBackendReady(false);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            throw new IllegalStateException("DSR precondition failed: " + err
                    + " — delete CT rules via API first; this path never silently deletes live CT objects");
        }

        final MemberSet members = collectEligibleUnionMembers(network, vipV4, vipV6, rule);
        if (members.backends.isEmpty()) {
            final String err = "no eligible backends (Running VM + NIC) for VIP v4=" + vipV4 + " v6=" + vipV6;
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
                desired.setLastError(err);
                desired.setBackendReady(false);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            throw new IllegalStateException(err);
        }

        final RouteProgramResult routes = programVipScopedRoutes(network, rule, vipV4, vipV6, members);
        if (!routes.ok) {
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
                desired.setLastError(routes.error);
                desired.setBackendReady(false);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            throw new IllegalStateException("DSR OVN route programming failed: " + routes.error);
        }

        // BGP withdraw: only when NO peer has proven ctWithdrawn=true in desired-state.
        // Never infer from sibling Add count (80+443 both Add would false-skip).
        final boolean peerWithdrew = peerAlreadyWithdrewCtBgp(network, vipV4, vipV6, rule.getId());
        final DualStackBgpResult bgp;
        if (peerWithdrew) {
            bgp = new DualStackBgpResult(true, false, false, false, null, vipV4, vipV6);
            LOGGER.info("DsrSoftwareLbService: peer already withdrew CT BGP for VIP; inherit ctWithdrawn rule id={}",
                    rule.getId());
        } else {
            bgp = withdrawCtLbBgpDualStack(network, rule, vipV4, vipV6);
        }
        if (!bgp.ok) {
            // H3: compensate — tear down DSR routes when no peer holds the VIP plane.
            if (!peerWithdrew) {
                try {
                    removeVipScopedRoutes(network, vipV4, vipV6);
                } catch (final Exception e) {
                    LOGGER.error("DsrSoftwareLbService: route compensation after BGP fail: {}", e.getMessage());
                }
            }
            if (desired != null) {
                desired.setCtWithdrawn(false);
                desired.setBackendReady(false);
                desired.setState(DsrLbDesiredStateVO.STATE_ROLLBACK);
                desired.setLastError(bgp.error);
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
            throw new IllegalStateException("DSR dual-stack BGP cutover failed: " + bgp.error);
        }

        if (desired != null) {
            // ctWithdrawn=true only after proven withdraw or proven peer inheritance.
            // Never set true merely because another rule is in Add/Active.
            desired.setCtWithdrawn(peerWithdrew || bgp.withdrewV4 || bgp.withdrewV6);
            desired.setBackendReady(true);
            desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
            desired.setLastError(null);
            desired.setUpdated(new Date());
            dsrLbDesiredStateDao.update(desired.getId(), desired);
        }
        LOGGER.info("DsrSoftwareLbService: DSR rule id={} PROGRAMMED routes={} bgp={} peerWithdrew={} "
                        + "(inventory-eligible backends; Envoy health is external preflight)",
                rule.getId(), routes, bgp, peerWithdrew);
    }

    private void revokeOne(final Network network, final LoadBalancingRule rule) {
        if (network == null || network.getVpcId() == null) {
            return;
        }
        final String vipV4 = canonicalizeVip(resolveVipV4(rule));
        final String vipV6 = canonicalizeVip(resolvePublicIpv6(rule));
        withVipLock(network.getVpcId(), vipV4, vipV6, () -> {
            revokeOneLocked(network, rule, vipV4, vipV6);
            return null;
        });
    }

    private void revokeOneLocked(final Network network, final LoadBalancingRule rule,
            final String vipV4, final String vipV6) {
        // Remaining siblings EXCLUDING this rule.
        final int remaining = countActiveDsrSiblings(network, vipV4, vipV6, rule.getId());
        // Restore CT BGP only when last sibling AND someone had withdrawn (self or peer).
        final boolean selfWithdrew = wasCtWithdrawn(rule.getId());
        final boolean peerWithdrew = peerAlreadyWithdrewCtBgp(network, vipV4, vipV6, rule.getId());
        if (remaining == 0) {
            // Last sibling: recompute union (empty) and remove VIP-scoped ECMP always.
            try {
                removeVipScopedRoutes(network, vipV4, vipV6);
            } catch (final Exception e) {
                LOGGER.warn("DsrSoftwareLbService: route cleanup on last-sibling revoke failed: {}",
                        e.getMessage());
            }
            if (selfWithdrew || peerWithdrew) {
                restoreCtLbBgpDualStack(network, rule, vipV4, vipV6);
            }
            LOGGER.info("DsrSoftwareLbService: last DSR sibling rule id={} revoked; "
                            + "VIP routes cleared; CT BGP restore={} (selfWithdrew={} peerWithdrew={}). "
                            + "CT Load_Balancer must be recreated via API if rollback requires it.",
                    rule.getId(), selfWithdrew || peerWithdrew, selfWithdrew, peerWithdrew);
        } else {
            // Sibling remains: re-converge ECMP from remaining inventory members.
            try {
                final MemberSet members = collectEligibleUnionMembers(network, vipV4, vipV6, null);
                if (members.backends.isEmpty()) {
                    // No eligible members left among siblings — clear VIP routes but keep BGP withdrawn.
                    removeVipScopedRoutes(network, vipV4, vipV6);
                } else {
                    programVipScopedRoutes(network, rule, vipV4, vipV6, members);
                }
            } catch (final Exception e) {
                LOGGER.warn("DsrSoftwareLbService: sibling re-converge after revoke id={} failed: {}",
                        rule.getId(), e.getMessage());
            }
            LOGGER.info("DsrSoftwareLbService: DSR rule id={} revoked; {} sibling(s) remain — "
                            + "VIP ECMP retained/reconverged; CT BGP not restored",
                    rule.getId(), remaining);
        }
        if (dsrLbDesiredStateDao != null) {
            final DsrLbDesiredStateVO desired = dsrLbDesiredStateDao.findByLoadBalancerId(rule.getId());
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_REVOKED);
                desired.setCtWithdrawn(remaining > 0);
                desired.setBackendReady(false);
                desired.setRemoved(new Date());
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
        }
    }

    // ------------------------------------------------------------------
    // VIP-scoped ownership
    // ------------------------------------------------------------------

    /**
     * Ownership marker value: {@code <vpcId>|<prefix>} (e.g. {@code 924|217.179.89.38/32}).
     * IPv6 prefixes are canonicalized (RFC 5952) so expanded/compressed forms share one owner.
     * Visible for tests.
     */
    public static String vipOwnerKey(final long vpcId, final String prefix) {
        return vpcId + "|" + canonicalizePrefix(prefix);
    }

    /** Canonicalize VIP address (v6 compressed). Visible for tests. */
    public static String canonicalizeVip(final String vip) {
        if (StringUtils.isBlank(vip)) {
            return vip;
        }
        final String t = vip.trim();
        if (t.contains(":")) {
            try {
                return NetUtils.standardizeIp6Address(t);
            } catch (final Exception e) {
                return t.toLowerCase(Locale.ROOT);
            }
        }
        return t;
    }

    /** Canonicalize {@code ip/len} prefix. Visible for tests. */
    public static String canonicalizePrefix(final String prefix) {
        if (StringUtils.isBlank(prefix)) {
            return prefix;
        }
        final int slash = prefix.indexOf('/');
        if (slash <= 0) {
            return canonicalizeVip(prefix);
        }
        return canonicalizeVip(prefix.substring(0, slash)) + prefix.substring(slash);
    }

    /**
     * Distributed multi-MS lock for one VPC+VIP family key.
     * Visible for tests (timeout constant).
     */
    <T> T withVipLock(final Long vpcId, final String vipV4, final String vipV6,
            final java.util.concurrent.Callable<T> body) {
        if (!dsrDistributedLockEnabled) {
            try {
                return body.call();
            } catch (final RuntimeException re) {
                throw re;
            } catch (final Exception e) {
                throw new IllegalStateException("DSR locked operation failed: " + e.getMessage(), e);
            }
        }
        final String name = "dsr.vip." + vipLockKey(vpcId, vipV4, vipV6);
        final GlobalLock lock = GlobalLock.getInternLock(name);
        try {
            if (!lock.lock(DSR_VIP_LOCK_TIMEOUT_SECS)) {
                throw new IllegalStateException("DSR VIP lock timeout after "
                        + DSR_VIP_LOCK_TIMEOUT_SECS + "s for " + name);
            }
            try {
                return body.call();
            } catch (final RuntimeException re) {
                throw re;
            } catch (final Exception e) {
                throw new IllegalStateException("DSR locked operation failed: " + e.getMessage(), e);
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    /**
     * True when a peer DSR sibling on the same VIP already has desired-state
     * {@code PROGRAMMED} (or non-revoked active) with {@code ctWithdrawn=true}.
     * Never infers from rule Add count alone. Visible for tests.
     */
    boolean peerAlreadyWithdrewCtBgp(final Network network, final String vipV4, final String vipV6,
            final long excludeRuleId) {
        if (dsrLbDesiredStateDao == null) {
            return false;
        }
        for (final LoadBalancerVO lb : listDsrSiblings(network, vipV4, vipV6, excludeRuleId)) {
            final DsrLbDesiredStateVO d = dsrLbDesiredStateDao.findByLoadBalancerId(lb.getId());
            if (d == null || d.getRemoved() != null) {
                continue;
            }
            if (DsrLbDesiredStateVO.STATE_REVOKED.equals(d.getState())
                    || DsrLbDesiredStateVO.STATE_ROLLBACK.equals(d.getState())) {
                continue;
            }
            // Only trust proven PROGRAMMED/MIGRATING rows with ctWithdrawn=true.
            if (d.isCtWithdrawn()
                    && (DsrLbDesiredStateVO.STATE_PROGRAMMED.equals(d.getState())
                    || DsrLbDesiredStateVO.STATE_MIGRATING.equals(d.getState()))) {
                return true;
            }
        }
        return false;
    }

    boolean wasCtWithdrawn(final long ruleId) {
        if (dsrLbDesiredStateDao == null) {
            return false;
        }
        final DsrLbDesiredStateVO d = dsrLbDesiredStateDao.findByLoadBalancerId(ruleId);
        return d != null && d.isCtWithdrawn();
    }

    /**
     * True when a listed route row is owned by this VIP scope, including legacy
     * ruleId-only owners for the same prefix (migration from 2b241edb).
     * Visible for tests.
     */
    public static boolean isOwnedByVipScope(final EcmpStaticRoute route, final long vpcId,
            final String prefix, final Set<Long> knownRuleIds) {
        if (route == null || StringUtils.isBlank(route.getPrefix())) {
            return false;
        }
        final String wantPrefix = canonicalizePrefix(prefix);
        final String gotPrefix = canonicalizePrefix(route.getPrefix());
        if (!wantPrefix.equals(gotPrefix)) {
            return false;
        }
        final String owner = route.getOwner();
        if (vipOwnerKey(vpcId, wantPrefix).equals(owner)) {
            return true;
        }
        // Legacy ruleId owner from first DSR route commit.
        if (knownRuleIds != null && StringUtils.isNotBlank(owner)) {
            try {
                final long id = Long.parseLong(owner.trim());
                return knownRuleIds.contains(id);
            } catch (final NumberFormatException ignore) {
                return false;
            }
        }
        return false;
    }

    static String vipLockKey(final Long vpcId, final String vipV4, final String vipV6) {
        return String.valueOf(vpcId) + "|" + nullToEmpty(vipV4) + "|" + nullToEmpty(vipV6);
    }

    // ------------------------------------------------------------------
    // Members (union across siblings) + eligibility
    // ------------------------------------------------------------------

    /** Inventory-eligible backends only (not Envoy Ready). Visible for tests. */
    public static final class MemberSet {
        public final List<String> backends;
        public final Set<Long> ruleIds;

        public MemberSet(final List<String> backends, final Set<Long> ruleIds) {
            this.backends = backends == null ? List.of() : List.copyOf(backends);
            this.ruleIds = ruleIds == null ? Set.of() : Set.copyOf(ruleIds);
        }
    }

    /**
     * Union of inventory-eligible backends across all active DSR siblings on the
     * same VIP(s). Eligibility = non-revoked map + Running VM + NIC IP present.
     * Envoy Ready is NOT asserted here (external preflight).
     */
    MemberSet collectEligibleUnionMembers(final Network network, final String vipV4, final String vipV6,
            final LoadBalancingRule primaryRule) {
        final LinkedHashSet<String> backends = new LinkedHashSet<>();
        final LinkedHashSet<Long> ruleIds = new LinkedHashSet<>();
        if (primaryRule != null) {
            ruleIds.add(primaryRule.getId());
            for (final String b : eligibleFromRuleDestinations(primaryRule)) {
                backends.add(b);
            }
        }
        if (network != null && loadBalancerDao != null) {
            for (final LoadBalancerVO lb : listDsrSiblings(network, vipV4, vipV6, -1L)) {
                ruleIds.add(lb.getId());
                for (final String b : inventoryEligibleBackends(lb)) {
                    backends.add(b);
                }
            }
        }
        return new MemberSet(new ArrayList<>(backends), ruleIds);
    }

    List<String> eligibleFromRuleDestinations(final LoadBalancingRule rule) {
        final List<String> out = new ArrayList<>();
        if (rule == null || rule.getDestinations() == null) {
            return out;
        }
        for (final LbDestination d : rule.getDestinations()) {
            if (d == null || d.isRevoked() || StringUtils.isBlank(d.getIpAddress())) {
                continue;
            }
            final String ip = canonicalizeVip(d.getIpAddress().trim());
            if (!(NetUtils.isValidIp4(ip) || NetUtils.isValidIp6(ip))) {
                continue;
            }
            // Require Running VM bound to this IP on the rule network.
            if (!isIpBoundToRunningVm(ip, rule.getNetworkId())) {
                LOGGER.debug("DsrSoftwareLbService: skip destination {} — no Running VM/NIC on network {}",
                        ip, rule.getNetworkId());
                continue;
            }
            out.add(ip);
        }
        return out;
    }

    /**
     * True when {@code ip} is on a NIC whose VM is Running in {@code networkId}.
     * When DAOs are unwired (unit tests), falls back to syntactic validity.
     * Visible for tests.
     */
    boolean isIpBoundToRunningVm(final String ip, final long networkId) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        if (nicDao == null && vmInstanceDao == null) {
            return NetUtils.isValidIp4(ip) || NetUtils.isValidIp6(ip);
        }
        if (nicDao == null) {
            return false;
        }
        NicVO nic = null;
        if (NetUtils.isValidIp4(ip)) {
            nic = nicDao.findByIp4AddressAndNetworkId(ip, networkId);
        } else if (NetUtils.isValidIp6(ip)) {
            // G1: resolve guest NIC by IPv6 address on the rule network.
            final List<NicVO> nics = nicDao.listByNetworkId(networkId);
            if (nics != null) {
                final String want = canonicalizeVip(ip);
                for (final NicVO candidate : nics) {
                    if (candidate == null || StringUtils.isBlank(candidate.getIPv6Address())) {
                        continue;
                    }
                    if (want.equals(canonicalizeVip(candidate.getIPv6Address().trim()))) {
                        nic = candidate;
                        break;
                    }
                }
            }
        }
        if (nic == null) {
            return false;
        }
        if (vmInstanceDao == null) {
            return true;
        }
        final VMInstanceVO vm = vmInstanceDao.findById(nic.getInstanceId());
        return vm != null && vm.getState() == VirtualMachine.State.Running;
    }

    List<String> inventoryEligibleBackends(final LoadBalancerVO lb) {
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
            if (vmInstanceDao != null && m.getInstanceId() > 0) {
                final VMInstanceVO vm = vmInstanceDao.findById(m.getInstanceId());
                if (vm == null || vm.getState() != VirtualMachine.State.Running) {
                    continue;
                }
            }
            if (StringUtils.isNotBlank(m.getInstanceIp())) {
                final String ip = m.getInstanceIp().trim();
                if (NetUtils.isValidIp4(ip) || NetUtils.isValidIp6(ip)) {
                    out.add(ip);
                }
                if (NetUtils.isValidIp4(ip) && nicDao != null) {
                    final NicVO nic = nicDao.findByIp4AddressAndNetworkId(ip, lb.getNetworkId());
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
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /**
     * H5: inventory eligibility only. destinationId may be 0 when unknown.
     * Visible for tests.
     */
    boolean isBackendInventoryEligible(final String ip, final long networkId, final long destinationId) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        if (destinationId > 0 && vmInstanceDao != null) {
            final VMInstanceVO vm = vmInstanceDao.findById(destinationId);
            if (vm != null && vm.getState() != VirtualMachine.State.Running) {
                return false;
            }
        }
        return NetUtils.isValidIp4(ip) || NetUtils.isValidIp6(ip);
    }

    // ------------------------------------------------------------------
    // Sibling discovery + BGP refcount
    // ------------------------------------------------------------------

    /**
     * Active DSR siblings on the same VIP family set (excluding optional id).
     * Visible for tests.
     */
    int countActiveDsrSiblings(final Network network, final String vipV4, final String vipV6,
            final long excludeRuleId) {
        return listDsrSiblings(network, vipV4, vipV6, excludeRuleId).size();
    }

    List<LoadBalancerVO> listDsrSiblings(final Network network, final String vipV4, final String vipV6,
            final long excludeRuleId) {
        final List<LoadBalancerVO> out = new ArrayList<>();
        if (network == null || loadBalancerDao == null) {
            return out;
        }
        final List<LoadBalancerVO> candidates = loadBalancerDao.listByNetworkIdOrVpcIdAndScheme(
                network.getId(), network.getVpcId(), Scheme.Public);
        if (candidates == null) {
            return out;
        }
        for (final LoadBalancerVO lb : candidates) {
            if (lb == null || lb.getId() == excludeRuleId) {
                continue;
            }
            if (lb.getLbKind() == null || !lb.getLbKind().isDsr()) {
                continue;
            }
            if (lb.getState() == FirewallRule.State.Revoke) {
                continue;
            }
            if (dsrLbDesiredStateDao != null) {
                final DsrLbDesiredStateVO d = dsrLbDesiredStateDao.findByLoadBalancerId(lb.getId());
                if (d != null && (DsrLbDesiredStateVO.STATE_REVOKED.equals(d.getState()) || d.getRemoved() != null)) {
                    continue;
                }
            }
            if (sameVipFamily(lb, vipV4, vipV6)) {
                out.add(lb);
            }
        }
        return out;
    }

    boolean sameVipFamily(final LoadBalancerVO lb, final String vipV4, final String vipV6) {
        if (lb == null) {
            return false;
        }
        if (StringUtils.isNotBlank(vipV4) && lb.getSourceIpAddressId() != null && ipAddressDao != null) {
            final IPAddressVO ip = ipAddressDao.findById(lb.getSourceIpAddressId());
            if (ip != null && ip.getAddress() != null && vipV4.equals(ip.getAddress().addr())) {
                return true;
            }
        }
        if (StringUtils.isNotBlank(vipV6) && lb.getPublicIpv6AddressId() != null
                && userPublicIpv6AddressDao != null) {
            final UserPublicIpv6AddressVO v6 = userPublicIpv6AddressDao.findById(lb.getPublicIpv6AddressId());
            if (v6 != null && vipV6.equals(v6.getAddress())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // B4 CT residual precondition
    // ------------------------------------------------------------------

    /** Residual CT object descriptor. Visible for tests. */
    public static final class CtResidual {
        public final String name;
        public final String vipKey;
        public final String kind;

        public CtResidual(final String name, final String vipKey, final String kind) {
            this.name = name;
            this.vipKey = vipKey;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return kind + " name=" + name + " vipKey=" + vipKey;
        }
    }

    /**
     * Fail-closed: any CT Load_Balancer / public-IPv6 LB still owning the same
     * VIP:port blocks DSR PROGRAMMED. Does not delete residual objects.
     * Visible for tests.
     */
    CtResidual findResidualCtOnVip(final Network network, final LoadBalancingRule rule,
            final String vipV4, final String vipV6) {
        if (pluginManager == null || network == null) {
            return null;
        }
        final OvnNbClient nb;
        try {
            nb = pluginManager.nbClient(network.getDataCenterId());
        } catch (final Exception e) {
            throw new IllegalStateException("nbClient failed during CT residual check: " + e.getMessage(), e);
        }
        if (nb == null) {
            throw new IllegalStateException("nbClient null during CT residual check");
        }
        final Integer port = rule.getSourcePortStart();
        final List<OwnedLoadBalancer> candidates = new ArrayList<>();
        candidates.addAll(nb.listOwnedLoadBalancers(OvnConstants.EXT_ID_LB_KIND));
        candidates.addAll(nb.listOwnedLoadBalancers(OvnConstants.EXT_ID_KIND));
        candidates.addAll(nb.listOwnedLoadBalancers(OvnConstants.EXT_ID_PUBLIC_IPV6_LB));
        for (final OwnedLoadBalancer lb : candidates) {
            if (lb == null || lb.getVips() == null) {
                continue;
            }
            final String owner = lb.getOwner() == null ? "" : lb.getOwner();
            if (OvnConstants.EXT_VAL_DSR_SOFTWARE.equals(owner)) {
                continue;
            }
            for (final String vipKey : lb.getVips().keySet()) {
                if (vipKeyMatches(vipKey, vipV4, port) || vipKeyMatches(vipKey, vipV6, port)) {
                    final String kind = owner.contains("|") ? "PUBLIC_IPV6_LB"
                            : ("CT_LB".equals(owner) ? "CT_LB" : "LOAD_BALANCER");
                    return new CtResidual(lb.getName(), vipKey, kind);
                }
            }
        }
        // Residual DNAT / dnat_and_snat on the same VIP (CT/PF leftovers). Does NOT
        // block pure SNAT (type=snat) used for private egress, nor StaticNat on a
        // different public IP. Same-VIP floating/PF NAT conflicts with DSR.
        for (final String vip : new String[] {vipV4, vipV6}) {
            if (StringUtils.isBlank(vip)) {
                continue;
            }
            for (final OwnedNat nat : nb.listNatsByExternalIp(vip)) {
                if (nat == null) {
                    continue;
                }
                final String type = nat.getType() == null ? "" : nat.getType();
                if ("snat".equals(type)) {
                    continue;
                }
                if ("dnat".equals(type) || "dnat_and_snat".equals(type)) {
                    // Port-scoped PF: if external_port_range present and does not
                    // cover this rule's port, skip (other PF on same VIP).
                    if (StringUtils.isNotBlank(nat.getExternalPortRange()) && port != null) {
                        if (!portRangeCovers(nat.getExternalPortRange(), port)) {
                            continue;
                        }
                    }
                    return new CtResidual(nat.getUuid(), vip + " type=" + type, "NAT");
                }
            }
        }
        return null;
    }

    /** Visible for tests. */
    public static boolean portRangeCovers(final String range, final int port) {
        if (StringUtils.isBlank(range)) {
            return true;
        }
        // Formats: "80" or "80-90"
        final String r = range.trim();
        final int dash = r.indexOf('-');
        try {
            if (dash < 0) {
                return Integer.parseInt(r) == port;
            }
            final int a = Integer.parseInt(r.substring(0, dash));
            final int b = Integer.parseInt(r.substring(dash + 1));
            return port >= Math.min(a, b) && port <= Math.max(a, b);
        } catch (final NumberFormatException e) {
            return true; // fail closed on unparsable
        }
    }

    /**
     * Match OVN VIP keys {@code ip:port} or {@code [ipv6]:port}.
     * Visible for tests.
     */
    public static boolean vipKeyMatches(final String vipKey, final String vip, final Integer port) {
        if (StringUtils.isBlank(vipKey) || StringUtils.isBlank(vip)) {
            return false;
        }
        final String key = vipKey.trim();
        if (port != null) {
            final String bareV4 = vip + ":" + port;
            final String bareV6 = "[" + vip + "]:" + port;
            if (key.equals(bareV4) || key.equals(bareV6)) {
                return true;
            }
        }
        // Also match VIP without requiring exact port if key host equals VIP.
        if (key.startsWith("[")) {
            final int close = key.indexOf(']');
            if (close > 1 && vip.equalsIgnoreCase(key.substring(1, close))) {
                return true;
            }
        } else {
            final int colon = key.lastIndexOf(':');
            final String host = colon > 0 ? key.substring(0, colon) : key;
            if (vip.equals(host)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // OVN route program (VIP-scoped, add-before-remove)
    // ------------------------------------------------------------------

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

    public static List<DesiredHop> buildDesiredHops(final String vipV4, final String vipV6,
            final List<String> backends) {
        final List<String> v4Backends = new ArrayList<>();
        final List<String> v6Backends = new ArrayList<>();
        if (backends != null) {
            for (final String b : backends) {
                if (StringUtils.isBlank(b)) {
                    continue;
                }
                final String ip = canonicalizeVip(b.trim());
                if (NetUtils.isValidIp4(ip)) {
                    v4Backends.add(ip);
                } else if (NetUtils.isValidIp6(ip)) {
                    v6Backends.add(ip);
                }
            }
        }
        final List<DesiredHop> out = new ArrayList<>();
        final String c4 = canonicalizeVip(vipV4);
        final String c6 = canonicalizeVip(vipV6);
        if (StringUtils.isNotBlank(c4) && NetUtils.isValidIp4(c4)) {
            final String prefix = c4 + "/32";
            for (final String nh : new LinkedHashSet<>(v4Backends)) {
                out.add(new DesiredHop(prefix, nh, FAMILY_V4));
            }
        }
        if (StringUtils.isNotBlank(c6) && NetUtils.isValidIp6(c6)) {
            final String prefix = c6 + "/128";
            for (final String nh : new LinkedHashSet<>(v6Backends)) {
                out.add(new DesiredHop(prefix, nh, FAMILY_V6));
            }
        }
        return out;
    }

    RouteProgramResult programVipScopedRoutes(final Network network, final LoadBalancingRule rule,
            final String vipV4, final String vipV6, final MemberSet members) {
        if (network == null || network.getVpcId() == null) {
            return failRoutes("network/vpc missing");
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
        if (StringUtils.isBlank(vipV4) && StringUtils.isBlank(vipV6)) {
            return failRoutes("DSR has no VIP (v4/v6)");
        }
        final List<DesiredHop> desired = buildDesiredHops(vipV4, vipV6,
                members == null ? List.of() : members.backends);
        if (StringUtils.isNotBlank(vipV4) && !hasFamily(desired, FAMILY_V4)) {
            return failRoutes("VIP v4 " + vipV4 + " has no valid IPv4 backend next-hop");
        }
        if (StringUtils.isNotBlank(vipV6) && !hasFamily(desired, FAMILY_V6)) {
            return failRoutes("VIP v6 " + vipV6 + " has no valid IPv6 backend next-hop");
        }
        if (desired.isEmpty()) {
            return failRoutes("no valid backend next-hops");
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
        return convergeRoutes(nb, lrUuid, network.getVpcId(), rule, vipV4, vipV6, desired,
                members == null ? Set.of() : members.ruleIds);
    }

    /**
     * H6: add missing hops first, then remove stale (avoid blackhole during churn).
     * H1: list errors propagate; post-condition miss rolls back adds and restores removes.
     */
    RouteProgramResult convergeRoutes(final OvnNbClient nb, final String lrUuid, final long vpcId,
            final LoadBalancingRule rule, final String vipV4, final String vipV6,
            final List<DesiredHop> desired, final Set<Long> ruleIds) {
        final Set<String> prefixes = new LinkedHashSet<>();
        for (final DesiredHop h : desired) {
            prefixes.add(h.prefix);
        }
        // Also include VIP prefixes even if desired empty for a family (cleanup).
        if (StringUtils.isNotBlank(vipV4) && NetUtils.isValidIp4(vipV4)) {
            prefixes.add(vipV4 + "/32");
        }
        if (StringUtils.isNotBlank(vipV6) && NetUtils.isValidIp6(vipV6)) {
            prefixes.add(vipV6 + "/128");
        }

        final List<EcmpStaticRoute> allDsr;
        try {
            allDsr = nb.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE);
        } catch (final OvnException e) {
            return failRoutes("listEcmpStaticRoutes failed: " + e.getMessage());
        }

        final Map<String, EcmpStaticRoute> existingByKey = new LinkedHashMap<>();
        for (final String prefix : prefixes) {
            for (final EcmpStaticRoute r : allDsr) {
                if (isOwnedByVipScope(r, vpcId, prefix, ruleIds)) {
                    existingByKey.put(r.getPrefix() + "|" + r.getNexthop(), r);
                }
            }
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
        // Also remove legacy ruleId-owned rows for these prefixes that are not in desired.
        for (final EcmpStaticRoute r : allDsr) {
            for (final String prefix : prefixes) {
                if (!prefix.equals(r.getPrefix())) {
                    continue;
                }
                if (!isOwnedByVipScope(r, vpcId, prefix, ruleIds)) {
                    continue;
                }
                final String k = r.getPrefix() + "|" + r.getNexthop();
                if (!desiredKeys.contains(k) && !toRemove.contains(r)
                        && !existingByKey.containsKey(k)) {
                    toRemove.add(r);
                }
            }
        }

        final List<String> addedUuids = new ArrayList<>();
        final List<EcmpStaticRoute> removedSnapshot = new ArrayList<>();
        try {
            // H6: ADD before REMOVE so ECMP never empties mid-churn.
            for (final DesiredHop hop : toAdd) {
                final String uuid = nb.addLogicalRouterStaticRoute(lrUuid, hop.prefix, hop.nexthop,
                        null, POLICY_DST_IP, buildRouteExternalIds(vpcId, rule, hop, ruleIds));
                if (StringUtils.isBlank(uuid)) {
                    throw new IllegalStateException("addLogicalRouterStaticRoute blank uuid for " + hop.key());
                }
                addedUuids.add(uuid);
            }
            for (final EcmpStaticRoute stale : toRemove) {
                nb.deleteLogicalRouterStaticRouteDirect(stale.getUuid());
                removedSnapshot.add(stale);
            }
        } catch (final Exception e) {
            LOGGER.error("DsrSoftwareLbService: route converge failed: {}", e.getMessage());
            final boolean rolled = compensate(nb, lrUuid, vpcId, rule, ruleIds, addedUuids, removedSnapshot);
            return new RouteProgramResult(false, addedUuids.size(), removedSnapshot.size(),
                    existingByKey.size() - toRemove.size(), rolled, e.getMessage(), lrUuid, desiredKeys);
        }

        // Post-condition
        final List<EcmpStaticRoute> after;
        try {
            after = nb.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE);
        } catch (final OvnException e) {
            final boolean rolled = compensate(nb, lrUuid, vpcId, rule, ruleIds, addedUuids, removedSnapshot);
            return new RouteProgramResult(false, addedUuids.size(), removedSnapshot.size(), 0, rolled,
                    "post-list failed: " + e.getMessage(), lrUuid, desiredKeys);
        }
        final Set<String> afterKeys = new LinkedHashSet<>();
        for (final EcmpStaticRoute r : after) {
            for (final String prefix : prefixes) {
                if (isOwnedByVipScope(r, vpcId, prefix, ruleIds)) {
                    afterKeys.add(r.getPrefix() + "|" + r.getNexthop());
                }
            }
        }
        for (final String key : desiredKeys) {
            if (!afterKeys.contains(key)) {
                final String err = "post-condition missing DSR route " + key + " on lr=" + lrUuid;
                LOGGER.error("DsrSoftwareLbService: {}", err);
                final boolean rolled = compensate(nb, lrUuid, vpcId, rule, ruleIds, addedUuids, removedSnapshot);
                return new RouteProgramResult(false, addedUuids.size(), removedSnapshot.size(),
                        afterKeys.size(), rolled, err, lrUuid, desiredKeys);
            }
        }
        final int kept = desiredKeys.size() - toAdd.size();
        LOGGER.info("DsrSoftwareLbService: VIP-scoped routes vpc={} +{} -{} keep={} keys={}",
                vpcId, toAdd.size(), removedSnapshot.size(), kept, desiredKeys);
        return new RouteProgramResult(true, toAdd.size(), removedSnapshot.size(), kept, false, null, lrUuid,
                desiredKeys);
    }

    private boolean compensate(final OvnNbClient nb, final String lrUuid, final long vpcId,
            final LoadBalancingRule rule, final Set<Long> ruleIds, final List<String> addedUuids,
            final List<EcmpStaticRoute> removedSnapshot) {
        boolean rolled = false;
        for (final String uuid : addedUuids) {
            try {
                nb.deleteLogicalRouterStaticRouteDirect(uuid);
                rolled = true;
            } catch (final Exception ex) {
                LOGGER.warn("DsrSoftwareLbService: compensate delete {} failed: {}", uuid, ex.getMessage());
            }
        }
        for (final EcmpStaticRoute was : removedSnapshot) {
            try {
                final DesiredHop hop = new DesiredHop(was.getPrefix(), was.getNexthop(),
                        was.getPrefix().contains(":") ? FAMILY_V6 : FAMILY_V4);
                nb.addLogicalRouterStaticRoute(lrUuid, was.getPrefix(), was.getNexthop(),
                        null, POLICY_DST_IP, buildRouteExternalIds(vpcId, rule, hop, ruleIds));
                rolled = true;
            } catch (final Exception ex) {
                LOGGER.warn("DsrSoftwareLbService: compensate restore {} failed: {}",
                        was.getUuid(), ex.getMessage());
            }
        }
        return rolled;
    }

    void removeVipScopedRoutes(final Network network, final String vipV4, final String vipV6) {
        if (pluginManager == null || network == null || network.getVpcId() == null) {
            return;
        }
        final OvnNbClient nb = pluginManager.nbClient(network.getDataCenterId());
        if (nb == null) {
            return;
        }
        final long vpcId = network.getVpcId();
        final Set<String> prefixes = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(vipV4) && NetUtils.isValidIp4(vipV4)) {
            prefixes.add(vipV4 + "/32");
        }
        if (StringUtils.isNotBlank(vipV6) && NetUtils.isValidIp6(vipV6)) {
            prefixes.add(vipV6 + "/128");
        }
        final Set<Long> known = new LinkedHashSet<>();
        for (final LoadBalancerVO lb : listDsrSiblings(network, vipV4, vipV6, -1L)) {
            known.add(lb.getId());
        }
        // Include active desired-state rule ids for legacy ruleId-owner cleanup.
        if (dsrLbDesiredStateDao != null) {
            for (final DsrLbDesiredStateVO d : dsrLbDesiredStateDao.listActive()) {
                if (d != null) {
                    known.add(d.getLoadBalancerId());
                }
            }
        }
        final List<EcmpStaticRoute> all = nb.listEcmpStaticRoutes(OvnConstants.EXT_ID_DSR_ROUTE);
        for (final EcmpStaticRoute r : all) {
            for (final String prefix : prefixes) {
                if (isOwnedByVipScope(r, vpcId, prefix, known)
                        || vipOwnerKey(vpcId, prefix).equals(r.getOwner())) {
                    try {
                        nb.deleteLogicalRouterStaticRouteDirect(r.getUuid());
                        LOGGER.info("DsrSoftwareLbService: removed VIP-scoped DSR route {} {} -> {}",
                                r.getUuid(), r.getPrefix(), r.getNexthop());
                    } catch (final OvnException oe) {
                        LOGGER.warn("DsrSoftwareLbService: delete {} failed: {}", r.getUuid(), oe.getMessage());
                    }
                }
            }
        }
    }

    Map<String, String> buildRouteExternalIds(final long vpcId, final LoadBalancingRule rule,
            final DesiredHop hop, final Set<Long> ruleIds) {
        final Map<String, String> ext = new LinkedHashMap<>();
        final String prefix = hop == null ? "" : hop.prefix;
        ext.put(OvnConstants.EXT_ID_DSR_ROUTE, vipOwnerKey(vpcId, prefix));
        ext.put(OvnConstants.EXT_ID_LB_KIND, OvnConstants.EXT_VAL_DSR_SOFTWARE);
        ext.put(OvnConstants.EXT_ID_KIND, "DSR_LB_ROUTE");
        if (rule != null) {
            ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
            if (rule.getUuid() != null) {
                ext.put("cs_uuid", rule.getUuid());
            }
        }
        if (ruleIds != null && !ruleIds.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (final Long id : ruleIds) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
            ext.put(EXT_ID_DSR_RULES, sb.toString());
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

    // ------------------------------------------------------------------
    // BGP dual-stack + sibling-aware restore
    // ------------------------------------------------------------------

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

    DualStackBgpResult withdrawCtLbBgpDualStack(final Network network, final LoadBalancingRule rule,
            final String vipV4In, final String vipV6In) {
        if (network == null || network.getVpcId() == null || bgpRedistributeManager == null) {
            return new DualStackBgpResult(true, false, false, false, null, vipV4In, vipV6In);
        }
        final long vpcId = network.getVpcId();
        final long zoneId = network.getDataCenterId();
        String vipV4 = vipV4In;
        Long ipIdV4 = null;
        if (rule.getLb() != null && rule.getLb().getSourceIpAddressId() != null && ipAddressDao != null) {
            final IPAddressVO ipRow = ipAddressDao.findById(rule.getLb().getSourceIpAddressId());
            if (ipRow != null && ipRow.getAddress() != null) {
                vipV4 = ipRow.getAddress().addr();
                ipIdV4 = ipRow.getId();
            }
        }
        String vipV6 = vipV6In != null ? vipV6In : resolvePublicIpv6(rule);

        final boolean needV4 = StringUtils.isNotBlank(vipV4) && ipIdV4 != null;
        final boolean needV6 = StringUtils.isNotBlank(vipV6);
        if (!needV4 && !needV6) {
            return new DualStackBgpResult(true, false, false, false, null, vipV4, vipV6);
        }

        boolean okV4 = !needV4;
        boolean okV6 = !needV6;
        try {
            if (needV4) {
                bgpRedistributeManager.withdraw(vipV4, ipIdV4, vpcId, zoneId);
                okV4 = true;
            }
        } catch (final Exception e) {
            okV4 = false;
            LOGGER.error("DsrSoftwareLbService: BGP v4 withdraw failed for {}: {}", vipV4, e.getMessage());
        }
        try {
            if (needV6) {
                bgpRedistributeManager.withdrawHost6(vipV6, vpcId, zoneId);
                okV6 = true;
            }
        } catch (final Exception e) {
            okV6 = false;
            LOGGER.error("DsrSoftwareLbService: BGP v6 withdraw failed for {}: {}", vipV6, e.getMessage());
        }
        if (okV4 && okV6) {
            return new DualStackBgpResult(true, needV4, needV6, false, null, vipV4, vipV6);
        }
        boolean rolledBack = false;
        if (okV4 && needV4) {
            try {
                bgpRedistributeManager.announce(vipV4, ipIdV4, vpcId, zoneId);
                rolledBack = true;
            } catch (final Exception e) {
                LOGGER.error("DsrSoftwareLbService: rollback announce v4 failed: {}", e.getMessage());
            }
        }
        if (okV6 && needV6) {
            try {
                bgpRedistributeManager.announceHost6(vipV6, vpcId, zoneId);
                rolledBack = true;
            } catch (final Exception e) {
                LOGGER.error("DsrSoftwareLbService: rollback announce v6 failed: {}", e.getMessage());
            }
        }
        return new DualStackBgpResult(false, okV4 && needV4, okV6 && needV6, rolledBack,
                "partial dual-stack BGP withdraw failure", vipV4, vipV6);
    }

    void restoreCtLbBgpDualStack(final Network network, final LoadBalancingRule rule,
            final String vipV4In, final String vipV6In) {
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
        final String vipV6 = vipV6In != null ? vipV6In : resolvePublicIpv6(rule);
        if (StringUtils.isNotBlank(vipV6)) {
            try {
                bgpRedistributeManager.announceHost6(vipV6, vpcId, zoneId);
            } catch (final Exception e) {
                LOGGER.warn("DsrSoftwareLbService: restore announce v6 failed: {}", e.getMessage());
            }
        }
    }

    // Legacy package-private wrappers used by existing dual-stack tests.
    DualStackBgpResult withdrawCtLbBgpDualStack(final Network network, final LoadBalancingRule rule) {
        return withdrawCtLbBgpDualStack(network, rule, resolveVipV4(rule), resolvePublicIpv6(rule));
    }

    void restoreCtLbBgpDualStack(final Network network, final LoadBalancingRule rule) {
        restoreCtLbBgpDualStack(network, rule, resolveVipV4(rule), resolvePublicIpv6(rule));
    }

    // ------------------------------------------------------------------
    // Inventory reprogram / helpers
    // ------------------------------------------------------------------

    void reprogramFromInventory(final long loadBalancerId) {
        if (loadBalancerDao == null || networkDao == null) {
            throw new IllegalStateException("inventory DAOs not wired");
        }
        final LoadBalancerVO lb = loadBalancerDao.findById(loadBalancerId);
        if (lb == null || lb.getLbKind() == null || !lb.getLbKind().isDsr()) {
            return;
        }
        final Network network = networkDao.findById(lb.getNetworkId());
        if (network == null) {
            throw new IllegalStateException("network missing for LB " + loadBalancerId);
        }
        final String vipV4 = resolveVipV4(lb);
        final String vipV6;
        if (lb.getPublicIpv6AddressId() != null && userPublicIpv6AddressDao != null) {
            final UserPublicIpv6AddressVO a = userPublicIpv6AddressDao.findById(lb.getPublicIpv6AddressId());
            vipV6 = a == null ? null : a.getAddress();
        } else {
            vipV6 = null;
        }
        if (lb.getState() == FirewallRule.State.Revoke) {
            final LoadBalancingRule rule = new LoadBalancingRule(lb, List.of(), List.of(), List.of(),
                    vipV4 == null ? null : new com.cloud.utils.net.Ip(vipV4), null, lb.getLbProtocol());
            revokeOne(network, rule);
            return;
        }
        final List<String> backends = inventoryEligibleBackends(lb);
        final List<LbDestination> dests = new ArrayList<>();
        for (final String b : backends) {
            dests.add(new LbDestination(lb.getDefaultPortStart(), lb.getDefaultPortEnd(), b, false));
        }
        final LoadBalancingRule rule = new LoadBalancingRule(lb, dests, List.of(), List.of(),
                vipV4 == null ? null : new com.cloud.utils.net.Ip(vipV4), null, lb.getLbProtocol());
        applyOne(network, rule);
    }

    private DsrLbDesiredStateVO upsertDesiredState(final Network network, final LoadBalancingRule rule,
            final String vipV4, final String vipV6) {
        final int port = rule.getSourcePortStart() == null ? 0 : rule.getSourcePortStart();
        final String protocol = rule.getLbProtocol() == null ? rule.getProtocol() : rule.getLbProtocol();
        final DsrLbDesiredStateVO desired = new DsrLbDesiredStateVO(rule.getId(), vipV4, vipV6, port, protocol,
                buildExternalIds(rule));
        return dsrLbDesiredStateDao.persist(desired);
    }

    private void refreshDesiredState(final DsrLbDesiredStateVO desired, final LoadBalancingRule rule,
            final String vipV4, final String vipV6) {
        desired.setExternalIds(buildExternalIds(rule));
        if (vipV4 != null) {
            desired.setVipV4(vipV4);
        }
        if (vipV6 != null) {
            desired.setVipV6(vipV6);
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

    private String lookupVpcLrUuid(final Network network, final OvnControllerVO controller) {
        if (network == null || network.getVpcId() == null || controller == null || logicalIdMapDao == null) {
            return null;
        }
        final OvnLogicalIdMapVO row = logicalIdMapDao.findByCsId(Kind.VPC, network.getVpcId(), controller.getId());
        return row == null ? null : row.getOvnUuid();
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
        sb.append(",\"ownership\":\"vip-scoped\"");
        sb.append('}');
        return sb.toString();
    }

    public static boolean isDsrRule(final LoadBalancingRule rule) {
        return rule != null && rule.getLb() != null && rule.getLb().getLbKind() != null
                && rule.getLb().getLbKind().isDsr();
    }

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

    private static String nullToEmpty(final String s) {
        return s == null ? "" : s;
    }

    // package helpers used by older tests
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
}
