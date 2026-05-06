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
package com.cloud.network.ovn.api.command.admin;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.ovn.api.response.OvnReconcileResultResponse;
import com.cloud.network.ovn.manager.OvnAdminService;

/**
 * Admin-only API to run a one-shot OVN NB reconcile pass against a zone.
 * Sweeps orphan NB rows whose CS-side mapping has gone away + stale
 * mapping rows whose NB UUID no longer resolves. Safe to invoke any time;
 * the runtime helpers already self-heal on per-entity touches — this
 * command collapses pre-existing drift in one shot.
 *
 * <p>Usage examples:
 * <pre>
 * cmk runOvnReconciler zoneid=&lt;zone-uuid&gt; dryrun=true   # report only
 * cmk runOvnReconciler zoneid=&lt;zone-uuid&gt;               # actually clean
 * </pre>
 */
@APICommand(name = RunOvnReconcilerCmd.APINAME,
        description = "Sweep orphan OVN NB rows + stale ovn_logical_id_map rows in a zone",
        responseObject = OvnReconcileResultResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.24.1.25")
public class RunOvnReconcilerCmd extends BaseCmd {

    public static final String APINAME = "runOvnReconciler";

    @Inject
    private OvnAdminService ovnAdminService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            required = true, description = "zone whose OVN NB DB should be reconciled")
    private Long zoneId;

    @Parameter(name = "dryrun", type = CommandType.BOOLEAN,
            description = "when true, return counts without mutating NB / DAO state (default false)")
    private Boolean dryRun;

    @Override
    public void execute() throws ServerApiException {
        final boolean isDryRun = Boolean.TRUE.equals(dryRun);
        final OvnReconcileResultResponse response = ovnAdminService.runReconciler(zoneId, isDryRun);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
