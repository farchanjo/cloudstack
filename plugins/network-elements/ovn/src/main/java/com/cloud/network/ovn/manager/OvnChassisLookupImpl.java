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
package com.cloud.network.ovn.manager;

import javax.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.network.ovn.OvnChassisLookup;
import com.cloud.network.ovn.dao.OvnChassisMapDao;

/** Database-backed read adapter; it performs no OVN writes. */
@Component
public class OvnChassisLookupImpl implements OvnChassisLookup {

    private final OvnChassisMapDao chassisMapDao;

    @Inject
    public OvnChassisLookupImpl(final OvnChassisMapDao chassisMapDao) {
        this.chassisMapDao = chassisMapDao;
    }

    @Override
    public String findChassisUuid(final long hostId) {
        final var row = chassisMapDao.findByHostId(hostId);
        return row == null ? null : row.getChassisUuid();
    }
}
