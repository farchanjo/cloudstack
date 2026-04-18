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

import com.cloud.network.router.SriovSfPoolVO;
import com.cloud.network.router.SriovSfPoolVO.State;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;

@Component
@DB
public class SriovSfPoolDaoImpl extends GenericDaoBase<SriovSfPoolVO, Long> implements SriovSfPoolDao {

    private final SearchBuilder<SriovSfPoolVO> hostStateSearch;
    private final SearchBuilder<SriovSfPoolVO> hostSfIndexSearch;
    private final SearchBuilder<SriovSfPoolVO> nicIdSearch;
    private final SearchBuilder<SriovSfPoolVO> hostPfSearch;

    public SriovSfPoolDaoImpl() {
        hostStateSearch = createSearchBuilder();
        hostStateSearch.and("hostId", hostStateSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostStateSearch.and("state", hostStateSearch.entity().getState(), SearchCriteria.Op.EQ);
        hostStateSearch.done();

        hostSfIndexSearch = createSearchBuilder();
        hostSfIndexSearch.and("hostId", hostSfIndexSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostSfIndexSearch.and("pfIndex", hostSfIndexSearch.entity().getPfIndex(), SearchCriteria.Op.EQ);
        hostSfIndexSearch.and("sfIndex", hostSfIndexSearch.entity().getSfIndex(), SearchCriteria.Op.EQ);
        hostSfIndexSearch.done();

        nicIdSearch = createSearchBuilder();
        nicIdSearch.and("allocatedToNicId", nicIdSearch.entity().getAllocatedToNicId(), SearchCriteria.Op.EQ);
        nicIdSearch.done();

        hostPfSearch = createSearchBuilder();
        hostPfSearch.and("hostId", hostPfSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostPfSearch.and("pfIndex", hostPfSearch.entity().getPfIndex(), SearchCriteria.Op.EQ);
        hostPfSearch.done();
    }

    @Override
    public List<SriovSfPoolVO> listByHost(long hostId) {
        SearchCriteria<SriovSfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        return listBy(sc);
    }

    @Override
    public List<SriovSfPoolVO> listByHostAndState(long hostId, String state) {
        SearchCriteria<SriovSfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public SriovSfPoolVO findByHostAndSfIndex(long hostId, int pfIndex, int sfIndex) {
        SearchCriteria<SriovSfPoolVO> sc = hostSfIndexSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("pfIndex", pfIndex);
        sc.setParameters("sfIndex", sfIndex);
        return findOneBy(sc);
    }

    @Override
    public int countByHostAndState(long hostId, String state) {
        SearchCriteria<SriovSfPoolVO> sc = hostStateSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("state", state);
        return getCount(sc);
    }

    @Override
    public SriovSfPoolVO allocate(final long hostId, final long nicId) {
        return Transaction.execute(new TransactionCallback<SriovSfPoolVO>() {
            @Override
            public SriovSfPoolVO doInTransaction(TransactionStatus status) {
                SearchCriteria<SriovSfPoolVO> sc = hostStateSearch.create();
                sc.setParameters("hostId", hostId);
                sc.setParameters("state", State.VDPA_READY.name());
                Filter orderBySfIndex = new Filter(SriovSfPoolVO.class, "sfIndex", true);
                List<SriovSfPoolVO> ready = lockRows(sc, orderBySfIndex, false);
                if (ready == null || ready.isEmpty()) {
                    return null;
                }
                SriovSfPoolVO sf = ready.get(0);
                SriovSfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.ALLOCATED.name());
                updateVo.setAllocatedToNicId(nicId);
                update(sf.getId(), updateVo);
                return sf;
            }
        });
    }

    @Override
    public boolean release(final long sfPoolId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                SriovSfPoolVO sf = lockRow(sfPoolId, false);
                if (sf == null) {
                    return false;
                }
                SriovSfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.VDPA_READY.name());
                updateVo.setAllocatedToNicId(null);
                update(sf.getId(), updateVo);
                return true;
            }
        });
    }

    @Override
    public boolean releaseByNicId(final long nicId) {
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                SearchCriteria<SriovSfPoolVO> sc = nicIdSearch.create();
                sc.setParameters("allocatedToNicId", nicId);
                List<SriovSfPoolVO> rows = lockRows(sc, null, false);
                if (rows == null || rows.isEmpty()) {
                    return false;
                }
                SriovSfPoolVO updateVo = createForUpdate();
                updateVo.setState(State.VDPA_READY.name());
                updateVo.setAllocatedToNicId(null);
                for (SriovSfPoolVO sf : rows) {
                    update(sf.getId(), updateVo);
                }
                return true;
            }
        });
    }

    @Override
    public int getNextAvailableSfIndex(long hostId, int pfIndex) {
        SearchCriteria<SriovSfPoolVO> sc = hostPfSearch.create();
        sc.setParameters("hostId", hostId);
        sc.setParameters("pfIndex", pfIndex);
        Filter orderByDesc = new Filter(SriovSfPoolVO.class, "sfIndex", false, 0L, 1L);
        List<SriovSfPoolVO> rows = listBy(sc, orderByDesc);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return rows.get(0).getSfIndex() + 1;
    }
}
