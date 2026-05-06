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

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.ovn.api.response.OvnLogicalIdResponse;
import com.cloud.network.ovn.manager.OvnAdminService;

/**
 * Adopts an existing OVN topology (LR + tied LSes) into a CloudStack VPC.
 * MVP scope: validate input + ping NB + create the VPC + tier rows. Full
 * NIC adoption is a TODO (Phase I.5 stub).
 */
@APICommand(name = ImportOvnVpcCmd.APINAME, description = "Import an existing OVN logical-router topology as a CloudStack VPC",
        responseObject = OvnLogicalIdResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.24.1.24")
public class ImportOvnVpcCmd extends BaseCmd {

    public static final String APINAME = "importOvnVpc";

    @Inject
    private OvnAdminService ovnAdminService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            required = true, description = "the zone the OVN topology lives in")
    private Long zoneId;

    @Parameter(name = "ovnlrname", type = CommandType.STRING, required = true,
            description = "OVN logical-router name to adopt (e.g. lr-test)")
    private String ovnLrName;

    @Parameter(name = "vpcname", type = CommandType.STRING, required = true,
            description = "CloudStack VPC name to create for the adopted topology")
    private String vpcName;

    public Long getZoneId() {
        return zoneId;
    }

    public String getOvnLrName() {
        return ovnLrName;
    }

    public String getVpcName() {
        return vpcName;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final List<OvnLogicalIdResponse> rows = ovnAdminService.importVpc(zoneId, ovnLrName, vpcName);
            final ListResponse<OvnLogicalIdResponse> response = new ListResponse<>();
            response.setResponses(new ArrayList<>(rows));
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
