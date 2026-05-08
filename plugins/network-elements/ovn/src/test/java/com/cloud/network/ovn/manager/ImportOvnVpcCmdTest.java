// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbReader;
import com.cloud.network.ovn.client.OvnNbReader.LrpRow;
import com.cloud.network.ovn.client.OvnNbReader.LrRow;
import com.cloud.network.ovn.client.OvnNbReader.LspRow;
import com.cloud.network.ovn.client.OvnNbReader.LsRow;
import com.cloud.network.ovn.client.OvnNbReader.NatRow;
import com.cloud.network.ovn.client.OvnNbReader.Topology;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnImportValidator.Plan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Drives the {@code importOvnVpc} adoption pipeline against captured
 * topology fixtures. The fixtures use synthetic OVN UUIDs so the live
 * cluster is never referenced by id.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code lr-only}: empty LR (no LRPs, no LSes) — validation rejects
 *       it because a public LS is required.
 *   <li>{@code lr-test}: full topology (3 LRPs, 2 tier LSes + 1 public,
 *       4 NAT rules, 1 owned LSP + 1 orphan LSP). Validates clean and
 *       gets adopted into the lookup map; re-running the adoption is a
 *       no-op (idempotency).
 *   <li>Duplicate-name path: a stub DAO that throws on the second insert
 *       — the importer must roll back and surface the failure.
 * </ul>
 *
 * <p>{@link com.cloud.utils.db.Transaction#execute(com.cloud.utils.db.TransactionCallback)}
 * falls through to the supplied callback when no DataSource is wired,
 * which is exactly what the unit test wants.
 */
public class ImportOvnVpcCmdTest {

    @Test
    public void parsesLrTestFixtureWithoutException() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        assertNotNull(topology);
        assertEquals("lr-test", topology.lr.name);
        assertEquals(3, topology.lrps.size());
        assertEquals("must isolate the 3 LSes attached via router-patch (ls-orphan has no patch port)",
                3, topology.attachedSwitches.size());
        assertEquals(4, topology.nats.size());
    }

    @Test
    public void validatorClassifiesPublicVsTierLses() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        final Plan plan = OvnImportValidator.validate(topology);

        assertEquals(2, plan.tierLses.size());
        assertEquals("ls-public", plan.publicLs.name);
        // The two tier LSes must be the private ones, in any order.
        final List<String> tierNames = new ArrayList<>();
        for (final LsRow ls : plan.tierLses) {
            tierNames.add(ls.name);
        }
        assertTrue(tierNames.contains("ls-net-A"));
        assertTrue(tierNames.contains("ls-net-B"));
    }

    @Test
    public void importerEmitsExpectedRowsForLrTestFixture() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        final Plan plan = OvnImportValidator.validate(topology);
        final TrackingDao tracker = new TrackingDao();
        final OvnVpcImporter importer = newImporter(tracker.dao);

        final List<OvnLogicalIdMapVO> rows = importer.adopt(plan, 7L, "imported-vpc");

        // Expected row count:
        // 1 VPC + 2 tier-NETWORKs + 1 public-NETWORK + 4 NAT
        // + LSPs across the 3 attached LSes:
        //   ls-net-A: vmA1 (NIC, owner present), vmA2 (ORPHAN_NIC) — router-patch + localnet skipped
        //   ls-net-B: vmB1 (NIC, owner present)
        //   ls-public: only router-patch + localnet — both skipped
        // Total LSPs adopted: 3
        // Sum: 1 + 3 + 4 + 3 = 11
        assertEquals(11, rows.size());

        final Map<Kind, Integer> byKind = countByKind(rows);
        assertEquals(Integer.valueOf(1), byKind.getOrDefault(Kind.VPC, 0));
        assertEquals(Integer.valueOf(3), byKind.getOrDefault(Kind.NETWORK, 0));
        assertEquals(Integer.valueOf(2), byKind.getOrDefault(Kind.NIC, 0));
        assertEquals(Integer.valueOf(1), byKind.getOrDefault(Kind.ORPHAN_NIC, 0));
        assertEquals(Integer.valueOf(2), byKind.getOrDefault(Kind.SOURCE_NAT, 0));
        assertEquals(Integer.valueOf(2), byKind.getOrDefault(Kind.STATIC_NAT, 0));
    }

    @Test
    public void reAdoptionIsIdempotent() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        final Plan plan = OvnImportValidator.validate(topology);
        final TrackingDao tracker = new TrackingDao();
        final OvnVpcImporter importer = newImporter(tracker.dao);

        final List<OvnLogicalIdMapVO> first = importer.adopt(plan, 7L, "imported-vpc");
        final int firstPersists = tracker.persists.get();
        final List<OvnLogicalIdMapVO> second = importer.adopt(plan, 7L, "imported-vpc");

        assertEquals(first.size(), second.size());
        // Second call must NOT have triggered a fresh insert for any row.
        assertEquals(firstPersists, tracker.persists.get());
    }

    @Test
    public void lrOnlyFixtureIsRejectedByValidator() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-only.json");
        try {
            OvnImportValidator.validate(topology);
            fail("expected validator to reject an LR with no LRPs");
        } catch (final OvnException oe) {
            assertTrue(oe.getMessage(), oe.getMessage().contains("no LRPs"));
        }
    }

    @Test
    public void duplicateNamePathRollsBack() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        final Plan plan = OvnImportValidator.validate(topology);
        final TrackingDao tracker = new TrackingDao(2);
        final OvnVpcImporter importer = newImporter(tracker.dao);

        try {
            importer.adopt(plan, 7L, "duplicate-name-vpc");
            fail("expected adoption to bubble up the duplicate-name failure");
        } catch (final RuntimeException expected) {
            // Transaction.execute lets the runtime exception propagate; the
            // production path then rolls back automatically. The test asserts
            // that the importer did not silently swallow the failure.
            assertTrue(expected.getMessage(), expected.getMessage().contains("duplicate"));
        }
        // Persist was attempted exactly 3 times (the first 2 succeeded, the
        // third triggered the simulated duplicate).
        assertEquals(3, tracker.persists.get());
    }

    @Test
    public void rejectsTopologyWithIpv6AddressOnLsp() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        // Mutate the in-memory topology to inject an IPv6-bearing LSP.
        final LsRow tier = topology.attachedSwitches.get(0);
        final LspRow rogue = new LspRow();
        rogue.uuid = "55555555-0000-0000-0000-0000000000ee";
        rogue.name = "vmA-ipv6";
        rogue.type = "";
        rogue.addresses = new ArrayList<>(List.of("02:00:00:0a:01:99 fd00::1"));
        rogue.options = new HashMap<>();
        rogue.externalIds = new HashMap<>();
        topology.lspsByLsUuid.get(tier.uuid).add(rogue);

        try {
            OvnImportValidator.validate(topology);
            fail("expected IPv6 to be rejected for the MVP");
        } catch (final OvnException oe) {
            assertTrue(oe.getMessage(), oe.getMessage().contains("IPv6"));
        }
    }

    @Test
    public void rejectsTopologyWithMissingPublicLs() throws Exception {
        final Topology topology = loadTopology("ovn-fixtures/lr-test.json");
        // Strip the public LS from the attached set.
        topology.attachedSwitches.removeIf(ls -> "ls-public".equals(ls.name));

        try {
            OvnImportValidator.validate(topology);
            fail("expected validator to reject a topology with no public LS");
        } catch (final OvnException oe) {
            assertTrue(oe.getMessage(), oe.getMessage().contains("no public-side LS"));
        }
    }

    @Test
    public void importerEmitsCorrectKindsForOnlySnatNatTopology() throws Exception {
        // Sanity: an LR with only snat NAT (no dnat_and_snat) yields only
        // SOURCE_NAT rows under STATIC_NAT.
        final Topology topology = buildSyntheticSnatOnly();
        final Plan plan = OvnImportValidator.validate(topology);
        final TrackingDao tracker = new TrackingDao();
        final OvnVpcImporter importer = newImporter(tracker.dao);

        final List<OvnLogicalIdMapVO> rows = importer.adopt(plan, 7L, "snat-only");

        final Map<Kind, Integer> byKind = countByKind(rows);
        assertEquals(Integer.valueOf(1), byKind.getOrDefault(Kind.VPC, 0));
        assertEquals(Integer.valueOf(2), byKind.getOrDefault(Kind.NETWORK, 0));
        assertEquals(Integer.valueOf(1), byKind.getOrDefault(Kind.SOURCE_NAT, 0));
        assertEquals(Integer.valueOf(0), byKind.getOrDefault(Kind.STATIC_NAT, 0));
    }

    private static Topology buildSyntheticSnatOnly() {
        final LrRow lr = new LrRow();
        lr.uuid = "11111111-aaaa-0000-0000-000000000001";
        lr.name = "lr-snat-only";

        final LrpRow lrp = new LrpRow();
        lrp.uuid = "22222222-aaaa-0000-0000-000000000001";
        lrp.name = "lrp-only";
        lrp.networks = List.of("10.50.0.1/24");

        final LsRow tier = new LsRow();
        tier.uuid = "33333333-aaaa-0000-0000-000000000001";
        tier.name = "ls-tier";

        final LsRow pub = new LsRow();
        pub.uuid = "33333333-aaaa-0000-0000-0000000000ff";
        pub.name = "ls-public";

        final LspRow tierPatch = patchLsp("rsp-tier", "lrp-only");
        final LspRow pubPatch = patchLsp("rsp-public", "lrp-only");
        final LspRow localnet = localnetLsp("ln-public", 200, "physnet1");

        final Map<String, List<LspRow>> lspsByLs = new HashMap<>();
        lspsByLs.put(tier.uuid, new ArrayList<>(List.of(tierPatch)));
        lspsByLs.put(pub.uuid, new ArrayList<>(List.of(pubPatch, localnet)));

        final NatRow nat = new NatRow();
        nat.uuid = "44444444-aaaa-0000-0000-000000000001";
        nat.type = "snat";
        nat.externalIp = "203.0.113.1";
        nat.logicalIp = "10.50.0.0/24";

        return new Topology(lr,
                new ArrayList<>(List.of(lrp)),
                new ArrayList<>(List.of(tier, pub)),
                lspsByLs,
                new ArrayList<>(List.of(nat)));
    }

    private static LspRow patchLsp(final String name, final String routerPort) {
        final LspRow lsp = new LspRow();
        lsp.uuid = "55555555-aaaa-" + Integer.toHexString(name.hashCode()) + "-0000-000000000000";
        lsp.name = name;
        lsp.type = "router";
        lsp.options = new HashMap<>();
        lsp.options.put("router-port", routerPort);
        return lsp;
    }

    private static LspRow localnetLsp(final String name, final int tag, final String physnet) {
        final LspRow lsp = new LspRow();
        lsp.uuid = "55555555-bbbb-0000-0000-000000000001";
        lsp.name = name;
        lsp.type = "localnet";
        lsp.tag = tag;
        lsp.options = new HashMap<>();
        lsp.options.put("network_name", physnet);
        return lsp;
    }

    // ------------------------------------------------------------------
    // Fixture loader and decoder.
    // ------------------------------------------------------------------

    private static Topology loadTopology(final String resourcePath) throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        try (final InputStream in = ImportOvnVpcCmdTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("fixture not on classpath: " + resourcePath);
            }
            final JsonNode root = mapper.readTree(in);
            return assemble(root);
        }
    }

    private static Topology assemble(final JsonNode root) {
        final LrRow lr = OvnNbReader.decodeLr(root.get("lr"));
        final List<LrpRow> lrps = new ArrayList<>();
        for (final JsonNode r : root.get("lrps")) {
            lrps.add(OvnNbReader.decodeLrp(r));
        }
        final List<LsRow> allLses = new ArrayList<>();
        for (final JsonNode r : root.get("lses")) {
            allLses.add(OvnNbReader.decodeLs(r));
        }
        final Map<String, LspRow> lspByUuid = new HashMap<>();
        for (final JsonNode r : root.get("lsps")) {
            final LspRow row = OvnNbReader.decodeLsp(r);
            lspByUuid.put(row.uuid, row);
        }
        final List<NatRow> nats = new ArrayList<>();
        for (final JsonNode r : root.get("nats")) {
            nats.add(OvnNbReader.decodeNat(r));
        }
        // Mirror the production assemble() logic: keep only LSes whose ports
        // include a router-patch LSP whose options.router-port matches an LRP.
        final List<String> lrpNames = new ArrayList<>();
        for (final LrpRow lrp : lrps) {
            lrpNames.add(lrp.name);
        }
        final List<LsRow> attached = new ArrayList<>();
        final Map<String, List<LspRow>> lspsByLs = new HashMap<>();
        for (final LsRow ls : allLses) {
            final List<LspRow> lsps = new ArrayList<>();
            for (final String pUuid : ls.portUuids) {
                final LspRow lsp = lspByUuid.get(pUuid);
                if (lsp != null) {
                    lsps.add(lsp);
                }
            }
            boolean attachedToLr = false;
            for (final LspRow lsp : lsps) {
                if ("router".equals(lsp.type)) {
                    final String routerPort = lsp.options.get("router-port");
                    if (routerPort != null && lrpNames.contains(routerPort)) {
                        attachedToLr = true;
                        break;
                    }
                }
            }
            if (attachedToLr) {
                attached.add(ls);
                lspsByLs.put(ls.uuid, lsps);
            }
        }
        return new Topology(lr, lrps, attached, lspsByLs, nats);
    }

    private static Map<Kind, Integer> countByKind(final List<OvnLogicalIdMapVO> rows) {
        final Map<Kind, Integer> out = new HashMap<>();
        for (final OvnLogicalIdMapVO row : rows) {
            out.merge(row.getKind(), 1, Integer::sum);
        }
        return out;
    }

    private static OvnVpcImporter newImporter(final OvnLogicalIdMapDao dao) throws Exception {
        final OvnVpcImporter importer = new OvnVpcImporter();
        final Field daoField = OvnVpcImporter.class.getDeclaredField("logicalIdMapDao");
        daoField.setAccessible(true);
        daoField.set(importer, dao);
        return importer;
    }

    /**
     * Mockito-backed in-memory tracker. Records every call to
     * {@code persist} (resolves duplicates against the recorded set) and
     * resolves {@code findByCsId} from the same set so re-imports collide
     * on the unique key.
     *
     * <p>When {@code failOnPersist > 0} the (failOnPersist+1)-th persist
     * throws a {@code "duplicate row (simulated)"} runtime exception,
     * exercising the rollback path.
     */
    private static class TrackingDao {
        final OvnLogicalIdMapDao dao = mock(OvnLogicalIdMapDao.class);
        final Map<String, OvnLogicalIdMapVO> rows = new HashMap<>();
        final AtomicInteger persists = new AtomicInteger();
        final int failOnPersist;

        TrackingDao() {
            this(0);
        }

        TrackingDao(final int failOnPersist) {
            this.failOnPersist = failOnPersist;
            when(dao.findByCsId(any(Kind.class), anyLong(), anyLong())).thenAnswer(new Answer<OvnLogicalIdMapVO>() {
                @Override
                public OvnLogicalIdMapVO answer(final InvocationOnMock invocation) {
                    final Kind kind = invocation.getArgument(0);
                    final long csId = invocation.getArgument(1);
                    final long controllerId = invocation.getArgument(2);
                    return rows.get(kind.name() + ":" + csId + ":" + controllerId);
                }
            });
            when(dao.persist(any(OvnLogicalIdMapVO.class))).thenAnswer(new Answer<OvnLogicalIdMapVO>() {
                @Override
                public OvnLogicalIdMapVO answer(final InvocationOnMock invocation) {
                    final int call = persists.incrementAndGet();
                    if (TrackingDao.this.failOnPersist > 0 && call > TrackingDao.this.failOnPersist) {
                        throw new RuntimeException("duplicate row (simulated)");
                    }
                    final OvnLogicalIdMapVO entity = invocation.getArgument(0);
                    final String key = entity.getCsKind() + ":" + entity.getCsId() + ":" + entity.getControllerId();
                    rows.put(key, entity);
                    return entity;
                }
            });
        }
    }
}
