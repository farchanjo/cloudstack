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

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

@Component
public class OvnPendingDeletionDaoImpl extends GenericDaoBase<OvnPendingDeletionVO, Long>
        implements OvnPendingDeletionDao {

    /** Sentinel value for controller_id when the actual controller was unknown at enqueue time. */
    public static final long CONTROLLER_SENTINEL = 0L;

    private final SearchBuilder<OvnPendingDeletionVO> pendingByControllerSearch;
    private final SearchBuilder<OvnPendingDeletionVO> pendingSentinelByZoneSearch;
    private final SearchBuilder<OvnPendingDeletionVO> allSentinelsSearch;
    private final SearchBuilder<OvnPendingDeletionVO> byOvnUuidKindSearch;

    public OvnPendingDeletionDaoImpl() {
        pendingByControllerSearch = createSearchBuilder();
        pendingByControllerSearch.and("controllerId", pendingByControllerSearch.entity().getControllerId(), Op.EQ);
        pendingByControllerSearch.and("removed", pendingByControllerSearch.entity().getRemoved(), Op.NULL);
        pendingByControllerSearch.done();

        pendingSentinelByZoneSearch = createSearchBuilder();
        pendingSentinelByZoneSearch.and("controllerId", pendingSentinelByZoneSearch.entity().getControllerId(), Op.EQ);
        pendingSentinelByZoneSearch.and("zoneId", pendingSentinelByZoneSearch.entity().getZoneId(), Op.EQ);
        pendingSentinelByZoneSearch.and("removed", pendingSentinelByZoneSearch.entity().getRemoved(), Op.NULL);
        pendingSentinelByZoneSearch.done();

        allSentinelsSearch = createSearchBuilder();
        allSentinelsSearch.and("controllerId", allSentinelsSearch.entity().getControllerId(), Op.EQ);
        allSentinelsSearch.and("removed", allSentinelsSearch.entity().getRemoved(), Op.NULL);
        allSentinelsSearch.done();

        byOvnUuidKindSearch = createSearchBuilder();
        byOvnUuidKindSearch.and("ovnUuid", byOvnUuidKindSearch.entity().getOvnUuid(), Op.EQ);
        byOvnUuidKindSearch.and("kind", byOvnUuidKindSearch.entity().getKindRaw(), Op.EQ);
        byOvnUuidKindSearch.and("removed", byOvnUuidKindSearch.entity().getRemoved(), Op.NULL);
        byOvnUuidKindSearch.done();
    }

    @Override
    public List<OvnPendingDeletionVO> findPendingByController(final long controllerId, final int limit) {
        final SearchCriteria<OvnPendingDeletionVO> sc = pendingByControllerSearch.create();
        sc.setParameters("controllerId", controllerId);
        // lastAttemptAt NULL first (never tried), then oldest attempt first.
        final Filter f = new Filter(OvnPendingDeletionVO.class, "lastAttemptAt", true, 0L, (long) limit);
        return search(sc, f);
    }

    @Override
    public List<OvnPendingDeletionVO> findPendingSentinelByZone(final long zoneId, final int limit) {
        final SearchCriteria<OvnPendingDeletionVO> sc = pendingSentinelByZoneSearch.create();
        sc.setParameters("controllerId", CONTROLLER_SENTINEL);
        sc.setParameters("zoneId", zoneId);
        final Filter f = new Filter(OvnPendingDeletionVO.class, "lastAttemptAt", true, 0L, (long) limit);
        return search(sc, f);
    }

    @Override
    public void markFailed(final long id, final String error) {
        final OvnPendingDeletionVO vo = findById(id);
        if (vo == null) {
            return;
        }
        vo.setAttempts(vo.getAttempts() + 1);
        vo.setLastAttemptAt(new Date());
        vo.setLastError(error);
        update(id, vo);
    }

    @Override
    public void markSucceeded(final long id) {
        final OvnPendingDeletionVO vo = findById(id);
        if (vo == null) {
            return;
        }
        vo.setRemoved(new Date());
        update(id, vo);
    }

    @Override
    public List<OvnPendingDeletionVO> findAllSentinels(final int limit) {
        final SearchCriteria<OvnPendingDeletionVO> sc = allSentinelsSearch.create();
        sc.setParameters("controllerId", CONTROLLER_SENTINEL);
        final Filter f = new Filter(OvnPendingDeletionVO.class, "lastAttemptAt", true, 0L, (long) limit);
        return search(sc, f);
    }

    @Override
    public boolean isPendingByOvnUuid(final String ovnUuid, final String kind) {
        final SearchCriteria<OvnPendingDeletionVO> sc = byOvnUuidKindSearch.create();
        sc.setParameters("ovnUuid", ovnUuid);
        sc.setParameters("kind", kind);
        return findOneBy(sc) != null;
    }
}
