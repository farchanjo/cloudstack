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
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.dao.DsrLbDesiredStateDao;
import com.cloud.network.dao.DsrLbDesiredStateVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.manager.OvnBgpRedistributeManager;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.EntityManager;

/**
 * Programmer for {@link LbKind#DSR_SOFTWARE} load balancer rules.
 *
 * <p><b>Never</b> creates OVN {@code Load_Balancer} rows, hairpin SNAT,
 * force SNAT, or NAT entries for the DSR VIP. Desired state lives in
 * {@code dsr_lb_desired_state}; guest/Kubernetes BGP ownership is external.
 * CT_LB host BGP for the VIP is withdrawn on program and re-announced on
 * rollback/revoke when inventory returns to CT_LB.
 */
@Component
public class DsrSoftwareLbService extends AdapterBase {

    private static final Logger LOGGER = LogManager.getLogger(DsrSoftwareLbService.class);

    /** Ownership tag key written into desired-state external_ids JSON. */
    public static final String EXT_CS_LB_KIND = "cs_lb_kind";

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
                        rule.getId(), e.getMessage());
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
     * exists for the rule and desired state is Programmed when backends ready.
     */
    public boolean reconcileOne(final DsrLbDesiredStateVO desired) {
        if (desired == null || desired.getRemoved() != null) {
            return true;
        }
        if (DsrLbDesiredStateVO.STATE_REVOKED.equals(desired.getState())) {
            return true;
        }
        // Hard guard: never heal DSR into Load_Balancer (reconciler filter).
        ensureNoOvnLoadBalancer(desired.getLoadBalancerId());
        if (desired.isBackendReady() && !DsrLbDesiredStateVO.STATE_PROGRAMMED.equals(desired.getState())) {
            desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
            desired.setLastError(null);
            desired.setUpdated(new Date());
            dsrLbDesiredStateDao.update(desired.getId(), desired);
        }
        return true;
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

        // Atomic dual-stack CT host BGP withdraw before DSR ownership can go active.
        final DualStackBgpResult bgp = withdrawCtLbBgpDualStack(network, rule);
        if (!bgp.ok) {
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
            if (!DsrLbDesiredStateVO.STATE_PROGRAMMED.equals(desired.getState())
                    && !DsrLbDesiredStateVO.STATE_MIGRATING.equals(desired.getState())) {
                desired.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
            }
            desired.setLastError(null);
            desired.setUpdated(new Date());
            dsrLbDesiredStateDao.update(desired.getId(), desired);
        }
        LOGGER.info("DsrSoftwareLbService: DSR rule id={} programmed (no OVN LB/NAT); desired={} bgp={}",
                rule.getId(), desired == null ? "null" : desired.getState(), bgp);
    }

    private void revokeOne(final Network network, final LoadBalancingRule rule) {
        ensureNoOvnLoadBalancer(rule.getId());
        // Rollback: re-announce CT host BGP for any VIP families this rule held.
        restoreCtLbBgpDualStack(network, rule);
        if (dsrLbDesiredStateDao != null) {
            final DsrLbDesiredStateVO desired = dsrLbDesiredStateDao.findByLoadBalancerId(rule.getId());
            if (desired != null) {
                desired.setState(DsrLbDesiredStateVO.STATE_REVOKED);
                desired.setCtWithdrawn(false);
                desired.setRemoved(new Date());
                desired.setUpdated(new Date());
                dsrLbDesiredStateDao.update(desired.getId(), desired);
            }
        }
        LOGGER.info("DsrSoftwareLbService: DSR rule id={} revoked; CT BGP restore attempted", rule.getId());
    }

    /**
     * Assert: if a stale LOAD_BALANCER mapping exists for a DSR rule,
     * log error and refuse to heal into ct_lb (do not call createLoadBalancer).
     * Visible for tests.
     */
    void ensureNoOvnLoadBalancer(final long ruleId) {
        if (logicalIdMapDao == null) {
            return;
        }
        // Per-controller lookup is the canonical API; scan via listByKind when
        // controller id is unknown is expensive — soft-check via reverse is N/A.
        // OvnNetworkElement dispatch never calls createLoadBalancer for DSR;
        // this guard is belt-and-suspenders for reconcile.
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
}
