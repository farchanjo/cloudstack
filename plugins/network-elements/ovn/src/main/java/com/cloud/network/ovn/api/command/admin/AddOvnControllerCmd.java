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
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.ovn.api.response.OvnControllerResponse;
import com.cloud.network.ovn.manager.OvnAdminService;

/**
 * Registers an OVN controller with CloudStack. The {@code nbendpoints}
 * parameter is a comma-separated list of {@code tcp:host:port} entries; the
 * plugin client picks the live endpoint at runtime.
 */
@APICommand(name = AddOvnControllerCmd.APINAME, description = "Add an OVN controller registration to CloudStack",
        responseObject = OvnControllerResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.24.1.24")
public class AddOvnControllerCmd extends BaseCmd {

    public static final String APINAME = "addOvnController";

    @Inject
    private OvnAdminService ovnAdminService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            required = true, description = "the zone the OVN deployment serves")
    private Long zoneId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true,
            description = "operator-assigned name (unique per zone)")
    private String name;

    @Parameter(name = "nbendpoints", type = CommandType.STRING, required = true,
            description = "comma-separated OVSDB-NB endpoints (tcp:host:port)")
    private String nbEndpoints;

    @Parameter(name = "sbendpoints", type = CommandType.STRING,
            description = "optional comma-separated OVSDB-SB endpoints (tcp:host:port)")
    private String sbEndpoints;

    public Long getZoneId() {
        return zoneId;
    }

    public String getName() {
        return name;
    }

    public String getNbEndpoints() {
        return nbEndpoints;
    }

    public String getSbEndpoints() {
        return sbEndpoints;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final OvnControllerResponse response = ovnAdminService.addController(zoneId, name, nbEndpoints, sbEndpoints);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (final RuntimeException re) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, re.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
