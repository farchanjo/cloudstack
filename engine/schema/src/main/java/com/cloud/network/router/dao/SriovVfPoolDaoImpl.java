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

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;

@Component
@DB
public class SriovVfPoolDaoImpl extends GenericDaoBase<SriovVfPoolVO, Long> implements SriovVfPoolDao {

    private final SearchBuilder<SriovVfPoolVO> hostStateSearch;
    private final SearchBuilder<SriovVfPoolVO> hostPciSearch;
    private final SearchBuilder<SriovVfPoolVO> nicIdSearch;

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
}
