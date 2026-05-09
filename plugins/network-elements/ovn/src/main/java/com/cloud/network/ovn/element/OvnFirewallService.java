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
import com.cloud.network.element.NetworkACLServiceProvider;
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
        enqueueAclDeletionIfAbsent(controller, aclUuid, rule.getId());
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
    private void enqueueAclDeletionIfAbsent(final OvnControllerVO controller,
                                             final String aclUuid, final long csId) {
        if (pendingDeletionDao.isPendingByOvnUuid(aclUuid, Kind.NETWORK_ACL.name())) {
            return;
        }
        final long cid = controller != null ? controller.getId() : OvnPendingDeletionDaoImpl.CONTROLLER_SENTINEL;
        final Long zoneId = controller != null ? controller.getZoneId() : null;
        final OvnPendingDeletionVO entry = new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), cid, zoneId, Kind.NETWORK_ACL, aclUuid, csId);
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
