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
package org.apache.cloudstack.api.command.admin.network;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

import com.cloud.user.Account;

@APICommand(name = "reconcileSystemPublicIpAddresses",
        description = "Reconciles orphaned system-VM (ConsoleProxy/SecondaryStorageVm) public IP addresses that were " +
                "left in the Allocated state after a start-failure or expunge, releasing only those with no live nic and no rules.",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.24.1",
        authorized = {RoleType.Admin})
public class ReconcileSystemPublicIpAddressesCmd extends BaseCmd {

    private static final String s_name = "reconcilesystempublicipaddressesresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "Optional zone (data center) to scope the reconcile to; all zones are scanned when omitted.")
    private Long zoneId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getZoneId() {
        return zoneId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        int reclaimed = _networkService.reconcileSystemPublicIpAddresses(getZoneId());
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setSuccess(true);
        response.setDisplayText(String.format("Reclaimed %d orphaned system-VM public IP address(es).", reclaimed));
        setResponseObject(response);
    }
}
