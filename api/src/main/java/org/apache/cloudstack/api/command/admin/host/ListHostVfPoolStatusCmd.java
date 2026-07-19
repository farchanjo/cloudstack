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

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.VfPoolStatusResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.router.VfPoolService;

@APICommand(name = ListHostVfPoolStatusCmd.NAME, description = "Lists read-only VF pool status for a host.",
        responseObject = VfPoolStatusResponse.class, authorized = {RoleType.Admin})
public class ListHostVfPoolStatusCmd extends BaseCmd {

    public static final String NAME = "listHostVfPoolStatus";

    @Parameter(name = "hostid", type = CommandType.UUID, entityType = HostResponse.class,
            required = true, description = "Host whose VF pool status is requested")
    private Long hostId;

    @Inject
    private VfPoolService vfPoolService;

    @Override
    public void execute() throws ServerApiException {
        if (vfPoolService == null) {
            throw new ServerApiException(org.apache.cloudstack.api.ApiErrorCode.INTERNAL_ERROR,
                    "VfPoolService is not wired in this management server build");
        }
        final VfPoolStatusResponse response = VfPoolStatusResponse.from(vfPoolService.getHostVfPoolStatus(hostId));
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public ApiCommandResourceType getApiResourceType() { return ApiCommandResourceType.Host; }

    @Override
    public Long getApiResourceId() { return hostId; }

    @Override
    public long getEntityOwnerId() { return CallContext.current().getCallingAccountId(); }

    @Override
    public String getCommandName() { return NAME + "response"; }
}
