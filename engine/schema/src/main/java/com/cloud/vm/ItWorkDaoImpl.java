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
package com.cloud.vm;

import java.util.List;
import java.util.Optional;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.time.InaccurateClock;
import com.cloud.vm.ItWorkVO.Step;
import com.cloud.vm.VirtualMachine.State;

@Component
public class ItWorkDaoImpl extends GenericDaoBase<ItWorkVO, String> implements ItWorkDao {
    private static final Logger LOGGER = LogManager.getLogger(ItWorkDaoImpl.class);
    private static final String LOCK_MIGRATION_LEASE = "SELECT migration_generation, migration_phase, "
            + "migration_recovery_lease_owner, migration_recovery_lease_token, "
            + "migration_recovery_lease_version, migration_recovery_lease_expires_at "
            + "FROM op_it_work WHERE id = ? FOR UPDATE";
    private static final String UPDATE_MIGRATION_LEASE = "UPDATE op_it_work SET "
            + "migration_recovery_lease_owner = ?, migration_recovery_lease_token = ?, "
            + "migration_recovery_lease_version = ?, migration_recovery_lease_expires_at = ?, "
            + "migration_recovery_lease_heartbeat = ? WHERE id = ? AND migration_generation = ? "
            + "AND migration_recovery_lease_owner = ? AND migration_recovery_lease_token = ? "
            + "AND migration_recovery_lease_version = ? AND migration_recovery_lease_expires_at = ?";
    private static final String TERMINALIZE_MIGRATION = "UPDATE op_it_work SET migration_phase = ? "
            + "WHERE id = ? AND migration_generation = ? AND migration_phase = ? "
            + "AND migration_recovery_lease_owner = ? AND migration_recovery_lease_token = ? "
            + "AND migration_recovery_lease_version = ? AND migration_recovery_lease_expires_at > UNIX_TIMESTAMP()";
    protected final SearchBuilder<ItWorkVO> AllFieldsSearch;
    protected final SearchBuilder<ItWorkVO> CleanupSearch;
    protected final SearchBuilder<ItWorkVO> OutstandingWorkSearch;
    protected final SearchBuilder<ItWorkVO> WorkInProgressSearch;

    protected ItWorkDaoImpl() {
        super();

        AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("instance", AllFieldsSearch.entity().getInstanceId(), Op.EQ);
        AllFieldsSearch.and("op", AllFieldsSearch.entity().getType(), Op.EQ);
        AllFieldsSearch.and("step", AllFieldsSearch.entity().getStep(), Op.EQ);
        AllFieldsSearch.done();

        CleanupSearch = createSearchBuilder();
        CleanupSearch.and("step", CleanupSearch.entity().getStep(), Op.EQ);
        CleanupSearch.and("time", CleanupSearch.entity().getUpdatedAt(), Op.LT);
        CleanupSearch.done();

        OutstandingWorkSearch = createSearchBuilder();
        OutstandingWorkSearch.and("instance", OutstandingWorkSearch.entity().getInstanceId(), Op.EQ);
        OutstandingWorkSearch.and("op", OutstandingWorkSearch.entity().getType(), Op.EQ);
        OutstandingWorkSearch.and("step", OutstandingWorkSearch.entity().getStep(), Op.NEQ);
        OutstandingWorkSearch.done();

        WorkInProgressSearch = createSearchBuilder();
        WorkInProgressSearch.and("server", WorkInProgressSearch.entity().getManagementServerId(), Op.EQ);
        WorkInProgressSearch.and("step", WorkInProgressSearch.entity().getStep(), Op.NIN);
        WorkInProgressSearch.done();
    }

    @Override
    public ItWorkVO findByOutstandingWork(long instanceId, State state) {
        SearchCriteria<ItWorkVO> sc = OutstandingWorkSearch.create();
        sc.setParameters("instance", instanceId);
        sc.setParameters("op", state);
        sc.setParameters("step", Step.Done);

        return findOneBy(sc);
    }

    @Override
    public void cleanup(long wait) {
        SearchCriteria<ItWorkVO> sc = CleanupSearch.create();
        sc.setParameters("step", Step.Done);
        sc.setParameters("time", InaccurateClock.getTimeInSeconds() - wait);

        remove(sc);
    }

    @Override
    public boolean update(String id, ItWorkVO work) {
        work.setUpdatedAt(InaccurateClock.getTimeInSeconds());

        return super.update(id, work);
    }

    @Override
    public boolean updateStep(ItWorkVO work, Step step) {
        work.setStep(step);
        return update(work.getId(), work);
    }

