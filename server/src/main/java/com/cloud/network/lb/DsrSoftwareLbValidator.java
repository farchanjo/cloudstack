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
package com.cloud.network.lb;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.EntityManager;

/**
 * Hard mutex and feature-gate enforcement for {@link LbKind#DSR_SOFTWARE}
 * (X1–X12 from the DSR design contract). Pure inventory checks — no OVN writes.
 */
@Component
public class DsrSoftwareLbValidator extends ManagerBase {

    @Inject
    private LoadBalancerDao lbDao;
    @Inject
    private PortForwardingRulesDao portForwardingRulesDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private EntityManager entityMgr;

    /**
     * Validate create of a public LB with the given kind.
     *
     * @param excludeRuleId existing rule id to ignore (update path), or null
     */
    public void validateCreate(LbKind kind, Network network, Long sourceIpId, Long publicIpv6Id,
            int publicPort, String algorithm, Long excludeRuleId) {
        if (kind == null || kind.isCtLb()) {
            // CT_LB path: still reject mixed-kind VIP:port collisions with DSR (X1/X11).
            rejectIfDsrOwnsVipPort(sourceIpId, publicIpv6Id, publicPort, excludeRuleId);
            return;
        }
        // DSR path
        if (!Boolean.TRUE.equals(DsrSoftwareLbConfig.DsrSoftwareEnabled.value())) {
            throw new InvalidParameterValueException(
                    "Load balancer kind DSR_SOFTWARE is disabled by feature gate");
        }
        assertOfferingSupportsKind(network, kind);
        assertNotHwOffloadOnly(network);
        assertAlgorithmAllowed(algorithm);
        assertNotSourceNatIp(sourceIpId);
        assertNoStaticNat(sourceIpId);
        assertNoPortForward(sourceIpId, publicPort);
        assertNoConflictingLb(sourceIpId, publicIpv6Id, publicPort, kind, excludeRuleId);
    }

    /**
     * Reject in-place kind flip (X10). Kind changes require delete + recreate
     * (or an explicit migrate that tears down the other plane first).
     */
    public void validateNoInPlaceKindFlip(LoadBalancerVO existing, LbKind requested) {
        if (existing == null || requested == null) {
            return;
        }
        LbKind current = existing.getLbKind();
        if (current != requested) {
            throw new InvalidParameterValueException(
                    "In-place load balancer kind flip from " + current.getApiName()
                            + " to " + requested.getApiName()
                            + " is not supported; delete and recreate (or use explicit migrate tear-down)");
        }
    }

    /**
     * DSR forbids CT stickiness (X6).
     */
    public void validateStickiness(LoadBalancerVO lb, String methodName) {
        if (lb == null || !lb.getLbKind().isDsr()) {
            return;
        }
        throw new InvalidParameterValueException(
                "Load balancer kind DSR_SOFTWARE is incompatible with CT stickiness method "
                        + methodName);
    }

