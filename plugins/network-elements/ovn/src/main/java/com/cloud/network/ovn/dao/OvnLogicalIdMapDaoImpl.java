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
package com.cloud.network.ovn.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

@Component
public class OvnLogicalIdMapDaoImpl extends GenericDaoBase<OvnLogicalIdMapVO, Long> implements OvnLogicalIdMapDao {

    private final SearchBuilder<OvnLogicalIdMapVO> csSearch;
    private final SearchBuilder<OvnLogicalIdMapVO> uuidSearch;
    private final SearchBuilder<OvnLogicalIdMapVO> kindSearch;
    private final SearchBuilder<OvnLogicalIdMapVO> csNetworkSearch;

    public OvnLogicalIdMapDaoImpl() {
        csSearch = createSearchBuilder();
        csSearch.and("csKind", csSearch.entity().getCsKind(), Op.EQ);
        csSearch.and("csId", csSearch.entity().getCsId(), Op.EQ);
        csSearch.and("controllerId", csSearch.entity().getControllerId(), Op.EQ);
        csSearch.done();

        uuidSearch = createSearchBuilder();
        uuidSearch.and("ovnUuid", uuidSearch.entity().getOvnUuid(), Op.EQ);
        uuidSearch.done();

        kindSearch = createSearchBuilder();
        kindSearch.and("csKind", kindSearch.entity().getCsKind(), Op.EQ);
        kindSearch.and("controllerId", kindSearch.entity().getControllerId(), Op.EQ);
        kindSearch.done();

        csNetworkSearch = createSearchBuilder();
        csNetworkSearch.and("csKind", csNetworkSearch.entity().getCsKind(), Op.EQ);
        csNetworkSearch.and("csId", csNetworkSearch.entity().getCsId(), Op.EQ);
        csNetworkSearch.and("controllerId", csNetworkSearch.entity().getControllerId(), Op.EQ);
        csNetworkSearch.and("networkId", csNetworkSearch.entity().getNetworkId(), Op.EQ);
        csNetworkSearch.done();
    }

    @Override
    public OvnLogicalIdMapVO findByCsId(final Kind kind, final long csId, final long controllerId) {
        final SearchCriteria<OvnLogicalIdMapVO> sc = csSearch.create();
        sc.setParameters("csKind", kind.name());
        sc.setParameters("csId", csId);
        sc.setParameters("controllerId", controllerId);
        return findOneBy(sc);
    }

    @Override
    public OvnLogicalIdMapVO findByOvnUuid(final String ovnUuid) {
        final SearchCriteria<OvnLogicalIdMapVO> sc = uuidSearch.create();
        sc.setParameters("ovnUuid", ovnUuid);
        return findOneBy(sc);
    }

    @Override
    public OvnLogicalIdMapVO findByCsId(final Kind kind, final long csId, final long controllerId,
                                        final long networkId) {
        final SearchCriteria<OvnLogicalIdMapVO> sc = csNetworkSearch.create();
        sc.setParameters("csKind", kind.name());
        sc.setParameters("csId", csId);
        sc.setParameters("controllerId", controllerId);
        sc.setParameters("networkId", networkId);
        return findOneBy(sc);
    }

    @Override
    public List<OvnLogicalIdMapVO> listByKind(final Kind kind, final long controllerId) {
        final SearchCriteria<OvnLogicalIdMapVO> sc = kindSearch.create();
        sc.setParameters("csKind", kind.name());
        sc.setParameters("controllerId", controllerId);
        return search(sc, null);
    }
}
