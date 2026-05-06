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
 * DAO over {@link OvnChassisMapVO}. Maps CloudStack hosts to OVN chassis.
 */
public interface OvnChassisMapDao extends GenericDao<OvnChassisMapVO, Long> {

    /**
     * Looks up the chassis row for a given host.
     *
     * @return the matching row, or {@code null} when the host has not been
     *         registered with the OVN plugin yet.
     */
    OvnChassisMapVO findByHostId(long hostId);

    /**
     * Looks up a row by its chassis UUID. Useful when reading from the OVN SB
     * DB and resolving back to the CloudStack host.
     *
     * @return the matching row, or {@code null} when no host is bound to the
     *         given chassis UUID.
     */
    OvnChassisMapVO findByChassisUuid(String chassisUuid);

    /**
     * Lists every chassis row attached to the given controller.
     */
    List<OvnChassisMapVO> listByController(long controllerId);
}