    /**
     * Dual-stack parity: when a rule binds both families, kinds must match (X8).
     * For separate v4/v6 rules on the same VIP role, reject mixed kinds on overlapping ports.
     */
    public void validateDualStackKindParity(Long sourceIpId, Long publicIpv6Id, int publicPort,
            LbKind kind, Long excludeRuleId) {
        if (sourceIpId == null && publicIpv6Id == null) {
            return;
        }
        // If both IDs are set on the same rule, kind is single — already OK.
        // Cross-rule parity is covered by assertNoConflictingLb.
        if (sourceIpId != null && publicIpv6Id != null && kind != null) {
            // single dual-stack rule carries one kind — X8 satisfied by schema.
            return;
        }
        assertNoConflictingLb(sourceIpId, publicIpv6Id, publicPort, kind, excludeRuleId);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    void assertOfferingSupportsKind(Network network, LbKind kind) {
        if (network == null || kind == null) {
            return;
        }
        Map<Capability, String> capMap = networkModel.getNetworkServiceCapabilities(network.getId(), Network.Service.Lb);
        if (capMap == null) {
            capMap = Collections.emptyMap();
        }
        String caps = capMap.get(Capability.SupportedLbKinds);
        if (StringUtils.isBlank(caps)) {
            // Missing capability => CT_LB only (fail closed for DSR).
            if (kind.isDsr()) {
                throw new InvalidParameterValueException(
                        "Network offering does not advertise SupportedLbKinds including dsr_software");
            }
            return;
        }
        String needle = kind.getApiName().toLowerCase(Locale.ROOT);
        boolean found = false;
        for (String token : caps.split(",")) {
            if (token.trim().equalsIgnoreCase(needle) || token.trim().equalsIgnoreCase(kind.name())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new InvalidParameterValueException(
                    "Network offering SupportedLbKinds does not include " + kind.getApiName()
                            + " (advertised: " + caps + ")");
        }
    }

    void assertNotHwOffloadOnly(Network network) {
        if (network == null || entityMgr == null) {
            return;
        }
        NetworkOffering off = entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
        if (off != null && off.isHwOffloadEnabled()) {
            // X3: DSR requires software datapath; reject HW-offload LB expectation.
            throw new InvalidParameterValueException(
                    "Load balancer kind DSR_SOFTWARE requires software datapath; network offering enables hardware offload LB");
        }
    }

    void assertAlgorithmAllowed(String algorithm) {
        if (algorithm == null) {
            return;
        }
        String algo = algorithm.toLowerCase(Locale.ROOT);
        if ("leastconn".equals(algo) || "least-connections".equals(algo) || "leastconnection".equals(algo)) {
            // X7
            throw new InvalidParameterValueException(
                    "Load balancer kind DSR_SOFTWARE is incompatible with algorithm leastconn");
        }
    }

    void assertNotSourceNatIp(Long sourceIpId) {
        if (sourceIpId == null) {
            return;
        }
        IPAddressVO ip = ipAddressDao.findById(sourceIpId);
        if (ip != null && ip.isSourceNat()) {
            // X12
            throw new InvalidParameterValueException(
                    "Load balancer kind DSR_SOFTWARE cannot use VPC source-NAT IP " + ip.getAddress());
        }
    }

    void assertNoStaticNat(Long sourceIpId) {
        if (sourceIpId == null) {
            return;
        }
        IPAddressVO ip = ipAddressDao.findById(sourceIpId);
        if (ip != null && ip.isOneToOneNat()) {
            // X4
            throw new InvalidParameterValueException(
                    "Load balancer kind DSR_SOFTWARE cannot share VIP " + ip.getAddress()
                            + " with StaticNat or PortForward");
        }
    }

    void assertNoPortForward(Long sourceIpId, int publicPort) {
        if (sourceIpId == null || portForwardingRulesDao == null) {
            return;
        }
        List<? extends PortForwardingRule> pfs = portForwardingRulesDao.listByIpAndNotRevoked(sourceIpId);
        if (pfs == null) {
            return;
        }
        for (PortForwardingRule pf : pfs) {
            if (pf.getSourcePortStart() != null && pf.getSourcePortStart() == publicPort) {
                // X5
                IPAddressVO ip = ipAddressDao.findById(sourceIpId);
                String addr = ip == null || ip.getAddress() == null ? String.valueOf(sourceIpId)
                        : ip.getAddress().addr();
                throw new InvalidParameterValueException(
                        "Load balancer kind DSR_SOFTWARE cannot share VIP " + addr
                                + " with StaticNat or PortForward");
            }
        }
    }

    void assertNoConflictingLb(Long sourceIpId, Long publicIpv6Id, int publicPort, LbKind kind,
            Long excludeRuleId) {
        if (sourceIpId != null) {
            List<LoadBalancerVO> existing = lbDao.listByIpAddress(sourceIpId);
            checkLbConflicts(existing, publicPort, kind, excludeRuleId, sourceIpId, true);
        }
        if (publicIpv6Id != null) {
            List<LoadBalancerVO> existing = lbDao.listByPublicIpv6AddressId(publicIpv6Id);
            checkLbConflicts(existing, publicPort, kind, excludeRuleId, publicIpv6Id, false);
        }
    }

    private void checkLbConflicts(List<LoadBalancerVO> existing, int publicPort, LbKind kind,
            Long excludeRuleId, long vipId, boolean ipv4) {
        if (existing == null) {
            return;
        }
        for (LoadBalancerVO rule : existing) {
            if (rule.getState() == FirewallRule.State.Revoke) {
                continue;
            }
            if (excludeRuleId != null && rule.getId() == excludeRuleId.longValue()) {
                continue;
            }
            if (rule.getSourcePortStart() == null || rule.getSourcePortStart() != publicPort) {
                continue;
            }
            LbKind other = rule.getLbKind();
            if (kind != null && other != null && kind != other) {
                // X1 / X8 / X11
                String addr = resolveAddr(rule, ipv4);
                throw new InvalidParameterValueException(
                        "Load balancer kind " + kind.name()
                                + " is incompatible with OVN conntrack LB (ct_lb) on VIP " + addr
                                + " port " + publicPort);
            }
            // same kind + same port already handled by port-conflict checks elsewhere
        }
    }

    private void rejectIfDsrOwnsVipPort(Long sourceIpId, Long publicIpv6Id, int publicPort,
            Long excludeRuleId) {
        if (sourceIpId != null) {
            List<LoadBalancerVO> existing = lbDao.listByIpAddress(sourceIpId);
            for (LoadBalancerVO rule : safe(existing)) {
                if (isActivePortMatch(rule, publicPort, excludeRuleId) && rule.getLbKind().isDsr()) {
                    throw new InvalidParameterValueException(
                            "Load balancer kind CT_LB is incompatible with DSR_SOFTWARE on VIP "
                                    + resolveAddr(rule, true) + " port " + publicPort);
                }
            }
        }
        if (publicIpv6Id != null) {
            List<LoadBalancerVO> existing = lbDao.listByPublicIpv6AddressId(publicIpv6Id);
            for (LoadBalancerVO rule : safe(existing)) {
                if (isActivePortMatch(rule, publicPort, excludeRuleId) && rule.getLbKind().isDsr()) {
                    throw new InvalidParameterValueException(
                            "Load balancer kind CT_LB is incompatible with DSR_SOFTWARE on VIP "
                                    + resolveAddr(rule, false) + " port " + publicPort);
                }
            }
        }
    }

    private static boolean isActivePortMatch(LoadBalancerVO rule, int publicPort, Long excludeRuleId) {
        if (rule.getState() == FirewallRule.State.Revoke) {
            return false;
        }
        if (excludeRuleId != null && rule.getId() == excludeRuleId.longValue()) {
            return false;
        }
        return rule.getSourcePortStart() != null && rule.getSourcePortStart() == publicPort;
    }

    private String resolveAddr(LoadBalancerVO rule, boolean preferV4) {
        if (preferV4 && rule.getSourceIpAddressId() != null) {
            IPAddressVO ip = ipAddressDao.findById(rule.getSourceIpAddressId());
            if (ip != null && ip.getAddress() != null) {
                return ip.getAddress().addr();
            }
        }
        return "id=" + rule.getId();
    }

    private static List<LoadBalancerVO> safe(List<LoadBalancerVO> list) {
        return list == null ? List.of() : list;
    }
}
