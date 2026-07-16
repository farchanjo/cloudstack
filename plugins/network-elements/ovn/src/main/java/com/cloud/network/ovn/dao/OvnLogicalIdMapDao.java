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

import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.utils.db.GenericDao;

/**
 * DAO over {@link OvnLogicalIdMapVO}. CloudStack id &harr; OVN UUID lookup.
 */
public interface OvnLogicalIdMapDao extends GenericDao<OvnLogicalIdMapVO, Long> {

    /**
     * Forward lookup CloudStack id -&gt; OVN UUID.
     *
     * @return the matching row, or {@code null}.
     */
    OvnLogicalIdMapVO findByCsId(Kind kind, long csId, long controllerId);

    OvnLogicalIdMapVO findByCsId(Kind kind, long csId, long controllerId, long networkId);

    /**
     * Reverse lookup: given an OVN UUID, find the CloudStack mapping. Useful
     * for the import flow ({@code ImportOvnVpcCmd}).
     *
     * @return the matching row, or {@code null}.
     */
    OvnLogicalIdMapVO findByOvnUuid(String ovnUuid);

    /**
     * Lists all CloudStack mappings of a given kind under a controller.
     */
    List<OvnLogicalIdMapVO> listByKind(Kind kind, long controllerId);
}
