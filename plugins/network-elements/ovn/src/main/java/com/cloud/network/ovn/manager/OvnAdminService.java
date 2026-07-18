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

import java.util.List;

import com.cloud.network.ovn.api.response.OvnControllerResponse;
import com.cloud.network.ovn.api.response.OvnLogicalIdResponse;
import com.cloud.network.ovn.api.response.OvnReconcileResultResponse;

/**
 * Admin-side service the API commands consume. Decouples the {@code @APICommand}
 * classes from the implementation so unit tests can mock the surface.
 */
public interface OvnAdminService {

    /**
     * Registers a new OVN controller in the given zone.
     *
     * @return the response object (carries the controller UUID + endpoints).
     */
    OvnControllerResponse addController(long zoneId, String name, String nbEndpoints, String sbEndpoints);

    /**
     * Removes an OVN controller by registration UUID.
     */
    void deleteController(String uuid);

    /**
     * Lists controllers, optionally filtered by zone.
     */
    List<OvnControllerResponse> listControllers(Long zoneId);

    /**
     * Imports an existing OVN logical-router topology into a new CloudStack
     * VPC. The MVP validates the input + reads OVN topology + creates the
     * mapping rows so the rest of the plugin can manage the pre-existing
     * entities. Tier creation (NIC adoption) is a TODO for the MVP.
     *
     * @param zoneId    target zone (selects the controller)
     * @param ovnLrName the OVN LR name to adopt (e.g. {@code lr-test})
     * @param vpcName   the CloudStack VPC name to create
     * @return one mapping row per adopted entity
     */
    List<OvnLogicalIdResponse> importVpc(long zoneId, String ovnLrName, String vpcName);

    /**
     * Run an OVN NB reconcile pass: drops orphan NB rows whose CS-side
     * mapping is gone, and removes stale mapping rows whose NB UUID no
     * longer resolves. {@code dryRun=true} reports counts without mutating.
     *
     * @param zoneId         target zone
     * @param dryRun         {@code true} = no mutation, just counts
     * @param purgeUntagged  {@code true} = also drop DHCP_Options / DNS / ACL
     *                       rows with empty external_ids (operator pollution
     *                       from manual ovn-nbctl sessions or pre-plugin
     *                       state). Off by default — destructive.
     * @return per-table counts + dry-run flag
     */
    OvnReconcileResultResponse runReconciler(long zoneId, boolean dryRun, boolean purgeUntagged);

    /**
     * Run either the zone-wide reconciler or a narrowly scoped resource pass.
     * Scoped reconciliation currently supports only {@code LOAD_BALANCER}; it
     * refuses to remove a mapping while the CloudStack rule still exists.
     */
    OvnReconcileResultResponse runReconciler(long zoneId, boolean dryRun, boolean purgeUntagged,
                                             String resourceKind, Long resourceId);
}
