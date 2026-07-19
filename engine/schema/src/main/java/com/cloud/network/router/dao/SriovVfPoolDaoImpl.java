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
package com.cloud.network.router.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.network.router.SriovVfPoolVO.VdpaKind;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
@DB
public class SriovVfPoolDaoImpl extends GenericDaoBase<SriovVfPoolVO, Long> implements SriovVfPoolDao {

    private static final Logger LOGGER = LogManager.getLogger(SriovVfPoolDaoImpl.class);

    /**
     * Orphan sweep — any ALLOCATED VF whose NIC or VM is gone becomes SUSPECT.
     * Destructive cleanup and FREE transition require a later exact-BDF agent
     * confirmation; absence in the application database is not sufficient.
     */
    private static final String SQL_SWEEP_ORPHANS =
            "UPDATE sriov_vf_pool p " +
            "LEFT JOIN nics n ON n.id = p.allocated_to_nic_id " +
            "LEFT JOIN vm_instance v ON v.id = n.instance_id " +
            "SET p.state = 'SUSPECT', p.updated = NOW() " +
            "WHERE p.state = 'ALLOCATED' " +
            "  AND ( p.allocated_to_nic_id IS NULL " +
            "     OR n.id IS NULL " +
            "     OR n.removed IS NOT NULL " +
            "     OR v.removed IS NOT NULL )";

    /**
     * Mark every ALLOCATED row on the host SUSPECT in one statement. The
     * Phase H.1 reconciler uses this when an agent disconnects so the operator
     * can review the affected VFs without auto-release.
     */
    private static final String SQL_MARK_SUSPECT_BY_HOST_ID =
            "UPDATE sriov_vf_pool " +
            "   SET state = 'SUSPECT', updated = NOW() " +
            " WHERE host_id = ? AND state = 'ALLOCATED'";


    /**
     * Stale ALLOCATED rows: last_seen older than NOW() - threshold seconds, OR
     * last_seen IS NULL (the agent never confirmed this row since the column
     * was added). Caller flips them to SUSPECT.
     */
    private static final String SQL_FIND_STALE_ALLOCATED =
            "SELECT id FROM sriov_vf_pool " +
            " WHERE state = 'ALLOCATED' " +
            "   AND (last_seen IS NULL OR last_seen < (NOW() - INTERVAL ? SECOND))";

    /**
     * Reverse pointer write: stamp {@code nics.vf_pool_id = ?} on the NIC
     * that the allocator just bound. Fired inside the same transaction as
     * the {@code sriov_vf_pool} row update so the two columns stay in sync.
     *
     * <p>The pointer is also the compare-and-clear guard used by exact release
     * and the canonical anchor validated by atomic commit/reconciliation.
     */
    private static final String SQL_BIND_NIC_VF_POOL_ID =
            "UPDATE nics SET vf_pool_id = ? WHERE id = ?";

    /**
     * Inverse of {@link #SQL_BIND_NIC_VF_POOL_ID} — clears the reverse
     * pointer when a VF is released. Fired inside the same release
     * transaction so a NIC that was bound through {@code allocate()} /
     * {@code allocateForVdpa()} ends up with {@code vf_pool_id IS NULL}
     * once exact cleanup evidence allows the VF to go back to FREE.
     */
    private static final String SQL_UNBIND_NIC_VF_POOL_ID_EXACT =
            "UPDATE nics SET vf_pool_id = NULL WHERE id = ? AND vf_pool_id = ?";

    private static final String SQL_LOCK_NIC_VF_POOL_ID =
            "SELECT vf_pool_id FROM nics WHERE id = ? FOR UPDATE";

    private static final String SQL_LOCK_POOL_ROWS_FOR_NIC =
            "SELECT id FROM sriov_vf_pool WHERE allocated_to_nic_id = ? ORDER BY id FOR UPDATE";

    private static final String SQL_FIND_VM_ID_FOR_NIC =
            "SELECT instance_id FROM nics WHERE id = ?";

    private static final String SQL_LOCK_VM =
            "SELECT state, host_id FROM vm_instance WHERE id = ? FOR UPDATE";

    private static final String SQL_LOCK_VM_NICS =
            "SELECT id, vf_pool_id FROM nics WHERE instance_id = ? ORDER BY id FOR UPDATE";

    private static final String SQL_LOCK_WORK =
            "SELECT instance_id, resource_id, type, step FROM op_it_work WHERE id = ? FOR UPDATE";

    private static final String SQL_LOCK_LATEST_VM_WORK =
            "SELECT id FROM op_it_work WHERE instance_id = ? " +
            "ORDER BY updated_at DESC, created_at DESC, id DESC LIMIT 1 FOR UPDATE";

    private static final String SQL_COUNT_CONFLICTING_WORK =
            "SELECT COUNT(*) FROM op_it_work WHERE instance_id = ? " +
            "AND type IN ('Starting','Stopping','Migrating') AND step <> 'Done'";

    // Broad quarantine intentionally leaves nics.vf_pool_id intact. Only
    // exact release may conditionally clear the pointer to its own row.

    private final SearchBuilder<SriovVfPoolVO> hostStateSearch;
    private final SearchBuilder<SriovVfPoolVO> hostPciSearch;
    private final SearchBuilder<SriovVfPoolVO> nicIdSearch;
    private final SearchBuilder<SriovVfPoolVO> hostNicIdSearch;
    /** Free + vDPA-capable VFs on a host. Used by {@link #findFreeVdpaCapableVf}. */
    private final SearchBuilder<SriovVfPoolVO> hostStateKindSearch;
    /** Lookup by {@code (hostId, vdpaName)} for the agent advertise reconciler. */
    private final SearchBuilder<SriovVfPoolVO> hostVdpaNameSearch;

