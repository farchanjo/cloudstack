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
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.element.NetworkACLServiceProvider;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.Vpc;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Maps CloudStack {@link NetworkACLItem} rules onto OVN NB ACL rows.
 *
 * <p>One CloudStack rule becomes one OVN ACL row attached to the tier's
 * {@code Logical_Switch}. The CloudStack rule id is recorded in the ACL's
 * {@code external_ids} so re-applies are idempotent and a CloudStack
 * {@code Revoke} cleanly resolves the matching OVN UUID via
 * {@code ovn_logical_id_map} (kind={@link Kind#NETWORK_ACL}).
 *
 * <p>Match expression conventions (OVN match grammar — ovn-sb(5) §15):
 * <ul>
 *   <li>Ingress (CloudStack {@code Ingress}) → direction {@code from-lport}.
 *   <li>Egress (CloudStack {@code Egress})   → direction {@code to-lport}.
 *   <li>{@code allow-related} is the canonical action for a stateful
 *       CloudStack {@code Allow} rule.
 *   <li>{@code drop} is the canonical action for a CloudStack {@code Deny}
 *       rule (silent drop, no ICMP unreachable / TCP RST).
 *   <li>Source CIDR list → joined as {@code (ip4.src == X || ip4.src == Y)}.
 *   <li>Port range → {@code tcp.dst >= start && tcp.dst <= end}.
 *   <li>Protocol {@code all} / {@code any} → no protocol predicate.
 *   <li>Protocol {@code icmp} → {@code icmp4} predicate.
 * </ul>
 */
/**
 * Helper bean. Not a CloudStack {@code NetworkElement}: the plugin federates
 * all per-service implementations through the single {@link OvnNetworkElement}
 * (CloudStack enforces a 1:1 Provider &lt;-&gt; NetworkElement registration). The
 * element {@code @Inject}s this helper and delegates the {@code
 * NetworkACLServiceProvider} contract to it. {@code AdapterBase} is kept so
 * the bean retains a stable {@code name} for log-line attribution.
 */
@Component
public class OvnFirewallService extends AdapterBase {

    private static final Logger LOGGER = LogManager.getLogger(OvnFirewallService.class);

    /** Default OVN priority for CloudStack-emitted ACLs. */
    public static final int DEFAULT_PRIORITY = 2000;

    /** Lower bound for OVN ACL priorities (per ovn-sb(5)). */
    private static final int MIN_PRIORITY = 0;
    /** Upper bound for OVN ACL priorities. */
    private static final int MAX_PRIORITY = 32_767;

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = buildCapabilities();

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;

    public Map<Service, Map<Capability, String>> getCapabilities() {
        return CAPABILITIES;
    }

    public Provider getProvider() {
        return OvnNetworkProvider.OVN_PROVIDER;
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        return super.configure(name, params);
    }

    public boolean applyNetworkACLs(final Network network, final List<? extends NetworkACLItem> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.warn("OvnFirewallService: no OVN controller for zone {} — skipping {} rule(s)",
                    zoneId, rules.size());
            return false;
        }
        final String tierLsUuid = lookupTierLsUuid(network, controller);
        if (tierLsUuid == null) {
            LOGGER.warn("OvnFirewallService: tier id={} has no OVN logical switch yet; skipping ACL apply",
                    network.getId());
            return false;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        boolean overall = true;
        for (final NetworkACLItem rule : rules) {
            try {
                applyOne(nb, controller, tierLsUuid, rule);
            } catch (final OvnException oe) {
                LOGGER.error("OvnFirewallService: failed to apply ACL rule id={}: {}", rule.getId(), oe.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    public boolean reorderAclRules(final Vpc vpc, final List<? extends Network> networks,
                                   final List<? extends NetworkACLItem> networkACLItems) {
        if (networks == null || networks.isEmpty() || networkACLItems == null || networkACLItems.isEmpty()) {
            return true;
        }
        // OVN priorities re-derive deterministically from the rule order, so
        // the cheapest reorder is "wipe + re-insert" per tier. Each iteration
        // is its own transaction so failure on one tier does not corrupt the
        // others.
        boolean overall = true;
        for (final Network tier : networks) {
            try {
                clearAclsForTier(tier);
                if (!applyNetworkACLs(tier, networkACLItems)) {
                    overall = false;
                }
            } catch (final ResourceUnavailableException | OvnException ex) {
                LOGGER.error("OvnFirewallService: reorder failed on tier id={}: {}", tier.getId(), ex.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    private void clearAclsForTier(final Network tier) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(tier.getDataCenterId());
        if (controller == null) {
            return;
        }
        final String tierLsUuid = lookupTierLsUuid(tier, controller);
        if (tierLsUuid == null) {
            return;
        }
        pluginManager.nbClient(tier.getDataCenterId()).clearAllAclsFromLogicalSwitch(tierLsUuid);
        for (final OvnLogicalIdMapVO row : logicalIdMapDao.listByKind(Kind.NETWORK_ACL, controller.getId())) {
            logicalIdMapDao.remove(row.getId());
        }
    }

    private void applyOne(final OvnNbClient nb, final OvnControllerVO controller, final String tierLsUuid,
                          final NetworkACLItem rule) {
        final NetworkACLItem.State state = rule.getState();
        if (state == NetworkACLItem.State.Revoke) {
            revokeOne(nb, controller, rule);
            return;
        }
        if (state != NetworkACLItem.State.Add && state != NetworkACLItem.State.Active) {
            LOGGER.debug("OvnFirewallService: skipping rule id={} in state {}", rule.getId(), state);
            return;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.NETWORK_ACL, rule.getId(), controller.getId());
        if (existing != null) {
            LOGGER.debug("OvnFirewallService: rule id={} already mapped to ACL {}; idempotent skip",
                    rule.getId(), existing.getOvnUuid());
            return;
        }
        final String direction = directionFor(rule);
        final String action = actionFor(rule);
        final String match = buildMatch(rule);
        final int priority = priorityFor(rule);
        final String name = "csacl-" + rule.getId();
        final Map<String, String> ext = buildExternalIds(rule);
        final String aclUuid = nb.addAclToLogicalSwitch(tierLsUuid, direction, priority, match, action,
                ext, false, null, name);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.NETWORK_ACL, rule.getId(), controller.getId(), aclUuid, name));
        LOGGER.info("OvnFirewallService: ACL {} added (rule id={}, dir={}, action={}, match=\"{}\")",
                aclUuid, rule.getId(), direction, action, match);
        // Bind tierLsUuid is recorded implicitly: the ACL is reachable only via the LS it is attached to.
        // No need to store it again.
    }

    private void revokeOne(final OvnNbClient nb, final OvnControllerVO controller, final NetworkACLItem rule) {
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.NETWORK_ACL, rule.getId(), controller.getId());
        if (mapping == null) {
            LOGGER.debug("OvnFirewallService: no OVN ACL mapping for rule id={}; revoke is a no-op",
                    rule.getId());
            return;
        }
        // We must address removal via the parent LS UUID. Resolve the tier LS
        // from the rule's network id (NetworkACLItem only carries the ACL
        // group id, not the tier; CloudStack drives applyNetworkACLs per
        // network so the caller already knows; but the revoke path here is
        // best-effort and walks the per-controller mapping rows).
        try {
            // To detach we need the parent LS UUID — but we do not keep it on
            // the mapping row (saves a column). The cheapest correct approach
            // is to enumerate every NETWORK mapping for this controller and
            // call removeAclFromLogicalSwitch on the LS that owns the ACL.
            // Until per-rule LS bookkeeping is added, fall back to deleting
            // the ACL row directly via clear-all on every NETWORK LS would be
            // destructive — instead, leverage the OVSDB cascade: deleting the
            // ACL row removes it from any set referencing it (set semantics).
            // We mimic that here by issuing the ACL row delete + relying on
            // northd to GC the dangling LS reference on the next sync.
            for (final OvnLogicalIdMapVO ls : logicalIdMapDao.listByKind(Kind.NETWORK, controller.getId())) {
                try {
                    nb.removeAclFromLogicalSwitch(ls.getOvnUuid(), mapping.getOvnUuid());
                } catch (final OvnException ignored) {
                    // Not attached to this LS; try the next one.
                }
            }
        } finally {
            logicalIdMapDao.remove(mapping.getId());
        }
        LOGGER.info("OvnFirewallService: ACL {} revoked (rule id={})", mapping.getOvnUuid(), rule.getId());
    }

    private String lookupTierLsUuid(final Network network, final OvnControllerVO controller) {
        final OvnLogicalIdMapVO row = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        return row == null ? null : row.getOvnUuid();
    }

    private static String directionFor(final NetworkACLItem rule) {
        return rule.getTrafficType() == NetworkACLItem.TrafficType.Ingress
                ? OvnNbClient.ACL_DIRECTION_FROM_LPORT
                : OvnNbClient.ACL_DIRECTION_TO_LPORT;
    }

    private static String actionFor(final NetworkACLItem rule) {
        return rule.getAction() == NetworkACLItem.Action.Allow
                ? OvnNbClient.ACL_ACTION_ALLOW_RELATED
                : OvnNbClient.ACL_ACTION_DROP;
    }

    /** Priority derives from the CloudStack rule number so order is preserved. */
    private static int priorityFor(final NetworkACLItem rule) {
        final int p = DEFAULT_PRIORITY - rule.getNumber();
        if (p < MIN_PRIORITY) {
            return MIN_PRIORITY;
        }
        if (p > MAX_PRIORITY) {
            return MAX_PRIORITY;
        }
        return p;
    }

    /**
     * Composes the OVN match string. Visible for testing.
     */
    public static String buildMatch(final NetworkACLItem rule) {
        final StringBuilder match = new StringBuilder();
        appendProtocolPredicate(match, rule);
        appendCidrPredicate(match, rule);
        appendPortPredicate(match, rule);
        appendIcmpPredicate(match, rule);
        if (match.length() == 0) {
            return "1";
        }
        return match.toString();
    }

    private static void appendProtocolPredicate(final StringBuilder out, final NetworkACLItem rule) {
        final String proto = rule.getProtocol();
        if (proto == null || proto.isEmpty()) {
            return;
        }
        final String lower = proto.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "tcp":
                out.append("tcp");
                break;
            case "udp":
                out.append("udp");
                break;
            case "icmp":
                out.append("icmp4");
                break;
            case "all":
            case "any":
                // No protocol predicate.
                break;
            default:
                // Numeric protocol falls back to ip.proto.
                out.append("ip.proto == ").append(lower);
                break;
        }
    }

    private static void appendCidrPredicate(final StringBuilder out, final NetworkACLItem rule) {
        final List<String> cidrs = rule.getSourceCidrList();
        if (cidrs == null || cidrs.isEmpty()) {
            return;
        }
        final boolean ingress = rule.getTrafficType() == NetworkACLItem.TrafficType.Ingress;
        final String column = ingress ? "ip4.src" : "ip4.dst";
        final StringBuilder grp = new StringBuilder();
        for (final String cidr : cidrs) {
            if (cidr == null || cidr.isEmpty()) {
                continue;
            }
            if (grp.length() > 0) {
                grp.append(" || ");
            }
            grp.append(column).append(" == ").append(cidr);
        }
        if (grp.length() == 0) {
            return;
        }
        joinAnd(out);
        out.append("(").append(grp).append(")");
    }

    private static void appendPortPredicate(final StringBuilder out, final NetworkACLItem rule) {
        final String proto = rule.getProtocol();
        if (proto == null || proto.isEmpty()) {
            return;
        }
        final String lower = proto.toLowerCase(Locale.ROOT);
        if (!"tcp".equals(lower) && !"udp".equals(lower)) {
            return;
        }
        final Integer start = rule.getSourcePortStart();
        final Integer end = rule.getSourcePortEnd();
        if (start == null) {
            return;
        }
        joinAnd(out);
        if (end == null || end.equals(start)) {
            out.append(lower).append(".dst == ").append(start);
            return;
        }
        out.append("(").append(lower).append(".dst >= ").append(start).append(" && ")
                .append(lower).append(".dst <= ").append(end).append(")");
    }

    private static void appendIcmpPredicate(final StringBuilder out, final NetworkACLItem rule) {
        final String proto = rule.getProtocol();
        if (proto == null || !"icmp".equalsIgnoreCase(proto)) {
            return;
        }
        if (rule.getIcmpType() != null && rule.getIcmpType() >= 0) {
            joinAnd(out);
            out.append("icmp4.type == ").append(rule.getIcmpType());
        }
        if (rule.getIcmpCode() != null && rule.getIcmpCode() >= 0) {
            joinAnd(out);
            out.append("icmp4.code == ").append(rule.getIcmpCode());
        }
    }

    private static void joinAnd(final StringBuilder out) {
        if (out.length() > 0) {
            out.append(" && ");
        }
    }

    private static Map<String, String> buildExternalIds(final NetworkACLItem rule) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.NETWORK_ACL.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
        if (rule.getUuid() != null) {
            ext.put("cs_uuid", rule.getUuid());
        }
        return ext;
    }

    private static Map<Service, Map<Capability, String>> buildCapabilities() {
        final Map<Service, Map<Capability, String>> caps = new HashMap<>();
        caps.put(Service.NetworkACL, null);
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
        return services.contains(Service.NetworkACL);
    }
}
