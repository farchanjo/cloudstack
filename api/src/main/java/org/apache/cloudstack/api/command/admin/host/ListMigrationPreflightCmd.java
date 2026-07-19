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
package org.apache.cloudstack.api.command.admin.host;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.MigrationPreflightResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.router.MigrationPreflightService;

@APICommand(name = ListMigrationPreflightCmd.NAME, description = "Checks read-only migration admission gates.",
        responseObject = MigrationPreflightResponse.class, authorized = {RoleType.Admin})
public class ListMigrationPreflightCmd extends BaseCmd {
    public static final String NAME = "listMigrationPreflight";

    @Parameter(name = "virtualmachineid", type = CommandType.UUID, entityType = UserVmResponse.class,
            required = true, description = "VM to check")
    private Long vmId;
    @Parameter(name = "hostid", type = CommandType.UUID, entityType = HostResponse.class,
            required = true, description = "Destination host to check")
    private Long destinationHostId;

    @Inject
    private MigrationPreflightService preflightService;

    @Override
    public void execute() throws ServerApiException {
        if (preflightService == null) {
            throw new ServerApiException(org.apache.cloudstack.api.ApiErrorCode.INTERNAL_ERROR,
                    "MigrationPreflightService is not wired in this management server build");
        }
        final MigrationPreflightResponse response = MigrationPreflightResponse.from(
                preflightService.preflight(vmId, destinationHostId));
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override public ApiCommandResourceType getApiResourceType() { return ApiCommandResourceType.Host; }
    @Override public Long getApiResourceId() { return destinationHostId; }
    @Override public long getEntityOwnerId() { return CallContext.current().getCallingAccountId(); }
    @Override public String getCommandName() { return NAME + "response"; }
}
