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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.VpcVO;

/**
 * Maps CloudStack {@link PortForwardingRule}s onto OVN {@code Load_Balancer}
 * rows attached to the VPC's {@code Logical_Router}. A 1:1 PF is the
 * degenerate single-backend LB shape OVN supports natively (the LB row's
 * {@code vips} map carries one {@code "publicIp:port" -> "privateIp:port"}
 * entry per rule). Cleanup uses the standard mapping table under
 * {@link Kind#PORT_FORWARDING}.
 */
@Component
public class OvnPortForwardingService {

    private static final Logger LOGGER = LogManager.getLogger(OvnPortForwardingService.class);

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private OvnVpcElement vpcElement;

    /**
     * Apply every supplied PF rule. Idempotent: an existing mapping for the
     * rule id collapses to a no-op (or to a backend-update when the
     * destination changed). Rules in {@code Revoke} state drop the LB and the
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
                applyOne(nb, controller, lrUuid, rule);
            } catch (OvnException e) {
                LOGGER.error("OvnPortForwardingService: rule id={} failed: {}", rule.getId(), e.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    private void applyOne(final OvnNbClient nb, final OvnControllerVO controller, final String lrUuid,
                          final PortForwardingRule rule) {
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.PORT_FORWARDING, rule.getId(), controller.getId());
        if (rule.getState() == FirewallRule.State.Revoke) {
            revokeOne(nb, lrUuid, existing);
            return;
        }
        if (rule.getState() != FirewallRule.State.Add && rule.getState() != FirewallRule.State.Active) {
            LOGGER.debug("OvnPortForwardingService: skipping rule id={} in state {}", rule.getId(), rule.getState());
            return;
        }
        final String publicIp = rule.getDestinationIpAddress() == null ? null : rule.getDestinationIpAddress().addr();
        final String privateIp = lookupVmIp(rule);
        if (StringUtils.isBlank(publicIp) || StringUtils.isBlank(privateIp)) {
            LOGGER.warn("OvnPortForwardingService: rule id={} missing public/private IP (pub={} priv={}); skipping",
                    rule.getId(), publicIp, privateIp);
            return;
        }
        final String pubPort = String.valueOf(rule.getSourcePortStart());
        final String privPort = String.valueOf(rule.getDestinationPortStart());
        final Map<String, String> vips = new HashMap<>();
        vips.put(publicIp + ":" + pubPort, privateIp + ":" + privPort);
        final String protocol = (rule.getProtocol() == null ? "tcp" : rule.getProtocol()).toLowerCase(Locale.ROOT);
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.PORT_FORWARDING.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));

        if (existing != null) {
            // Backend may have changed (VM moved, port renumber); replace
            // atomically. Re-attach is unnecessary — the LB row is already
            // attached to the LR.
            nb.updateLoadBalancerBackends(existing.getOvnUuid(), vips);
            LOGGER.debug("OvnPortForwardingService: rule id={} backend updated to {}", rule.getId(), vips);
            return;
        }
        final String name = "cs-pf-" + rule.getId();
        final String lbUuid = nb.createLoadBalancer(name, vips, mapProtocol(protocol), null, ext);
        nb.attachLoadBalancerToLogicalRouter(lrUuid, lbUuid);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.PORT_FORWARDING, rule.getId(), controller.getId(), lbUuid, name));
        LOGGER.info("OvnPortForwardingService: PF {} added (rule id={}, vips={}, proto={})",
                lbUuid, rule.getId(), vips, protocol);
    }

    private void revokeOne(final OvnNbClient nb, final String lrUuid, final OvnLogicalIdMapVO mapping) {
        if (mapping == null) {
            return;
        }
        try {
            nb.detachLoadBalancerFromLogicalRouter(lrUuid, mapping.getOvnUuid());
            nb.deleteLoadBalancer(mapping.getOvnUuid());
        } catch (OvnException e) {
            LOGGER.warn("OvnPortForwardingService: revoke {} failed: {}", mapping.getOvnUuid(), e.getMessage());
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
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

    private static String mapProtocol(final String proto) {
        switch (proto) {
            case "udp":
                return OvnNbClient.LB_PROTOCOL_UDP;
            case "sctp":
                return OvnNbClient.LB_PROTOCOL_SCTP;
            case "tcp":
            default:
                return OvnNbClient.LB_PROTOCOL_TCP;
        }
    }
}
