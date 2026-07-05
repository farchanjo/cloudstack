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
import java.util.UUID;

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
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionDaoImpl;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;
import com.cloud.network.rules.FirewallRule;
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
 *   <li>Ingress (CloudStack {@code Ingress}) → direction {@code to-lport}
 *       (traffic arriving AT the VM from the LS).
 *   <li>Egress (CloudStack {@code Egress})   → direction {@code from-lport}
 *       (traffic leaving FROM the VM into the LS).
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

    /**
     * Very low priority for the standalone-network default-DENY baseline ACLs.
     * Any user Firewall allow rule (see {@link #priorityForFw}) sits far above
     * this, so an explicit allow always wins over the baseline drop.
     */
    public static final int FW_BASELINE_DENY_PRIORITY = 10;
    /**
     * Priority for the default-egress ALLOW override installed when a
     * standalone network's egress default policy is {@code allow}. Sits above
     * the baseline drop ({@link #FW_BASELINE_DENY_PRIORITY}) but below user
     * rules so per-rule matches still take precedence.
     */
    public static final int FW_DEFAULT_EGRESS_ALLOW_PRIORITY = 100;

    /**
     * Infrastructure (DHCP/DNS) allow-related ACLs. Above the baseline drop
     * ({@link #FW_BASELINE_DENY_PRIORITY}) and the default-egress allow so an
     * empty ruleset still lets a VM obtain a DHCP lease + resolve DNS — the OVN
     * ls_in_acl stage runs BEFORE the native DHCP/DNS responder, so a blanket
     * drop would otherwise starve the VM. Below user rules.
     */
    public static final int FW_BASELINE_INFRA_ALLOW_PRIORITY = 150;
    /** Base priority for user Firewall rules (well above the baseline). */
    private static final int FW_USER_RULE_BASE_PRIORITY = DEFAULT_PRIORITY;
    /** Span subtracted from the base to derive a stable per-rule priority. */
    private static final long FW_PRIORITY_SPAN = 1000L;

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = buildCapabilities();

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

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
            // The zone's OVN controller has been deregistered or was never registered.
            // There is no OVN state to clean up, so this is a successful no-op.
            LOGGER.info("OvnFirewallService: no OVN controller registered for zone {} — ACL cleanup is a no-op",
                    zoneId);
            return true;
        }
        final String tierLsUuid = lookupTierLsUuid(network, controller);
        if (tierLsUuid == null) {
            // The tier is in Allocated state — its OVN logical switch was never provisioned.
            // There is no ACL state to remove, so this is a successful no-op.
            LOGGER.info("OvnFirewallService: tier id={} has no OVN logical switch — ACL cleanup is a no-op",
                    network.getId());
            return true;
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
            revokeOne(nb, controller, tierLsUuid, rule);
            return;
        }
        if (state != NetworkACLItem.State.Add && state != NetworkACLItem.State.Active) {
            LOGGER.debug("OvnFirewallService: skipping rule id={} in state {}", rule.getId(), state);
            return;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.NETWORK_ACL, rule.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when ACL row was deleted out-of-band.
            if (nb.rowExistsByUuid("ACL", existing.getOvnUuid())) {
                LOGGER.debug("OvnFirewallService: rule id={} already mapped to ACL {}; idempotent skip",
                        rule.getId(), existing.getOvnUuid());
                return;
            }
            LOGGER.warn("OvnFirewallService: NETWORK_ACL mapping rule={} -> {} stale; recreating",
                    rule.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
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

    private void revokeOne(final OvnNbClient nb, final OvnControllerVO controller,
                           final String tierLsUuid, final NetworkACLItem rule) {
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.NETWORK_ACL, rule.getId(), controller.getId());
        if (mapping == null) {
            LOGGER.debug("OvnFirewallService: no OVN ACL mapping for rule id={}; revoke is a no-op",
                    rule.getId());
            return;
        }
        final String aclUuid = mapping.getOvnUuid();
        enqueueAclDeletionIfAbsent(controller, Kind.NETWORK_ACL, aclUuid, rule.getId());
        try {
            // removeAclFromLogicalSwitch issues a two-op transaction:
            //   1. mutate Logical_Switch.acls to remove the UUID from the strong-ref set
            //   2. delete the ACL row
            // This ordering is mandatory: deleting the ACL row while it is still
            // referenced by Logical_Switch.acls violates the OVSDB strong-reference
            // constraint and leaves the ACL alive in OVN NB (DEF-2 root cause).
            nb.removeAclFromLogicalSwitch(tierLsUuid, aclUuid);
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(aclUuid, Kind.NETWORK_ACL.name());
            LOGGER.info("OvnFirewallService: ACL {} propagated to OVN NB delete (rule id={})", aclUuid, rule.getId());
        } catch (final OvnException oe) {
            // Leave the mapping and the queue row so the processor retries.
            LOGGER.warn("OvnFirewallService: sync ACL delete failed for rule id={} uuid={}: {}",
                    rule.getId(), aclUuid, oe.getMessage());
        }
    }

    /**
     * Enqueues the ACL UUID into ovn_pending_deletion BEFORE the synchronous
     * NB delete so an async retry covers any controller flap mid-delete.
     * Idempotent: no-op when a live row for the same UUID+kind already exists.
     */
    private void enqueueAclDeletionIfAbsent(final OvnControllerVO controller, final Kind kind,
                                             final String aclUuid, final long csId) {
        if (pendingDeletionDao.isPendingByOvnUuid(aclUuid, kind.name())) {
            return;
        }
        final long cid = controller != null ? controller.getId() : OvnPendingDeletionDaoImpl.CONTROLLER_SENTINEL;
        final Long zoneId = controller != null ? controller.getZoneId() : null;
        final OvnPendingDeletionVO entry = new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), cid, zoneId, kind, aclUuid, csId);
        pendingDeletionDao.persist(entry);
    }

    private String lookupTierLsUuid(final Network network, final OvnControllerVO controller) {
        final OvnLogicalIdMapVO row = logicalIdMapDao.findByCsId(Kind.NETWORK, network.getId(), controller.getId());
        return row == null ? null : row.getOvnUuid();
    }

    private static String directionFor(final NetworkACLItem rule) {
        // OVN ACL direction semantics (ovn-nb(5) §ACL):
        //   from-lport = packets sent FROM a logical port (egress from VM into LS)
        //   to-lport   = packets sent TO a logical port (ingress to VM from LS)
        // CloudStack TrafficType.Ingress = inbound to VM/network, so it must
        // map to to-lport. Egress = outbound, maps to from-lport.
        return rule.getTrafficType() == NetworkACLItem.TrafficType.Ingress
                ? OvnNbClient.ACL_DIRECTION_TO_LPORT
                : OvnNbClient.ACL_DIRECTION_FROM_LPORT;
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
     * Composes the OVN match string for a VPC-tier {@link NetworkACLItem}.
     * Visible for testing.
     */
    public static String buildMatch(final NetworkACLItem rule) {
        return composeMatch(rule.getProtocol(),
                rule.getTrafficType() == NetworkACLItem.TrafficType.Ingress,
                rule.getSourceCidrList(), rule.getSourcePortStart(), rule.getSourcePortEnd(),
                rule.getIcmpType(), rule.getIcmpCode());
    }

    /**
     * Composes the OVN match string for a standalone-network {@link FirewallRule}.
     * A {@code FirewallRule} carries the same match-relevant shape as a
     * {@link NetworkACLItem} (protocol / traffic direction / source CIDR list /
     * port range / ICMP type+code), so it reuses the shared {@link #composeMatch}
     * core and produces byte-identical output. Visible for testing.
     */
    public static String buildMatch(final FirewallRule rule) {
        return composeMatch(rule.getProtocol(),
                rule.getTrafficType() == FirewallRule.TrafficType.Ingress,
                rule.getSourceCidrList(), rule.getSourcePortStart(), rule.getSourcePortEnd(),
                rule.getIcmpType(), rule.getIcmpCode());
    }

    /**
     * Shared match-composition core for both {@link NetworkACLItem} and
     * {@link FirewallRule}. Kept protocol/direction/port/ICMP semantics identical
     * to the original per-rule builders so existing ACL behaviour is unchanged.
     */
    private static String composeMatch(final String proto, final boolean ingress,
                                       final List<String> cidrs, final Integer portStart,
                                       final Integer portEnd, final Integer icmpType,
                                       final Integer icmpCode) {
        final StringBuilder match = new StringBuilder();
        appendProtocolPredicate(match, proto);
        appendCidrPredicate(match, ingress, cidrs);
        appendPortPredicate(match, proto, portStart, portEnd);
        appendIcmpPredicate(match, proto, icmpType, icmpCode);
        if (match.length() == 0) {
            return "1";
        }
        return match.toString();
    }

    private static void appendProtocolPredicate(final StringBuilder out, final String proto) {
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

    private static void appendCidrPredicate(final StringBuilder out, final boolean ingress,
                                            final List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return;
        }
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

    private static void appendPortPredicate(final StringBuilder out, final String proto,
                                            final Integer start, final Integer end) {
        if (proto == null || proto.isEmpty()) {
            return;
        }
        final String lower = proto.toLowerCase(Locale.ROOT);
        if (!"tcp".equals(lower) && !"udp".equals(lower)) {
            return;
        }
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

    private static void appendIcmpPredicate(final StringBuilder out, final String proto,
                                            final Integer icmpType, final Integer icmpCode) {
        if (proto == null || !"icmp".equalsIgnoreCase(proto)) {
            return;
        }
        if (icmpType != null && icmpType >= 0) {
            joinAnd(out);
            out.append("icmp4.type == ").append(icmpType);
        }
        if (icmpCode != null && icmpCode >= 0) {
            joinAnd(out);
            out.append("icmp4.code == ").append(icmpCode);
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

    // ==================================================================
    // Standalone Isolated network Firewall service (Phase A).
    //
    // Translates CloudStack FirewallRules into OVN ACLs on the network's
    // Logical_Switch, reusing the same LS lookup + NB ACL primitives as the
    // VPC NetworkACL path. Mappings persist under Kind.FIREWALL so they never
    // collide with the Kind.NETWORK_ACL rows.
    //
    // SECURITY: a standalone OVN Logical_Switch with an empty ACL set is wide
    // open (unlike a VR, which ships a baked default-drop iptables ruleset).
    // installDefaultDenyBaseline programs low-priority from-lport + to-lport
    // DROP ACLs so an empty ruleset denies by default; applyFirewallRules and
    // applyDefaultEgressRule both ensure the baseline exists before touching
    // any user rule.
    // ==================================================================

    /**
     * Applies a batch of standalone-network {@link FirewallRule}s to the OVN
     * Logical_Switch backing {@code network}. Ensures the default-DENY baseline
     * exists first, then adds/revokes one OVN ACL per rule (keyed by
     * {@code FirewallRule.id} under {@link Kind#FIREWALL}).
     */
    public boolean applyFirewallRules(final Network network, final List<? extends FirewallRule> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.info("OvnFirewallService: no OVN controller registered for zone {} — firewall apply is a no-op",
                    zoneId);
            return true;
        }
        final String tierLsUuid = lookupTierLsUuid(network, controller);
        if (tierLsUuid == null) {
            LOGGER.info("OvnFirewallService: network id={} has no OVN logical switch — firewall apply is a no-op",
                    network.getId());
            return true;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        // Secure-by-default: guarantee the drop baseline before any user rule.
        installDefaultDenyBaseline(nb, controller, network, tierLsUuid);
        boolean overall = true;
        for (final FirewallRule rule : rules) {
            try {
                applyOneFw(nb, controller, tierLsUuid, rule);
            } catch (final OvnException oe) {
                LOGGER.error("OvnFirewallService: failed to apply firewall rule id={}: {}",
                        rule.getId(), oe.getMessage());
                overall = false;
            }
        }
        return overall;
    }

    private void applyOneFw(final OvnNbClient nb, final OvnControllerVO controller, final String tierLsUuid,
                            final FirewallRule rule) {
        final FirewallRule.State state = rule.getState();
        if (state == FirewallRule.State.Revoke) {
            revokeOneFw(nb, controller, tierLsUuid, rule);
            return;
        }
        if (state != FirewallRule.State.Add && state != FirewallRule.State.Active) {
            LOGGER.debug("OvnFirewallService: skipping firewall rule id={} in state {}", rule.getId(), state);
            return;
        }
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.FIREWALL, rule.getId(), controller.getId());
        if (existing != null) {
            // Stale-mapping guard — recreate when the ACL row was deleted out-of-band.
            if (nb.rowExistsByUuid("ACL", existing.getOvnUuid())) {
                LOGGER.debug("OvnFirewallService: firewall rule id={} already mapped to ACL {}; idempotent skip",
                        rule.getId(), existing.getOvnUuid());
                return;
            }
            LOGGER.warn("OvnFirewallService: FIREWALL mapping rule={} -> {} stale; recreating",
                    rule.getId(), existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final String direction = directionForFw(rule);
        // CloudStack Firewall user rules are always ALLOW (no Deny action on the
        // FirewallRule contract); stateful allow-related is the canonical action.
        final String action = OvnNbClient.ACL_ACTION_ALLOW_RELATED;
        final String match = buildMatch(rule);
        final int priority = priorityForFw(rule);
        final String name = "csfw-" + rule.getId();
        final Map<String, String> ext = buildFwExternalIds(rule);
        final String aclUuid = nb.addAclToLogicalSwitch(tierLsUuid, direction, priority, match, action,
                ext, false, null, name);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.FIREWALL, rule.getId(), controller.getId(), aclUuid, name));
        LOGGER.info("OvnFirewallService: firewall ACL {} added (rule id={}, dir={}, action={}, match=\"{}\")",
                aclUuid, rule.getId(), direction, action, match);
    }

    private void revokeOneFw(final OvnNbClient nb, final OvnControllerVO controller,
                             final String tierLsUuid, final FirewallRule rule) {
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.FIREWALL, rule.getId(), controller.getId());
        if (mapping == null) {
            LOGGER.debug("OvnFirewallService: no OVN ACL mapping for firewall rule id={}; revoke is a no-op",
                    rule.getId());
            return;
        }
        final String aclUuid = mapping.getOvnUuid();
        enqueueAclDeletionIfAbsent(controller, Kind.FIREWALL, aclUuid, rule.getId());
        try {
            nb.removeAclFromLogicalSwitch(tierLsUuid, aclUuid);
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(aclUuid, Kind.FIREWALL.name());
            LOGGER.info("OvnFirewallService: firewall ACL {} propagated to OVN NB delete (rule id={})",
                    aclUuid, rule.getId());
        } catch (final OvnException oe) {
            LOGGER.warn("OvnFirewallService: sync firewall ACL delete failed for rule id={} uuid={}: {}",
                    rule.getId(), aclUuid, oe.getMessage());
        }
    }

    /**
     * Applies the System default-egress firewall rule for a standalone network,
     * mirroring {@code VirtualRouterElement.applyFWRules}. The baseline drop is
     * always ensured first; the effect of the default policy is then:
     * <ul>
     *   <li>{@code defaultAllow && add} → install (idempotent) a from-lport
     *       ALLOW-related override at {@link #FW_DEFAULT_EGRESS_ALLOW_PRIORITY}
     *       so egress is open by default.</li>
     *   <li>otherwise (default deny, or the rule is being revoked) → remove any
     *       previously-installed allow override so the low-priority baseline drop
     *       governs and egress is denied by default.</li>
     * </ul>
     */
    public boolean applyDefaultEgressRule(final Network network, final boolean defaultAllow, final boolean add) {
        final long zoneId = network.getDataCenterId();
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.info("OvnFirewallService: no OVN controller for zone {} — default-egress apply is a no-op", zoneId);
            return true;
        }
        final String tierLsUuid = lookupTierLsUuid(network, controller);
        if (tierLsUuid == null) {
            LOGGER.info("OvnFirewallService: network id={} has no OVN logical switch — default-egress apply is a no-op",
                    network.getId());
            return true;
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        installDefaultDenyBaseline(nb, controller, network, tierLsUuid);
        final long csId = defaultEgressCsId(network.getId());
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.FIREWALL, csId, controller.getId());
        if (add && defaultAllow) {
            if (existing != null && nb.rowExistsByUuid("ACL", existing.getOvnUuid())) {
                LOGGER.debug("OvnFirewallService: default-egress allow override already present for network id={}",
                        network.getId());
                return true;
            }
            if (existing != null) {
                logicalIdMapDao.remove(existing.getId());
            }
            final String name = "csfw-egress-default-allow-" + network.getId();
            final Map<String, String> ext = buildSyntheticExternalIds(network.getId(), name);
            final String aclUuid = nb.addAclToLogicalSwitch(tierLsUuid, OvnNbClient.ACL_DIRECTION_FROM_LPORT,
                    FW_DEFAULT_EGRESS_ALLOW_PRIORITY, "ip4", OvnNbClient.ACL_ACTION_ALLOW_RELATED,
                    ext, false, null, name);
            logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.FIREWALL, csId, controller.getId(), aclUuid, name));
            LOGGER.info("OvnFirewallService: default-egress ALLOW override {} installed (network id={})",
                    aclUuid, network.getId());
            return true;
        }
        // Default policy is DENY (or the default rule is being revoked): drop the
        // allow override so the baseline drop denies egress by default.
        if (existing != null) {
            try {
                nb.removeAclFromLogicalSwitch(tierLsUuid, existing.getOvnUuid());
            } catch (final OvnException oe) {
                LOGGER.warn("OvnFirewallService: failed to remove default-egress override {} for network id={}: {}",
                        existing.getOvnUuid(), network.getId(), oe.getMessage());
            }
            logicalIdMapDao.remove(existing.getId());
            LOGGER.info("OvnFirewallService: default-egress ALLOW override removed (network id={}) — baseline drop governs",
                    network.getId());
        }
        return true;
    }

    /**
     * Programs the secure-by-default baseline: a low-priority from-lport DROP
     * and a low-priority to-lport DROP ({@code match="1"}) on the network's
     * Logical_Switch, so an empty firewall ruleset denies all traffic. Both
     * ACLs are idempotent (keyed by a synthetic negative cs_id under
     * {@link Kind#FIREWALL}) and self-heal if the ACL row was deleted
     * out-of-band.
     */
    private void installDefaultDenyBaseline(final OvnNbClient nb, final OvnControllerVO controller,
                                            final Network network, final String tierLsUuid) {
        ensureBaselineDrop(nb, controller, network, tierLsUuid, OvnNbClient.ACL_DIRECTION_FROM_LPORT, true);
        ensureBaselineDrop(nb, controller, network, tierLsUuid, OvnNbClient.ACL_DIRECTION_TO_LPORT, false);
        // Infrastructure allows ABOVE the drop: OVN's ls_in_acl stage precedes
        // the native DHCP/DNS responders, so without these a VM on a firewalled
        // network never gets a lease. allow-related => the return path is
        // conntrack-permitted. ARP/ND already pass (drop is ip-scoped).
        ensureInfraAllow(nb, controller, network, tierLsUuid, OvnNbClient.ACL_DIRECTION_FROM_LPORT, 4,
                "udp && (udp.dst == 67 || udp.dst == 68)", "dhcp-egress");
        ensureInfraAllow(nb, controller, network, tierLsUuid, OvnNbClient.ACL_DIRECTION_TO_LPORT, 5,
                "udp && (udp.dst == 67 || udp.dst == 68)", "dhcp-ingress");
        ensureInfraAllow(nb, controller, network, tierLsUuid, OvnNbClient.ACL_DIRECTION_FROM_LPORT, 6,
                "udp.dst == 53 || tcp.dst == 53", "dns-egress");
    }

    /**
     * Idempotent infrastructure allow-related ACL (DHCP/DNS) at
     * {@link #FW_BASELINE_INFRA_ALLOW_PRIORITY}, keyed under Kind.FIREWALL by a
     * synthetic negative cs_id ({@link #infraCsId}) so it never collides with
     * the baseline drops or user rules. Self-heals if the OVN row was deleted
     * out-of-band.
     */
    private void ensureInfraAllow(final OvnNbClient nb, final OvnControllerVO controller, final Network network,
                                  final String tierLsUuid, final String direction, final int slot,
                                  final String match, final String label) {
        final long csId = infraCsId(network.getId(), slot);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.FIREWALL, csId, controller.getId());
        if (existing != null) {
            if (nb.rowExistsByUuid("ACL", existing.getOvnUuid())) {
                return;
            }
            logicalIdMapDao.remove(existing.getId());
        }
        final String name = "csfw-allow-" + label + "-" + network.getId();
        final Map<String, String> ext = buildSyntheticExternalIds(network.getId(), name);
        final String aclUuid = nb.addAclToLogicalSwitch(tierLsUuid, direction, FW_BASELINE_INFRA_ALLOW_PRIORITY,
                match, OvnNbClient.ACL_ACTION_ALLOW_RELATED, ext, false, null, name);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.FIREWALL, csId, controller.getId(), aclUuid, name));
        LOGGER.info("OvnFirewallService: infra-allow {} ({}) installed (network id={}, dir={})",
                aclUuid, label, network.getId(), direction);
    }

    private void ensureBaselineDrop(final OvnNbClient nb, final OvnControllerVO controller, final Network network,
                                    final String tierLsUuid, final String direction, final boolean fromLport) {
        final long csId = baselineCsId(network.getId(), fromLport);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.FIREWALL, csId, controller.getId());
        if (existing != null) {
            if (nb.rowExistsByUuid("ACL", existing.getOvnUuid())) {
                return;
            }
            LOGGER.warn("OvnFirewallService: baseline-drop mapping net={} dir={} -> {} stale; recreating",
                    network.getId(), direction, existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        final String name = "csfw-deny-" + (fromLport ? "egress-" : "ingress-") + network.getId();
        final Map<String, String> ext = buildSyntheticExternalIds(network.getId(), name);
        // Scope the drop to IP only: ARP/ND (non-IP) must pass for L2 to work,
        // and DHCP/DNS (UDP/IP) are re-permitted by the higher-priority infra
        // allows below. A blanket match="1" would black-hole ARP -> no gateway.
        final String aclUuid = nb.addAclToLogicalSwitch(tierLsUuid, direction, FW_BASELINE_DENY_PRIORITY,
                "ip4 || ip6", OvnNbClient.ACL_ACTION_DROP, ext, false, null, name);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.FIREWALL, csId, controller.getId(), aclUuid, name));
        LOGGER.info("OvnFirewallService: baseline DROP {} installed (network id={}, dir={})",
                aclUuid, network.getId(), direction);
    }

    private static String directionForFw(final FirewallRule rule) {
        // Same OVN direction semantics as the NetworkACL path: CloudStack
        // Ingress (inbound to the VM) maps to to-lport; Egress maps to from-lport.
        return rule.getTrafficType() == FirewallRule.TrafficType.Ingress
                ? OvnNbClient.ACL_DIRECTION_TO_LPORT
                : OvnNbClient.ACL_DIRECTION_FROM_LPORT;
    }

    /**
     * FirewallRule has no {@code getNumber()} (unlike NetworkACLItem), so the
     * OVN priority derives from the rule id: a stable value in
     * {@code [FW_USER_RULE_BASE_PRIORITY - FW_PRIORITY_SPAN + 1 .. FW_USER_RULE_BASE_PRIORITY]}.
     * Every user rule therefore sits far above the baseline drop and the
     * default-egress override, so an explicit allow always wins.
     */
    private static int priorityForFw(final FirewallRule rule) {
        final int offset = (int) Math.floorMod(rule.getId(), FW_PRIORITY_SPAN);
        final int p = FW_USER_RULE_BASE_PRIORITY - offset;
        if (p < MIN_PRIORITY) {
            return MIN_PRIORITY;
        }
        if (p > MAX_PRIORITY) {
            return MAX_PRIORITY;
        }
        return p;
    }

    /**
     * Synthetic NEGATIVE cs_id for a baseline-drop row, unique per network and
     * direction. Real FirewallRule ids are positive, so these never collide.
     */
    // Synthetic cs_id slots per network (spaced by 16, so slots never collide
    // across networks): 1=drop-egress, 2=drop-ingress, 3=default-egress-allow,
    // 4..=infrastructure allows. Offset by FW_SYNTHETIC_CSID_BASE (a huge value
    // real FirewallRule ids never reach) so a synthetic row never collides with
    // a user-rule row (cs_id = rule.getId()) under Kind.FIREWALL. MUST be
    // POSITIVE: the ovn_logical_id_map.cs_id column is `bigint unsigned`, so a
    // negative value is rejected with "Out of range value for column 'cs_id'".
    private static final long FW_SYNTHETIC_CSID_BASE = 9_000_000_000_000_000_000L;

    private static long baselineCsId(final long networkId, final boolean fromLport) {
        return FW_SYNTHETIC_CSID_BASE + networkId * 16 + (fromLport ? 1 : 2);
    }

    /** Synthetic cs_id for the default-egress ALLOW override row. */
    private static long defaultEgressCsId(final long networkId) {
        return FW_SYNTHETIC_CSID_BASE + networkId * 16 + 3;
    }

    /** Synthetic cs_id for an infrastructure-allow row (slot 4..15). */
    private static long infraCsId(final long networkId, final int slot) {
        return FW_SYNTHETIC_CSID_BASE + networkId * 16 + slot;
    }

    private static Map<String, String> buildFwExternalIds(final FirewallRule rule) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.FIREWALL.name());
        ext.put(OvnConstants.EXT_ID_ID, String.valueOf(rule.getId()));
        if (rule.getUuid() != null) {
            ext.put("cs_uuid", rule.getUuid());
        }
        return ext;
    }

    private static Map<String, String> buildSyntheticExternalIds(final long networkId, final String name) {
        final Map<String, String> ext = new HashMap<>();
        ext.put(OvnConstants.EXT_ID_KIND, Kind.FIREWALL.name());
        ext.put(OvnConstants.EXT_ID_ID, name);
        ext.put("cs_network_id", String.valueOf(networkId));
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
