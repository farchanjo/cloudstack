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
package org.apache.cloudstack.api.command.user.address;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.PublicIpv6AddressResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.UserPublicIpv6Address;
import com.cloud.user.Account;

/**
 * Releases a public IPv6 address back to the Free pool.
 */
@APICommand(name = DisassociatePublicIpv6AddressCmd.APINAME,
        description = "Disassociates a public IPv6 address from the account and returns it to the Free pool.",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        entityType = {UserPublicIpv6Address.class})
public class DisassociatePublicIpv6AddressCmd extends BaseAsyncCmd {
    public static final String APINAME = "disassociatePublicIpv6Address";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = PublicIpv6AddressResponse.class,
            description = "The ID of the public IPv6 address to disassociate. Mutually exclusive with ip6address")
    private Long id;

    @Parameter(name = ApiConstants.IP6_ADDRESS,
            type = CommandType.STRING,
            description = "Public IPv6 address to disassociate. Mutually exclusive with id; requires zoneid")
    private String ip6Address;

    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "Zone of the public IPv6 address when using ip6address parameter")
    private Long zoneId;

    // unexposed parameter needed for events logging
    @Parameter(name = ApiConstants.ACCOUNT_ID, type = CommandType.UUID, entityType = AccountResponse.class, expose = false)
    private Long ownerId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public long getPublicIpv6AddressId() {
        UserPublicIpv6Address addr = getPublicIpv6Address();
        return addr.getId();
    }

    private UserPublicIpv6Address getPublicIpv6Address() {
        if (id != null && ip6Address != null) {
            throw new InvalidParameterValueException("id parameter is mutually exclusive with ip6address parameter");
        }
        if (id != null) {
            UserPublicIpv6Address addr = publicIpv6AddressManager.findById(id);
            if (addr == null) {
                throw new InvalidParameterValueException("Unable to find public IPv6 address by ID=" + id);
            }
            return addr;
        }
        if (ip6Address != null) {
            if (zoneId == null) {
                throw new InvalidParameterValueException("zoneid is required when specifying ip6address");
            }
            UserPublicIpv6Address addr = publicIpv6AddressManager.findByZoneAndAddress(zoneId, ip6Address);
            if (addr == null) {
                throw new InvalidParameterValueException(
                        "Unable to find public IPv6 address " + ip6Address + " in zone id=" + zoneId);
            }
            return addr;
        }
        throw new InvalidParameterValueException("Please specify either id or ip6address");
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ResourceUnavailableException, ConcurrentOperationException {
        UserPublicIpv6Address addr = getPublicIpv6Address();
        CallContext.current().setEventDetails("Public IPv6 address ID: " + addr.getUuid());

        Account caller = CallContext.current().getCallingAccount();
        _accountService.checkAccess(caller, null, true, addr);

        boolean result = publicIpv6AddressManager.release(addr.getId());
        if (result) {
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to disassociate public IPv6 address");
        }
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_PUBLIC_IPV6_RELEASE;
    }

    @Override
    public String getEventDescription() {
        return "Disassociating public IPv6 address id=" + getPublicIpv6Address().getUuid();
    }

    @Override
    public long getEntityOwnerId() {
        if (ownerId == null) {
            UserPublicIpv6Address addr = getPublicIpv6Address();
            long accountId = addr.getAccountId();
            ownerId = accountId > 0 ? accountId : Account.ACCOUNT_ID_SYSTEM;
        }
        return ownerId;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.PublicIpv6Address;
    }

    @Override
    public Long getApiResourceId() {
        return getPublicIpv6AddressId();
    }
}
