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

import com.cloud.utils.db.GenericDao;

public interface MigrationNicDao extends GenericDao<MigrationNicVO, Long> {
    List<MigrationNicVO> listByWorkAndGeneration(String workId, long generation);
    int deleteByWorkAndGeneration(String workId, long generation);
    int markTerminalByWorkAndGeneration(String workId, long generation);
}
