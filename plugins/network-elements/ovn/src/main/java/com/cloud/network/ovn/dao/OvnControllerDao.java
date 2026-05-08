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

import com.cloud.utils.db.GenericDao;

/**
 * DAO over {@link OvnControllerVO}. Records the OVN controller endpoints the
 * plugin connects to.
 */
public interface OvnControllerDao extends GenericDao<OvnControllerVO, Long> {

    /**
     * Looks up a controller registration by its public UUID.
     *
     * @return the matching row, or {@code null} when no row exists.
     */
    OvnControllerVO findByUuid(String uuid);

    /**
     * Lists all controller registrations attached to the given CloudStack zone.
     *
     * @return a (possibly empty) list of registrations; never {@code null}.
     */
    List<OvnControllerVO> listByZone(long zoneId);

    /**
     * Resolves a controller registration by its operator-assigned name within
     * a zone. Names are unique-per-zone at the application layer.
     *
     * @return the matching row, or {@code null} when no row exists.
     */
    OvnControllerVO findByZoneAndName(long zoneId, String name);
}