    public SriovVfPoolDaoImpl() {
        hostStateSearch = createSearchBuilder();
        hostStateSearch.and("hostId", hostStateSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostStateSearch.and("state", hostStateSearch.entity().getState(), SearchCriteria.Op.EQ);
        hostStateSearch.done();

        hostPciSearch = createSearchBuilder();
        hostPciSearch.and("hostId", hostPciSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostPciSearch.and("pciAddress", hostPciSearch.entity().getPciAddress(), SearchCriteria.Op.EQ);
        hostPciSearch.done();

        nicIdSearch = createSearchBuilder();
        nicIdSearch.and("allocatedToNicId", nicIdSearch.entity().getAllocatedToNicId(), SearchCriteria.Op.EQ);
        nicIdSearch.done();

        hostNicIdSearch = createSearchBuilder();
        hostNicIdSearch.and("hostId", hostNicIdSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostNicIdSearch.and("allocatedToNicId", hostNicIdSearch.entity().getAllocatedToNicId(), SearchCriteria.Op.EQ);
        hostNicIdSearch.done();

        hostStateKindSearch = createSearchBuilder();
        hostStateKindSearch.and("hostId", hostStateKindSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostStateKindSearch.and("state", hostStateKindSearch.entity().getState(), SearchCriteria.Op.EQ);
        hostStateKindSearch.and("vdpaKind", hostStateKindSearch.entity().getVdpaKind(), SearchCriteria.Op.EQ);
        hostStateKindSearch.done();

        hostVdpaNameSearch = createSearchBuilder();
        hostVdpaNameSearch.and("hostId", hostVdpaNameSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostVdpaNameSearch.and("vdpaName", hostVdpaNameSearch.entity().getVdpaName(), SearchCriteria.Op.EQ);
        hostVdpaNameSearch.done();
    }

    @Override
    public List<SriovVfPoolVO> listByHost(long hostId) {
        SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        return listBy(sc);
    }

    @Override
    public List<SriovVfPoolVO> listByHostAndState(long hostId, State state) {
        SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", state.name());
        return listBy(sc);
    }

    @Override
    public SriovVfPoolVO findByHostAndPci(long hostId, String pciAddress) {
        SearchCriteria<SriovVfPoolVO> sc = hostPciSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("pciAddress", pciAddress);
        return findOneBy(sc);
    }

    @Override
    public int countByHostAndState(long hostId, State state) {
        SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", state.name());
        return getCount(sc);
    }

    @Override
    public int countFreeVdpaCapable(final long hostId) {
        final SearchCriteria<SriovVfPoolVO> sc = hostStateKindSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", State.FREE.name());
        sc.setParameters("vdpaKind", VdpaKind.PASSTHROUGH.name());
        return getCount(sc);
    }

    @Override
    public SriovVfPoolVO allocate(final long hostId, final long nicId) {
        return allocateOrReserve(hostId, nicId, VdpaKind.PASSTHROUGH, null);
    }

    @Override
    public SriovVfPoolVO allocateOrReserve(final long hostId, final long nicId,
                                           final VdpaKind kind, final String vdpaName) {
        return Transaction.execute(new TransactionCallback<SriovVfPoolVO>() {
            @Override
            public SriovVfPoolVO doInTransaction(TransactionStatus status) {
                lockVmForNic(nicId);
                final Long canonicalId = lockNicAndReadVfPoolId(nicId);
                final List<SriovVfPoolVO> existing = lockRowsForNic(nicId);
                for (final SriovVfPoolVO row : existing) {
                    if (row.getHostId() == hostId) {
                        if (State.ALLOCATED.name().equals(row.getState())
                                || State.RESERVED.name().equals(row.getState())) {
                            return row;
                        }
                        throw new CloudRuntimeException(String.format(
                                "VF pool row %d for nic=%d host=%d is %s; refusing to reuse uncertain ownership",
                                row.getId(), nicId, hostId, row.getState()));
                    }
                }

                final SriovVfPoolVO canonical = canonicalId == null ? null : findById(canonicalId);
                final boolean reserve = canonical != null
                        && canonical.getHostId() != hostId
                        && Long.valueOf(nicId).equals(canonical.getAllocatedToNicId())
                        && !State.FREE.name().equals(canonical.getState());
                SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
                sc.setParameters("hostId", hostId);
                sc.setParameters("state", State.FREE.name());
                SriovVfPoolVO vf = lockOneRandomRow(sc, true);
                if (vf == null) {
                    return null;
                }
                SriovVfPoolVO updateVo = createForUpdate();
                applyOwnershipState(updateVo, nicId, reserve ? State.RESERVED : State.ALLOCATED, kind, vdpaName);
                update(vf.getId(), updateVo);
                applyOwnershipState(vf, nicId, reserve ? State.RESERVED : State.ALLOCATED, kind, vdpaName);
                if (!reserve) {
                    bindNicVfPoolId(nicId, vf.getId());
                }
                return vf;
            }
        });
    }

    @Override
    public boolean release(final long vfPoolId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                final SriovVfPoolVO observed = findById(vfPoolId);
                if (observed == null || observed.getAllocatedToNicId() == null) {
                    return false;
                }
                final Long expectedNic = observed.getAllocatedToNicId();
                lockVmForNic(expectedNic);
                lockNicAndReadVfPoolId(expectedNic);
                final SriovVfPoolVO vf = lockRow(vfPoolId, true);
                if (vf == null || !expectedNic.equals(vf.getAllocatedToNicId())) {
                    return false;
                }
                markSuspectLocked(vf);
                return true;
            }
        });
    }

    @Override
    public List<SriovVfPoolVO> listByNicId(final long nicId) {
        SearchCriteria<SriovVfPoolVO> sc = nicIdSearch.create();
        sc.setParameters("allocatedToNicId", nicId);
        return listBy(sc);
    }

    @Override
    public List<SriovVfPoolVO> commitVmReservations(final long vmId, final Long expectedSourceHostId,
                                                     final long destinationHostId, final String workId) {
        return executeWithDeadlockRetry(() -> Transaction.execute(
                (TransactionCallback<List<SriovVfPoolVO>>) status -> {
            final VmState vm = lockVm(vmId);
            requireAuthoritativeDestination(vmId, vm, destinationHostId);
            validateWork(vmId, destinationHostId, workId);
            final Map<Long, Long> canonicalByNic = lockVmNics(vmId);
            final Map<Long, List<SriovVfPoolVO>> rowsByNic = lockPoolRowsForNics(canonicalByNic);
            final List<CommitChange> changes = validateVmCommit(vmId, expectedSourceHostId,
                    destinationHostId, canonicalByNic, rowsByNic);
            final List<SriovVfPoolVO> prior = new ArrayList<>();
            for (final CommitChange change : changes) {
                final SriovVfPoolVO promote = createForUpdate();
                promote.setState(State.ALLOCATED.name());
                update(change.destination.getId(), promote);
                bindNicVfPoolId(change.nicId, change.destination.getId());
                for (final SriovVfPoolVO row : change.prior) {
                    markSuspectLocked(row);
                    prior.add(row);
                }
            }
            return prior;
                }));
    }

    @Override
    public List<SriovVfPoolVO> quarantineVmDestinationRows(final long vmId, final long destinationHostId,
                                                           final boolean includeAllocated, final String workId) {
        return executeWithDeadlockRetry(() -> Transaction.execute(
                (TransactionCallback<List<SriovVfPoolVO>>) status -> {
            lockVm(vmId);
            validateWorkIfPresent(vmId, destinationHostId, workId);
            final Map<Long, Long> canonicalByNic = lockVmNics(vmId);
            final Map<Long, List<SriovVfPoolVO>> rowsByNic = lockPoolRowsForNics(canonicalByNic);
            final List<SriovVfPoolVO> quarantined = new ArrayList<>();
            for (final List<SriovVfPoolVO> rows : rowsByNic.values()) {
                for (final SriovVfPoolVO row : rows) {
                    final boolean eligible = State.RESERVED.name().equals(row.getState())
                            || includeAllocated && State.ALLOCATED.name().equals(row.getState())
                            || State.SUSPECT.name().equals(row.getState());
                    if (row.getHostId() != destinationHostId || !eligible) {
                        continue;
                    }
                    if (!State.SUSPECT.name().equals(row.getState())) {
                        markSuspectLocked(row);
                    }
                    quarantined.add(row);
                }
            }
            return quarantined;
                }));
    }

    @Override
    public boolean markSuspect(final long vfPoolId, final long expectedNicId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                lockVmForNic(expectedNicId);
                lockNicAndReadVfPoolId(expectedNicId);
                final SriovVfPoolVO row = lockRow(vfPoolId, true);
                if (row == null || !Long.valueOf(expectedNicId).equals(row.getAllocatedToNicId())) {
                    return false;
                }
                markSuspectLocked(row);
                return true;
            }
        });
    }

    @Override
    public boolean releaseExact(final long vfPoolId, final long expectedNicId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                lockVmForNic(expectedNicId);
                lockNicAndReadVfPoolId(expectedNicId);
                final SriovVfPoolVO row = lockRow(vfPoolId, true);
                if (row == null || !Long.valueOf(expectedNicId).equals(row.getAllocatedToNicId())) {
                    return false;
                }
                SriovVfPoolVO updateVo = createForUpdate();
                applyFreeState(updateVo);
                update(row.getId(), updateVo);
                unbindNicVfPoolIdExact(expectedNicId, row.getId());
                return true;
            }
        });
    }

    @Override
    public boolean prepareReconciliationPlan(final List<VfReconciliationCandidate> requestedCandidates) {
        return executeWithDeadlockRetry(() -> Transaction.execute(
                (TransactionCallback<Boolean>) status -> {
            if (requestedCandidates == null || requestedCandidates.isEmpty()) {
                return false;
            }
            final List<VfReconciliationCandidate> candidates = new ArrayList<>(requestedCandidates);
            candidates.sort((left, right) -> {
                final int vmOrder = Long.compare(left.getVmId(), right.getVmId());
                return vmOrder != 0 ? vmOrder : Long.compare(left.getStalePoolId(), right.getStalePoolId());
            });
            if (hasDuplicateReconciliationRows(candidates)) {
                return false;
            }
            final Map<Long, Long> nics = new LinkedHashMap<>();
            final Map<Long, VmState> vmStates = new HashMap<>();
            long lastVmId = Long.MIN_VALUE;
            for (final VfReconciliationCandidate candidate : candidates) {
                if (candidate.getVmId() != lastVmId) {
                    final VmState vm = lockVm(candidate.getVmId());
                    vmStates.put(candidate.getVmId(), vm);
                    requireNoConflictingWork(candidate.getVmId());
                    lastVmId = candidate.getVmId();
                }
                requireAuthoritativeDestination(candidate.getVmId(), vmStates.get(candidate.getVmId()),
                        candidate.getCurrentHostId());
            }
            lastVmId = Long.MIN_VALUE;
            for (final VfReconciliationCandidate candidate : candidates) {
                if (candidate.getVmId() != lastVmId) {
                    nics.putAll(lockVmNics(candidate.getVmId()));
                    lastVmId = candidate.getVmId();
                }
            }
            final Map<Long, List<Long>> additional = new HashMap<>();
            for (final VfReconciliationCandidate candidate : candidates) {
                additional.computeIfAbsent(candidate.getNicId(), ignored -> new ArrayList<>())
                        .addAll(java.util.Arrays.asList(candidate.getCurrentPoolId(), candidate.getStalePoolId()));
            }
            final Map<Long, List<SriovVfPoolVO>> rowsByNic = lockPoolRowsForNics(nics, additional);
            for (final VfReconciliationCandidate candidate : candidates) {
                if (!validReconciliationCandidate(candidate, nics, rowsByNic)) {
                    return false;
                }
            }
            for (final VfReconciliationCandidate candidate : candidates) {
                final List<SriovVfPoolVO> rows = rowsByNic.get(candidate.getNicId());
                final SriovVfPoolVO current = findRowById(rows, candidate.getCurrentPoolId());
                final SriovVfPoolVO stale = findRowById(rows, candidate.getStalePoolId());
                if (candidate.isPromoteCurrent()) {
                    final SriovVfPoolVO promote = createForUpdate();
                    applyOwnershipState(promote, candidate.getNicId(), State.ALLOCATED,
                            current.getVdpaKindEnum(), current.getVdpaName());
                    update(candidate.getCurrentPoolId(), promote);
                    bindNicVfPoolId(candidate.getNicId(), candidate.getCurrentPoolId());
                }
                if (!State.SUSPECT.name().equals(stale.getState())) {
                    markSuspectLocked(stale);
                }
            }
            return true;
                }));
    }

    @Override
    public boolean completeReconciliation(final long vmId, final long nicId, final long currentHostId,
                                          final long currentPoolId, final long stalePoolId) {
        return executeWithDeadlockRetry(() -> Transaction.execute(
                (TransactionCallback<Boolean>) status -> {
            final VmState vm = lockVm(vmId);
            requireAuthoritativeDestination(vmId, vm, currentHostId);
            requireNoConflictingWork(vmId);
            final Map<Long, Long> nics = lockVmNics(vmId);
            if (!Long.valueOf(currentPoolId).equals(nics.get(nicId))) {
                return false;
            }
            final Map<Long, List<Long>> additional = new HashMap<>();
            additional.put(nicId, java.util.Arrays.asList(currentPoolId, stalePoolId));
            final Map<Long, List<SriovVfPoolVO>> rowsByNic = lockPoolRowsForNics(nics, additional);
            final List<SriovVfPoolVO> rows = rowsByNic.get(nicId);
            final SriovVfPoolVO current = findRowById(rows, currentPoolId);
            final SriovVfPoolVO stale = findRowById(rows, stalePoolId);
            if (!validReconciliationRows(nicId, currentHostId, current, stale, rows)
                    || !State.ALLOCATED.name().equals(current.getState())
                    || !State.SUSPECT.name().equals(stale.getState())) {
                return false;
            }
            final SriovVfPoolVO free = createForUpdate();
            applyFreeState(free);
            update(stalePoolId, free);
            unbindNicVfPoolIdExact(nicId, stalePoolId);
            return true;
                }));
    }

    @Override
    public boolean releaseByNicId(final long nicId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                lockVmForNic(nicId);
                lockNicAndReadVfPoolId(nicId);
                final List<SriovVfPoolVO> rows = lockRowsForNic(nicId);
                for (final SriovVfPoolVO row : rows) {
                    markSuspectLocked(row);
                }
                return !rows.isEmpty();
            }
        });
    }

    @Override
    public int quarantineByVmId(final long vmId) {
        return Transaction.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                lockVm(vmId);
                final Map<Long, Long> nics = lockVmNics(vmId);
                final Map<Long, List<SriovVfPoolVO>> rowsByNic = lockPoolRowsForNics(nics);
                int affected = 0;
                for (final List<SriovVfPoolVO> rows : rowsByNic.values()) {
                    for (final SriovVfPoolVO row : rows) {
                        if (State.ALLOCATED.name().equals(row.getState())
                                || State.RESERVED.name().equals(row.getState())) {
                            markSuspectLocked(row);
                            affected++;
                        }
                    }
                }
                return affected;
            }
        });
    }

    @Override
    public int sweepOrphans() {
        return Transaction.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                return executeUpdateWithCount(SQL_SWEEP_ORPHANS);
            }
        });
    }

    @Override
    public SriovVfPoolVO findFreeVdpaCapableVf(final long hostId) {
        // For now any FREE VF is vDPA-capable (the eswitch itself is the gate;
        // any mlx5 VF behind a switchdev PF can host a vDPA mgmtdev). Future
        // hardware-specific gating (e.g. ConnectX-7-only sub-set) belongs
        // here as an extra column or filter.
        SearchCriteria<SriovVfPoolVO> sc = hostStateKindSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", State.FREE.name());
        sc.setParameters("vdpaKind", VdpaKind.PASSTHROUGH.name());
        List<SriovVfPoolVO> free = listBy(sc);
        return (free == null || free.isEmpty()) ? null : free.get(0);
    }

    @Override
    public SriovVfPoolVO allocateForVdpa(final long hostId, final long nicId, final String mac, final int maxVqs) {
        final SriovVfPoolVO vf = allocateOrReserve(hostId, nicId, VdpaKind.VDPA, buildVdpaName(nicId));
        if (vf != null) {
            LOGGER.info("allocateForVdpa: host={} nic={} vf={} pci={} mac={} maxVqs={} vdpaName={} state={}",
                    hostId, nicId, vf.getUuid(), vf.getPciAddress(), mac, maxVqs, vf.getVdpaName(), vf.getState());
        }
        return vf;
    }

    @Override
    public boolean releaseVdpa(final long vfPoolId) {
        // Same free-state wipe as release() — kept as a named API for
        // call-sites that know they held a vDPA binding.
        return release(vfPoolId);
    }

    /**
     * Stamp a {@code createForUpdate()} proxy (or real VO) with the FREE
     * bookkeeping: clear nic binding + blank every vdpa_* column and force
     * {@code vdpa_kind=PASSTHROUGH}. Shared by every release path so a row
     * that once hosted a vDPA mgmt-device cannot leak kind/name into the
     * next hostdev PASSTHROUGH allocation.
     *
     * <p>Enum-backed columns receive their {@code .name()} String so the
     * createForUpdate proxy never stores an enum object (MySQL utf8mb4
     * rejects the resulting serialized blob).
     */
    static void applyFreeState(SriovVfPoolVO updateVo) {
        updateVo.setState(State.FREE.name());
        updateVo.setAllocatedToNicId(null);
        updateVo.setVdpaKind(VdpaKind.PASSTHROUGH.name());
        updateVo.setVdpaName(null);
        updateVo.setVdpaDevice(null);
    }

    /**
     * Stamp a hostdev PASSTHROUGH allocation onto a {@code createForUpdate()}
     * proxy: ALLOCATED + nic binding + forced PASSTHROUGH kind with blank
     * vdpa_name/device. setState routes through setUpdated so the proxy's
     * change map includes {@code updated}.
     */
    static void applyPassthroughAllocated(SriovVfPoolVO updateVo, long nicId) {
        updateVo.setState(State.ALLOCATED.name());
        updateVo.setAllocatedToNicId(nicId);
        updateVo.setVdpaKind(VdpaKind.PASSTHROUGH.name());
        updateVo.setVdpaName(null);
        updateVo.setVdpaDevice(null);
    }

    static void applyOwnershipState(final SriovVfPoolVO updateVo, final long nicId, final State state,
                                    final VdpaKind kind, final String vdpaName) {
        updateVo.setState(state.name());
        updateVo.setAllocatedToNicId(nicId);
        updateVo.setVdpaKind(kind.name());
        updateVo.setVdpaName(kind == VdpaKind.VDPA ? vdpaName : null);
        updateVo.setVdpaDevice(null);
    }

    /**
     * Build the canonical vDPA mgmt-device name for a NIC id. The ConnectX
     * family limits the name length, so we keep it short ({@code vdpa-<nicId>})
     * — the NIC id is unique within a CloudStack deployment and short enough
     * to fit even with a future {@code -mig} / {@code -dst} suffix.
     */
    private static String buildVdpaName(long nicId) {
        return "vdpa-" + nicId;
    }

    @Override
    public boolean touchLastSeen(final long hostId, final String pciAddress) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                SriovVfPoolVO vf = findByHostAndPci(hostId, pciAddress);
                if (vf == null) {
                    return false;
                }
                SriovVfPoolVO updateVo = createForUpdate();
                updateVo.setLastSeen(new Date());
                update(vf.getId(), updateVo);
                return true;
            }
        });
    }

    @Override
    public int markSuspectByHostId(final long hostId) {
        return Transaction.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                return executeUpdateWithCount(SQL_MARK_SUSPECT_BY_HOST_ID, hostId);
            }
        });
    }

    @Override
    public int forceReleaseByHostId(final long hostId) {
        return markSuspectByHostId(hostId);
    }

    @Override
    public int recoverByHostId(final long hostId) {
        LOGGER.warn("Broad VF recovery is deactivated for host {}", hostId);
        return 0;
    }

    @Override
    public List<SriovVfPoolVO> findStaleAllocated(final int thresholdSeconds) {
        return Transaction.execute(new TransactionCallback<List<SriovVfPoolVO>>() {
            @Override
            public List<SriovVfPoolVO> doInTransaction(TransactionStatus status) {
                List<Long> ids = new ArrayList<>();
                TransactionLegacy txn = TransactionLegacy.currentTxn();
                try {
                    PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_FIND_STALE_ALLOCATED);
                    pstmt.setInt(1, thresholdSeconds);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getLong(1));
                        }
                    }
                } catch (SQLException e) {
                    LOGGER.warn(String.format("findStaleAllocated failed: %s", e.getMessage()));
                    throw new CloudRuntimeException("findStaleAllocated query failed", e);
                }
                List<SriovVfPoolVO> out = new ArrayList<>(ids.size());
                for (Long id : ids) {
                    SriovVfPoolVO vf = findById(id);
                    if (vf != null) {
                        out.add(vf);
                    }
                }
                return out;
            }
        });
    }

    @Override
    public SriovVfPoolVO findByHostAndVdpaName(final long hostId, final String vdpaName) {
        if (vdpaName == null) {
            return null;
        }
        SearchCriteria<SriovVfPoolVO> sc = hostVdpaNameSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("vdpaName", vdpaName);
        return findOneBy(sc);
    }

    /**
     * Stamp {@code nics.vf_pool_id = poolId} on the given NIC. Caller must
     * already be inside a transaction; the helper delegates to {@link
     * #executeUpdateWithCount} which uses the enclosing connection.
     *
     * <p>No-ops cleanly when the NIC row was removed between allocation and
     * this call (UPDATE matches zero rows, no error).
     */
    private void bindNicVfPoolId(long nicId, long poolId) {
        executeUpdateWithCount(SQL_BIND_NIC_VF_POOL_ID, poolId, nicId);
    }

    private List<SriovVfPoolVO> lockRowsForNic(final long nicId) {
        return lockRowsForNic(nicId, null);
    }

    private List<SriovVfPoolVO> lockRowsForNic(final long nicId, final Long additionalRowId) {
        final List<SriovVfPoolVO> rows = currentLockedRowsForNic(nicId);
        if (additionalRowId != null && findRowById(rows, additionalRowId) == null) {
            final SriovVfPoolVO additional = findById(additionalRowId);
            if (additional != null) {
                rows.add(additional);
            }
        }
        rows.sort((left, right) -> Long.compare(left.getId(), right.getId()));
        final List<SriovVfPoolVO> locked = new ArrayList<>();
        for (final SriovVfPoolVO row : rows) {
            final SriovVfPoolVO lockedRow = lockRow(row.getId(), true);
            if (lockedRow != null) {
                locked.add(lockedRow);
            }
        }
        return locked;
    }

    private List<SriovVfPoolVO> currentLockedRowsForNic(final long nicId) {
        final List<SriovVfPoolVO> rows = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            final PreparedStatement statement = txn.prepareAutoCloseStatement(SQL_LOCK_POOL_ROWS_FOR_NIC);
            statement.setLong(1, nicId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            for (final Long id : ids) {
                final SriovVfPoolVO row = lockRow(id, true);
                if (row != null) {
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to lock VF rows for NIC " + nicId, e);
        }
    }

    private SriovVfPoolVO findOwnedRow(final List<SriovVfPoolVO> rows, final long hostId) {
        for (final SriovVfPoolVO row : rows) {
            if (row.getHostId() == hostId) {
                return row;
            }
        }
        return null;
    }

    private SriovVfPoolVO findRowById(final List<SriovVfPoolVO> rows, final Long id) {
        if (id == null) {
            return null;
        }
        for (final SriovVfPoolVO row : rows) {
            if (row.getId() == id) {
                return row;
            }
        }
        return null;
    }

    private void markSuspectLocked(final SriovVfPoolVO row) {
        SriovVfPoolVO suspect = createForUpdate();
        suspect.setState(State.SUSPECT.name());
        update(row.getId(), suspect);
        row.setState(State.SUSPECT);
    }

    private Long lockNicAndReadVfPoolId(final long nicId) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_LOCK_NIC_VF_POOL_ID);
            pstmt.setLong(1, nicId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    throw new CloudRuntimeException("NIC " + nicId + " does not exist");
                }
                final long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to lock NIC " + nicId, e);
        }
    }

    private long lockVmForNic(final long nicId) {
        final long vmId = findVmIdForNic(nicId);
        lockVm(vmId);
        return vmId;
    }

    private long findVmIdForNic(final long nicId) {
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            final PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_FIND_VM_ID_FOR_NIC);
            pstmt.setLong(1, nicId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    throw new CloudRuntimeException("NIC " + nicId + " does not exist");
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to resolve VM for NIC " + nicId, e);
        }
    }

    private VmState lockVm(final long vmId) {
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            final PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_LOCK_VM);
            pstmt.setLong(1, vmId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    throw new CloudRuntimeException("VM " + vmId + " does not exist");
                }
                final long hostValue = rs.getLong("host_id");
                return new VmState(rs.getString("state"), rs.wasNull() ? null : hostValue);
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to lock VM " + vmId, e);
        }
    }

    private Map<Long, Long> lockVmNics(final long vmId) {
        final Map<Long, Long> canonicalByNic = new LinkedHashMap<>();
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            final PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_LOCK_VM_NICS);
            pstmt.setLong(1, vmId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    final long value = rs.getLong("vf_pool_id");
                    canonicalByNic.put(rs.getLong("id"), rs.wasNull() ? null : value);
                }
            }
            return canonicalByNic;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to lock NICs for VM " + vmId, e);
        }
    }

    private Map<Long, List<SriovVfPoolVO>> lockPoolRowsForNics(final Map<Long, Long> canonicalByNic) {
        return lockPoolRowsForNics(canonicalByNic, Collections.emptyMap());
    }

    private Map<Long, List<SriovVfPoolVO>> lockPoolRowsForNics(
            final Map<Long, Long> canonicalByNic, final Map<Long, List<Long>> additionalByNic) {
        final Map<Long, List<SriovVfPoolVO>> observedByNic = new LinkedHashMap<>();
        final Map<Long, SriovVfPoolVO> uniqueRows = new java.util.TreeMap<>();
        for (final Map.Entry<Long, Long> entry : canonicalByNic.entrySet()) {
            final List<SriovVfPoolVO> rows = new ArrayList<>(listByNicId(entry.getKey()));
            final Long canonicalId = entry.getValue();
            if (canonicalId != null && findRowById(rows, canonicalId) == null) {
                final SriovVfPoolVO canonical = findById(canonicalId);
                if (canonical != null) {
                    rows.add(canonical);
                }
            }
            for (final Long additionalId : additionalByNic.getOrDefault(entry.getKey(), Collections.emptyList())) {
                if (additionalId != null && findRowById(rows, additionalId) == null) {
                    final SriovVfPoolVO additional = findById(additionalId);
                    if (additional != null) {
                        rows.add(additional);
                    }
                }
            }
            observedByNic.put(entry.getKey(), rows);
            for (final SriovVfPoolVO row : rows) {
                uniqueRows.put(row.getId(), row);
            }
        }
        final Map<Long, SriovVfPoolVO> lockedRows = new HashMap<>();
        for (final Long rowId : uniqueRows.keySet()) {
            final SriovVfPoolVO locked = lockRow(rowId, true);
            if (locked != null) {
                lockedRows.put(rowId, locked);
            }
        }
        final Map<Long, List<SriovVfPoolVO>> lockedByNic = new LinkedHashMap<>();
        for (final Map.Entry<Long, List<SriovVfPoolVO>> entry : observedByNic.entrySet()) {
            final List<SriovVfPoolVO> rows = new ArrayList<>();
            for (final SriovVfPoolVO row : entry.getValue()) {
                if (lockedRows.containsKey(row.getId())) {
                    rows.add(lockedRows.get(row.getId()));
                }
            }
            lockedByNic.put(entry.getKey(), rows);
        }
        return lockedByNic;
    }

    List<CommitChange> validateVmCommit(final long vmId, final Long expectedSourceHostId,
                                        final long destinationHostId,
                                        final Map<Long, Long> canonicalByNic,
                                        final Map<Long, List<SriovVfPoolVO>> rowsByNic) {
        final List<CommitChange> changes = new ArrayList<>();
        for (final Map.Entry<Long, List<SriovVfPoolVO>> entry : rowsByNic.entrySet()) {
            final long nicId = entry.getKey();
            final List<SriovVfPoolVO> rows = entry.getValue();
            if (rows.isEmpty()) {
                continue;
            }
            final SriovVfPoolVO destination = findOwnedRow(rows, destinationHostId);
            if (destination == null || !(State.RESERVED.name().equals(destination.getState())
                    || State.ALLOCATED.name().equals(destination.getState()))) {
                throw new CloudRuntimeException(String.format(
                        "Atomic VF commit refused for vm=%d nic=%d: destination host=%d row missing or not owned",
                        vmId, nicId, destinationHostId));
            }
            for (final SriovVfPoolVO row : rows) {
                if (State.RESERVED.name().equals(row.getState()) && row.getId() != destination.getId()) {
                    throw new CloudRuntimeException("Atomic VF commit refused: unrelated RESERVED row " + row.getId());
                }
            }
            final SriovVfPoolVO canonical = findRowById(rows, canonicalByNic.get(nicId));
            if (expectedSourceHostId != null && (canonical == null
                    || canonical.getHostId() != expectedSourceHostId && canonical.getId() != destination.getId())) {
                throw new CloudRuntimeException(String.format(
                        "Atomic VF commit refused for vm=%d nic=%d: expected source=%d canonical=%s",
                        vmId, nicId, expectedSourceHostId, canonical));
            }
            final List<SriovVfPoolVO> prior = new ArrayList<>();
            for (final SriovVfPoolVO row : rows) {
                if (row.getId() != destination.getId() && !State.FREE.name().equals(row.getState())) {
                    prior.add(row);
                }
            }
            changes.add(new CommitChange(nicId, destination, prior));
        }
        return changes;
    }

    private void requireAuthoritativeDestination(final long vmId, final VmState vm,
                                                 final long destinationHostId) {
        if (!"Running".equals(vm.state) || vm.hostId == null || vm.hostId != destinationHostId) {
            throw new CloudRuntimeException(String.format(
                    "Atomic VF commit refused for vm=%d: state=%s host=%s destination=%d",
                    vmId, vm.state, vm.hostId, destinationHostId));
        }
    }

    private void validateWorkIfPresent(final long vmId, final long destinationHostId,
                                       final String workId) {
        if (workId != null && !workId.trim().isEmpty()) {
            validateWork(vmId, destinationHostId, workId);
        }
    }

    private void validateWork(final long vmId, final long destinationHostId, final String workId) {
        if (workId == null || workId.trim().isEmpty()) {
            throw new CloudRuntimeException("Atomic VF ownership change requires an operation work id");
        }
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            final PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_LOCK_WORK);
            pstmt.setString(1, workId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next() || rs.getLong("instance_id") != vmId) {
                    throw new CloudRuntimeException("Stale or foreign VF ownership work id " + workId);
                }
                final String type = rs.getString("type");
                final String step = rs.getString("step");
                final long resourceId = rs.getLong("resource_id");
                final boolean validStep = "Prepare".equals(step) || "Started".equals(step) || "Done".equals(step)
                        || "Starting".equals(step) || "Migrating".equals(step)
                        || "Release".equals(step);
                final boolean validType = "Starting".equals(type) || "Migrating".equals(type);
                final boolean validDestination = !"Migrating".equals(type) || resourceId == destinationHostId;
                if (!validStep || !validType || !validDestination) {
                    throw new CloudRuntimeException(String.format(
                            "Stale VF ownership work id=%s type=%s step=%s resource=%d destination=%d",
                            workId, type, step, resourceId, destinationHostId));
                }
            }
            final PreparedStatement latest = txn.prepareAutoCloseStatement(SQL_LOCK_LATEST_VM_WORK);
            latest.setLong(1, vmId);
            try (ResultSet rs = latest.executeQuery()) {
                if (!rs.next() || !workId.equals(rs.getString("id"))) {
                    throw new CloudRuntimeException("Stale VF ownership operation generation " + workId);
                }
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to validate VF ownership work " + workId, e);
        }
    }

    private void requireNoConflictingWork(final long vmId) {
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            final PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_COUNT_CONFLICTING_WORK);
            pstmt.setLong(1, vmId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next() || rs.getLong(1) != 0) {
                    throw new CloudRuntimeException("VF reconciliation refused: VM " + vmId
                            + " has conflicting ownership work");
                }
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Failed to check work for VM " + vmId, e);
        }
    }

    private boolean validReconciliationRows(final long nicId, final long currentHostId,
                                            final SriovVfPoolVO current, final SriovVfPoolVO stale,
                                            final List<SriovVfPoolVO> rows) {
        if (current == null || stale == null || current.getId() == stale.getId()
                || current.getHostId() != currentHostId
                || !Long.valueOf(nicId).equals(stale.getAllocatedToNicId())) {
            return false;
        }
        for (final SriovVfPoolVO row : rows) {
            if (State.RESERVED.name().equals(row.getState())) {
                return false;
            }
        }
        return true;
    }

    private boolean validReconciliationCandidate(final VfReconciliationCandidate candidate,
                                                 final Map<Long, Long> canonicalByNic,
                                                 final Map<Long, List<SriovVfPoolVO>> rowsByNic) {
        final List<SriovVfPoolVO> rows = rowsByNic.get(candidate.getNicId());
        if (rows == null) {
            return false;
        }
        final SriovVfPoolVO current = findRowById(rows, candidate.getCurrentPoolId());
        final SriovVfPoolVO stale = findRowById(rows, candidate.getStalePoolId());
        if (!validReconciliationRows(candidate.getNicId(), candidate.getCurrentHostId(), current, stale, rows)) {
            return false;
        }
        final Long canonical = canonicalByNic.get(candidate.getNicId());
        if (candidate.isPromoteCurrent()) {
            final boolean pending = canonical != null && canonical == candidate.getStalePoolId()
                    && State.ALLOCATED.name().equals(stale.getState())
                    && State.FREE.name().equals(current.getState())
                    && current.getAllocatedToNicId() == null;
            final boolean quarantined = canonical != null && canonical == candidate.getCurrentPoolId()
                    && State.ALLOCATED.name().equals(current.getState())
                    && Long.valueOf(candidate.getNicId()).equals(current.getAllocatedToNicId())
                    && State.SUSPECT.name().equals(stale.getState());
            return pending || quarantined;
        }
        return canonical != null && canonical == candidate.getCurrentPoolId()
                && State.ALLOCATED.name().equals(current.getState());
    }

    private boolean hasDuplicateReconciliationRows(final List<VfReconciliationCandidate> candidates) {
        final java.util.Set<Long> staleRows = new java.util.HashSet<>();
        for (final VfReconciliationCandidate candidate : candidates) {
            if (!staleRows.add(candidate.getStalePoolId())) {
                return true;
            }
        }
        return false;
    }

    <T> T executeWithDeadlockRetry(final Supplier<T> action) {
        CloudRuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return action.get();
            } catch (CloudRuntimeException e) {
                if (!isDeadlock(e) || attempt == 2) {
                    throw e;
                }
                last = e;
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CloudRuntimeException("Interrupted while retrying VF ownership deadlock", interrupted);
                }
            }
        }
        throw last;
    }

    private boolean isDeadlock(final Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException) {
                final SQLException sql = (SQLException) current;
                if (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class VmState {
        private final String state;
        private final Long hostId;

        private VmState(final String state, final Long hostId) {
            this.state = state;
            this.hostId = hostId;
        }
    }

    static final class CommitChange {
        private final long nicId;
        private final SriovVfPoolVO destination;
        private final List<SriovVfPoolVO> prior;

        private CommitChange(final long nicId, final SriovVfPoolVO destination,
                             final List<SriovVfPoolVO> prior) {
            this.nicId = nicId;
            this.destination = destination;
            this.prior = prior;
        }
    }

    /**
     * Inverse of {@link #bindNicVfPoolId} — clears the reverse pointer on
     * a single NIC. Used by every release path so a NIC that went through
     * dual-write allocation does not stay tagged with a stale {@code
     * vf_pool_id} after the VF was returned to the pool.
     */
    private void unbindNicVfPoolIdExact(long nicId, long vfPoolId) {
        executeUpdateWithCount(SQL_UNBIND_NIC_VF_POOL_ID_EXACT, nicId, vfPoolId);
    }

    /**
     * Cross-table UPDATE helper for {@link #quarantineByVmId(long)} / {@link #sweepOrphans()}.
     * Uses {@link TransactionLegacy#prepareAutoCloseStatement(String)} on the enclosing
     * transaction's connection; the returned PreparedStatement closes itself when the
     * transaction commits / rolls back — we must NOT close the TransactionLegacy itself
     * (that would close the outer Spring transaction).
     */
    private int executeUpdateWithCount(String sql, Object... params) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(sql);
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warn(String.format("executeUpdateWithCount failed for %s: %s", sql, e.getMessage()));
            throw new CloudRuntimeException("sriov_vf_pool update failed", e);
        }
    }
}
