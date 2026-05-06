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
package com.cloud.network.ovn.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.cloud.network.ovn.client.op.OvnNamedUuid;
import com.cloud.network.ovn.client.op.OvnOpFactory;
import com.cloud.network.ovn.client.op.OvnRowRef;
import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.cloud.network.ovn.client.transport.OvsdbEndpoint;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * High-level OVN Northbound client. Wraps a multi-endpoint
 * {@link OvsdbConnectionPool} and exposes the operations the network-element
 * plugin needs (LR / LRP / LS / LSP / NAT and the patch pair that binds an
 * LR to an LS).
 *
 * <p>All write operations land inside a single {@link OvnTransaction} so the
 * NB DB never sees a half-applied state. The {@code external_ids} map on
 * every entity carries the CloudStack source-of-truth ids
 * ({@code cs_kind}, {@code cs_id}) so an {@code ImportOvnVpcCmd} can later
 * adopt entities created out-of-band.
 */
public class OvnNbClient implements AutoCloseable {

    /** OVN Northbound DB name. */
    public static final String DB_NB = "OVN_Northbound";

    /** Default OVSDB connect timeout (ms). */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    /** Default socket read timeout (ms). */
    public static final int DEFAULT_SO_TIMEOUT_MS = 30_000;

    private final OvsdbConnectionPool pool;

    public OvnNbClient(final OvsdbConnectionPool pool) {
        this.pool = pool;
    }

    public static OvnNbClient fromCsv(final String csv) {
        final List<OvsdbEndpoint> endpoints = OvsdbEndpoint.parseList(csv);
        final OvsdbConnectionPool pool = new OvsdbConnectionPool(endpoints,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_SO_TIMEOUT_MS);
        return new OvnNbClient(pool);
    }

    /**
     * Cheap probe: sends a {@code list_dbs} call and verifies the live
     * endpoint advertises the OVN_Northbound database. Useful for the
     * {@code AddOvnControllerCmd} happy path so the operator gets a fast
     * error if the endpoint list is wrong.
     */
    public boolean ping() {
        try {
            final ArrayNode params = JsonNodeFactory.instance.arrayNode();
            final var dbs = pool.call("list_dbs", params);
            if (dbs == null) {
                return false;
            }
            for (int i = 0; i < dbs.size(); i++) {
                if (DB_NB.equals(dbs.get(i).asText())) {
                    return true;
                }
            }
            return false;
        } catch (final OvnException oe) {
            return false;
        }
    }

    public OvnTransaction newTransaction() {
        return new OvnTransaction(pool, DB_NB);
    }

    // ------------------------------------------------------------------
    // Logical Router operations.
    // ------------------------------------------------------------------

    public String createLogicalRouter(final String name, final Map<String, String> externalIds) {
        final OvnTransaction tx = newTransaction();
        final String namedUuid = OvnNamedUuid.next("lr");
        tx.add(buildInsertLogicalRouter(name, externalIds, namedUuid));
        return tx.commit().insertedUuid(0);
    }

