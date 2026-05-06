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