    @Override
    public List<ItWorkVO> listWorkInProgressFor(long nodeId) {
        SearchCriteria<ItWorkVO> sc = WorkInProgressSearch.create();
        sc.setParameters("server", nodeId);
        sc.setParameters("step", Step.Done);

        return search(sc, null);

    }

    @Override
    public int expungeByVmList(List<Long> vmIds, Long batchSize) {
        if (CollectionUtils.isEmpty(vmIds)) {
            return 0;
        }
        SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("vmIds", sb.entity().getInstanceId(), SearchCriteria.Op.IN);
        SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("vmIds", vmIds.toArray());
        return batchExpunge(sc, batchSize);
    }

    @Override
    public boolean advanceMigrationPhase(final String id, final long generation,
            final ItWorkVO.MigrationPhase expectedPhase, final long ownerManagementId,
            final String leaseToken, final long leaseVersion, final long leaseExpiry,
            final ItWorkVO.MigrationPhase nextPhase) {
        if (id == null || expectedPhase == null || leaseToken == null || nextPhase == null
                || !expectedPhase.canTransitionTo(nextPhase) || leaseExpiry <= System.currentTimeMillis() / 1000L) {
            return false;
        }
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.and("generation", sb.entity().getMigrationGeneration(), Op.EQ);
        sb.and("phase", sb.entity().getMigrationPhaseValue(), Op.EQ);
        sb.and("leaseOwner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.EQ);
        sb.and("leaseToken", sb.entity().getMigrationRecoveryLeaseToken(), Op.EQ);
        sb.and("leaseVersion", sb.entity().getMigrationRecoveryLeaseVersion(), Op.EQ);
        sb.and("leaseExpiry", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.GT);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("id", id);
        sc.setParameters("generation", generation);
        sc.setParameters("phase", expectedPhase.name());
        sc.setParameters("leaseOwner", ownerManagementId);
        sc.setParameters("leaseToken", leaseToken);
        sc.setParameters("leaseVersion", leaseVersion);
        sc.setParameters("leaseExpiry", System.currentTimeMillis() / 1000L);
        final ItWorkVO work = findById(id);
        if (work == null || work.getMigrationGeneration() != generation
                || work.getMigrationPhase() != expectedPhase) {
            return false;
        }
        work.setMigrationPhase(nextPhase);
        final boolean updated = update(work, sc) == 1;
        if (updated) {
            work.setMigrationPhase(nextPhase);
        }
        return updated;
    }

    @Override
    public boolean markMigrationManualIntervention(final ItWorkVO work) {
        return work != null && advanceMigrationPhase(work.getId(), work.getMigrationGeneration(),
                work.getMigrationPhase(), work.getMigrationRecoveryLeaseOwner() == null
                        ? 0L : work.getMigrationRecoveryLeaseOwner(), work.getMigrationRecoveryLeaseToken(),
                work.getMigrationRecoveryLeaseVersion(), work.getMigrationRecoveryLeaseExpiresAt() == null
                        ? 0L : work.getMigrationRecoveryLeaseExpiresAt(), ItWorkVO.MigrationPhase.MANUAL_INTERVENTION);
    }

    @Override
    public ItWorkVO findNonterminalColdMigrationForVm(final long vmId) {
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("instanceId", sb.entity().getInstanceId(), Op.EQ);
        sb.and("type", sb.entity().getType(), Op.EQ);
        sb.and("phase", sb.entity().getMigrationPhaseValue(), Op.NNULL);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("instanceId", vmId);
        sc.setParameters("type", com.cloud.vm.VirtualMachine.State.Migrating);
        return search(sc, null).stream()
                .filter(work -> work.getMigrationPhase() != ItWorkVO.MigrationPhase.DONE)
                .findFirst().orElse(null);
    }

    @Override
    public long nextColdMigrationGeneration(final long vmId) {
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("instanceId", sb.entity().getInstanceId(), Op.EQ);
        sb.and("type", sb.entity().getType(), Op.EQ);
        sb.and("phase", sb.entity().getMigrationPhaseValue(), Op.NNULL);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("instanceId", vmId);
        sc.setParameters("type", com.cloud.vm.VirtualMachine.State.Migrating);
        return search(sc, null).stream().mapToLong(ItWorkVO::getMigrationGeneration).max().orElse(0L) + 1L;
    }

    @Override
    public List<ItWorkVO> listNonterminalColdMigrations() {
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("type", sb.entity().getType(), Op.EQ);
        sb.and("phase", sb.entity().getMigrationPhaseValue(), Op.NNULL);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("type", com.cloud.vm.VirtualMachine.State.Migrating);
        return search(sc, null).stream()
                .filter(work -> work.getMigrationPhase() != ItWorkVO.MigrationPhase.DONE
                        && work.getMigrationPhase() != ItWorkVO.MigrationPhase.MANUAL_INTERVENTION)
                .toList();
    }

    @Override
    public boolean claimMigrationLease(final ItWorkVO work, final long owner, final String token,
            final Long expectedOwner, final String expectedToken, final Long expectedExpiry,
            final long now, final long expiresAt) {
        if (work == null || token == null || token.isBlank()) {
            return false;
        }
        return claimLease(work, owner, token, expectedOwner, expectedToken, expectedExpiry, now, expiresAt);
    }

    @Override
    public Optional<MigrationLeaseClaim> takeOverExpiredMigrationLease(final String workId,
            final long generation, final long newOwner, final String newToken, final long now,
            final long expiresAt) {
        try {
            return Transaction.execute((TransactionCallback<Optional<MigrationLeaseClaim>>) status -> {
                final TransactionLegacy txn = TransactionLegacy.currentTxn();
                try (PreparedStatement lock = txn.prepareAutoCloseStatement(LOCK_MIGRATION_LEASE)) {
                    lock.setString(1, workId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next() || rs.getLong("migration_generation") != generation
                                || rs.getString("migration_phase") == null
                                || ItWorkVO.MigrationPhase.DONE.name().equals(rs.getString("migration_phase"))
                                || ItWorkVO.MigrationPhase.MANUAL_INTERVENTION.name().equals(rs.getString("migration_phase"))) {
                            return Optional.empty();
                        }
                        final long oldOwner = rs.getLong("migration_recovery_lease_owner");
                        final Long oldOwnerValue = rs.wasNull() ? null : oldOwner;
                        final String oldToken = rs.getString("migration_recovery_lease_token");
                        final long oldVersion = rs.getLong("migration_recovery_lease_version");
                        final long oldExpiryValue = rs.getLong("migration_recovery_lease_expires_at");
                        final Long oldExpiry = rs.wasNull() ? null : oldExpiryValue;
                        if (oldOwnerValue == null || oldToken == null || oldExpiry == null || oldExpiry >= now) {
                            return Optional.empty();
                        }
                        final long newVersion = oldVersion + 1;
                        try (PreparedStatement update = txn.prepareAutoCloseStatement(UPDATE_MIGRATION_LEASE)) {
                            update.setLong(1, newOwner); update.setString(2, newToken); update.setLong(3, newVersion);
                            update.setLong(4, expiresAt); update.setLong(5, now); update.setString(6, workId);
                            update.setLong(7, generation); update.setLong(8, oldOwnerValue); update.setString(9, oldToken);
                            update.setLong(10, oldVersion); update.setLong(11, oldExpiry);
                            if (update.executeUpdate() != 1) {
                                throw new MigrationTransactionException("Migration lease takeover predicate failed");
                            }
                        }
                        return Optional.of(new MigrationLeaseClaim(workId, generation, oldOwnerValue, oldToken,
                                oldVersion, oldExpiry, newOwner, newToken, newVersion, expiresAt));
                    }
                } catch (java.sql.SQLException e) {
                    throw new MigrationTransactionException("Unable to atomically take over migration lease", e);
                }
            });
        } catch (MigrationTransactionException e) {
            LOGGER.warn("Migration lease takeover failed for work {}", workId, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean terminalizeMigration(final String id, final long generation, final long owner,
            final String token, final long version) {
        final ItWorkVO work = findById(id);
        if (work == null || work.getMigrationGeneration() != generation
                || work.getMigrationPhase() != ItWorkVO.MigrationPhase.FENCE_CLEANUP_PENDING
                || work.getMigrationRecoveryLeaseExpiresAt() == null
                || work.getMigrationRecoveryLeaseExpiresAt() <= System.currentTimeMillis() / 1000L) {
            return false;
        }
        try {
            return Transaction.execute((TransactionCallback<Boolean>) status -> {
                final TransactionLegacy txn = TransactionLegacy.currentTxn();
                try (PreparedStatement update = txn.prepareAutoCloseStatement(TERMINALIZE_MIGRATION)) {
                    update.setString(1, ItWorkVO.MigrationPhase.DONE.name());
                    update.setString(2, id);
                    update.setLong(3, generation);
                    update.setString(4, ItWorkVO.MigrationPhase.FENCE_CLEANUP_PENDING.name());
                    update.setLong(5, owner);
                    update.setString(6, token);
                    update.setLong(7, version);
                    final boolean updated = update.executeUpdate() == 1;
                    if (!updated) {
                        throw new MigrationTransactionException("Migration terminalization predicate failed");
                    }
                } catch (java.sql.SQLException e) {
                    throw new MigrationTransactionException("Unable to terminalize migration work", e);
                }
                work.setMigrationPhase(ItWorkVO.MigrationPhase.DONE);
                return true;
            });
        } catch (MigrationTransactionException e) {
            LOGGER.warn("Migration terminalization failed for work {}", id, e);
            return false;
        }
    }

    private static final class MigrationTransactionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MigrationTransactionException(final String message) {
            super(message);
        }

        MigrationTransactionException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private boolean claimLease(final ItWorkVO work, final long owner, final String token,
            final Long expectedOwner, final String expectedToken, final Long expectedExpiry,
            final long now, final long expiresAt) {
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.and("generation", sb.entity().getMigrationGeneration(), Op.EQ);
        sb.and("version", sb.entity().getMigrationRecoveryLeaseVersion(), Op.EQ);
        if (expectedOwner == null) {
            sb.and("leaseOwner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.NULL);
            sb.and("leaseToken", sb.entity().getMigrationRecoveryLeaseToken(), Op.NULL);
            sb.and("leaseExpiryNull", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.NULL);
        } else {
            sb.and("leaseOwner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.EQ);
            sb.and("leaseToken", sb.entity().getMigrationRecoveryLeaseToken(), Op.EQ);
            sb.and("leaseExpiryExpected", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.EQ);
            sb.and("leaseExpired", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.LT);
        }
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("id", work.getId());
        sc.setParameters("generation", work.getMigrationGeneration());
        sc.setParameters("version", work.getMigrationRecoveryLeaseVersion());
        if (expectedOwner != null) {
            sc.setParameters("leaseOwner", expectedOwner);
            sc.setParameters("leaseToken", expectedToken);
            sc.setParameters("leaseExpiryExpected", expectedExpiry);
            sc.setParameters("leaseExpired", now);
        }
        work.setMigrationRecoveryLeaseOwner(owner);
        work.setMigrationRecoveryLeaseToken(token);
        work.setMigrationRecoveryLeaseExpiresAt(expiresAt);
        work.setMigrationRecoveryLeaseHeartbeat(now);
        work.setMigrationRecoveryLeaseVersion(work.getMigrationRecoveryLeaseVersion() + 1);
        final boolean updated = update(work, sc) == 1;
        if (!updated) {
            work.setMigrationRecoveryLeaseVersion(work.getMigrationRecoveryLeaseVersion() - 1);
        }
        return updated;
    }

    @Override
    public boolean renewMigrationLease(final ItWorkVO work, final long owner, final String token,
            final long version, final long now, final long expiresAt) {
        if (work == null || token == null || !token.equals(work.getMigrationRecoveryLeaseToken())
                || !Long.valueOf(owner).equals(work.getMigrationRecoveryLeaseOwner())
                || work.getMigrationRecoveryLeaseVersion() != version
                || work.getMigrationRecoveryLeaseExpiresAt() == null
                || work.getMigrationRecoveryLeaseExpiresAt() <= now) {
            return false;
        }
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.and("generation", sb.entity().getMigrationGeneration(), Op.EQ);
        sb.and("owner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.EQ);
        sb.and("token", sb.entity().getMigrationRecoveryLeaseToken(), Op.EQ);
        sb.and("version", sb.entity().getMigrationRecoveryLeaseVersion(), Op.EQ);
        sb.and("expiry", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.GT);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("id", work.getId());
        sc.setParameters("generation", work.getMigrationGeneration());
        sc.setParameters("owner", owner);
        sc.setParameters("token", token);
        sc.setParameters("version", version);
        sc.setParameters("expiry", now);
        final Long oldExpiry = work.getMigrationRecoveryLeaseExpiresAt();
        final Long oldHeartbeat = work.getMigrationRecoveryLeaseHeartbeat();
        work.setMigrationRecoveryLeaseExpiresAt(expiresAt);
        work.setMigrationRecoveryLeaseHeartbeat(now);
        final boolean updated = update(work, sc) == 1;
        if (updated) {
            work.setMigrationRecoveryLeaseVersion(version);
        } else {
            work.setMigrationRecoveryLeaseExpiresAt(oldExpiry);
            work.setMigrationRecoveryLeaseHeartbeat(oldHeartbeat);
        }
        return updated;
    }

    @Override
    public boolean releaseMigrationLease(final ItWorkVO work, final long owner, final String token,
            final long version) {
        if (work == null || token == null) {
            return false;
        }
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.and("generation", sb.entity().getMigrationGeneration(), Op.EQ);
        sb.and("owner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.EQ);
        sb.and("token", sb.entity().getMigrationRecoveryLeaseToken(), Op.EQ);
        sb.and("version", sb.entity().getMigrationRecoveryLeaseVersion(), Op.EQ);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("id", work.getId());
        sc.setParameters("generation", work.getMigrationGeneration());
        sc.setParameters("owner", owner);
        sc.setParameters("token", token);
        sc.setParameters("version", version);
        final Long oldOwner = work.getMigrationRecoveryLeaseOwner();
        final String oldToken = work.getMigrationRecoveryLeaseToken();
        final Long oldExpiry = work.getMigrationRecoveryLeaseExpiresAt();
        final Long oldHeartbeat = work.getMigrationRecoveryLeaseHeartbeat();
        work.setMigrationRecoveryLeaseOwner(null);
        work.setMigrationRecoveryLeaseToken(null);
        work.setMigrationRecoveryLeaseExpiresAt(null);
        work.setMigrationRecoveryLeaseHeartbeat(null);
        work.setMigrationRecoveryLeaseVersion(version + 1);
        final boolean updated = update(work, sc) == 1;
        if (!updated) {
            work.setMigrationRecoveryLeaseOwner(oldOwner);
            work.setMigrationRecoveryLeaseToken(oldToken);
            work.setMigrationRecoveryLeaseExpiresAt(oldExpiry);
            work.setMigrationRecoveryLeaseHeartbeat(oldHeartbeat);
            work.setMigrationRecoveryLeaseVersion(version);
        }
        return updated;
    }

    @Override
    public boolean updateMigrationWorkLeased(final ItWorkVO work, final long owner, final String token,
            final long version) {
        if (work == null || token == null || work.getMigrationRecoveryLeaseExpiresAt() == null) {
            return false;
        }
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.and("generation", sb.entity().getMigrationGeneration(), Op.EQ);
        sb.and("owner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.EQ);
        sb.and("token", sb.entity().getMigrationRecoveryLeaseToken(), Op.EQ);
        sb.and("version", sb.entity().getMigrationRecoveryLeaseVersion(), Op.EQ);
        sb.and("expiry", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.GT);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("id", work.getId());
        sc.setParameters("generation", work.getMigrationGeneration());
        sc.setParameters("owner", owner);
        sc.setParameters("token", token);
        sc.setParameters("version", version);
        sc.setParameters("expiry", System.currentTimeMillis() / 1000L);
        final Step oldStep = work.getStep();
        final boolean updated = update(work, sc) == 1;
        if (!updated) {
            work.setStep(oldStep);
        }
        return updated;
    }

    @Override
    public boolean updateMigrationStepLeased(final ItWorkVO work, final Step expectedStep,
            final Step nextStep, final long owner, final String token, final long version) {
        if (work == null || nextStep == null || token == null
                || work.getMigrationPhase() == null || work.getMigrationRecoveryLeaseExpiresAt() == null) {
            return false;
        }
        final SearchBuilder<ItWorkVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), Op.EQ);
        sb.and("generation", sb.entity().getMigrationGeneration(), Op.EQ);
        sb.and("phase", sb.entity().getMigrationPhaseValue(), Op.EQ);
        if (expectedStep == null) {
            sb.and("step", sb.entity().getStep(), Op.NULL);
        } else {
            sb.and("step", sb.entity().getStep(), Op.EQ);
        }
        sb.and("owner", sb.entity().getMigrationRecoveryLeaseOwner(), Op.EQ);
        sb.and("token", sb.entity().getMigrationRecoveryLeaseToken(), Op.EQ);
        sb.and("version", sb.entity().getMigrationRecoveryLeaseVersion(), Op.EQ);
        sb.and("expiry", sb.entity().getMigrationRecoveryLeaseExpiresAt(), Op.GT);
        final SearchCriteria<ItWorkVO> sc = sb.create();
        sc.setParameters("id", work.getId());
        sc.setParameters("generation", work.getMigrationGeneration());
        sc.setParameters("phase", work.getMigrationPhase().name());
        if (expectedStep != null) {
            sc.setParameters("step", expectedStep);
        }
        sc.setParameters("owner", owner);
        sc.setParameters("token", token);
        sc.setParameters("version", version);
        sc.setParameters("expiry", System.currentTimeMillis() / 1000L);
        work.setStep(nextStep);
        final boolean updated = update(work, sc) == 1;
        if (!updated) {
            work.setStep(expectedStep);
        }
        return updated;
    }
}
