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

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.utils.db.GenericDao;

public interface SriovVfPoolDao extends GenericDao<SriovVfPoolVO, Long> {

    /** All VFs on a host, regardless of state. */
    List<SriovVfPoolVO> listByHost(long hostId);

    /** VFs in a specific state on a host. */
    List<SriovVfPoolVO> listByHostAndState(long hostId, State state);

    /** Lookup by PCI address (unique per host). */
    SriovVfPoolVO findByHostAndPci(long hostId, String pciAddress);

    /**
     * Atomically take a free VF on the host and bind it to a NIC.
     * Returns null if no FREE VF is available.
     * Caller is responsible for calling release on failure of subsequent steps.
     */
    SriovVfPoolVO allocate(long hostId, long nicId);

    /** Release a VF back to the FREE pool, clearing nic binding. Idempotent. */
    boolean release(long vfPoolId);

    /** Release VF by NIC id (used when NIC is being removed). */
    boolean releaseByNicId(long nicId);

    /** Counts of FREE/ALLOCATED/RESERVED/UNAVAILABLE per host (for capacity reporting). */
    int countByHostAndState(long hostId, State state);
}
