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
import org.apache.cloudstack.api.ApiArgValidator;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.host.Host;
import com.cloud.network.router.VfPoolService;

/**
 * Admin-only API: force-release every {@code ALLOCATED} or {@code SUSPECT} VF
 * row on the target host back to {@code FREE}. Phase H.1.
 *
 * <p>Used after a host disconnect / fault when the operator has confirmed the
 * VF inventory should be reset (e.g. after a host reinstall, hardware
 * replacement, or accepting an unsalvageable VR fleet). Always destructive —
 * the corresponding VRs / user VMs lose their NIC binding the next time the
 * agent comes back; they must be restarted.
 */
@APICommand(name = ForceReleaseHostVfsCmd.NAME,
        description = "Force-release every ALLOCATED or SUSPECT SR-IOV VF row on the host back to FREE. "
                + "Use after a host fault / reinstall when the VF inventory is known to be stale. "
                + "Destructive: dependent VRs / VMs must be restarted to reacquire NIC bindings.",
        responseObject = SuccessResponse.class,
        since = "4.24.1.25",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class ForceReleaseHostVfsCmd extends BaseCmd {

    public static final String NAME = "forceReleaseHostVfs";

    @Inject
    private VfPoolService vfPoolService;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class,
            required = true, description = "Host ID whose VF pool entries should be force-released",
            validations = {ApiArgValidator.PositiveNumber})
    private Long hostId;

    public Long getHostId() {
        return hostId;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Host;
    }

    @Override
    public Long getApiResourceId() {
        return hostId;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public void execute() throws ServerApiException {
        if (vfPoolService == null) {
            throw new ServerApiException(org.apache.cloudstack.api.ApiErrorCode.INTERNAL_ERROR,
                    "VfPoolService is not wired in this management server build");
        }
        Host host = _resourceService.getHost(hostId);
        if (host == null) {
            throw new ServerApiException(org.apache.cloudstack.api.ApiErrorCode.PARAM_ERROR,
                    "Host not found: id=" + hostId);
        }
        int released = vfPoolService.forceReleaseByHostId(host.getId());
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setObjectName("vfpool");
        response.setSuccess(released >= 0);
        response.setDisplayText(String.format(
                "Quarantined %d VF row(s) on host %s (id=%d); no row was broadly freed.",
                released, host.getName(), host.getId()));
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return NAME + "response";
    }
}
