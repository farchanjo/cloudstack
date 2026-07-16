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
 * Admin-only API: recover the SR-IOV / vDPA VF pool state on the target host
 * by re-binding {@code FREE} pool entries to the live NICs that still
 * reference them via {@code nics.vf_pool_id}. Phase H.1.
 *
 * <p>Companion to {@link ForceReleaseHostVfsCmd} — used to undo an
 * over-zealous force-release without bouncing live VMs / VRs. Walks
 * {@code nics} ⇒ {@code vm_instance} filtering on live VMs (Running /
 * Starting / Stopping / Migrating), flips the matching pool row to
 * {@code ALLOCATED}, stamps {@code allocated_to_nic_id}, and refreshes
 * {@code last_seen}. Idempotent and non-destructive — leaves running
 * vfio-pci bindings on the hypervisor untouched.
 *
 * <p>Typical use:
 * <pre>
 *   1. operator runs forceReleaseHostVfs to clear stale rows;
 *   2. realises live VMs / VRs were also touched;
 *   3. runs recoverHostVfs to restore the live bindings.
 * </pre>
 */
@APICommand(name = RecoverHostVfsCmd.NAME,
        description = "Recover the SR-IOV / vDPA VF pool state on the host by re-binding every "
                + "FREE pool entry to the live NIC that still references it via nics.vf_pool_id. "
                + "Companion to forceReleaseHostVfs — non-destructive, idempotent, leaves the "
                + "live vfio-pci bindings on the hypervisor untouched.",
        responseObject = SuccessResponse.class,
        since = "4.24.1.25",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class RecoverHostVfsCmd extends BaseCmd {

    public static final String NAME = "recoverHostVfs";

    @Inject
    private VfPoolService vfPoolService;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class,
            required = true, description = "Host ID whose VF pool entries should be recovered "
                    + "from FREE → ALLOCATED via live-NIC join",
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
        int recovered = vfPoolService.recoverByHostId(host.getId());
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setObjectName("vfpool");
        response.setSuccess(recovered >= 0);
        response.setDisplayText(String.format(
                "Legacy broad recovery is deactivated; recovered %d VF row(s) on host %s (id=%d).",
                recovered, host.getName(), host.getId()));
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return NAME + "response";
    }
}
