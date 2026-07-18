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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cloud.network.ovn.client.op.OvnNamedUuid;
import com.cloud.network.ovn.client.op.OvnOpFactory;
import com.cloud.network.ovn.client.op.OvnRowRef;
import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.cloud.network.ovn.client.transport.OvsdbEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * Replace {@code external_ids} on an existing Logical_Router in place.
     * Used by the VPC rename re-sync hook: every {@code createLogicalRouterFor}
     * invocation passes the current VPC name + cs_kind + cs_id so a CloudStack
     * {@code updateVPC} that changed the VPC name eventually shows up in
     * {@code LR.external_ids[cs_name]} on the next plugin touch (no
     * dedicated rename callback exists in {@code VpcProvider}).
     */
    public void updateLogicalRouterExternalIds(final String uuid, final Map<String, String> externalIds) {
        if (uuid == null || uuid.isEmpty() || externalIds == null) {
            return;
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("external_ids", buildMap(externalIds));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Router", OvnOpFactory.whereUuid(uuid), row));
        tx.commit();
    }

    /**
     * List every row UUID in the supplied NB table. Used by the reconciler
     * to walk a table without an external_ids predicate (e.g. find rows
     * whose external_ids map is empty / missing cs_kind).
     */
    public List<String> listAllUuids(final String table) {
        final List<String> out = new ArrayList<>();
        if (table == null || table.isEmpty()) {
            return out;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select(table, OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r;
        try {
            r = tx.commit();
        } catch (OvnException e) {
            return out;
        }
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return out;
        }
        final var entry = arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            final var uuidNode = row == null ? null : row.get("_uuid");
            if (uuidNode != null && uuidNode.size() >= 2) {
                out.add(uuidNode.get(1).asText());
            }
        }
        return out;
    }

    /**
     * List UUIDs of every row in {@code table} whose {@code external_ids}
     * map contains an entry {@code key=value}. Used by destroy paths to
     * sweep orphan rows when the local mapping has already been wiped or
     * was never persisted (e.g. earlier failed transaction). Implementation
     * walks the table client-side because OVSDB's {@code includes} predicate
     * over a typed map returns a column-indexed shape; iterating the rows
     * is robust and the affected NB tables (DHCP_Options, DNS) hold a
     * handful of rows per zone.
     */
    public List<String> findUuidsByExternalIds(final String table, final String key, final String value) {
        final List<String> out = new ArrayList<>();
        if (table == null || table.isEmpty() || key == null || value == null) {
            return out;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("external_ids");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select(table, OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r;
        try {
            r = tx.commit();
        } catch (OvnException e) {
            return out;
        }
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return out;
        }
        final var entry = arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var ext = row.get("external_ids");
            if (ext == null || ext.size() < 2 || !"map".equals(ext.get(0).asText())) {
                continue;
            }
            final var pairs = ext.get(1);
            if (pairs == null) {
                continue;
            }
            for (int j = 0; j < pairs.size(); j++) {
                final var pair = pairs.get(j);
                if (pair != null && pair.size() >= 2
                        && key.equals(pair.get(0).asText())
                        && value.equals(pair.get(1).asText())) {
                    final var uuidNode = row.get("_uuid");
                    if (uuidNode != null && uuidNode.size() >= 2) {
                        out.add(uuidNode.get(1).asText());
                    }
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Check whether a row with the given UUID still lives in the supplied
     * NB table. Used by ensure* idempotent helpers to detect stale mapping
     * rows whose underlying NB DB entity was deleted out-of-band (admin
     * intervention, prior bug, partial cleanup). When this returns false
     * the caller should drop the local mapping row and re-create the NB
     * entity so the cluster heals on the next reconcile pass.
     *
     * @param table OVN_Northbound table name (e.g. {@code Logical_Switch},
     *              {@code Logical_Router}, {@code HA_Chassis_Group}).
     * @param uuid  the row UUID to probe.
     * @return {@code true} if the row exists, {@code false} when the table
     *         lookup returns zero rows or the input is blank.
     */
    public boolean rowExistsByUuid(final String table, final String uuid) {
        if (table == null || table.isEmpty() || uuid == null || uuid.isEmpty()) {
            return false;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select(table, OvnOpFactory.whereUuid(uuid), columns));
        try {
            final OvnTransaction.Result r = tx.commit();
            final ArrayNode arr = r.raw();
            if (arr == null || arr.size() == 0) {
                return false;
            }
            final var entry = arr.get(0);
            final var rows = entry == null ? null : entry.get("rows");
            return rows != null && rows.size() > 0;
        } catch (OvnException e) {
            // Treat transport / parse errors as "unknown" -> conservative
            // false so the caller falls back to the recreate path. Logging
            // is the caller's responsibility (we keep this surface silent
            // to avoid double-logging on retries).
            return false;
        }
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

    /**
     * Replace {@code networks} (and optionally {@code mac}) of an existing
     * LRP in place. Used when a tier's gateway IP / CIDR changes — the
     * router-patch pair stays put, only the LRP's gateway prefix list is
     * rewritten so OVN northd recomputes the L3 forwarding entries on the
     * next tick. Pass {@code null} for {@code mac} to leave it untouched.
     */
    public void updateLogicalRouterPortNetworks(final String lrpUuid, final List<String> networks, final String mac) {
        if (networks == null || networks.isEmpty()) {
            throw new OvnException("updateLogicalRouterPortNetworks requires at least one network");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("networks", stringSet(networks));
        if (mac != null && !mac.isEmpty()) {
            row.put("mac", mac);
        }
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid), row));
        tx.commit();
    }

    public void deleteLogicalRouterPort(final String lrpUuid) {
        // Detach from parent Logical_Router.ports before deleting the row;
        // otherwise OVSDB rejects with "referential integrity violation".
        // Mirrors the LSP detach pattern in deleteLogicalSwitchPort.
        final String parentLrUuid = findLogicalRouterOwningPort(lrpUuid);
        final OvnTransaction tx = newTransaction();
        if (parentLrUuid != null) {
            tx.add(OvnOpFactory.mutateDeleteSet("Logical_Router",
                    OvnOpFactory.whereUuid(parentLrUuid), "ports",
                    OvnRowRef.singletonSet(OvnRowRef.realUuid(lrpUuid))));
        }
        tx.add(OvnOpFactory.delete("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid)));
        tx.commit();
    }

    /** Locate the LR row holding the supplied LRP in its {@code ports} set. */
    private String findLogicalRouterOwningPort(final String lrpUuid) {
        if (lrpUuid == null || lrpUuid.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("ports");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router", OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return null;
        }
        final var entry = arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var ports = row.get("ports");
            if (ports == null || ports.size() < 2) {
                continue;
            }
            if ("uuid".equals(ports.get(0).asText())) {
                if (lrpUuid.equals(ports.get(1).asText())) {
                    return row.get("_uuid").get(1).asText();
                }
                continue;
            }
            final var elements = ports.get(1);
            if (elements == null) {
                continue;
            }
            for (int j = 0; j < elements.size(); j++) {
                final var ref = elements.get(j);
                if (ref != null && ref.size() >= 2 && lrpUuid.equals(ref.get(1).asText())) {
                    return row.get("_uuid").get(1).asText();
                }
            }
        }
        return null;
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
        // Idempotent insert: an LSP with the same name (NIC UUID-derived)
        // can survive in OVN_Northbound when an earlier deploy attempt
        // crashed between LSP create and CloudStack-side mapping persist.
        // Pre-query by name and adopt the existing row instead of failing
        // the new transaction with "constraint violation: identical name".
        final String existing = findLogicalSwitchPortUuidByName(name);
        if (existing != null) {
            // Re-attach to the LS in case the orphan got detached
            // somehow; mutateInsertSet on a set is idempotent (OVSDB
            // semantics: adding a duplicate to a set is a no-op).
            final OvnTransaction reAttach = newTransaction();
            reAttach.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                    OvnOpFactory.whereUuid(lsUuid), "ports",
                    OvnRowRef.singletonSet(OvnRowRef.realUuid(existing))));
            reAttach.commit();
            return existing;
        }
        final String namedLsp = OvnNamedUuid.next("lsp");
        final ObjectNode lspRow = buildLspRow(name, addresses, type, options);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Logical_Switch_Port", namedLsp, lspRow));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(namedLsp))));
        return tx.commit().insertedUuid(0);
    }

    /**
     * Look up a Logical_Switch_Port UUID by its unique {@code name} column.
     * Returns {@code null} when no row matches.
     */
    public String findLogicalSwitchPortUuidByName(final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("name");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Switch_Port", OvnOpFactory.whereName(name), columns));
        final OvnTransaction.Result r = tx.commit();
        final List<String> uuids = extractUuidSet(r.raw(), 0, "_uuid");
        return uuids.isEmpty() ? null : uuids.get(0);
    }

    private ObjectNode buildLspRow(final String name, final List<String> addresses, final String type,
                                   final Map<String, String> options) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        if (addresses != null && !addresses.isEmpty()) {
            row.set("addresses", stringSet(addresses));
            row.set("port_security", stringSet(addresses));
        }
        if (type != null && !type.isEmpty()) {
            row.put("type", type);
        }
        if (options != null && !options.isEmpty()) {
            row.set("options", buildMap(options));
        }
        return row;
    }

    /**
     * Replace {@code addresses} (and {@code port_security}) of an existing
     * LSP in place. Used when a NIC's IP changes without a release+prepare
     * cycle (CloudStack {@code updateVmNicIp} surfaces this path). The LSP
     * UUID, parent LS attachment, and external_ids stay the same.
     */
    public void updateLogicalSwitchPortAddresses(final String lspUuid, final List<String> addresses) {
        if (addresses == null) {
            throw new OvnException("updateLogicalSwitchPortAddresses requires non-null addresses");
        }
        final OvnTransaction tx = newTransaction();
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("addresses", stringSet(addresses));
        // Keep port_security in lockstep with addresses (spoof-guard mirrors
        // declared addresses; otherwise OVN drops legitimate traffic when
        // the IP changes underneath the spoof rule).
        row.set("port_security", stringSet(addresses));
        tx.add(OvnOpFactory.update("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), row));
        tx.commit();
    }

    public void deleteLogicalSwitchPort(final String lspUuid) {
        // OVSDB rejects "delete LSP" while the parent Logical_Switch.ports
        // set still references the row (referential integrity violation).
        // Detach + delete in a single transaction to satisfy the strong-ref
        // contract; if no LS owns the port (already detached / orphan) the
        // mutate becomes a no-op since the set won't change.
        final String parentLsUuid = findLogicalSwitchOwningPort(lspUuid);
        final OvnTransaction tx = newTransaction();
        if (parentLsUuid != null) {
            tx.add(OvnOpFactory.mutateDeleteSet("Logical_Switch",
                    OvnOpFactory.whereUuid(parentLsUuid), "ports",
                    OvnRowRef.singletonSet(OvnRowRef.realUuid(lspUuid))));
        }
        tx.add(OvnOpFactory.delete("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid)));
        tx.commit();
    }

    /**
     * Locate the Logical_Switch row that holds the supplied LSP in its
     * {@code ports} set. Returns null when no LS references the LSP (an
     * orphan or already-detached row). The select walks the LS table once
     * — fine for typical deployments (tens of LSes per zone) and avoids
     * forcing every caller to remember the parent UUID.
     */
    private String findLogicalSwitchOwningPort(final String lspUuid) {
        if (lspUuid == null || lspUuid.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("ports");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Switch", OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return null;
        }
        final var entry = arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var ports = row.get("ports");
            if (ports == null || ports.size() < 2) {
                continue;
            }
            // ports column is ["set", [["uuid", id1], ["uuid", id2]]] or
            // ["uuid", id] when single. Walk both shapes.
            if ("uuid".equals(ports.get(0).asText())) {
                if (lspUuid.equals(ports.get(1).asText())) {
                    return row.get("_uuid").get(1).asText();
                }
                continue;
            }
            final var elements = ports.get(1);
            if (elements == null) {
                continue;
            }
            for (int j = 0; j < elements.size(); j++) {
                final var ref = elements.get(j);
                if (ref != null && ref.size() >= 2 && lspUuid.equals(ref.get(1).asText())) {
                    return row.get("_uuid").get(1).asText();
                }
            }
        }
        return null;
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

    /**
     * Set or clear the {@code tag} column on an existing Logical_Switch_Port
     * (typically a {@code type=localnet} LSP). Pass a non-null
     * {@code vlanTag} to set the VLAN; pass {@code null} to clear (encoded as
     * {@code ["set", []]}, the OVSDB representation of an empty optional
     * integer).
     *
     * <p>Used to retrofit a VLAN tag on the public-side localnet LSP after
     * the row was created untagged — auto-detection drives this when the
     * CloudStack Public network broadcastUri carries a VLAN id.
     */
    public void setLogicalSwitchPortTag(final String lspUuid, final Integer vlanTag) {
        if (lspUuid == null || lspUuid.isEmpty()) {
            throw new OvnException("setLogicalSwitchPortTag requires a non-empty LSP UUID");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        if (vlanTag == null) {
            // Empty optional integer: ["set", []].
            final ArrayNode emptySet = JsonNodeFactory.instance.arrayNode();
            emptySet.add("set");
            emptySet.add(JsonNodeFactory.instance.arrayNode());
            row.set("tag", emptySet);
        } else {
            // OVSDB accepts a bare integer when the column is "set of integer"
            // with at most 1 element and the value is present — this matches
            // the encoding used by addLocalnetPort.
            row.put("tag", vlanTag.intValue());
        }
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), row));
        tx.commit();
    }

    /**
     * Read the {@code tag} column of an existing Logical_Switch_Port. Returns
     * {@code null} when the row has no tag (untagged localnet) or does not
     * exist. Used by the reconciler to detect VLAN drift on the public
     * localnet port without pulling the full LSP row through {@link OvnNbReader}.
     */
    public Integer getLogicalSwitchPortTag(final String lspUuid) {
        if (lspUuid == null || lspUuid.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("tag");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return null;
        }
        final var rows = arr.get(0) == null ? null : arr.get(0).get("rows");
        if (rows == null || rows.size() == 0) {
            return null;
        }
        final var tag = rows.get(0).get("tag");
        if (tag == null) {
            return null;
        }
        if (tag.isInt()) {
            return tag.asInt();
        }
        // ["set", []] empty / ["set", [N]] single-element. Walk both shapes.
        if (tag.isArray() && tag.size() == 2) {
            final var inner = tag.get(1);
            if (inner != null && inner.isArray() && inner.size() == 1) {
                final var first = inner.get(0);
                if (first != null && first.isInt()) {
                    return first.asInt();
                }
            }
        }
        return null;
    }

    /**
     * Read the {@code mac} column of a Logical_Router_Port. Used by the BGP
     * redistribute path so the gateway-chassis agent can install a permanent
     * neighbour for the LRP next-hop (avoids flaky NDP on multi-LRP localnets).
     *
     * @return bare MAC string, or {@code null} when the row / column is absent
     */
    public String getLogicalRouterPortMac(final String lrpUuid) {
        if (lrpUuid == null || lrpUuid.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("mac");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid), columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return null;
        }
        final var rows = arr.get(0) == null ? null : arr.get(0).get("rows");
        if (rows == null || rows.size() == 0) {
            return null;
        }
        final var mac = rows.get(0).get("mac");
        if (mac == null || !mac.isTextual()) {
            return null;
        }
        final String text = mac.asText();
        return text == null || text.isEmpty() ? null : text;
    }

    /**
     * Read the {@code networks} column of a Logical_Router_Port (e.g.
     * {@code ["217.179.89.34/24"]}). Used by {@code OvnBgpRedistributeManager}
     * to resolve the VPC public LRP's own IP (the /32 route next-hop). Returns
     * an empty list when the row / column is absent. Handles both OVSDB set
     * shapes: a bare string (single element) and {@code ["set", [ ... ]]}.
     */
    public List<String> getLogicalRouterPortNetworks(final String lrpUuid) {
        final List<String> out = new java.util.ArrayList<>();
        if (lrpUuid == null || lrpUuid.isEmpty()) {
            return out;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("networks");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid), columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return out;
        }
        final var rows = arr.get(0) == null ? null : arr.get(0).get("rows");
        if (rows == null || rows.size() == 0) {
            return out;
        }
        final var nets = rows.get(0).get("networks");
        if (nets == null) {
            return out;
        }
        if (nets.isTextual()) {
            out.add(nets.asText());
            return out;
        }
        // ["set", ["a/24", "b/24"]] — walk the inner array.
        if (nets.isArray() && nets.size() == 2) {
            final var inner = nets.get(1);
            if (inner != null && inner.isArray()) {
                for (final var e : inner) {
                    if (e != null && e.isTextual()) {
                        out.add(e.asText());
                    }
                }
            }
        }
        return out;
    }

    /** Read a Logical_Switch_Port's UUID by name. Used by the public-localnet
     *  reconciler to look up the well-known {@code lsp-public-localnet} row
     *  on a per-zone public LS. Returns {@code null} when no row matches. */
    public String findLogicalSwitchPortUuidByExactName(final String name) {
        // Delegates to the existing finder which already returns null for
        // misses; kept as a separate method so tests targeting the public
        // localnet path can stub a more specific seam.
        return findLogicalSwitchPortUuidByName(name);
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
        return addNatRule(lrUuid, type, externalIp, logicalIp, logicalPort, null, null, null);
    }

    /**
     * Insert a NAT row with the full surface OVN exposes for hardware-friendly
     * DNAT-and-SNAT (e.g. ConnectX-6 Dx TC flower CT-NAT 5-tuple offload):
     *
     * <ul>
     *   <li>{@code external_port_range} — single port (e.g. {@code "22"}) or
     *       a port range (e.g. {@code "8080-8090"}); empty/null leaves the
     *       column unset (any-port match).</li>
     *   <li>{@code external_mac} — MAC OVN uses for proxy ARP / GARP on the
     *       distributed gateway port. Required for distributed
     *       {@code dnat_and_snat} when {@code logical_port} is set so the
     *       reply-side SNAT picks the right source MAC.</li>
     *   <li>{@code externalIds} — CloudStack source-of-truth tags
     *       ({@code cs_kind}, {@code cs_id}, {@code cs_zone_id}). Picked up
     *       by the reconciler / import flow.</li>
     * </ul>
     *
     * <p>All optional columns; {@code null} or empty leaves the corresponding
     * OVSDB column unset (matches the {@code ovn-nbctl lr-nat-add} semantics).
     */
    public String addNatRule(final String lrUuid, final String type, final String externalIp, final String logicalIp,
                             final String logicalPort, final String externalPortRange,
                             final String externalMac, final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("type", type);
        row.put("external_ip", externalIp);
        row.put("logical_ip", logicalIp);
        if (logicalPort != null && !logicalPort.isEmpty()) {
            row.set("logical_port", JsonNodeFactory.instance.textNode(logicalPort));
        }
        if (externalPortRange != null && !externalPortRange.isEmpty()) {
            row.put("external_port_range", externalPortRange);
        }
        if (externalMac != null && !externalMac.isEmpty()) {
            row.set("external_mac", JsonNodeFactory.instance.textNode(externalMac));
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("nat");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("NAT", named, row));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "nat",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        return tx.commit().insertedUuid(0);
    }

    /**
     * Replace the {@code external_ip} (and optionally {@code logical_ip}) of
     * an existing NAT row in place. Used by SourceNAT IP rotation
     * ({@link com.cloud.network.element.VpcProvider#updateVpcSourceNatIp})
     * so the SNAT row keeps the same UUID + parent reference; only the
     * external IP changes. Pass {@code null} for {@code logicalIp} to leave
     * the column untouched.
     */
    public void updateNatRule(final String natUuid, final String externalIp, final String logicalIp) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        if (externalIp != null && !externalIp.isEmpty()) {
            row.put("external_ip", externalIp);
        }
        if (logicalIp != null && !logicalIp.isEmpty()) {
            row.put("logical_ip", logicalIp);
        }
        if (row.size() == 0) {
            return;
        }
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("NAT", OvnOpFactory.whereUuid(natUuid), row));
        tx.commit();
    }

    public void deleteNatRule(final String natUuid) {
        // OVSDB rejects "delete NAT" while the parent Logical_Router.nat set
        // still references the row (referential integrity violation). Detach
        // + delete in a single transaction to satisfy the strong-ref
        // contract; if no LR owns the row (orphan / already detached) the
        // mutate becomes a no-op.
        final String parentLrUuid = findLogicalRouterOwningNat(natUuid);
        final OvnTransaction tx = newTransaction();
        if (parentLrUuid != null) {
            tx.add(OvnOpFactory.mutateDeleteSet("Logical_Router",
                    OvnOpFactory.whereUuid(parentLrUuid), "nat",
                    OvnRowRef.singletonSet(OvnRowRef.realUuid(natUuid))));
        }
        tx.add(OvnOpFactory.delete("NAT", OvnOpFactory.whereUuid(natUuid)));
        tx.commit();
    }

    /** Locate the LR row holding the supplied NAT in its {@code nat} set. */
    private String findLogicalRouterOwningNat(final String natUuid) {
        if (natUuid == null || natUuid.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("nat");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router", OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return null;
        }
        final var entry = arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var nat = row.get("nat");
            if (nat == null || nat.size() < 2) {
                continue;
            }
            if ("uuid".equals(nat.get(0).asText())) {
                if (natUuid.equals(nat.get(1).asText())) {
                    return row.get("_uuid").get(1).asText();
                }
                continue;
            }
            final var elements = nat.get(1);
            if (elements == null) {
                continue;
            }
            for (int j = 0; j < elements.size(); j++) {
                final var ref = elements.get(j);
                if (ref != null && ref.size() >= 2 && natUuid.equals(ref.get(1).asText())) {
                    return row.get("_uuid").get(1).asText();
                }
            }
        }
        return null;
    }

    /**
     * Delete legacy port-forward {@code dnat_and_snat} NAT rows that carry an
     * {@code external_port_range} — the pre-Load_Balancer PF shape. Matches on
     * {@code type} + {@code external_ip} + {@code logical_ip}, then filters on
     * an exact {@code external_port_range}. The mandatory non-empty
     * {@code external_port_range} filter keeps full-IP static-NAT rows (which
     * never set the column) untouched. Returns the number of rows removed.
     * Self-heal for deployments whose PF rules pre-date the LB migration.
     */
    public int deleteNatByMatch(final String type, final String externalIp,
                                final String externalPortRange, final String logicalIp) {
        if (externalIp == null || externalIp.isEmpty()
                || externalPortRange == null || externalPortRange.isEmpty()
                || logicalIp == null || logicalIp.isEmpty()) {
            return 0;
        }
        final List<String> uuids = findLegacyPfNatUuids(type, externalIp, externalPortRange, logicalIp);
        for (final String uuid : uuids) {
            deleteNatRule(uuid);
        }
        return uuids.size();
    }

    private List<String> findLegacyPfNatUuids(final String type, final String externalIp,
                                              final String externalPortRange, final String logicalIp) {
        final ArrayNode where = JsonNodeFactory.instance.arrayNode();
        where.add(equalityCondition("type", type));
        where.add(equalityCondition("external_ip", externalIp));
        where.add(equalityCondition("logical_ip", logicalIp));
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("external_port_range");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("NAT", where, columns));
        return matchByExternalPortRange(tx.commit().raw(), externalPortRange);
    }

    private static ArrayNode equalityCondition(final String column, final String value) {
        final ArrayNode condition = JsonNodeFactory.instance.arrayNode();
        condition.add(column);
        condition.add("==");
        condition.add(value);
        return condition;
    }

    private static List<String> matchByExternalPortRange(final ArrayNode raw, final String externalPortRange) {
        final List<String> out = new ArrayList<>();
        if (raw == null || raw.size() == 0) {
            return out;
        }
        final var entry = raw.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var epr = row.get("external_port_range");
            if (epr != null && epr.isTextual() && externalPortRange.equals(epr.asText())) {
                final var uuid = row.get("_uuid");
                if (uuid != null && uuid.size() >= 2) {
                    out.add(uuid.get(1).asText());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Address_Set operations (SNAT destination exemption).
    //
    // OVN's NAT.exempted_ext_ips column (optional single ref, RFC 7047
    // "set" wire form with 0 or 1 element) points at an Address_Set whose
    // addresses bypass a source-NAT rule for matching destinations. Used by
    // OvnSourceNatService to let guests reach specific fabric destinations
    // (e.g. BGP route reflectors) with their real address instead of the
    // VPC-wide SNAT IP.
    // ------------------------------------------------------------------

    /**
     * Look up an {@code Address_Set} UUID by its unique {@code name} column.
     * Returns {@code null} when no row matches.
     */
    public String findAddressSetUuidByName(final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Address_Set", OvnOpFactory.whereName(name), columns));
        final OvnTransaction.Result r = tx.commit();
        final List<String> uuids = extractUuidSet(r.raw(), 0, "_uuid");
        return uuids.isEmpty() ? null : uuids.get(0);
    }

    /** Reads the current {@code addresses} column of an {@code Address_Set} row. */
    public List<String> listAddressSetAddresses(final String addressSetUuid) {
        if (addressSetUuid == null || addressSetUuid.isEmpty()) {
            return new ArrayList<>();
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("addresses");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Address_Set", OvnOpFactory.whereUuid(addressSetUuid), columns));
        return extractStringSet(tx.commit().raw(), 0, "addresses");
    }

    private String createAddressSet(final String name, final List<String> addresses) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        row.set("addresses", stringSet(addresses));
        final String named = OvnNamedUuid.next("aset");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Address_Set", named, row));
        return tx.commit().insertedUuid(0);
    }

    private void updateAddressSetAddresses(final String addressSetUuid, final List<String> addresses) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("addresses", stringSet(addresses));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Address_Set", OvnOpFactory.whereUuid(addressSetUuid), row));
        tx.commit();
    }

    /**
     * Idempotent {@code Address_Set} writer: creates the set when absent,
     * rewrites {@code addresses} in place when it drifted from the desired
     * list (order-insensitive compare), and is a no-op otherwise. The
     * {@code name} column is the natural idempotency key — mirrors {@link
     * #ensureRouterPeerLsp} (name-based lookup, create on miss).
     *
     * <p><b>Naming gotcha:</b> the name MUST use underscores only. OVN's
     * match-language parser rejects a hyphen inside an address-set name
     * token (observed: {@code "Syntax error at `$rr' expecting address set
     * name"} for a set named {@code rr-snat-exempt}).
     */
    public String ensureAddressSet(final String name, final List<String> addresses) {
        final List<String> desired = addresses == null ? new ArrayList<>() : addresses;
        final String existing = findAddressSetUuidByName(name);
        if (existing == null) {
            return createAddressSet(name, desired);
        }
        final Set<String> current = new HashSet<>(listAddressSetAddresses(existing));
        if (!current.equals(new HashSet<>(desired))) {
            updateAddressSetAddresses(existing, desired);
        }
        return existing;
    }

    /**
     * Points a NAT row's {@code exempted_ext_ips} (optional single ref) at
     * the given {@code Address_Set}. Destinations in that set bypass this
     * NAT rule. Unconditional write, same pattern as {@link
     * #lrpSetHaChassisGroup}.
     */
    public void natSetExemptedExtIps(final String natUuid, final String addressSetUuid) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("exempted_ext_ips", OvnRowRef.singletonSet(OvnRowRef.realUuid(addressSetUuid)));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("NAT", OvnOpFactory.whereUuid(natUuid), row));
        tx.commit();
    }

    private List<String> extractStringSet(final ArrayNode replies, final int index, final String column) {
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
        if (col == null) {
            return out;
        }
        // Single-element sets may be sent as the bare atom (RFC 7047 §5.1).
        if (col.isTextual()) {
            out.add(col.asText());
            return out;
        }
        if (col.size() < 2 || !"set".equals(col.get(0).asText())) {
            return out;
        }
        final var elements = col.get(1);
        for (int i = 0; elements != null && i < elements.size(); i++) {
            out.add(elements.get(i).asText());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Load_Balancer operations.
    // ------------------------------------------------------------------

    /** OVN load_balancer protocol values (per ovn-nb(5)). */
    public static final String LB_PROTOCOL_TCP = "tcp";
    public static final String LB_PROTOCOL_UDP = "udp";
    public static final String LB_PROTOCOL_SCTP = "sctp";

    /** Logical_Router options key forcing SNAT of load-balanced flows. */
    public static final String LR_OPT_LB_FORCE_SNAT = "lb_force_snat_ip";

    /** ovn-nb(5) magic value: SNAT load-balanced flows to the egress LRP IP. */
    public static final String LB_FORCE_SNAT_ROUTER_IP = "router_ip";

    /**
     * Logical_Router options key that pins a router to one chassis. OVN's
     * northd ({@code northd/en-lr-nat.c}) only special-cases the
     * {@link #LB_FORCE_SNAT_ROUTER_IP} magic value when the router also has
     * {@code options:chassis} set (centralized / gateway-chassis router).
     * On a distributed router (no {@code options:chassis}) the magic value
     * is fed to {@code extract_ip_address("router_ip")}, which logs
     * {@code bad ip router_ip} and leaves forced-SNAT unset — so the magic
     * value must never be written on a distributed router.
     */
    public static final String LR_OPT_CHASSIS = "chassis";

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
        return createLoadBalancer(name, vips, protocol, selectionFields, externalIds, null);
    }

    /**
     * Overload that also writes the {@code options} column. Used to enable
     * OVN load_balancer hairpin so that a backend hitting its own VIP gets
     * SNAT'd to {@code options:hairpin_snat_ip} (typically the VIP itself),
     * preventing the kernel-loopback short-circuit and forcing the reflected
     * packet back through the LR datapath.
     *
     * @param options OVN load_balancer options (e.g.
     *                {@code hairpin_snat_ip}, {@code hairpin_orig_tuple},
     *                {@code skip_snat}); {@code null}/empty omits the column
     */
    public String createLoadBalancer(final String name, final Map<String, String> vips, final String protocol,
                                     final List<String> selectionFields, final Map<String, String> externalIds,
                                     final Map<String, String> options) {
        if (vips == null || vips.isEmpty()) {
            throw new OvnException("createLoadBalancer requires at least one VIP entry");
        }
        final String namedLb = OvnNamedUuid.next("lb");
        final ObjectNode row = buildLbRow(name, vips, protocol, selectionFields, externalIds, options);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Load_Balancer", namedLb, row));
        return tx.commit().insertedUuid(0);
    }

    private ObjectNode buildLbRow(final String name, final Map<String, String> vips, final String protocol,
                                  final List<String> selectionFields, final Map<String, String> externalIds,
                                  final Map<String, String> options) {
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
        if (options != null && !options.isEmpty()) {
            row.set("options", buildMap(options));
        }
        return row;
    }

    /**
     * Attaches a load_balancer row to a Logical_Router (north-south LB on
     * the VPC's gateway) and asserts the router-level force-SNAT option so
     * the VIP is also reachable east-west (see {@link #ensureLbForceSnat}).
     */
    public void attachLoadBalancerToLogicalRouter(final String lrUuid, final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "load_balancer",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(lbUuid))));
        tx.commit();
        ensureLbForceSnat(lrUuid);
    }

    /**
     * Ensure the LR SNATs load-balanced flows to its egress LRP IP
     * ({@code options:lb_force_snat_ip="router_ip"}, per ovn-nb(5)) <em>only
     * when the router is centralized</em>. Without it, a client on the same
     * subnet as a VIP backend receives the backend's reply directly over the
     * logical switch (src = backend IP, not the VIP) and drops it — east-west
     * VIP traffic fails 100%. The per-LB {@code hairpin_snat_ip} only covers
     * client == backend.
     *
     * <p><b>Topology gate (mandatory):</b> OVN northd
     * ({@code northd/en-lr-nat.c}) only resolves the {@code "router_ip"}
     * magic value when the router also carries {@code options:chassis} (a
     * centralized / gateway-chassis router). On a distributed router the
     * magic value is fed verbatim to {@code extract_ip_address("router_ip")},
     * which logs {@code bad ip router_ip} and leaves forced-SNAT unset — the
     * exact east-west break the option was meant to prevent. CloudStack VPC
     * logical routers are distributed by design (no {@code options:chassis}
     * is ever written by {@code OvnVpcElement.createLogicalRouterFor}), so
     * writing the magic value there is provably inert and only pollutes
     * {@code ovn-northd.log}. Centralized routers (gateway chassis, an HA
     * chassis group, or any future topology that sets {@code options:chassis})
     * keep the valid {@code router_ip} write.
     *
     * <p>Scope: affects load-balanced flows only; {@code snat} /
     * {@code dnat_and_snat} NAT rows are untouched. Idempotent — writes
     * only when the option must change. The option is deliberately NOT
     * removed on LB detach: it is inert without LBs and removal would race
     * concurrent attaches. The legacy cleanup of an invalid
     * {@code router_ip} value on a distributed router runs on the
     * {@link #ensureLbForceSnatOnRoutersWithLb} reconcile path (and here,
     * the first time an LB is attached to an already-drifted row).
     */
    public void ensureLbForceSnat(final String lrUuid) {
        final Map<String, String> options = readLogicalRouterOptions(lrUuid);
        if (options == null) {
            return;
        }
        final boolean centralized = options.containsKey(LR_OPT_CHASSIS);
        final String current = options.get(LR_OPT_LB_FORCE_SNAT);
        if (centralized) {
            // Gateway / centralized router — the magic value is valid.
            if (LB_FORCE_SNAT_ROUTER_IP.equals(current)) {
                return;
            }
            final Map<String, String> merged = new LinkedHashMap<>(options);
            merged.put(LR_OPT_LB_FORCE_SNAT, LB_FORCE_SNAT_ROUTER_IP);
            writeLogicalRouterOptions(lrUuid, merged);
            return;
        }
        // Distributed router — the magic value is inert and produces the
        // northd "bad ip router_ip" log line. Drop any legacy value left
        // behind by a prior plugin version; leave an explicit IPv4/IPv6
        // SNAT IP untouched (a caller may have set one directly via NB).
        if (!LB_FORCE_SNAT_ROUTER_IP.equals(current)) {
            return;
        }
        final Map<String, String> merged = new LinkedHashMap<>(options);
        merged.remove(LR_OPT_LB_FORCE_SNAT);
        writeLogicalRouterOptions(lrUuid, merged);
    }

    /**
     * Reads the {@code options} map of one Logical_Router. Returns
     * {@code null} when the row does not exist.
     */
    private Map<String, String> readLogicalRouterOptions(final String lrUuid) {
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("options");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router", OvnOpFactory.whereUuid(lrUuid), columns));
        final ArrayNode raw = tx.commit().raw();
        if (raw == null || raw.size() == 0) {
            return null;
        }
        final JsonNode rows = raw.get(0) == null ? null : raw.get(0).get("rows");
        if (rows == null || rows.size() == 0) {
            return null;
        }
        return OvnNbReader.decodeMap(rows.get(0).get("options"));
    }

    /**
     * Replaces the {@code options} column of one Logical_Router with the
     * supplied map. Caller is responsible for preserving any keys it did
     * not intend to touch (read-modify-write against
     * {@link #readLogicalRouterOptions}).
     */
    private void writeLogicalRouterOptions(final String lrUuid, final Map<String, String> options) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("options", buildMap(options));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Router", OvnOpFactory.whereUuid(lrUuid), row));
        tx.commit();
    }

    /**
     * Reconcile-time safety net: walks every Logical_Router carrying at
     * least one Load_Balancer and applies the topology-aware
     * {@link #ensureLbForceSnat} rule — write {@code lb_force_snat_ip=router_ip}
     * on centralized routers (where the {@code router_ip} magic value is
     * valid), and strip any stale {@code lb_force_snat_ip=router_ip} on
     * distributed routers (where northd logs {@code bad ip router_ip} and
     * leaves forced-SNAT unset). Attach-time enforcement covers new LBs;
     * this covers routers whose LBs pre-date the topology gate. Returns
     * the number of routers fixed.
     */
    public int ensureLbForceSnatOnRoutersWithLb() {
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("load_balancer");
        columns.add("options");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router", OvnOpFactory.whereAll(), columns));
        final ArrayNode raw = tx.commit().raw();
        if (raw == null || raw.size() == 0) {
            return 0;
        }
        final JsonNode rows = raw.get(0) == null ? null : raw.get(0).get("rows");
        if (rows == null) {
            return 0;
        }
        int fixed = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (fixLrForceSnatIfNeeded(rows.get(i))) {
                fixed++;
            }
        }
        return fixed;
    }

    /**
     * One row of {@link #ensureLbForceSnatOnRoutersWithLb}: classify by
     * topology, return {@code true} when a write was needed. The
     * centralized case needs a write whenever the option is missing or set
     * to something other than the magic value; the distributed case needs a
     * write only when a stale legacy {@code router_ip} magic value is
     * present (any other value is left to its owner).
     */
    private boolean fixLrForceSnatIfNeeded(final JsonNode row) {
        if (row == null || OvnNbReader.decodeUuidSet(row.get("load_balancer")).isEmpty()) {
            return false;
        }
        final Map<String, String> options = OvnNbReader.decodeMap(row.get("options"));
        final boolean centralized = options.containsKey(LR_OPT_CHASSIS);
        final String current = options.get(LR_OPT_LB_FORCE_SNAT);
        final boolean needsWrite = centralized
                ? !LB_FORCE_SNAT_ROUTER_IP.equals(current)
                : LB_FORCE_SNAT_ROUTER_IP.equals(current);
        if (!needsWrite) {
            return false;
        }
        ensureLbForceSnat(OvnNbReader.decodeUuidColumn(row, "_uuid"));
        return true;
    }

    /**
     * Lists Load_Balancer UUIDs currently attached to a Logical_Router.
     * Used by the VPC delete cascade to detach LB weak refs before dropping
     * the LR (OVSDB does not cascade weak refs on parent delete).
     */
    public List<String> listLoadBalancersOnLogicalRouter(final String lrUuid) {
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("load_balancer");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router", OvnOpFactory.whereUuid(lrUuid), columns));
        final OvnTransaction.Result r = tx.commit();
        return extractUuidSet(r.raw(), 0, "load_balancer");
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
     * Attaches a load_balancer row to a Logical_Switch. LS-attached LBs run
     * {@code ct_lb} in the {@code ls_in_lb} stage of the SOURCE chassis —
     * fully distributed and symmetric — so a guest on the tier can reach the
     * VIP (east-west) without router-centralized NAT. Idempotent (OVSDB set
     * insert of a present element is a no-op).
     */
    public void attachLoadBalancerToLogicalSwitch(final String lsUuid, final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "load_balancer",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(lbUuid))));
        tx.commit();
    }

    /** Detaches a load_balancer row from a Logical_Switch. */
    public void detachLoadBalancerFromLogicalSwitch(final String lsUuid, final String lbUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateDeleteSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "load_balancer",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(lbUuid))));
        tx.commit();
    }

    /**
     * Adds a {@code Load_Balancer_Health_Check} row for one VIP and writes
     * the LB's {@code ip_port_mappings} in a single transaction (per
     * ovn-nb(5) both are required for the service monitor to probe
     * backends). Dead backends are then excluded from rotation instead of
     * blackholing new connections. The health-check row is garbage-collected
     * by OVSDB when its {@code health_check} reference goes away with the LB
     * — no explicit delete needed on revoke.
     *
     * @param vip            must exactly match one key of the LB's
     *                       {@code vips} map (e.g. {@code "ip:port"})
     * @param ipPortMappings backend IP -> {@code "<lsp-name>:<source-ip>"};
     *                       the source IP must be an otherwise unused
     *                       address on the backend's logical switch
     * @param hcOptions      {@code interval} / {@code timeout} /
     *                       {@code success_count} / {@code failure_count}
     */
    public void configureLoadBalancerHealthCheck(final String lbUuid, final String vip,
                                                 final Map<String, String> ipPortMappings,
                                                 final Map<String, String> hcOptions,
                                                 final Map<String, String> externalIds) {
        if (ipPortMappings == null || ipPortMappings.isEmpty()) {
            throw new OvnException("configureLoadBalancerHealthCheck requires ip_port_mappings");
        }
        final ObjectNode hcRow = JsonNodeFactory.instance.objectNode();
        hcRow.put("vip", vip);
        if (hcOptions != null && !hcOptions.isEmpty()) {
            hcRow.set("options", buildMap(hcOptions));
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            hcRow.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("hc");
        final ObjectNode lbRow = JsonNodeFactory.instance.objectNode();
        lbRow.set("ip_port_mappings", buildMap(ipPortMappings));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Load_Balancer_Health_Check", named, hcRow));
        tx.add(OvnOpFactory.mutateInsertSet("Load_Balancer", OvnOpFactory.whereUuid(lbUuid),
                "health_check", OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        tx.add(OvnOpFactory.update("Load_Balancer", OvnOpFactory.whereUuid(lbUuid), lbRow));
        tx.commit();
    }

    /** True when the LB already carries at least one health_check row. */
    public boolean loadBalancerHasHealthCheck(final String lbUuid) {
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("health_check");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Load_Balancer", OvnOpFactory.whereUuid(lbUuid), columns));
        return !extractUuidSet(tx.commit().raw(), 0, "health_check").isEmpty();
    }

    /**
     * Fully replaces {@code Load_Balancer.ip_port_mappings} with the supplied
     * map (desired state only — never merge). An empty map clears every
     * mapping so removed backends cannot leave stale LSP probe entries.
     *
     * <p>Callers must rebuild the map from the current live destinations on
     * every apply/update; a merge-style update would retain deleted member
     * IPs after destroy+recreate chaos and leave health checks targeting
     * dead LSPs while missing the new member.
     */
    public void updateLoadBalancerIpPortMappings(final String lbUuid, final Map<String, String> ipPortMappings) {
        if (lbUuid == null || lbUuid.isEmpty()) {
            throw new OvnException("updateLoadBalancerIpPortMappings requires a non-null lbUuid");
        }
        if (ipPortMappings == null) {
            throw new OvnException("updateLoadBalancerIpPortMappings requires a non-null ip_port_mappings map");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("ip_port_mappings", buildMap(ipPortMappings));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Load_Balancer", OvnOpFactory.whereUuid(lbUuid), row));
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
     * Atomically updates {@code selection_fields} and {@code external_ids} on an
     * existing {@code Load_Balancer} row without touching {@code vips}.
     *
     * <p>Called by {@link com.cloud.network.ovn.element.OvnLoadBalancerService}
     * when an operator changes the load-balancing algorithm via
     * {@code updateLoadBalancerRule} — a change that does not alter backends but
     * must be reflected in OVN immediately. A {@code null} or empty
     * {@code selectionFields} list signals "round-robin" (OVN default) and
     * causes the column to be set to the empty OVSDB set.
     *
     * <p><b>Side-effect</b>: changing {@code selection_fields} flushes OVN
     * conntrack state for that VIP, rescheduling any in-flight connections.
     * This is acceptable for an admin-driven algorithm update.
     */
    public void updateLoadBalancerProperties(final String lbUuid, final List<String> selectionFields,
                                             final Map<String, String> externalIds) {
        if (lbUuid == null || lbUuid.isEmpty()) {
            throw new OvnException("updateLoadBalancerProperties requires a non-null lbUuid");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("selection_fields", stringSet(selectionFields == null ? List.of() : selectionFields));
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
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
        return bindLrToLs(req, null);
    }

    /**
     * Same as {@link #bindLrToLs(BindRequest)} but also tags the router-type
     * peer LSP with {@code external_ids}, so a caller (e.g.
     * {@link com.cloud.network.ovn.element.OvnPublicNetworkManager#bindVpcToPublic})
     * can make the row classifiable by {@code OvnReconcilerService} instead of
     * leaving it invisible to orphan detection.
     *
     * @param lspExternalIds external_ids map for the peer LSP row, or
     *                       {@code null}/empty to omit the column entirely
     *                       (identical to {@link #bindLrToLs(BindRequest)}).
     */
    public BindResult bindLrToLs(final BindRequest req, final Map<String, String> lspExternalIds) {
        final String lrpNamed = OvnNamedUuid.next("lrp");
        final String lspNamed = OvnNamedUuid.next("rsp");
        final OvnTransaction tx = newTransaction();
        tx.add(buildInsertLrp(req.lrpName, req.lrpMac, req.lrpNetworks, lrpNamed));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(req.lrUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(lrpNamed))));
        tx.add(OvnOpFactory.insert("Logical_Switch_Port", lspNamed,
                buildRouterTypeLspRow(req.lspName, req.lrpName, lspExternalIds)));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(req.lsUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(lspNamed))));
        final OvnTransaction.Result r = tx.commit();
        return new BindResult(r.insertedUuid(0), r.insertedUuid(2));
    }

    private ObjectNode buildRouterTypeLspRow(final String lspName, final String lrpName) {
        return buildRouterTypeLspRow(lspName, lrpName, null);
    }

    private ObjectNode buildRouterTypeLspRow(final String lspName, final String lrpName,
                                             final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", lspName);
        row.put("type", "router");
        row.set("addresses", stringSet(List.of("router")));
        row.set("options", buildMap(Map.of("router-port", lrpName)));
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        return row;
    }

    /**
     * Self-healing helper: ensures a {@code type=router} LSP with
     * {@code options:router-port=<lrpName>} exists on the given
     * Logical_Switch and is listed in its {@code ports} set.
     *
     * <p>This closes the gap where a prior buggy deploy path (or manual
     * {@code ovn-nbctl} edit) created the LRP on the router side but omitted
     * or later lost the matching peer LSP on the switch side, leaving
     * {@code Logical_Router_Port.peer = []} and breaking inter-tier L3
     * forwarding for the VPC.
     *
     * <p>Idempotent: if an LSP whose {@code name} matches {@code lspName}
     * already exists it is adopted (and re-attached to the LS when detached)
     * without creating a duplicate row — identical to the pattern used by
     * {@link #addLogicalSwitchPort}.
     *
     * @param lsUuid  UUID of the tier {@code Logical_Switch}
     * @param lspName name for the peer LSP (convention: {@code rsp-<tierUUID>})
     * @param lrpName name of the LRP this LSP must peer with
     *                ({@code options:router-port})
     * @return UUID of the (existing or newly created) peer LSP
     */
    public String ensureRouterPeerLsp(final String lsUuid, final String lspName, final String lrpName) {
        final String existing = findLogicalSwitchPortUuidByName(lspName);
        if (existing != null) {
            final OvnTransaction reAttach = newTransaction();
            reAttach.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                    OvnOpFactory.whereUuid(lsUuid), "ports",
                    OvnRowRef.singletonSet(OvnRowRef.realUuid(existing))));
            reAttach.commit();
            return existing;
        }
        final String lspNamed = OvnNamedUuid.next("rsp");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Logical_Switch_Port", lspNamed,
                buildRouterTypeLspRow(lspName, lrpName)));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "ports",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(lspNamed))));
        return tx.commit().insertedUuid(0);
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
    // DHCP_Options operations.
    // ------------------------------------------------------------------

    /**
     * Creates a {@code DHCP_Options} row keyed by the tier CIDR. The
     * {@code options} column carries DHCPv4 fields the
     * {@code ovn-controller} agent injects directly into the OF pipeline
     * (no dnsmasq/dhcpd; replies are stamped by OVN itself).
     *
     * <p>Required keys per ovn-nb(5) §DHCP_Options:
     * <ul>
     *   <li>{@code server_id}    — 4-byte IP shown as DHCP server identifier
     *   <li>{@code server_mac}   — MAC OVN replies from
     *   <li>{@code lease_time}   — seconds (string)
     *   <li>{@code router}       — default gateway (option 3)
     * </ul>
     * Optional: {@code dns_server} (option 6), {@code mtu} (option 26),
     * {@code domain_name} (option 15), {@code classless_static_route}
     * (option 121).
     *
     * @param cidr        tier CIDR ({@code 10.101.0.0/24}) — used as the row key
     * @param options     option map (string→string per OVN schema)
     * @param externalIds CloudStack metadata
     * @return new DHCP_Options UUID
     */
    public String createDhcpOptions(final String cidr, final Map<String, String> options,
                                    final Map<String, String> externalIds) {
        if (cidr == null || cidr.isEmpty()) {
            throw new OvnException("createDhcpOptions requires a CIDR");
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("cidr", cidr);
        if (options != null && !options.isEmpty()) {
            row.set("options", buildMap(options));
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("dhcp");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("DHCP_Options", named, row));
        return tx.commit().insertedUuid(0);
    }

    /**
     * Replaces the {@code options} map on a DHCP_Options row. Use to bump the
     * lease time, change DNS, or add a new option without recreating the row.
     */
    public void updateDhcpOptions(final String dhcpOptUuid, final Map<String, String> options) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("options", buildMap(options == null ? Map.of() : options));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("DHCP_Options", OvnOpFactory.whereUuid(dhcpOptUuid), row));
        tx.commit();
    }

    /** Removes a DHCP_Options row. Detach from any LSP first. */
    public void deleteDhcpOptions(final String dhcpOptUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("DHCP_Options", OvnOpFactory.whereUuid(dhcpOptUuid)));
        tx.commit();
    }

    /**
     * Pins a DHCP_Options row on an LSP's {@code dhcpv4_options} column.
     * Replies for that NIC then come from OVN's distributed DHCP responder.
     */
    public void lspSetDhcpv4Options(final String lspUuid, final String dhcpOptUuid) {
        setLspSingleUuidColumn(lspUuid, "dhcpv4_options", dhcpOptUuid);
    }

    /** IPv6 counterpart of {@link #lspSetDhcpv4Options}. */
    public void lspSetDhcpv6Options(final String lspUuid, final String dhcpOptUuid) {
        setLspSingleUuidColumn(lspUuid, "dhcpv6_options", dhcpOptUuid);
    }

    /** Clears the {@code dhcpv4_options} pin on an LSP. */
    public void lspClearDhcpv4Options(final String lspUuid) {
        clearLspSetColumn(lspUuid, "dhcpv4_options");
    }

    /** Clears the {@code dhcpv6_options} pin on an LSP. */
    public void lspClearDhcpv6Options(final String lspUuid) {
        clearLspSetColumn(lspUuid, "dhcpv6_options");
    }

    /**
     * Stamps the {@code port_security} column on an LSP. OVN's pipeline
     * filters traffic to/from the port to the supplied {@code "<mac> [<ip>]"}
     * strings — the spoofing-protection contract that lets OVN safely export
     * a tenant LSP straight onto the integration bridge.
     */
    public void lspSetPortSecurity(final String lspUuid, final List<String> portSecurity) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("port_security", stringSet(portSecurity == null ? List.of() : portSecurity));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), row));
        tx.commit();
    }

    /**
     * Merges the supplied key/value pairs into the {@code options} column of an
     * existing {@code Logical_Switch_Port} row. Pre-existing options not present
     * in the given map are left untouched (OVSDB map-mutate semantics). Use
     * after {@link #addLogicalSwitchPort} to set {@code requested-chassis},
     * {@code arp_proxy}, or other per-port options that are not known at row
     * creation time.
     *
     * @param lspUuid LSP row UUID
     * @param options entries to merge (must be non-null and non-empty)
     */
    public void lspSetOptions(final String lspUuid, final Map<String, String> options) {
        if (lspUuid == null || lspUuid.isEmpty() || options == null || options.isEmpty()) {
            return;
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("options", buildMap(options));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), row));
        tx.commit();
    }

    private void setLspSingleUuidColumn(final String lspUuid, final String column, final String targetUuid) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set(column, OvnRowRef.singletonSet(OvnRowRef.realUuid(targetUuid)));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), row));
        tx.commit();
    }

    private void clearLspSetColumn(final String lspUuid, final String column) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        final ArrayNode emptySet = JsonNodeFactory.instance.arrayNode();
        emptySet.add("set");
        emptySet.add(JsonNodeFactory.instance.arrayNode());
        row.set(column, emptySet);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Switch_Port", OvnOpFactory.whereUuid(lspUuid), row));
        tx.commit();
    }

    // ------------------------------------------------------------------
    // DNS operations.
    // ------------------------------------------------------------------

    /**
     * Inserts a {@code DNS} row and attaches it to the given Logical_Switch.
     * The {@code records} map holds {@code hostname → "ip[ ip2 ...]"}; OVN's
     * pipeline answers DNS queries for any local LSP from this map.
     */
    public String createDnsRecords(final String lsUuid, final Map<String, String> records,
                                   final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("records", buildMap(records == null ? Map.of() : records));
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("dns");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("DNS", named, row));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "dns_records",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        return tx.commit().insertedUuid(0);
    }

    /**
     * Replaces the {@code records} map on an existing DNS row. Useful when
     * the manager-side aggregates all hostnames per tier and pushes a single
     * authoritative snapshot.
     */
    public void updateDnsRecords(final String dnsUuid, final Map<String, String> records) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("records", buildMap(records == null ? Map.of() : records));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("DNS", OvnOpFactory.whereUuid(dnsUuid), row));
        tx.commit();
    }

    /** Read the authoritative records column immediately before a DNS update. */
    public Map<String, String> readDnsRecords(final String dnsUuid) {
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("records");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("DNS", OvnOpFactory.whereUuid(dnsUuid), columns));
        final ArrayNode raw = tx.commit().raw();
        if (raw == null || raw.size() == 0 || raw.get(0).get("rows") == null
                || raw.get(0).get("rows").size() == 0) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(OvnNbReader.decodeMap(raw.get(0).get("rows").get(0).get("records")));
    }

    /** Atomically inserts/replaces one DNS map entry without a JVM snapshot. */
    public void mutateDnsRecord(final String dnsUuid, final String hostname, final String address) {
        final ArrayNode pair = JsonNodeFactory.instance.arrayNode();
        pair.add(hostname);
        pair.add(address);
        final ArrayNode pairs = JsonNodeFactory.instance.arrayNode().add(pair);
        final ArrayNode map = JsonNodeFactory.instance.arrayNode().add("map").add(pairs);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateMap("DNS", OvnOpFactory.whereUuid(dnsUuid), "records", "insert", map));
        tx.commit();
    }

    /** Atomically deletes DNS map keys; concurrent writers are not lost. */
    public void removeDnsRecordKeys(final String dnsUuid, final List<String> hostnames) {
        if (hostnames == null || hostnames.isEmpty()) return;
        final ArrayNode keys = JsonNodeFactory.instance.arrayNode();
        hostnames.forEach(keys::add);
        final ArrayNode keySet = JsonNodeFactory.instance.arrayNode().add("set").add(keys);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateMap("DNS", OvnOpFactory.whereUuid(dnsUuid), "records", "delete", keySet));
        tx.commit();
    }

    /** Detaches a DNS row from a switch and deletes the row. */
    public void deleteDnsRecords(final String lsUuid, final String dnsUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateDeleteSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "dns_records",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(dnsUuid))));
        tx.add(OvnOpFactory.delete("DNS", OvnOpFactory.whereUuid(dnsUuid)));
        tx.commit();
    }

    /**
     * Direct delete of a DNS row when the owning Logical_Switch is unknown
     * (typical orphan path: parent LS already cascaded out, the DNS row
     * lingered with no remaining strong-ref). Skips the LS detach step in
     * {@link #deleteDnsRecords} which would fail OVSDB without a live
     * lsUuid.
     */
    public void deleteDnsRowDirect(final String dnsUuid) {
        if (dnsUuid == null || dnsUuid.isEmpty()) {
            return;
        }
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("DNS", OvnOpFactory.whereUuid(dnsUuid)));
        tx.commit();
    }

    // ------------------------------------------------------------------
    // QoS operations (rate-limit / dscp).
    // ------------------------------------------------------------------

    /** OVN QoS direction towards the LSP's input (egress from the VM). */
    public static final String QOS_DIRECTION_FROM_LPORT = "from-lport";
    /** OVN QoS direction towards the LSP's output (ingress to the VM). */
    public static final String QOS_DIRECTION_TO_LPORT = "to-lport";

    /**
     * Inserts a {@code QoS} row attached to a Logical_Switch and matched by
     * an OVN match expression. {@code bandwidth} carries
     * {@code rate} / {@code burst} (kbps); {@code action} carries
     * {@code dscp} when DSCP marking is desired.
     */
    public String addQosToLogicalSwitch(final String lsUuid, final String direction, final int priority,
                                        final String match, final Map<String, Integer> bandwidth,
                                        final Map<String, Integer> action,
                                        final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("direction", direction);
        row.put("priority", priority);
        row.put("match", match == null ? "1" : match);
        if (bandwidth != null && !bandwidth.isEmpty()) {
            row.set("bandwidth", buildIntMap(bandwidth));
        }
        if (action != null && !action.isEmpty()) {
            row.set("action", buildIntMap(action));
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("qos");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("QoS", named, row));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "qos_rules",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        return tx.commit().insertedUuid(0);
    }

    /** Detaches and deletes a QoS row. */
    public void removeQosFromLogicalSwitch(final String lsUuid, final String qosUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateDeleteSet("Logical_Switch",
                OvnOpFactory.whereUuid(lsUuid), "qos_rules",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(qosUuid))));
        tx.add(OvnOpFactory.delete("QoS", OvnOpFactory.whereUuid(qosUuid)));
        tx.commit();
    }

    /**
     * Direct delete of a QoS row when the owning Logical_Switch mapping is
     * unknown (typical orphan path: the tier LS mapping died first, so the
     * parent {@code lsUuid} required by {@link #removeQosFromLogicalSwitch}
     * is no longer resolvable). Skips the LS detach step; OVSDB GCs any
     * dangling {@code Logical_Switch.qos_rules} reference on the next
     * northd sync.
     */
    public void deleteQosRowDirect(final String qosUuid) {
        if (qosUuid == null || qosUuid.isEmpty()) {
            return;
        }
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("QoS", OvnOpFactory.whereUuid(qosUuid)));
        tx.commit();
    }

    // ------------------------------------------------------------------
    // Logical_Router_Static_Route operations.
    // ------------------------------------------------------------------

    /**
     * Inserts a static route on an LR. {@code prefix} is the destination CIDR
     * ({@code 0.0.0.0/0} for default), {@code nexthop} is the next-hop IP
     * (or special token {@code "discard"} for a black-hole), and
     * {@code outputPort} optionally pins egress to a specific LRP name.
     *
     * <p>{@code policy} can be {@code "src-ip"} or {@code "dst-ip"} to
     * indicate which side OVN matches; default is {@code dst-ip}.
     */
    public String addLogicalRouterStaticRoute(final String lrUuid, final String prefix, final String nexthop,
                                              final String outputPort, final String policy,
                                              final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("ip_prefix", prefix);
        row.put("nexthop", nexthop);
        if (outputPort != null && !outputPort.isEmpty()) {
            row.set("output_port", JsonNodeFactory.instance.textNode(outputPort));
        }
        if (policy != null && !policy.isEmpty()) {
            row.put("policy", policy);
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("lrsr");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("Logical_Router_Static_Route", named, row));
        tx.add(OvnOpFactory.mutateInsertSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "static_routes",
                OvnRowRef.singletonSet(OvnRowRef.namedUuid(named))));
        return tx.commit().insertedUuid(0);
    }

    /** Detach + delete a static route row. */
    public void deleteLogicalRouterStaticRoute(final String lrUuid, final String routeUuid) {
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.mutateDeleteSet("Logical_Router",
                OvnOpFactory.whereUuid(lrUuid), "static_routes",
                OvnRowRef.singletonSet(OvnRowRef.realUuid(routeUuid))));
        tx.add(OvnOpFactory.delete("Logical_Router_Static_Route", OvnOpFactory.whereUuid(routeUuid)));
        tx.commit();
    }

    /**
     * Delete a {@code Logical_Router_Static_Route} row by UUID when the caller
     * does not know (or no longer has) the parent LR UUID — used by the ECMP
     * reconciler's stale-row prune path and the pending-deletion processor.
     *
     * <p>{@code Logical_Router.static_routes} is a <em>strong</em> reference set,
     * so a bare row delete is rejected by OVSDB with a referential-integrity
     * violation ({@code cannot delete ... because of N remaining reference(s)})
     * for as long as any router still lists the row — OVSDB does <em>not</em> GC
     * a strongly-referenced row. This method therefore first discovers the owning
     * LR ({@link #findLrForStaticRoute}) and detaches + deletes atomically
     * ({@link #deleteLogicalRouterStaticRoute}); only when no LR references the
     * row is a direct single-op delete issued (row already detached / orphaned).
     * Idempotent — a blank UUID or an already-gone row is a no-op.
     */
    public void deleteLogicalRouterStaticRouteDirect(final String routeUuid) {
        if (routeUuid == null || routeUuid.isEmpty()) {
            return;
        }
        final String lrUuid = findLrForStaticRoute(routeUuid);
        if (lrUuid != null) {
            deleteLogicalRouterStaticRoute(lrUuid, routeUuid);
            return;
        }
        // Not referenced by any LR — delete directly (idempotent).
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.delete("Logical_Router_Static_Route", OvnOpFactory.whereUuid(routeUuid)));
        tx.commit();
    }

    /**
     * Returns the UUID of the {@code Logical_Router} that holds the given static
     * route UUID in its {@code static_routes} set, or {@code null} when no router
     * references it (already detached or the row itself does not exist). Mirrors
     * {@link #findLsForAcl} for the LR/static-route strong reference.
     */
    String findLrForStaticRoute(final String routeUuid) {
        final OvnTransaction tx = newTransaction();
        final ArrayNode where = JsonNodeFactory.instance.arrayNode();
        final ArrayNode condition = JsonNodeFactory.instance.arrayNode();
        condition.add("static_routes");
        condition.add("includes");
        condition.add(OvnRowRef.realUuid(routeUuid));
        where.add(condition);
        final ArrayNode cols = JsonNodeFactory.instance.arrayNode();
        cols.add("_uuid");
        tx.add(OvnOpFactory.select("Logical_Router", where, cols));
        final OvnTransaction.Result r = tx.commit();
        final List<String> uuids = r.selectedUuids(0);
        return uuids.isEmpty() ? null : uuids.get(0);
    }

    /**
     * List every {@code Logical_Router_Static_Route} row whose
     * {@code external_ids} carries the supplied marker key, returning its UUID,
     * destination prefix, next-hop, and the marker value (the owning entity id).
     * Used by the ECMP static-route reconciler to diff desired routes against
     * the plugin-owned rows currently in the NB DB — never touching manual or
     * other-purpose static routes, which lack the marker.
     *
     * <p>Walks the table client-side (same rationale as
     * {@link #findUuidsByExternalIds}): the affected row count per zone is small
     * and the {@code includes}-over-typed-map predicate returns an awkward
     * column-indexed shape.
     *
     * @param markerKey the {@code external_ids} key that tags an owned row
     * @return owned route descriptors, never {@code null}
     */
    public List<EcmpStaticRoute> listEcmpStaticRoutes(final String markerKey) {
        final List<EcmpStaticRoute> out = new ArrayList<>();
        if (markerKey == null || markerKey.isEmpty()) {
            return out;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("ip_prefix");
        columns.add("nexthop");
        columns.add("external_ids");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Logical_Router_Static_Route", OvnOpFactory.whereAll(), columns));
        // Fail closed: do not return an empty list on transport/OVSDB errors —
        // callers would treat "no owned routes" as truth and skip converge or
        // incorrectly report success. Propagate OvnException.
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        final var entry = arr == null || arr.size() == 0 ? null : arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            addOwnedRoute(out, rows.get(i), markerKey);
        }
        return out;
    }

    /** Decode one static-route row into an {@link EcmpStaticRoute} when it
     *  carries {@code markerKey} in its {@code external_ids}; otherwise skip. */
    private void addOwnedRoute(final List<EcmpStaticRoute> out, final JsonNode row, final String markerKey) {
        if (row == null) {
            return;
        }
        final String owner = mapValue(row.get("external_ids"), markerKey);
        if (owner == null) {
            return;
        }
        final var uuidNode = row.get("_uuid");
        if (uuidNode == null || uuidNode.size() < 2) {
            return;
        }
        final JsonNode prefixNode = row.get("ip_prefix");
        final JsonNode nexthopNode = row.get("nexthop");
        out.add(new EcmpStaticRoute(uuidNode.get(1).asText(),
                prefixNode == null ? "" : prefixNode.asText(),
                nexthopNode == null ? "" : nexthopNode.asText(), owner));
    }

    /** Read the value for {@code key} out of an OVSDB {@code ["map", [[k,v],...]]}
     *  column node, or {@code null} when the key is absent / the node is not a map. */
    private String mapValue(final JsonNode ext, final String key) {
        if (ext == null || ext.size() < 2 || !"map".equals(ext.get(0).asText())) {
            return null;
        }
        final var pairs = ext.get(1);
        if (pairs == null) {
            return null;
        }
        for (int j = 0; j < pairs.size(); j++) {
            final var pair = pairs.get(j);
            if (pair != null && pair.size() >= 2 && key.equals(pair.get(0).asText())) {
                return pair.get(1).asText();
            }
        }
        return null;
    }

    /**
     * List every {@code Load_Balancer} row whose {@code external_ids} carries
     * the supplied marker key (e.g. {@code cs-pub6-lb}), returning UUID, name,
     * vips map, protocol, and the marker value. Used by the public IPv6 LB
     * reconciler to diff desired entries against plugin-owned rows only.
     *
     * <p>Walks the table client-side (same rationale as
     * {@link #listEcmpStaticRoutes} / {@link #findUuidsByExternalIds}).
     *
     * @param markerKey the {@code external_ids} key that tags an owned row
     * @return owned LB descriptors, never {@code null}
     */
    public List<OwnedLoadBalancer> listOwnedLoadBalancers(final String markerKey) {
        final List<OwnedLoadBalancer> out = new ArrayList<>();
        if (markerKey == null || markerKey.isEmpty()) {
            return out;
        }
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("name");
        columns.add("vips");
        columns.add("protocol");
        columns.add("external_ids");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("Load_Balancer", OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r;
        try {
            r = tx.commit();
        } catch (OvnException e) {
            return out;
        }
        final ArrayNode arr = r.raw();
        final var entry = arr == null || arr.size() == 0 ? null : arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            addOwnedLoadBalancer(out, rows.get(i), markerKey);
        }
        return out;
    }

    private void addOwnedLoadBalancer(final List<OwnedLoadBalancer> out, final JsonNode row,
                                      final String markerKey) {
        if (row == null) {
            return;
        }
        final String owner = mapValue(row.get("external_ids"), markerKey);
        if (owner == null) {
            return;
        }
        final var uuidNode = row.get("_uuid");
        if (uuidNode == null || uuidNode.size() < 2) {
            return;
        }
        final JsonNode nameNode = row.get("name");
        final JsonNode protocolNode = row.get("protocol");
        out.add(new OwnedLoadBalancer(
                uuidNode.get(1).asText(),
                nameNode == null || nameNode.isNull() ? "" : nameNode.asText(),
                OvnNbReader.decodeMap(row.get("vips")),
                protocolNode == null || protocolNode.isNull() ? "" : protocolNode.asText(),
                owner));
    }

    /**
     * Immutable descriptor of a plugin-owned {@code Load_Balancer} as read from
     * the NB DB: row UUID, name, vips map, protocol, and the marker value
     * ({@code external_ids:cs-pub6-lb}) identifying the stable entry key.
     */
    public static final class OwnedLoadBalancer {
        private final String uuid;
        private final String name;
        private final Map<String, String> vips;
        private final String protocol;
        private final String owner;

        public OwnedLoadBalancer(final String uuid, final String name, final Map<String, String> vips,
                                 final String protocol, final String owner) {
            this.uuid = uuid;
            this.name = name;
            this.vips = vips == null ? Map.of() : Map.copyOf(vips);
            this.protocol = protocol;
            this.owner = owner;
        }

        public String getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public Map<String, String> getVips() {
            return vips;
        }

        public String getProtocol() {
            return protocol;
        }

        public String getOwner() {
            return owner;
        }
    }

    /**
     * List NAT rows whose {@code external_ip} equals {@code externalIp}
     * (used by DSR residual-CT precheck). Returns uuid/type/external_port_range.
     */
    public List<OwnedNat> listNatsByExternalIp(final String externalIp) {
        final List<OwnedNat> out = new ArrayList<>();
        if (externalIp == null || externalIp.isEmpty()) {
            return out;
        }
        final ArrayNode where = JsonNodeFactory.instance.arrayNode();
        where.add(equalityCondition("external_ip", externalIp));
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("type");
        columns.add("external_ip");
        columns.add("external_port_range");
        columns.add("logical_ip");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.select("NAT", where, columns));
        final OvnTransaction.Result r = tx.commit();
        final ArrayNode arr = r.raw();
        final var entry = arr == null || arr.size() == 0 ? null : arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var uuid = row.get("_uuid");
            if (uuid == null || uuid.size() < 2) {
                continue;
            }
            final String type = row.get("type") == null || row.get("type").isNull()
                    ? "" : row.get("type").asText();
            final String epr = row.get("external_port_range") == null || row.get("external_port_range").isNull()
                    ? null : row.get("external_port_range").asText();
            final String lip = row.get("logical_ip") == null || row.get("logical_ip").isNull()
                    ? null : row.get("logical_ip").asText();
            out.add(new OwnedNat(uuid.get(1).asText(), type, externalIp, epr, lip));
        }
        return out;
    }

    /** Immutable NAT row descriptor for residual checks. */
    public static final class OwnedNat {
        private final String uuid;
        private final String type;
        private final String externalIp;
        private final String externalPortRange;
        private final String logicalIp;

        public OwnedNat(final String uuid, final String type, final String externalIp,
                final String externalPortRange, final String logicalIp) {
            this.uuid = uuid;
            this.type = type;
            this.externalIp = externalIp;
            this.externalPortRange = externalPortRange;
            this.logicalIp = logicalIp;
        }

        public String getUuid() {
            return uuid;
        }

        public String getType() {
            return type;
        }

        public String getExternalIp() {
            return externalIp;
        }

        public String getExternalPortRange() {
            return externalPortRange;
        }

        public String getLogicalIp() {
            return logicalIp;
        }
    }

    /**
     * Immutable descriptor of a plugin-owned ECMP static route as read from the
     * NB DB: the route row UUID, destination {@code ip_prefix}, {@code nexthop},
     * and the marker value ({@code external_ids:cs-ecmp-route}) identifying the
     * owning CloudStack network.
     */
    public static final class EcmpStaticRoute {
        private final String uuid;
        private final String prefix;
        private final String nexthop;
        private final String owner;

        public EcmpStaticRoute(final String uuid, final String prefix, final String nexthop, final String owner) {
            this.uuid = uuid;
            this.prefix = prefix;
            this.nexthop = nexthop;
            this.owner = owner;
        }

        public String getUuid() {
            return uuid;
        }

        public String getPrefix() {
            return prefix;
        }

        public String getNexthop() {
            return nexthop;
        }

        public String getOwner() {
            return owner;
        }
    }

    /**
     * Deletes an ACL row by UUID, first removing it from its parent
     * {@code Logical_Switch.acls} strong-reference set to satisfy the OVSDB
     * referential-integrity constraint.
     *
     * <p>The parent LS is discovered via an OVSDB select on
     * {@code Logical_Switch} using the {@code includes} condition on the
     * {@code acls} column. When the ACL is not referenced by any LS (already
     * detached or already deleted), the single-op delete is issued directly.
     * Idempotent: no-op when {@code aclUuid} no longer exists.
     */
    public void deleteAclByUuid(final String aclUuid) {
        if (aclUuid == null || aclUuid.isEmpty()) {
            return;
        }
        final String lsUuid = findLsForAcl(aclUuid);
        if (lsUuid != null) {
            removeAclFromLogicalSwitch(lsUuid, aclUuid);
        } else {
            // ACL not referenced by any LS — delete directly (idempotent).
            final OvnTransaction tx = newTransaction();
            tx.add(OvnOpFactory.delete("ACL", OvnOpFactory.whereUuid(aclUuid)));
            tx.commit();
        }
    }

    /**
     * Returns the UUID of the {@code Logical_Switch} that holds the given ACL
     * UUID in its {@code acls} set, or {@code null} when no switch references
     * it (already detached or the ACL itself does not exist).
     */
    String findLsForAcl(final String aclUuid) {
        final OvnTransaction tx = newTransaction();
        final ArrayNode where = JsonNodeFactory.instance.arrayNode();
        final ArrayNode condition = JsonNodeFactory.instance.arrayNode();
        condition.add("acls");
        condition.add("includes");
        condition.add(OvnRowRef.realUuid(aclUuid));
        where.add(condition);
        final ArrayNode cols = JsonNodeFactory.instance.arrayNode();
        cols.add("_uuid");
        tx.add(OvnOpFactory.select("Logical_Switch", where, cols));
        final OvnTransaction.Result r = tx.commit();
        final List<String> uuids = r.selectedUuids(0);
        return uuids.isEmpty() ? null : uuids.get(0);
    }

    // ------------------------------------------------------------------
    // HA chassis group operations.
    // ------------------------------------------------------------------

    /**
     * Inserts an {@code HA_Chassis} row referencing the named chassis with the
     * given priority. Returns the new UUID; caller attaches it to a group.
     */
    public String createHaChassis(final String chassisName, final int priority) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("chassis_name", chassisName);
        row.put("priority", priority);
        final String named = OvnNamedUuid.next("hac");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("HA_Chassis", named, row));
        return tx.commit().insertedUuid(0);
    }

    /**
     * Inserts an {@code HA_Chassis_Group} with a precomputed list of HA_Chassis
     * UUIDs. The group can then be attached to a Logical_Router_Port to drive
     * gateway-chassis failover for north-south traffic.
     */
    public String createHaChassisGroup(final String name, final List<String> haChassisUuids,
                                       final Map<String, String> externalIds) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("name", name);
        if (haChassisUuids != null && !haChassisUuids.isEmpty()) {
            row.set("ha_chassis", uuidSet(haChassisUuids));
        }
        if (externalIds != null && !externalIds.isEmpty()) {
            row.set("external_ids", buildMap(externalIds));
        }
        final String named = OvnNamedUuid.next("hag");
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("HA_Chassis_Group", named, row));
        return tx.commit().insertedUuid(0);
    }

    /**
     * Atomic single-transaction variant: inserts every HA_Chassis row + the
     * parent HA_Chassis_Group referencing them by named UUID, in one
     * commit. Required because OVSDB GCs orphan HA_Chassis rows between
     * transactions — the original {@link #createHaChassis} +
     * {@link #createHaChassisGroup} pair fails referential integrity when
     * the chassis rows get GC'd before the group references them.
     *
     * @param name           HA_Chassis_Group.name
     * @param members        ordered list of (chassisName, priority) pairs
     * @param externalIds    HA_Chassis_Group.external_ids
     * @return the new HA_Chassis_Group UUID
     */
    public String createHaChassisGroupAtomic(final String name,
                                             final List<Map.Entry<String, Integer>> members,
                                             final Map<String, String> externalIds) {
        if (members == null || members.isEmpty()) {
            throw new OvnException("createHaChassisGroupAtomic requires at least one member");
        }
        final OvnTransaction tx = newTransaction();
        final ArrayNode hacRefs = JsonNodeFactory.instance.arrayNode();
        for (final Map.Entry<String, Integer> m : members) {
            final ObjectNode hacRow = JsonNodeFactory.instance.objectNode();
            hacRow.put("chassis_name", m.getKey());
            hacRow.put("priority", m.getValue());
            final String hacNamed = OvnNamedUuid.next("hac");
            tx.add(OvnOpFactory.insert("HA_Chassis", hacNamed, hacRow));
            hacRefs.add(OvnRowRef.namedUuid(hacNamed));
        }
        // Wrap the named-uuid array as a typed OVSDB set.
        final ArrayNode hacSet = JsonNodeFactory.instance.arrayNode();
        hacSet.add("set");
        hacSet.add(hacRefs);
        final ObjectNode hagRow = JsonNodeFactory.instance.objectNode();
        hagRow.put("name", name);
        hagRow.set("ha_chassis", hacSet);
        if (externalIds != null && !externalIds.isEmpty()) {
            hagRow.set("external_ids", buildMap(externalIds));
        }
        final String hagNamed = OvnNamedUuid.next("hag");
        tx.add(OvnOpFactory.insert("HA_Chassis_Group", hagNamed, hagRow));
        // The HA_Chassis_Group insert is the last op; its uuid is at index
        // == members.size() (HA_Chassis inserts come first).
        return tx.commit().insertedUuid(members.size());
    }

    /**
     * Drop an HA_Chassis_Group row + its ha_chassis members in one
     * transaction. The group's {@code ha_chassis} column is a strong-ref
     * set, so we must blank it before delete; the embedded HA_Chassis rows
     * then become orphans + are GC'd by ovsdb-server on next sync.
     * Used by the reaper when a stale {@code hag-public-z<zone>} appears
     * (chassis pool changed, prior plugin version pre-stale-guard).
     */
    public void destroyHaChassisGroup(final String hagUuid) {
        if (hagUuid == null || hagUuid.isEmpty()) {
            return;
        }
        final OvnTransaction tx = newTransaction();
        // Empty the ha_chassis set so ovsdb-server stops holding references.
        final ObjectNode emptyRow = JsonNodeFactory.instance.objectNode();
        final ArrayNode emptySet = JsonNodeFactory.instance.arrayNode();
        emptySet.add("set");
        emptySet.add(JsonNodeFactory.instance.arrayNode());
        emptyRow.set("ha_chassis", emptySet);
        tx.add(OvnOpFactory.update("HA_Chassis_Group", OvnOpFactory.whereUuid(hagUuid), emptyRow));
        tx.add(OvnOpFactory.delete("HA_Chassis_Group", OvnOpFactory.whereUuid(hagUuid)));
        tx.commit();
    }

    /** Pins an LRP to an HA_Chassis_Group (sets the {@code ha_chassis_group} column). */
    public void lrpSetHaChassisGroup(final String lrpUuid, final String hagUuid) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("ha_chassis_group", OvnRowRef.singletonSet(OvnRowRef.realUuid(hagUuid)));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid), row));
        tx.commit();
    }

    /**
     * Look up the top-priority {@code chassis_name} inside a given
     * {@code HA_Chassis_Group} (by UUID). Walks the
     * {@code ha_chassis} set on the group, reads the {@code priority} +
     * {@code chassis_name} of each member, and returns the {@code chassis_name}
     * carrying the highest priority. Used by the BGP /32 redistributor to
     * decide which agent host should announce the route — matches OVN's
     * own selection algorithm modulo runtime liveness (which northd applies
     * by also consulting the SB DB; this NB-only path is best-effort and
     * reverts to the next reconcile cycle if the picked chassis is down).
     *
     * @return the {@code chassis_name} (system-id) of the top-priority
     *         chassis, or {@code null} when the group is empty / missing.
     */
    public String findTopPriorityChassisName(final String hagUuid) {
        if (hagUuid == null || hagUuid.isEmpty()) {
            return null;
        }
        // Step 1: read the group's ha_chassis set.
        final ArrayNode groupCols = JsonNodeFactory.instance.arrayNode();
        groupCols.add("_uuid");
        groupCols.add("ha_chassis");
        final OvnTransaction txGroup = newTransaction();
        txGroup.add(OvnOpFactory.select("HA_Chassis_Group", OvnOpFactory.whereUuid(hagUuid), groupCols));
        final OvnTransaction.Result rGroup;
        try {
            rGroup = txGroup.commit();
        } catch (OvnException e) {
            return null;
        }
        final ArrayNode gArr = rGroup.raw();
        if (gArr == null || gArr.size() == 0) {
            return null;
        }
        final var gRows = gArr.get(0) == null ? null : gArr.get(0).get("rows");
        if (gRows == null || gRows.size() == 0) {
            return null;
        }
        final List<String> memberUuids = decodeUuidSetColumn(gRows.get(0).get("ha_chassis"));
        if (memberUuids.isEmpty()) {
            return null;
        }
        // Step 2: read each HA_Chassis row to pick the highest priority.
        final ArrayNode memberCols = JsonNodeFactory.instance.arrayNode();
        memberCols.add("_uuid");
        memberCols.add("chassis_name");
        memberCols.add("priority");
        String bestChassis = null;
        int bestPriority = Integer.MIN_VALUE;
        for (final String memberUuid : memberUuids) {
            final OvnTransaction tx = newTransaction();
            tx.add(OvnOpFactory.select("HA_Chassis", OvnOpFactory.whereUuid(memberUuid), memberCols));
            final OvnTransaction.Result r;
            try {
                r = tx.commit();
            } catch (OvnException e) {
                continue;
            }
            final ArrayNode arr = r.raw();
            if (arr == null || arr.size() == 0) {
                continue;
            }
            final var rows = arr.get(0) == null ? null : arr.get(0).get("rows");
            if (rows == null || rows.size() == 0) {
                continue;
            }
            final var row = rows.get(0);
            final var priorityNode = row.get("priority");
            final var nameNode = row.get("chassis_name");
            final int priority = priorityNode != null && priorityNode.isInt() ? priorityNode.asInt() : Integer.MIN_VALUE;
            final String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (priority > bestPriority) {
                bestPriority = priority;
                bestChassis = name;
            }
        }
        return bestChassis;
    }

    /**
     * Decode an OVSDB {@code set of uuid} column into a flat list of UUIDs.
     * Tolerates both the single-element shape ({@code ["uuid", id]}) and the
     * regular set shape ({@code ["set", [["uuid", id1], ["uuid", id2]]]}).
     */
    private static List<String> decodeUuidSetColumn(final JsonNode column) {
        final List<String> out = new ArrayList<>();
        if (column == null || !column.isArray() || column.size() < 2) {
            return out;
        }
        if ("uuid".equals(column.get(0).asText())) {
            out.add(column.get(1).asText());
            return out;
        }
        final var inner = column.get(1);
        if (inner == null || !inner.isArray()) {
            return out;
        }
        for (int i = 0; i < inner.size(); i++) {
            final var ref = inner.get(i);
            if (ref != null && ref.size() >= 2 && "uuid".equals(ref.get(0).asText())) {
                out.add(ref.get(1).asText());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // IPv6 RA + LS multicast snooping.
    // ------------------------------------------------------------------

    /**
     * Replaces the {@code ipv6_ra_configs} column on a Logical_Router_Port,
     * driving SLAAC / Router Advertisements without needing radvd.
     * Recommended keys: {@code address_mode=slaac|dhcpv6_stateful|dhcpv6_stateless},
     * {@code mtu=<int>}, {@code dnssl=<domain>}, {@code rdnss=<ip>}.
     */
    public void lrpSetIpv6RaConfigs(final String lrpUuid, final Map<String, String> raConfigs) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("ipv6_ra_configs", buildMap(raConfigs == null ? Map.of() : raConfigs));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Router_Port", OvnOpFactory.whereUuid(lrpUuid), row));
        tx.commit();
    }

    /**
     * Merges entries into {@code Logical_Router.options} for the given LR UUID.
     * Used to push CT inactive-timeout values (e.g. {@code ct_tcp_idle_timeout},
     * {@code ct_udp_idle_timeout}, {@code ct_snat_idle_timeout},
     * {@code ct_icmp_idle_timeout}) supported by OVN >= 21.x. A no-op when
     * {@code options} is null or empty, preserving the idempotency contract.
     *
     * @param lrUuid  Logical_Router row UUID (must be non-blank)
     * @param options key=value pairs to write into the {@code options} column
     */
    public void lrSetOptions(final String lrUuid, final Map<String, String> options) {
        if (lrUuid == null || lrUuid.isEmpty() || options == null || options.isEmpty()) {
            return;
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.set("options", buildMap(options));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Router", OvnOpFactory.whereUuid(lrUuid), row));
        tx.commit();
    }

    /**
     * Inserts a {@code BFD} table row that probes {@code dstIp} through the
     * named logical port. If a BFD row for the same {@code logicalPort} already
     * exists (detected by walking the table), the existing row is deleted first
     * so the new parameters are always applied cleanly.
     *
     * <p>OVN BFD table (schema since OVN 21.x):
     * <ul>
     *   <li>{@code logical_port} — name of the Logical_Switch_Port</li>
     *   <li>{@code dst_ip} — BFD peer IP to probe</li>
     *   <li>{@code min_rx} — minimum receive interval (ms)</li>
     *   <li>{@code min_tx} — minimum transmit interval (ms)</li>
     *   <li>{@code detect_mult} — failure-detection multiplier</li>
     * </ul>
     *
     * @param logicalPort LSP name (NOT UUID) as required by the BFD schema
     * @param dstIp       BFD peer IP address string
     * @param minRx       minimum RX interval in milliseconds
     * @param minTx       minimum TX interval in milliseconds
     * @param detectMult  detection multiplier
     */
    public void addBfdSession(final String logicalPort, final String dstIp,
                              final int minRx, final int minTx, final int detectMult) {
        if (logicalPort == null || logicalPort.isEmpty() || dstIp == null || dstIp.isEmpty()) {
            return;
        }
        // Remove any existing BFD row for this port before inserting fresh parameters.
        removeBfdSession(logicalPort);

        final String named = OvnNamedUuid.next("bfd");
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("logical_port", logicalPort);
        row.put("dst_ip", dstIp);
        row.put("min_rx", minRx);
        row.put("min_tx", minTx);
        row.put("detect_mult", detectMult);

        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.insert("BFD", named, row));
        tx.commit();
    }

    /**
     * Deletes all {@code BFD} rows whose {@code logical_port} column matches
     * {@code logicalPort}. Called on LSP release to avoid leaving orphaned
     * BFD sessions in the NB DB after a VM is destroyed.
     *
     * @param logicalPort LSP name whose BFD sessions should be removed
     */
    public void removeBfdSession(final String logicalPort) {
        if (logicalPort == null || logicalPort.isEmpty()) {
            return;
        }
        // Walk the BFD table client-side (typically O(1) rows per port) and
        // collect matching UUIDs, then delete each in a single transaction.
        final ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        columns.add("_uuid");
        columns.add("logical_port");
        final OvnTransaction readTx = newTransaction();
        readTx.add(OvnOpFactory.select("BFD", OvnOpFactory.whereAll(), columns));
        final OvnTransaction.Result r;
        try {
            r = readTx.commit();
        } catch (OvnException e) {
            // BFD table may not exist on older OVN builds — treat as no-op.
            return;
        }
        final ArrayNode arr = r.raw();
        if (arr == null || arr.size() == 0) {
            return;
        }
        final var entry = arr.get(0);
        final var rows = entry == null ? null : entry.get("rows");
        if (rows == null || rows.size() == 0) {
            return;
        }
        final List<String> toDelete = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            final var row = rows.get(i);
            if (row == null) {
                continue;
            }
            final var portNode = row.get("logical_port");
            if (portNode != null && logicalPort.equals(portNode.asText())) {
                final var uuidNode = row.get("_uuid");
                if (uuidNode != null && uuidNode.size() >= 2) {
                    toDelete.add(uuidNode.get(1).asText());
                }
            }
        }
        if (toDelete.isEmpty()) {
            return;
        }
        final OvnTransaction delTx = newTransaction();
        for (final String uuid : toDelete) {
            delTx.add(OvnOpFactory.delete("BFD", OvnOpFactory.whereUuid(uuid)));
        }
        delTx.commit();
    }

    /**
     * Toggles IGMP/MLD snooping on a Logical_Switch via {@code other_config}.
     * <p>
     * Snooping alone is safe. {@code mcast_querier=true} without a querier IP/MAC
     * makes every chassis pinctrl spam
     * {@code IGMP Querier enabled without a valid IPv4 or IPv6 address} and burns
     * CPU — so we never enable the querier here (snoop-only when {@code enable}).
     */
    public void lsSetMcastSnoop(final String lsUuid, final boolean enable) {
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        final Map<String, String> oc = new java.util.HashMap<>();
        oc.put("mcast_snoop", Boolean.toString(enable));
        // Keep querier off unless a future API sets mcast_querier_ip explicitly.
        oc.put("mcast_querier", "false");
        row.set("other_config", buildMap(oc));
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("Logical_Switch", OvnOpFactory.whereUuid(lsUuid), row));
        tx.commit();
    }

    /** Sets {@code HA_Chassis.priority} on an existing row. */
    public void haChassisSetPriority(final String haChassisUuid, final int priority) {
        if (haChassisUuid == null || haChassisUuid.isEmpty()) {
            return;
        }
        final ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("priority", priority);
        final OvnTransaction tx = newTransaction();
        tx.add(OvnOpFactory.update("HA_Chassis", OvnOpFactory.whereUuid(haChassisUuid), row));
        tx.commit();
    }

    /**
     * Aligns HA_Chassis members of a group to the desired (chassis_name, priority)
     * list: updates priority on known names; does not remove extra members.
     *
     * @return number of rows whose priority was written
     */
    public int syncHaChassisGroupPriorities(final String hagUuid,
                                            final List<Map.Entry<String, Integer>> desired) {
        if (hagUuid == null || hagUuid.isEmpty() || desired == null || desired.isEmpty()) {
            return 0;
        }
        final Map<String, Integer> want = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : desired) {
            want.put(e.getKey(), e.getValue());
        }
        final ArrayNode groupCols = JsonNodeFactory.instance.arrayNode();
        groupCols.add("_uuid");
        groupCols.add("ha_chassis");
        final OvnTransaction txGroup = newTransaction();
        txGroup.add(OvnOpFactory.select("HA_Chassis_Group", OvnOpFactory.whereUuid(hagUuid), groupCols));
        final OvnTransaction.Result rGroup;
        try {
            rGroup = txGroup.commit();
        } catch (OvnException e) {
            return 0;
        }
        final ArrayNode gArr = rGroup.raw();
        if (gArr == null || gArr.size() == 0) {
            return 0;
        }
        final var gRows = gArr.get(0) == null ? null : gArr.get(0).get("rows");
        if (gRows == null || gRows.size() == 0) {
            return 0;
        }
        final List<String> memberUuids = decodeUuidSetColumn(gRows.get(0).get("ha_chassis"));
        if (memberUuids.isEmpty()) {
            return 0;
        }
        int updated = 0;
        final ArrayNode cols = JsonNodeFactory.instance.arrayNode();
        cols.add("_uuid");
        cols.add("chassis_name");
        cols.add("priority");
        for (final String hacUuid : memberUuids) {
            final OvnTransaction tx = newTransaction();
            tx.add(OvnOpFactory.select("HA_Chassis", OvnOpFactory.whereUuid(hacUuid), cols));
            final OvnTransaction.Result r;
            try {
                r = tx.commit();
            } catch (OvnException e) {
                continue;
            }
            final ArrayNode arr = r.raw();
            if (arr == null || arr.size() == 0) {
                continue;
            }
            final var rows = arr.get(0) == null ? null : arr.get(0).get("rows");
            if (rows == null || rows.size() == 0) {
                continue;
            }
            final var row = rows.get(0);
            final var nameNode = row.get("chassis_name");
            final String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
            if (name == null || !want.containsKey(name)) {
                continue;
            }
            final int target = want.get(name);
            final var prioNode = row.get("priority");
            final int cur = prioNode != null && prioNode.isInt() ? prioNode.asInt() : -1;
            if (cur != target) {
                haChassisSetPriority(hacUuid, target);
                updated++;
            }
        }
        return updated;
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

    /**
     * Builds an OVSDB-format {@code ["map", [[k, v], ...]]} where values are
     * integers. Required for QoS bandwidth / action columns whose schema type
     * is {@code map of string-integer pairs}.
     */
    private ArrayNode buildIntMap(final Map<String, Integer> entries) {
        final ArrayNode mapNode = JsonNodeFactory.instance.arrayNode();
        mapNode.add("map");
        final ArrayNode pairs = JsonNodeFactory.instance.arrayNode();
        for (final Map.Entry<String, Integer> e : entries.entrySet()) {
            final ArrayNode pair = JsonNodeFactory.instance.arrayNode();
            pair.add(e.getKey());
            pair.add(e.getValue() == null ? 0 : e.getValue().intValue());
            pairs.add(pair);
        }
        mapNode.add(pairs);
        return mapNode;
    }

    /**
     * Builds an OVSDB-format {@code ["set", [["uuid", uuid], ...]]} from a list
     * of real UUIDs. Used by HA_Chassis_Group's {@code ha_chassis} column and
     * any other multi-uuid set column.
     */
    private ArrayNode uuidSet(final List<String> uuids) {
        final ArrayNode setNode = JsonNodeFactory.instance.arrayNode();
        setNode.add("set");
        final ArrayNode elements = JsonNodeFactory.instance.arrayNode();
        for (final String u : uuids) {
            final ArrayNode pair = JsonNodeFactory.instance.arrayNode();
            pair.add("uuid");
            pair.add(u);
            elements.add(pair);
        }
        setNode.add(elements);
        return setNode;
    }

    public OvsdbConnectionPool getPool() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }
}
