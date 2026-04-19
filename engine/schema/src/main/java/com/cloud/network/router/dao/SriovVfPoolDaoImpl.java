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
import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
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

    private final SearchBuilder<SriovVfPoolVO> hostStateSearch;
    private final SearchBuilder<SriovVfPoolVO> hostPciSearch;
    private final SearchBuilder<SriovVfPoolVO> nicIdSearch;
    private final SearchBuilder<SriovVfPoolVO> hostNicIdSearch;

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
