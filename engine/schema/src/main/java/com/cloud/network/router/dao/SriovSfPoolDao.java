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

import com.cloud.network.router.SriovSfPoolVO;
import com.cloud.utils.db.GenericDao;

public interface SriovSfPoolDao extends GenericDao<SriovSfPoolVO, Long> {

    /** All SFs on a host, regardless of state. */
    List<SriovSfPoolVO> listByHost(long hostId);

    /** SFs in a specific state on a host. */
    List<SriovSfPoolVO> listByHostAndState(long hostId, String state);

    /** Lookup by host, PF index, and SF index (unique tuple). */
    SriovSfPoolVO findByHostAndSfIndex(long hostId, int pfIndex, int sfIndex);

    /**
     * Atomically take a VDPA_READY SF on the host and bind it to a NIC.
     * Picks the lowest sf_index available.
     * Returns null if no VDPA_READY SF is available.
     * Caller is responsible for calling release on failure of subsequent steps.
     */
    SriovSfPoolVO allocate(long hostId, long nicId);

    /** Release an SF back to VDPA_READY, clearing nic binding. Idempotent. */
    boolean release(long sfPoolId);

    /** Release SF by NIC id (used when NIC is being removed). */
    boolean releaseByNicId(long nicId);

    /** Count SFs in a given state on a host (for capacity reporting). */
    int countByHostAndState(long hostId, String state);

    /**
     * Returns the next available SF index on a given PF for a host.
     * Scans existing entries and returns max(sf_index) + 1, or 0 if none exist.
     */
    int getNextAvailableSfIndex(long hostId, int pfIndex);
}
