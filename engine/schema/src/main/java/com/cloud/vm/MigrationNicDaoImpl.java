// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
package com.cloud.vm;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class MigrationNicDaoImpl extends GenericDaoBase<MigrationNicVO, Long> implements MigrationNicDao {
    private final SearchBuilder<MigrationNicVO> workSearch;

    protected MigrationNicDaoImpl() {
        workSearch = createSearchBuilder();
        workSearch.and("workId", workSearch.entity().getWorkId(), SearchCriteria.Op.EQ);
        workSearch.and("generation", workSearch.entity().getGeneration(), SearchCriteria.Op.EQ);
        workSearch.done();
    }

    @Override
    public List<MigrationNicVO> listByWorkAndGeneration(final String workId, final long generation) {
        final SearchCriteria<MigrationNicVO> sc = workSearch.create();
        sc.setParameters("workId", workId);
        sc.setParameters("generation", generation);
        return search(sc, null);
    }

    @Override
    public int deleteByWorkAndGeneration(final String workId, final long generation) {
        final SearchCriteria<MigrationNicVO> sc = workSearch.create();
        sc.setParameters("workId", workId);
        sc.setParameters("generation", generation);
        return remove(sc);
    }

    @Override
    public int markTerminalByWorkAndGeneration(final String workId, final long generation) {
        final List<MigrationNicVO> rows = listByWorkAndGeneration(workId, generation);
        rows.forEach(row -> row.setTerminal(true));
        return rows.stream().mapToInt(row -> update(row.getId(), row) ? 1 : 0).sum();
    }
}
