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
import java.util.Date;
import java.util.List;

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
     * Release by VM id via JOIN into {@code nics} (covering removed NICs too)
     * and blanking the binding. A single cross-table UPDATE is the atomic
     * primitive for the VR-expunge race where {@code releaseByNicId} may miss
     * NICs whose {@code removed} column is already set.
     */
    private static final String SQL_RELEASE_BY_VM_ID =
            "UPDATE sriov_vf_pool p " +
            "JOIN nics n ON n.id = p.allocated_to_nic_id " +
            "SET p.state = 'FREE', p.allocated_to_nic_id = NULL, p.updated = NOW() " +
            "WHERE n.instance_id = ? AND p.state = 'ALLOCATED'";

    /**
     * Orphan sweep — any ALLOCATED VF whose NIC is gone (nics.removed IS NOT NULL)
     * or whose VM is gone (vm_instance.removed IS NOT NULL) gets freed. Safety net
     * for the race where the listener missed the VR state transition window.
     */
    private static final String SQL_SWEEP_ORPHANS =
            "UPDATE sriov_vf_pool p " +
            "LEFT JOIN nics n ON n.id = p.allocated_to_nic_id " +
            "LEFT JOIN vm_instance v ON v.id = n.instance_id " +
            "SET p.state = 'FREE', p.allocated_to_nic_id = NULL, p.updated = NOW() " +
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
     * Force every ALLOCATED or SUSPECT row on the host back to FREE — clears
     * nic binding and vdpa fields. Driven by the {@code forceReleaseHostVfs}
     * admin command. Idempotent.
     */
    private static final String SQL_FORCE_RELEASE_BY_HOST_ID =
            "UPDATE sriov_vf_pool " +
            "   SET state = 'FREE', allocated_to_nic_id = NULL, " +
            "       vdpa_kind = 'PASSTHROUGH', vdpa_name = NULL, vdpa_device = NULL, " +
            "       updated = NOW() " +
            " WHERE host_id = ? AND state IN ('ALLOCATED', 'SUSPECT')";

    /**
     * Stale ALLOCATED rows: last_seen older than NOW() - threshold seconds, OR
     * last_seen IS NULL (the agent never confirmed this row since the column
     * was added). Caller flips them to SUSPECT.
     */
    private static final String SQL_FIND_STALE_ALLOCATED =
            "SELECT id FROM sriov_vf_pool " +
            " WHERE state = 'ALLOCATED' " +
            "   AND (last_seen IS NULL OR last_seen < (NOW() - INTERVAL ? SECOND))";

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
    public SriovVfPoolVO allocate(final long hostId, final long nicId) {
        return Transaction.execute(new TransactionCallback<SriovVfPoolVO>() {
            @Override
            public SriovVfPoolVO doInTransaction(TransactionStatus status) {
                // Idempotency: if this (hostId, nicId) already has an ALLOCATED entry,
                // reuse it instead of taking another FREE VF. Multiple StartCommand
                // re-fires (HA, mgmt-cluster races) will each call allocate(), and
                // without this check we'd burn a fresh VF every time, exhausting the
                // host pool after a few retries.
                SearchCriteria<SriovVfPoolVO> existSc = hostNicIdSearch.create();
                existSc.setParameters("hostId", hostId);
                existSc.setParameters("allocatedToNicId", nicId);
                List<SriovVfPoolVO> existing = listBy(existSc);
                if (existing != null && !existing.isEmpty()) {
                    return existing.get(0);
                }

                SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
                sc.setParameters("hostId", hostId);
                sc.setParameters("state", State.FREE.name());
                List<SriovVfPoolVO> free = lockRows(sc, null, false);
                if (free == null || free.isEmpty()) {
                    return null;
                }
                SriovVfPoolVO vf = free.get(0);
                // createForUpdate() returns a clean VO (no CGLIB proxy) for partial update.
                // This avoids the enum serialization issue that occurs when calling
                // update() on the proxy object returned by lockRows().
                SriovVfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.ALLOCATED.name());
                updateVo.setAllocatedToNicId(nicId);
                update(vf.getId(), updateVo);
                vf.setState(State.ALLOCATED.name());
                vf.setAllocatedToNicId(nicId);
                return vf;
            }
        });
    }

    @Override
    public boolean release(final long vfPoolId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                SriovVfPoolVO vf = lockRow(vfPoolId, false);
                if (vf == null) {
                    return false;
                }
                SriovVfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.FREE.name());
                updateVo.setAllocatedToNicId(null);
                update(vf.getId(), updateVo);
                return true;
            }
        });
    }

    @Override
    public boolean releaseByNicId(final long nicId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                // Bulk UPDATE WHERE allocated_to_nic_id=? — one SQL stmt that
                // releases every row matching the nic id (across hosts). The
                // previous lockRows + per-row update loop only updated the
                // first row reliably because the reused createForUpdate() VO
                // had its dirty flags cleared after the first update() call,
                // making subsequent update(id, vo) calls no-op.
                SearchCriteria<SriovVfPoolVO> sc = nicIdSearch.create();
                sc.setParameters("allocatedToNicId", nicId);
                SriovVfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.FREE.name());
                updateVo.setAllocatedToNicId(null);
                int affected = update(updateVo, sc);
                return affected > 0;
            }
        });
    }

    @Override
    public int releaseByVmId(final long vmId) {
        return Transaction.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                return executeUpdateWithCount(SQL_RELEASE_BY_VM_ID, vmId);
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
        SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", State.FREE.name());
        List<SriovVfPoolVO> free = listBy(sc);
        return (free == null || free.isEmpty()) ? null : free.get(0);
    }

    @Override
    public SriovVfPoolVO allocateForVdpa(final long hostId, final long nicId, final String mac, final int maxVqs) {
        return Transaction.execute(new TransactionCallback<SriovVfPoolVO>() {
            @Override
            public SriovVfPoolVO doInTransaction(TransactionStatus status) {
                // Idempotency: an existing ALLOCATED row for (hostId, nicId)
                // whose vdpa_kind is already VDPA is reused as-is. Avoids
                // burning a fresh VF on StartCommand re-fires (HA, mgmt
                // cluster races) — same shape as plain allocate().
                SearchCriteria<SriovVfPoolVO> existSc = hostNicIdSearch.create();
                existSc.setParameters("hostId", hostId);
                existSc.setParameters("allocatedToNicId", nicId);
                List<SriovVfPoolVO> existing = listBy(existSc);
                if (existing != null && !existing.isEmpty()) {
                    SriovVfPoolVO row = existing.get(0);
                    if (VdpaKind.VDPA.name().equals(row.getVdpaKind())) {
                        return row;
                    }
                    // Row exists but was previously bound as PASSTHROUGH —
                    // flip it in place rather than allocating a second VF.
                    SriovVfPoolVO promote = createForUpdate();
                    promote.setVdpaKind(VdpaKind.VDPA);
                    promote.setVdpaName(buildVdpaName(nicId));
                    update(row.getId(), promote);
                    row.setVdpaKind(VdpaKind.VDPA);
                    row.setVdpaName(buildVdpaName(nicId));
                    return row;
                }

                SearchCriteria<SriovVfPoolVO> sc = hostStateSearch.create();
                sc.setParameters("hostId", hostId);
                sc.setParameters("state", State.FREE.name());
                List<SriovVfPoolVO> free = lockRows(sc, null, false);
                if (free == null || free.isEmpty()) {
                    return null;
                }
                SriovVfPoolVO vf = free.get(0);
                SriovVfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.ALLOCATED.name());
                updateVo.setAllocatedToNicId(nicId);
                updateVo.setVdpaKind(VdpaKind.VDPA);
                updateVo.setVdpaName(buildVdpaName(nicId));
                update(vf.getId(), updateVo);
                vf.setState(State.ALLOCATED.name());
                vf.setAllocatedToNicId(nicId);
                vf.setVdpaKind(VdpaKind.VDPA);
                vf.setVdpaName(buildVdpaName(nicId));
                LOGGER.info(String.format(
                    "allocateForVdpa: host=%d nic=%d vf=%s pci=%s mac=%s maxVqs=%d vdpaName=%s",
                    hostId, nicId, vf.getUuid(), vf.getPciAddress(), mac, maxVqs, vf.getVdpaName()));
                return vf;
            }
        });
    }

    @Override
    public boolean releaseVdpa(final long vfPoolId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                SriovVfPoolVO vf = lockRow(vfPoolId, false);
                if (vf == null) {
                    return false;
                }
                SriovVfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.FREE.name());
                updateVo.setAllocatedToNicId(null);
                updateVo.setVdpaKind(VdpaKind.PASSTHROUGH);
                updateVo.setVdpaName(null);
                updateVo.setVdpaDevice(null);
                update(vf.getId(), updateVo);
                return true;
            }
        });
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
        return Transaction.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                return executeUpdateWithCount(SQL_FORCE_RELEASE_BY_HOST_ID, hostId);
            }
        });
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
     * Cross-table UPDATE helper for {@link #releaseByVmId(long)} / {@link #sweepOrphans()}.
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