    private ObjectNode buildInsertLogicalRouter(final String name, final Map<String, String> externalIds,
                                                final String namedUuid) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        return OvnOpFactory.insert("Logical_Router", namedUuid, row);
    }

    public void deleteLogicalRouter(final String uuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("Logical_Router", OvnOpFactory.whereUuid(uuid)));
        tx.commit();
    }

    public String addLogicalRouterPort(final String lrUuid, final String name, final String mac, final List<String> networks) {
        if (networks == null || networks.isEmpty()) {
            throw new OvnException("addLogicalRouterPort requires at least one network");
        }
        final String lrpNamed = OvnNamedUuid.next("lrp");
        final ObjectNode insert = buildInsertLrp(name, mac, networks, lrpNamed);
        final ObjectNode mutate = OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(lrpNamed)));
        final OvnTransaction tx = newTransaction();
        tx.add(insert).add(mutate);
        return tx.commit().insertedUuid(0);
    }

    private ObjectNode buildInsertLrp(final String name, final String mac, final List<String> networks,
                                      final String namedUuid) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        row.put("mac", mac);
        row.set("networks", stringSet(networks));
        return OvnOpFactory.insert("Logical_Router_Port", namedUuid, row);
    }

    public void deleteLogicalRouterPort(final String lrpUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid)));
        tx.commit();
    }

    // ------------------------------------------------------------------
    // Logical Switch operations.
    // ------------------------------------------------------------------

    public String createLogicalSwitch(final String name, final Map<String, String> externalIds) {
        final OvnTransaction tx = newTransaction();
        final String named = OvnNamedUuid.next("ls");
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        tx.add(OvnOpFactory.insert("Logical_Switch", named, row));
        return tx.commit().insertedUuid(0);
    }

    public void deleteLogicalSwitch(final String uuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("Logical_Switch", OvnOpFactory.whereUuid(uuid)));
        tx.commit();
    }

    public String addLogicalSwitchPort(final String lsUuid, final String name, final List<String> addresses,
                                       final String type, final Map<String, String> options) {
        final String namedLsp = OvnNamedUuid.next("lsp");
        final ObjectNode lspRow = buildLspRow(name, addresses, type, options);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Logical_Switch_Port", namedLsp, lspRow));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(namedLsp))));
        return tx.commit().insertedUuid(0);
    }

    private ObjectNode buildLspRow(final String name, final List<String> addresses, final String type,
                                   final Map<String, String> options) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        if (addresses != null && !addresses.isEmpty()) {
            row.set("addresses", stringSet(addresses));
        }
        if (type != null && !type.isEmpty()) {
            row.put("type", type);
        }
        if (options != null && !options.isEmpty()) {
            row.set("options", buildMap(options));
        }
        return row;
    }

    public void deleteLogicalSwitchPort(final String lspUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid)));
        tx.commit();
    }

    /**
     * Adds a {@code type=localnet} LSP to a logical switch (the public-side
     * bridge into a physnet). {@code tag} maps to the VLAN id (omit for an
     * untagged localnet).
     */
    public String addLocalnetPort(final String lsUuid, final String name, final Integer vlanTag, final String physnet) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        row.put("type", "localnet");
        if (vlanTag != null) {
            row.put("tag", vlanTag.intValue());
        }
        final ArrayNode addrSet = JsonNodeFactory.instance.arrayNode();
        addrSet.add("set");
        final ArrayNode unknown = JsonNodeFactory.instance.arrayNode();
        unknown.add("unknown");
        addrSet.add(unknown);
        row.set("addresses", addrSet);
        if (physnet != null && !physnet.isEmpty()) {
            row.set("options", buildMap(Map.of("network_name", physnet)));
        }
        final String named = OvnNamedUuid.next("lnport");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Logical_Switch_Port", named, row));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        return tx.commit().insertedUuid(0);
    }

    // ------------------------------------------------------------------
    // ACL operations.
    // ------------------------------------------------------------------

    /** OVN ACL direction applied at ingress to the LSP / from the guest. */
    public static final String ACL_DIRECTION_FROM_LPORT = "from-lport";
    /** OVN ACL direction applied at egress towards the LSP / to the guest. */
    public static final String ACL_DIRECTION_TO_LPORT = "to-lport";

    /** OVN ACL action: allow but skip conntrack (rare; default-deny baseline). */
    public static final String ACL_ACTION_ALLOW = "allow";
    /** OVN ACL action: stateful allow; the canonical CloudStack default. */
    public static final String ACL_ACTION_ALLOW_RELATED = "allow-related";
    /** OVN ACL action: stateless allow (no conntrack entry created). */
    public static final String ACL_ACTION_ALLOW_STATELESS = "allow-stateless";
    /** OVN ACL action: silently drop. */
    public static final String ACL_ACTION_DROP = "drop";
    /** OVN ACL action: drop and emit ICMP unreachable / TCP RST. */
    public static final String ACL_ACTION_REJECT = "reject";

    /**
     * Inserts one ACL row and links it to a logical switch in a single
     * transaction. Returns the new ACL UUID.
     *
     * @param lsUuid       parent logical switch UUID
     * @param direction    {@link #ACL_DIRECTION_FROM_LPORT} or
     *                     {@link #ACL_DIRECTION_TO_LPORT}
     * @param priority     OVN priority (0..32767, larger wins)
     * @param match        OVN match expression (see ovn-sb(5) §15)
     * @param action       OVN action (allow / allow-related /
     *                     allow-stateless / drop / reject)
     * @param externalIds  source-of-truth metadata (CloudStack rule id)
     * @param log          when {@code true} the datapath emits a hit record
     * @param severity     log severity (alert / warning / notice / info /
     *                     debug); ignored when {@code log} is false
     * @param name         optional human-readable label
     */
    public String addAclToLogicalSwitch(final String lsUuid, final String direction, final int priority,
                                        final String match, final String action,
                                        final Map<String, String> externalIds,
                                        final boolean log, final String severity, final String name) {
        final String namedAcl = OvnNamedUuid.next("acl");
        final ObjectNode aclRow = buildAclRow(direction, priority, match, action, externalIds, log, severity, name);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("ACL", namedAcl, aclRow));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "acls",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(namedAcl))));
        return tx.commit().insertedUuid(0);
    }

    private ObjectNode buildAclRow(final String direction, final int priority, final String match, final String action,
                                   final Map<String, String> externalIds, final boolean log, final String severity,
                                   final String name) {
        if (direction == null || direction.isEmpty()) {
            throw new OvnException("ACL direction is required");
        }
        if (action == null || action.isEmpty()) {
            throw new OvnException("ACL action is required");
        }
        if (match == null || match.isEmpty()) {
            throw new OvnException("ACL match is required");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("direction", direction);
        row.put("priority", priority);
        row.put("match", match);
        row.put("action", action);
        row.put("log", log);
        if (severity != null && !severity.isEmpty()) {
            row.put("severity", severity);
        }
        if (name != null && !name.isEmpty()) {
            row.put("name", name);
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        return row;
    }

    /**
     * Detaches an ACL row from the parent logical switch and deletes the row
     * itself in a single transaction.
     */
    public void removeAclFromLogicalSwitch(final String lsUuid, final String aclUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateDeleteSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "acls",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(aclUuid))));
        tx.add(OvnOpFactory.delete("ACL", OvnOpFactory.whereUuid(aclUuid)));
        tx.commit();
    }

    /**
     * Clears the {@code acls} set on the given logical switch (sets it to
     * empty). Note that orphaned ACL rows are then garbage-collected by
     * northd; for an explicit cascade delete the caller must list-then-delete.
     */
    public void clearAllAclsFromLogicalSwitch(final String lsUuid) {
        final OvnTransaction tx = newTransaction();
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        final ArrayNode emptySet = JsonNodeFactory.instance.arrayNode();
        emptySet.add("set");
        emptySet.add(JsonNodeFactory.instance.arrayNode());
        row.set("acls", emptySet);
        tx.add(OvnOpFactory.update("Logical_Switch", OvnOpFactory.whereUuid(lsUuid), row));
        tx.commit();
    }

    /**
     * Lists the ACL UUIDs currently attached to the given logical switch.
     * Returns an empty list when the switch has no ACLs.
     */
    public List<String> listAclsOnLogicalSwitch(final String lsUuid) {
        final OvnTransaction tx = newTransaction();
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("acls");
        tx.add(OvnOpFactory.select("Logical_Switch", OvnOpFactory.whereUuid(lsUuid), columns));
        final OvnTransaction.Result r = tx.commit();
        return extractUuidSet(r.raw(), 0, "acls");
    }

    private List<String> extractUuidSet(final ArrayNode replies, final int index, final String column) {
        final List<String> out = new ArrayList<>();
        if (replies == null || replies.size() <= index) {
            return out;
        }
        final var entry = replies.get(index);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null || rows.size() == 0) {
            return out;
        }
        final var col = rows.get(0).get(column);
        if (col == null || col.size() < 2) {
            return out;
        }
        // Either ["uuid", "<id>"] for a single value or ["set", [["uuid", id], ...]].
        if (col.get(0).asText().equals("uuid")) {
            out.add(col.get(1).asText());
            return out;
        }
        final var elements = col.get(1);
        if (elements == null) {
            return out;
        }
        for (int i = 0; i < elements.size(); i++) {
            final var ref = elements.get(i);
            if (ref != null && ref.size() >= 2 && "uuid".equals(ref.get(0).asText())) {
                out.add(ref.get(1).asText());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // NAT operations.
    // ------------------------------------------------------------------

    public String addNatRule(final String lrUuid, final String type, final String externalIp, final String logicalIp,
                             final String logicalPort) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("type", type);
        row.put("external_ip", externalIp);
        row.put("logical_ip", logicalIp);
        if (logicalPort != null && !logicalPort.isEmpty()) {
            row.set("logical_port", JsonNodeFactory.instance.textNode(logicalPort));
        }
        final String named = OvnNamedUuid.next("nat");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("NAT", named, row));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "nat",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        return tx.commit().insertedUuid(0);
    }

    public void deleteNatRule(final String natUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("NAT", OvnOpFactory.whereUuid(natUuid)));
        tx.commit();
    }

    // ------------------------------------------------------------------
    // Load_Balancer operations.
    // ------------------------------------------------------------------

    /** OVN load_balancer protocol values (per ovn-nb(5)). */
    public static final String LB_PROTOCOL_TCP = "tcp";
    public static final String LB_PROTOCOL_UDP = "udp";
    public static final String LB_PROTOCOL_SCTP = "sctp";

    /**
     * Inserts one {@code load_balancer} row. Returns the new UUID. The caller
     * is responsible for attaching the row to a Logical_Router or
     * Logical_Switch via {@link #attachLoadBalancerToLogicalRouter} /
     * {@link #attachLoadBalancerToLogicalSwitch}.
     *
     * @param name           human-readable name; CloudStack uses
     *                       {@code cs-lb-<rule-id>}
     * @param vips           map of {@code "vip:port"} ->
     *                       {@code "ip1:port,ip2:port,..."} (OVN format)
     * @param protocol       one of {@link #LB_PROTOCOL_TCP},
     *                       {@link #LB_PROTOCOL_UDP},
     *                       {@link #LB_PROTOCOL_SCTP}; {@code null} for
     *                       protocol-agnostic
     * @param selectionFields source-hash columns (e.g.
     *                       {@code ip4_src,ip4_dst,tcp_src,tcp_dst}); empty
     *                       or {@code null} for OVN's default round-robin
     * @param externalIds    metadata; CloudStack records the rule id here
     */
    public String createLoadBalancer(final String name, final Map<String, String> vips, final String protocol,
                                     final List<String> selectionFields, final Map<String, String> externalIds) {
        if (vips == null || vips.isEmpty()) {
            throw new OvnException("createLoadBalancer requires at least one VIP entry");
        }
        final String namedLb = OvnNamedUuid.next("lb");
        final ObjectNode row = buildLbRow(name, vips, protocol, selectionFields, externalIds);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Load_Balancer", namedLb, row));
        return tx.commit().insertedUuid(0);
    }

    private ObjectNode buildLbRow(final String name, final Map<String, String> vips, final String protocol,
                                  final List<String> selectionFields, final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        if (name != null && !name.isEmpty()) {
            row.put("name", name);
        }
        row.set("vips", buildMap(vips));
        if (protocol != null && !protocol.isEmpty()) {
            row.put("protocol", protocol);
        }
        if (selectionFields != null && !selectionFields.isEmpty()) {
            row.set("selection_fields", stringSet(selectionFields));
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        return row;
    }

    /**
     * Attaches a load_balancer row to a Logical_Router (north-south LB on
     * the VPC's gateway).
     */
    public void attachLoadBalancerToLogicalRouter(final String lrUuid, final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "load_balancer",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(lbUuid))));
        tx.commit();
    }

    /** Detaches a load_balancer row from a Logical_Router. */
    public void detachLoadBalancerFromLogicalRouter(final String lrUuid, final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateDeleteSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "load_balancer",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(lbUuid))));
        tx.commit();
    }

    /**
     * Attaches a load_balancer row to a Logical_Switch (east-west LB on a
     * tier; not used by the MVP but exposed for completeness).
     */
    public void attachLoadBalancerToLogicalSwitch(final String lsUuid, final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "load_balancer",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(lbUuid))));
        tx.commit();
    }

    /**
     * Atomically replaces the backend list for a single VIP. Other VIPs on
     * the same load_balancer are preserved by re-emitting the supplied
     * {@code allVips} map: the caller is expected to compute the desired
     * full state and pass it in (CloudStack drives the LB rule lifecycle so
     * it knows the full state).
     */
    public void updateLoadBalancerBackends(final String lbUuid, final Map<String, String> allVips) {
        if (allVips == null) {
            throw new OvnException("updateLoadBalancerBackends requires a non-null vips map");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("vips", buildMap(allVips));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Load_Balancer", OvnOpFactory.whereUuid(lbUuid), row));
        tx.commit();
    }

    /**
     * Deletes a load_balancer row. The caller is responsible for detaching
     * it from any Logical_Router / Logical_Switch first; otherwise the OVSDB
     * server emits a constraint violation.
     */
    public void deleteLoadBalancer(final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("Load_Balancer", OvnOpFactory.whereUuid(lbUuid)));
        tx.commit();
    }

    // ------------------------------------------------------------------
    // LR <-> LS patch pair.
    // ------------------------------------------------------------------

    /**
     * Connects a logical router to a logical switch via the OVN router-patch
     * pair. The LRP holds the gateway IP/MAC; the LSP on the LS side has
     * {@code type=router} and {@code addresses=router}.
     */
    public BindResult bindLrToLs(final BindRequest req) {
        final String lrpNamed = OvnNamedUuid.next("lrp");
        final String lspNamed = OvnNamedUuid.next("rsp");
        final OvnTransaction tx = newTransaction();
        tx.add(buildInsertLrp(req.lrpName, req.lrpMac, req.lrpNetworks, lrpNamed));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(req.lrUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(lrpNamed))));
        tx.add(OvnOpFactory.insert("Logical_Switch_Port", lspNamed,
                buildRouterTypeLspRow(req.lspName, req.lrpName)));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(req.lsUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(lspNamed))));
        final OvnTransaction.Result r = tx.commit();
        return new BindResult(r.insertedUuid(0), r.insertedUuid(2));
    }

    private ObjectNode buildRouterTypeLspRow(final String lspName, final String lrpName) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", lspName);
        row.put("type", "router");
        row.set("addresses", stringSet(List.of("router")));
        row.set("options", buildMap(Map.of("router-port", lrpName)));
        return row;
    }

    /** Plain-data input to {@link #bindLrToLs(BindRequest)}. */
    public static class BindRequest {
        public final String lrUuid;
        public final String lsUuid;
        public final String lrpName;
        public final String lrpMac;
        public final List<String> lrpNetworks;
        public final String lspName;

        public BindRequest(final String lrUuid, final String lsUuid, final String lrpName, final String lrpMac,
                           final List<String> lrpNetworks, final String lspName) {
            this.lrUuid = lrUuid;
            this.lsUuid = lsUuid;
            this.lrpName = lrpName;
            this.lrpMac = lrpMac;
            this.lrpNetworks = lrpNetworks;
            this.lspName = lspName;
        }
    }

    /** Plain-data output of {@link #bindLrToLs(BindRequest)}. */
    public static class BindResult {
        public final String lrpUuid;
        public final String lspUuid;

        public BindResult(final String lrpUuid, final String lspUuid) {
            this.lrpUuid = lrpUuid;
            this.lspUuid = lspUuid;
        }
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private ArrayNode stringSet(final List<String> values) {
        final ArrayNode set = JsonNodeFactory.instance.arrayNode();
        set.add("set");
        final ArrayNode elements = JsonNodeFactory.instance.arrayNode();
        for (final String v : values) {
            elements.add(v);
        }
        set.add(elements);
        return set;
    }

    private ArrayNode buildMap(final Map<String, String> entries) {
        final ArrayNode mapNode = JsonNodeFactory.instance.arrayNode();
        mapNode.add("map");
        final ArrayNode pairs = JsonNodeFactory.instance.arrayNode();
        for (final Map.Entry<String, String> e : entries.entrySet()) {
            final ArrayNode pair = JsonNodeFactory.instance.arrayNode();
            pair.add(e.getKey());
            pair.add(e.getValue());
            pairs.add(pair);
        }
        mapNode.add(pairs);
        return mapNode;
    }

    public OvsdbConnectionPool getPool() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }
}
