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

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

@Component
public class OvnChassisMapDaoImpl extends GenericDaoBase<OvnChassisMapVO, Long> implements OvnChassisMapDao {

    private final SearchBuilder<OvnChassisMapVO> hostSearch;
    private final SearchBuilder<OvnChassisMapVO> chassisSearch;
    private final SearchBuilder<OvnChassisMapVO> controllerSearch;

    public OvnChassisMapDaoImpl() {
        hostSearch = createSearchBuilder();
        hostSearch.and("hostId", hostSearch.entity().getHostId(), Op.EQ);
        hostSearch.done();

        chassisSearch = createSearchBuilder();
        chassisSearch.and("chassisUuid", chassisSearch.entity().getChassisUuid(), Op.EQ);
        chassisSearch.done();

        controllerSearch = createSearchBuilder();
        controllerSearch.and("controllerId", controllerSearch.entity().getControllerId(), Op.EQ);
        controllerSearch.done();
    }

    @Override
    public OvnChassisMapVO findByHostId(final long hostId) {
        final SearchCriteria<OvnChassisMapVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return findOneBy(sc);
    }

    @Override
    public OvnChassisMapVO findByChassisUuid(final String chassisUuid) {
        final SearchCriteria<OvnChassisMapVO> sc = chassisSearch.create();
        sc.setParameters("chassisUuid", chassisUuid);
        return findOneBy(sc);
    }

    @Override
    public List<OvnChassisMapVO> listByController(final long controllerId) {
        final SearchCriteria<OvnChassisMapVO> sc = controllerSearch.create();
        sc.setParameters("controllerId", controllerId);
        return search(sc, null);
    }
}
