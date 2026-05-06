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
public class OvnControllerDaoImpl extends GenericDaoBase<OvnControllerVO, Long> implements OvnControllerDao {

    private final SearchBuilder<OvnControllerVO> uuidSearch;
    private final SearchBuilder<OvnControllerVO> zoneSearch;
    private final SearchBuilder<OvnControllerVO> zoneAndNameSearch;

    public OvnControllerDaoImpl() {
        uuidSearch = createSearchBuilder();
        uuidSearch.and("uuid", uuidSearch.entity().getUuid(), Op.EQ);
        uuidSearch.done();

        zoneSearch = createSearchBuilder();
        zoneSearch.and("zoneId", zoneSearch.entity().getZoneId(), Op.EQ);
        zoneSearch.done();

        zoneAndNameSearch = createSearchBuilder();
        zoneAndNameSearch.and("zoneId", zoneAndNameSearch.entity().getZoneId(), Op.EQ);
        zoneAndNameSearch.and("name", zoneAndNameSearch.entity().getName(), Op.EQ);
        zoneAndNameSearch.done();
    }

    @Override
    public OvnControllerVO findByUuid(final String uuid) {
        final SearchCriteria<OvnControllerVO> sc = uuidSearch.create();
        sc.setParameters("uuid", uuid);
        return findOneBy(sc);
    }

    @Override
    public List<OvnControllerVO> listByZone(final long zoneId) {
        final SearchCriteria<OvnControllerVO> sc = zoneSearch.create();
        sc.setParameters("zoneId", zoneId);
        return search(sc, null);
    }

    @Override
    public OvnControllerVO findByZoneAndName(final long zoneId, final String name) {
        final SearchCriteria<OvnControllerVO> sc = zoneAndNameSearch.create();
        sc.setParameters("zoneId", zoneId);
        sc.setParameters("name", name);
        return findOneBy(sc);
    }
}
