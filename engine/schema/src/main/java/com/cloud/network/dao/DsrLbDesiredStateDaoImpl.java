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
package com.cloud.network.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class DsrLbDesiredStateDaoImpl extends GenericDaoBase<DsrLbDesiredStateVO, Long> implements DsrLbDesiredStateDao {

    private final SearchBuilder<DsrLbDesiredStateVO> LbIdSearch;
    private final SearchBuilder<DsrLbDesiredStateVO> StateSearch;
    private final SearchBuilder<DsrLbDesiredStateVO> ActiveSearch;

    public DsrLbDesiredStateDaoImpl() {
        LbIdSearch = createSearchBuilder();
        LbIdSearch.and("loadBalancerId", LbIdSearch.entity().getLoadBalancerId(), SearchCriteria.Op.EQ);
        LbIdSearch.and("removed", LbIdSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        LbIdSearch.done();

        StateSearch = createSearchBuilder();
        StateSearch.and("state", StateSearch.entity().getState(), SearchCriteria.Op.EQ);
        StateSearch.and("removed", StateSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        StateSearch.done();

        ActiveSearch = createSearchBuilder();
        ActiveSearch.and("removed", ActiveSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        ActiveSearch.and("state", ActiveSearch.entity().getState(), SearchCriteria.Op.NEQ);
        ActiveSearch.done();
    }

    @Override
    public DsrLbDesiredStateVO findByLoadBalancerId(long loadBalancerId) {
        SearchCriteria<DsrLbDesiredStateVO> sc = LbIdSearch.create();
        sc.setParameters("loadBalancerId", loadBalancerId);
        return findOneBy(sc);
    }

    @Override
    public List<DsrLbDesiredStateVO> listActive() {
        SearchCriteria<DsrLbDesiredStateVO> sc = ActiveSearch.create();
        sc.setParameters("state", DsrLbDesiredStateVO.STATE_REVOKED);
        return listBy(sc);
    }

    @Override
    public List<DsrLbDesiredStateVO> listByState(String state) {
        SearchCriteria<DsrLbDesiredStateVO> sc = StateSearch.create();
        sc.setParameters("state", state);
        return listBy(sc);
    }
}
