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
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.ovn.api.response.OvnControllerResponse;
import com.cloud.network.ovn.manager.OvnAdminService;

@APICommand(name = ListOvnControllersCmd.APINAME, description = "List OVN controllers",
        responseObject = OvnControllerResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.24.1.24")
public class ListOvnControllersCmd extends BaseListCmd {

    public static final String APINAME = "listOvnControllers";

    @Inject
    private OvnAdminService ovnAdminService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "list controllers for the given zone only")
    private Long zoneId;

    @Override
    public void execute() throws ServerApiException {
        final List<OvnControllerResponse> rows = ovnAdminService.listControllers(zoneId);
        final ListResponse<OvnControllerResponse> response = new ListResponse<>();
        response.setResponses(new ArrayList<>(rows));
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
