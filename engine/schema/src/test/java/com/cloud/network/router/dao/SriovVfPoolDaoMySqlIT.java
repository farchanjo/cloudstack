// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
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
package com.cloud.network.router.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDaoImpl;

/**
 * Opt-in MySQL 8 integration coverage for the real VF DAO transaction SQL.
 * Run only through the vf-mysql-it Maven profile against cloudstack_vf_it.
 */
public class SriovVfPoolDaoMySqlIT {

    private static final String REQUIRED_DATABASE = "cloudstack_vf_it";
    private static final String SCHEMA_RESOURCE =
            "/com/cloud/network/router/dao/sriov-vf-mysql-it-schema.sql";
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private static SriovVfPoolDaoImpl dao;
    private static NicDaoImpl nicDao;

    @BeforeClass
    public static void configureDatabase() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("vf.mysql.it.enabled", "false"))) {
            throw new IllegalStateException("VF MySQL IT requires explicit vf.mysql.it.enabled=true");
        }
        final String database = requiredProperty("vf.mysql.it.database");
        final String host = requiredProperty("vf.mysql.it.host");
        if (!REQUIRED_DATABASE.equals(database)) {
            throw new IllegalStateException("Refusing unsafe integration database: " + database);
        }
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "vm.services".equalsIgnoreCase(host))) {
            throw new IllegalStateException("VF MySQL IT accepts only a local ephemeral database");
        }
        username = requiredProperty("vf.mysql.it.username");
        password = System.getProperty("vf.mysql.it.password", "");
        final int port = Integer.parseInt(requiredProperty("vf.mysql.it.port"));
        jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC", host, port, database);
        Class.forName("com.mysql.cj.jdbc.Driver");
        initializeCloudStackDataSource();
        dao = new SriovVfPoolDaoImpl();
        nicDao = new NicDaoImpl();
        try (Connection connection = newConnection()) {
            assertServerConfiguration(connection);
        }
        try (Connection connection = TransactionLegacy.getStandaloneConnectionWithException()) {
            assertEquals(REQUIRED_DATABASE, connection.getCatalog());
            assertServerConfiguration(connection);
        }
    }

    @AfterClass
    public static void clearReferences() {
        dao = null;
        nicDao = null;
    }

    @Before
    public void resetSchema() throws Exception {
        try (Connection connection = newConnection()) {
            executeSchema(connection);
        }
    }

    @Test
    public void selectForUpdateBlocksRealDaoAndLockHolderRollbackIsComplete() throws Exception {
        fixtureSingleNic();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = newConnection()) {
            holder.setAutoCommit(false);
            update(holder, "UPDATE sriov_vf_pool SET state='UNAVAILABLE' WHERE id=1000");
            lockRow(holder, 1000L);
            final CountDownLatch started = new CountDownLatch(1);
            final Future<Boolean> blocked = executor.submit(() -> {
                started.countDown();
                return dao.markSuspect(1000L, 100L);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertBlocked(blocked, Duration.ofMillis(500));
            holder.rollback();
            assertTrue(blocked.get(5, TimeUnit.SECONDS));
        } finally {
            shutdown(executor);
        }
        assertEquals("SUSPECT", stringValue("SELECT state FROM sriov_vf_pool WHERE id=1000"));
    }

    @Test
    public void concurrentDestinationReserveCreatesAtMostOneReservedRow() throws Exception {
        fixtureMigrationReserve();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            final Future<SriovVfPoolVO> left = executor.submit(() -> reserveAfterBarrier(barrier));
            final Future<SriovVfPoolVO> right = executor.submit(() -> reserveAfterBarrier(barrier));
            assertNotNull(left.get(10, TimeUnit.SECONDS));
            assertNotNull(right.get(10, TimeUnit.SECONDS));
        } finally {
            shutdown(executor);
        }
        assertEquals(1L, longValue("SELECT COUNT(*) FROM sriov_vf_pool WHERE state='RESERVED'"));
        assertEquals(1000L, longValue("SELECT vf_pool_id FROM nics WHERE id=100"));
        assertEquals(1L, longValue("SELECT COUNT(*) FROM sriov_vf_pool WHERE state='RESERVED' AND host_id=2"));
    }

    @Test
    public void duplicateAtomicMultiNicCommitHasOneAuthoritativeFinalTransition() throws Exception {
        fixtureMultiNicCommit();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            final Future<List<SriovVfPoolVO>> left = executor.submit(() -> commitAfterBarrier(barrier));
            final Future<List<SriovVfPoolVO>> right = executor.submit(() -> commitAfterBarrier(barrier));
            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        } finally {
            shutdown(executor);
        }
        assertCommitState(100L, 1000L, 2000L);
        assertCommitState(101L, 1001L, 2001L);
        assertEquals(2L, longValue("SELECT COUNT(*) FROM sriov_vf_pool WHERE state='SUSPECT'"));
        assertEquals(2L, longValue("SELECT COUNT(*) FROM sriov_vf_pool WHERE state='ALLOCATED' AND host_id=2"));
    }

    @Test
    public void exactReleaseConditionallyClearsOnlyItsOwnPointerAndReplayIsSafe() throws Exception {
        fixtureExactRelease();
        assertTrue(dao.releaseExact(1001L, 100L));
        assertEquals(1000L, longValue("SELECT vf_pool_id FROM nics WHERE id=100"));
        assertFalse(dao.releaseExact(1001L, 100L));
        assertTrue(dao.releaseExact(1000L, 100L));
        final NicVO observed = nicDao.findById(100L);
        assertNotNull(observed);
        assertNull(observed.getVfPoolId());
        assertFalse(dao.releaseExact(1000L, 100L));
        assertEquals(2L, longValue("SELECT COUNT(*) FROM sriov_vf_pool WHERE state='FREE'"));
    }

    @Test
    public void exactReleaseRejectsDirectAllocatedAndReservedRows() throws Exception {
        fixtureSingleNic();
        insertPool(1001L, 1L, "0000:01:00.2", "ALLOCATED", 100L);
        insertPool(1002L, 1L, "0000:01:00.3", "RESERVED", 100L);

        assertFalse(dao.releaseExact(1000L, 100L));
        assertFalse(dao.releaseExact(1001L, 100L));
        assertFalse(dao.releaseExact(1002L, 100L));
        assertEquals("ALLOCATED", poolState(1000L));
        assertEquals("ALLOCATED", poolState(1001L));
        assertEquals("RESERVED", poolState(1002L));
    }

    @Test
    public void sameHostPassthroughVdpaKindMismatchIsRejected() throws Exception {
        fixtureSingleNic();
        update("UPDATE sriov_vf_pool SET vdpa_kind='VDPA', vdpa_name='vdpa-100' WHERE id=1000");

        expectCloudRuntime(() -> dao.allocateOrReserve(1L, 100L, VdpaKind.PASSTHROUGH, null));

        update("UPDATE sriov_vf_pool SET vdpa_kind='PASSTHROUGH', vdpa_name=NULL WHERE id=1000");
        expectCloudRuntime(() -> dao.allocateOrReserve(1L, 100L, VdpaKind.VDPA, "vdpa-100"));
    }

    @Test
    public void softRemovedNicWithNullReversePointerReplaysSuspectCleanupUntilExactRelease() throws Exception {
        insertHost(22L);
        insertVm(1591L, 22L);
        insertNic(8934L, 1591L, 980L);
        update("UPDATE nics SET removed=NOW(), vf_pool_id=NULL WHERE id=8934");
        insertPool(980L, 22L, "0000:01:04.1", "ALLOCATED", 8934L);
        insertPool(981L, 22L, "0000:01:04.2", "RESERVED", 8934L);
        insertPool(982L, 22L, "0000:01:04.3", "SUSPECT", 8934L);

        assertEquals(3, dao.quarantineAndListByVmId(1591L).size());
        assertEquals("SUSPECT", poolState(980L));
        assertEquals("SUSPECT", poolState(981L));
        assertEquals("SUSPECT", poolState(982L));
        assertEquals(3, dao.quarantineAndListByVmId(1591L).size());
        assertEquals("SUSPECT", poolState(980L));

        assertTrue(dao.releaseExact(980L, 8934L));
        assertTrue(dao.releaseExact(981L, 8934L));
        assertTrue(dao.releaseExact(982L, 8934L));
        assertEquals("FREE", poolState(980L));
        assertNull(longValueOrNull("SELECT vf_pool_id FROM nics WHERE id=8934"));
        assertTrue(dao.quarantineAndListByVmId(1591L).isEmpty());
        assertFalse(dao.releaseExact(980L, 8934L));
    }

    @Test
    public void suspectOwnershipFailsClosedUntilExactReleaseAndReplayIsIdempotent() throws Exception {
        insertHost(22L);
        insertVm(1592L, 22L);
        insertNic(8935L, 1592L, null);
        update("UPDATE nics SET removed=NOW(), vf_pool_id=NULL WHERE id=8935");
        insertPool(981L, 22L, "0000:01:04.2", "SUSPECT", 8935L);

        assertEquals(1, dao.quarantineAndListByVmId(1592L).size());
        assertEquals("SUSPECT", poolState(981L));
        expectCloudRuntime(() -> assertNull(
                dao.allocateOrReserve(22L, 8935L, VdpaKind.PASSTHROUGH, null)));
        assertTrue(dao.releaseExact(981L, 8935L));
        assertEquals("FREE", poolState(981L));
        assertTrue(dao.quarantineAndListByVmId(1592L).isEmpty());
        assertFalse(dao.releaseExact(981L, 8935L));
    }

    @Test
    public void reconciliationRejectsReservedConflictingWorkAndVmHostDrift() throws Exception {
        fixtureReconciliation();
        final VfReconciliationCandidate candidate =
                new VfReconciliationCandidate(10L, 100L, 1L, 1000L, 1001L, false);
        insertPool(1002L, 1L, "0000:01:00.3", "RESERVED", 100L);
        assertFalse(dao.prepareReconciliationPlan(List.of(candidate)));
        update("DELETE FROM sriov_vf_pool WHERE id=1002");
        insertWork("race-work", 10L, 2L, "Migrating", "Started");
        expectCloudRuntime(() -> dao.prepareReconciliationPlan(List.of(candidate)));
        update("DELETE FROM op_it_work");
        update("UPDATE vm_instance SET host_id=2 WHERE id=10");
        expectCloudRuntime(() -> dao.prepareReconciliationPlan(List.of(candidate)));
        assertEquals("ALLOCATED", stringValue("SELECT state FROM sriov_vf_pool WHERE id=1001"));
    }

    @Test
    public void immutableIncidentResumesEveryCrashBoundaryAndReachesExactFinalCounts() throws Exception {
        final List<VfReconciliationCandidate> incident = fixtureImmutableIncident();
        expectCloudRuntime(() -> Transaction.execute((TransactionCallback<Boolean>) status -> {
            assertTrue(dao.prepareReconciliationPlan(incident));
            throw new CloudRuntimeException("simulated crash after quarantine");
        }));
        assertEquals(27L, countState("ALLOCATED"));
        assertEquals(237L, countState("FREE"));
        assertTrue(dao.prepareReconciliationPlan(incident));
        assertEquals(19L, countState("ALLOCATED"));
        assertEquals(234L, countState("FREE"));
        final VfReconciliationCandidate first = incident.get(0);
        expectCloudRuntime(() -> Transaction.execute((TransactionCallback<Boolean>) status -> {
            assertTrue(complete(first));
            throw new CloudRuntimeException("simulated crash after agent success before DB completion");
        }));
        assertEquals("SUSPECT", poolState(first.getStalePoolId()));
        assertTrue(complete(first));
        assertFalse(complete(first));
        for (int index = 1; index < 5; index++) {
            assertTrue(complete(incident.get(index)));
        }
        final List<VfReconciliationCandidate> remaining = incompleteCandidates(incident);
        assertTrue(dao.prepareReconciliationPlan(remaining));
        for (final VfReconciliationCandidate candidate : remaining) {
            assertTrue(complete(candidate));
        }
        assertEquals(19L, countState("ALLOCATED"));
        assertEquals(245L, countState("FREE"));
        assertEquals("SUSPECT", poolState(999999L));
        assertTrue(incompleteCandidates(incident).isEmpty());
    }

    @Test
    public void realDeadlockIsRetriedAndLogicalUpdatesCommitExactlyOnce() throws Exception {
        update("INSERT INTO vf_it_deadlock(id,counter_value) VALUES (1,0),(2,0)");
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CyclicBarrier firstAttemptBarrier = new CyclicBarrier(2);
        final AtomicInteger leftAttempts = new AtomicInteger();
        final AtomicInteger rightAttempts = new AtomicInteger();
        try {
            final Future<Boolean> left = executor.submit(() ->
                    deadlockWorker(1L, 2L, leftAttempts, firstAttemptBarrier));
            final Future<Boolean> right = executor.submit(() ->
                    deadlockWorker(2L, 1L, rightAttempts, firstAttemptBarrier));
            assertTrue(left.get(15, TimeUnit.SECONDS));
            assertTrue(right.get(15, TimeUnit.SECONDS));
        } finally {
            shutdown(executor);
        }
        assertTrue(leftAttempts.get() > 1 || rightAttempts.get() > 1);
        assertEquals(2L, longValue("SELECT counter_value FROM vf_it_deadlock WHERE id=1"));
        assertEquals(2L, longValue("SELECT counter_value FROM vf_it_deadlock WHERE id=2"));
    }

    @Test
    public void forcedMidTransactionExceptionRollsBackPoolAndNicPointer() throws Exception {
        fixtureSingleNic();
        expectCloudRuntime(() -> Transaction.execute((TransactionCallback<Boolean>) status -> {
            assertTrue(dao.releaseExact(1000L, 100L));
            throw new CloudRuntimeException("forced rollback");
        }));
        assertEquals("ALLOCATED", poolState(1000L));
        assertEquals(1000L, longValue("SELECT vf_pool_id FROM nics WHERE id=100"));
        assertEquals(100L, longValue("SELECT allocated_to_nic_id FROM sriov_vf_pool WHERE id=1000"));
    }

    private static void initializeCloudStackDataSource() throws Exception {
        Class.forName(TransactionLegacy.class.getName(), true, TransactionLegacy.class.getClassLoader());
    }

    private static void assertServerConfiguration(final Connection connection) throws SQLException {
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT VERSION(), @@transaction_isolation, @@innodb_deadlock_detect")) {
            assertTrue(result.next());
            assertTrue("Expected MySQL 8.x but got " + result.getString(1), result.getString(1).startsWith("8."));
            assertEquals("REPEATABLE-READ", result.getString(2));
            assertEquals(1, result.getInt(3));
        }
    }

    private static void executeSchema(final Connection connection) throws IOException, SQLException {
        try (InputStream stream = SriovVfPoolDaoMySqlIT.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing schema resource " + SCHEMA_RESOURCE);
            }
            final String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^--.*$", "");
            for (final String sql : script.split(";")) {
                if (!sql.trim().isEmpty()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private static void fixtureSingleNic() throws SQLException {
        insertHost(1L);
        insertVm(10L, 1L);
        insertNic(100L, 10L, 1000L);
        insertPool(1000L, 1L, "0000:01:00.1", "ALLOCATED", 100L);
    }

    private static void fixtureMigrationReserve() throws SQLException {
        fixtureSingleNic();
        insertHost(2L);
        insertPool(2000L, 2L, "0000:02:00.1", "FREE", null);
        insertPool(2001L, 2L, "0000:02:00.2", "FREE", null);
    }

    private static void fixtureMultiNicCommit() throws SQLException {
        insertHost(1L);
        insertHost(2L);
        insertVm(10L, 2L);
        insertNic(100L, 10L, 1000L);
        insertNic(101L, 10L, 1001L);
        insertPool(1000L, 1L, "0000:01:00.1", "ALLOCATED", 100L);
        insertPool(1001L, 1L, "0000:01:00.2", "ALLOCATED", 101L);
        insertPool(2000L, 2L, "0000:02:00.1", "RESERVED", 100L);
        insertPool(2001L, 2L, "0000:02:00.2", "RESERVED", 101L);
        insertWork("work-commit", 10L, 2L, "Migrating", "Started");
    }

    private static void fixtureExactRelease() throws SQLException {
        fixtureSingleNic();
        insertPool(1001L, 1L, "0000:01:00.2", "ALLOCATED", 100L);
    }

    private static void fixtureReconciliation() throws SQLException {
        insertHost(1L);
        insertHost(2L);
        insertVm(10L, 1L);
        insertNic(100L, 10L, 1000L);
        insertPool(1000L, 1L, "0000:01:00.1", "ALLOCATED", 100L);
        insertPool(1001L, 2L, "0000:02:00.1", "ALLOCATED", 100L);
    }

    private static List<VfReconciliationCandidate> fixtureImmutableIncident() throws SQLException {
        insertHost(16L);
        insertHost(269L);
        insertHost(300L);
        final long[][] specs = {{857, 833, 8820}, {1427, 833, 8820}, {2435, 833, 8820},
                {764, 827, 8913}, {896, 827, 8913}, {995, 827, 8913}, {1469, 827, 8913},
                {2537, 827, 8913}, {1625, 1022, 8829}, {818, 749, 8847}, {1016, 2468, 8925}};
        final Map<Long, Long> vmByNic = Map.of(8820L, 1553L, 8913L, 1554L, 8829L, 1555L,
                8847L, 1556L, 8925L, 1557L);
        for (final Map.Entry<Long, Long> entry : vmByNic.entrySet()) {
            insertVm(entry.getValue(), 16L);
        }
        insertNic(8820L, 1553L, 833L);
        insertNic(8913L, 1554L, 827L);
        insertNic(8829L, 1555L, 1625L);
        insertNic(8847L, 1556L, 818L);
        insertNic(8925L, 1557L, 1016L);
        insertPool(833L, 16L, pci(833L), "ALLOCATED", 8820L);
        insertPool(827L, 16L, pci(827L), "ALLOCATED", 8913L);
        final List<VfReconciliationCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < specs.length; index++) {
            final long stale = specs[index][0];
            final long current = specs[index][1];
            final long nic = specs[index][2];
            final boolean promote = index >= 8;
            insertPool(stale, 269L, pci(stale), "ALLOCATED", nic);
            if (promote) {
                insertPool(current, 16L, pci(current), "FREE", null);
            }
            candidates.add(new VfReconciliationCandidate(vmByNic.get(nic), nic, 16L, current, stale, promote));
        }
        insertIncidentFillers();
        insertVm(9999L, 300L);
        insertNic(9999L, 9999L, null);
        insertPool(999999L, 300L, pci(999999L), "SUSPECT", 9999L);
        assertEquals(27L, countState("ALLOCATED"));
        assertEquals(237L, countState("FREE"));
        return candidates;
    }

    private static void insertIncidentFillers() throws SQLException {
        for (int index = 0; index < 14; index++) {
            final long vm = 2000L + index;
            final long nic = 9000L + index;
            final long pool = 5000L + index;
            insertVm(vm, 300L);
            insertNic(nic, vm, pool);
            insertPool(pool, 300L, pci(pool), "ALLOCATED", nic);
        }
        for (int index = 0; index < 234; index++) {
            final long pool = 10000L + index;
            insertPool(pool, 300L, pci(pool), "FREE", null);
        }
    }

    private static List<VfReconciliationCandidate> incompleteCandidates(
            final List<VfReconciliationCandidate> candidates) throws SQLException {
        final List<VfReconciliationCandidate> result = new ArrayList<>();
        for (final VfReconciliationCandidate candidate : candidates) {
            if (!"FREE".equals(poolState(candidate.getStalePoolId()))) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean complete(final VfReconciliationCandidate candidate) {
        return dao.completeReconciliation(candidate.getVmId(), candidate.getNicId(),
                candidate.getCurrentHostId(), candidate.getCurrentPoolId(), candidate.getStalePoolId());
    }

    private static SriovVfPoolVO reserveAfterBarrier(final CyclicBarrier barrier) throws Exception {
        await(barrier);
        return dao.allocateOrReserve(2L, 100L, VdpaKind.PASSTHROUGH, null);
    }

    private static List<SriovVfPoolVO> commitAfterBarrier(final CyclicBarrier barrier) throws Exception {
        await(barrier);
        return dao.commitVmReservations(10L, 1L, 2L, "work-commit");
    }

    private static boolean deadlockWorker(final long first, final long second,
                                          final AtomicInteger attempts,
                                          final CyclicBarrier firstAttemptBarrier) {
        return dao.executeWithDeadlockRetry(() -> Transaction.execute(
                (TransactionCallback<Boolean>) status -> {
            final int attempt = attempts.incrementAndGet();
            incrementDeadlockRow(first);
            if (attempt == 1) {
                await(firstAttemptBarrier);
            }
            incrementDeadlockRow(second);
            return true;
        }));
    }

    private static void incrementDeadlockRow(final long id) {
        try {
            final PreparedStatement statement = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                    "UPDATE vf_it_deadlock SET counter_value=counter_value+1 WHERE id=?");
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("Deadlock fixture update failed", e);
        }
    }

    private static void assertCommitState(final long nicId, final long sourceId,
                                          final long destinationId) throws SQLException {
        assertEquals(destinationId, longValue("SELECT vf_pool_id FROM nics WHERE id=" + nicId));
        assertEquals("SUSPECT", poolState(sourceId));
        assertEquals("ALLOCATED", poolState(destinationId));
    }

    private static void insertHost(final long id) throws SQLException {
        update("INSERT INTO host(id) VALUES (?)", id);
    }

    private static void insertVm(final long id, final long hostId) throws SQLException {
        update("INSERT INTO vm_instance(id,state,host_id,removed) VALUES (?,'Running',?,NULL)", id, hostId);
    }

    private static void insertNic(final long id, final long vmId, final Long poolId) throws SQLException {
        update("INSERT INTO nics(id,instance_id,mac_address,network_id,state,device_id,default_nic,"
                + "created,uuid,secondary_ip,enabled,vf_pool_id) VALUES (?,?,?,1,'Allocated',0,0,NOW(),?,0,1,?)",
                id, vmId, mac(id), UUID.randomUUID().toString(), poolId);
    }

    private static void insertPool(final long id, final long hostId, final String pci,
                                   final String state, final Long nicId) throws SQLException {
        update("INSERT INTO sriov_vf_pool(id,uuid,host_id,pci_address,pf_name,representor_name,state,"
                + "allocated_to_nic_id,vdpa_kind,created) VALUES (?,?,?,?,?,? ,?,?,?,NOW())",
                id, UUID.randomUUID().toString(), hostId, pci, "pf" + hostId,
                "rep" + id, state, nicId, "PASSTHROUGH");
    }

    private static void insertWork(final String id, final long vmId, final long resourceId,
                                   final String type, final String step) throws SQLException {
        update("INSERT INTO op_it_work(id,mgmt_server_id,created_at,thread,updated_at,instance_id,"
                + "resource_id,resource_type,type,step,vm_type) VALUES (?,1,UNIX_TIMESTAMP(),?,"
                + "UNIX_TIMESTAMP(),?,?,'Host',?,?,'User')", id, "vf-mysql-it", vmId, resourceId, type, step);
    }

    private static void lockRow(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM sriov_vf_pool WHERE id=? FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    private static void assertBlocked(final Future<?> future, final Duration timeout) throws Exception {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            fail("Expected real InnoDB row lock blocking");
        } catch (TimeoutException expected) {
            assertFalse(future.isDone());
        }
    }

    private static void expectCloudRuntime(final Runnable operation) {
        try {
            operation.run();
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void await(final CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new CloudRuntimeException("Concurrency barrier failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException("Concurrency barrier interrupted", e);
        }
    }

    private static void shutdown(final ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static long countState(final String state) throws SQLException {
        return longValue("SELECT COUNT(*) FROM sriov_vf_pool WHERE state='" + state + "'");
    }

    private static String poolState(final long id) throws SQLException {
        return stringValue("SELECT state FROM sriov_vf_pool WHERE id=" + id);
    }

    private static long longValue(final String sql) throws SQLException {
        try (Connection connection = newConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static Long longValueOrNull(final String sql) throws SQLException {
        try (Connection connection = newConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            final long value = result.getLong(1);
            return result.wasNull() ? null : value;
        }
    }

    private static String stringValue(final String sql) throws SQLException {
        try (Connection connection = newConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void update(final String sql, final Object... parameters) throws SQLException {
        try (Connection connection = newConnection()) {
            update(connection, sql, parameters);
        }
    }

    private static void update(final Connection connection, final String sql,
                               final Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private static Connection newConnection() throws SQLException {
        final Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        return connection;
    }

    private static String requiredProperty(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty() || value.startsWith("${")) {
            throw new IllegalStateException("Missing explicit integration property " + name);
        }
        return value.trim();
    }

    private static String mac(final long id) {
        return String.format("02:00:00:%02x:%02x:%02x", id >> 16 & 255, id >> 8 & 255, id & 255);
    }

    private static String pci(final long id) {
        return String.format("0000:%02x:%02x.%d", id / 256 & 255, id / 8 & 31, id & 7);
    }
}
