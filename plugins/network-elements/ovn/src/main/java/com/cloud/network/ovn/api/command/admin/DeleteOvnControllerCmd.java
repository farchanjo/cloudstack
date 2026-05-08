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
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.ovn.manager.OvnAdminService;

/**
 * Removes an OVN controller registration by registration UUID. Drops the
 * cached client pool so the next call to the deleted zone fails fast.
 */
@APICommand(name = DeleteOvnControllerCmd.APINAME, description = "Remove an OVN controller registration",
        responseObject = SuccessResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.24.1.24")
public class DeleteOvnControllerCmd extends BaseCmd {

    public static final String APINAME = "deleteOvnController";

    @Inject
    private OvnAdminService ovnAdminService;

    @Parameter(name = ApiConstants.UUID, type = CommandType.STRING, required = true,
            description = "the OVN controller registration UUID")
    private String uuid;

    public String getUuid() {
        return uuid;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            ovnAdminService.deleteController(uuid);
            final SuccessResponse response = new SuccessResponse(getCommandName());
            response.setSuccess(true);
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
