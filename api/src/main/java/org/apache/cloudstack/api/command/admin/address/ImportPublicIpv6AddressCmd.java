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
package org.apache.cloudstack.api.command.admin.address;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.PublicIpv6AddressResponse;
import org.apache.cloudstack.api.response.VpcResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.UserPublicIpv6Address;
import com.cloud.network.vpc.Vpc;
import com.cloud.user.Account;
import com.cloud.utils.net.NetUtils;

/**
 * Admin-only import of a grandfathered public IPv6 VIP into
 * {@code user_public_ipv6_address} as Allocated (transport-band host ids allowed).
 * Used to migrate Phase-1 ConfigKey VIPs (e.g. {@code ::100}/{@code ::101}) into inventory.
 */
@APICommand(name = ImportPublicIpv6AddressCmd.APINAME,
        description = "Imports a grandfathered public IPv6 address into inventory as Allocated "
                + "(system). Either zoneId, networkId, or vpcId is required. Admin only.",
        responseObject = PublicIpv6AddressResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        entityType = {UserPublicIpv6Address.class},
        authorized = {RoleType.Admin},
        since = "4.24.1.30")
public class ImportPublicIpv6AddressCmd extends BaseAsyncCmd implements AdminCmd {
    public static final String APINAME = "importPublicIpv6Address";
    private static final String s_name = "importpublicipv6addressresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ACCOUNT,
            type = CommandType.STRING,
            description = "Account that will own the imported address. Must be used with domainId. "
                    + "Defaults to the calling admin account.")
    private String accountName;

    @Parameter(name = ApiConstants.DOMAIN_ID,
            type = CommandType.UUID,
            entityType = DomainResponse.class,
            description = "Domain of the owner account. Required when account is specified.")
    private Long domainId;

    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "Zone that owns this public IPv6 address")
    private Long zoneId;

    @Parameter(name = ApiConstants.NETWORK_ID,
            type = CommandType.UUID,
            entityType = NetworkResponse.class,
            description = "Optional network to associate; also used to derive the zone when zoneId is omitted")
    private Long networkId;

    @Parameter(name = ApiConstants.VPC_ID,
            type = CommandType.UUID,
            entityType = VpcResponse.class,
            description = "Optional VPC to associate; also used to derive the zone when zoneId is omitted")
    private Long vpcId;

    @Parameter(name = ApiConstants.IP6_ADDRESS,
            type = CommandType.STRING,
            required = true,
            description = "Public IPv6 address to import (must sit inside ovn.public.ipv6.prefix; "
                    + "transport-band grandfather VIPs are allowed)")
    private String ip6Address;

    @Parameter(name = ApiConstants.FOR_DISPLAY,
            type = CommandType.BOOLEAN,
            description = "Whether to display the address to end users (default false for system imports)",
            authorized = {RoleType.Admin})
    private Boolean display;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getIp6Address() {
        return ip6Address;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public Long getVpcId() {
        return vpcId;
    }

    public Long getZoneIdParam() {
        return zoneId;
    }

    long resolveZoneId() {
        if (zoneId != null) {
            return zoneId;
        } else if (vpcId != null) {
            Vpc vpc = _entityMgr.findById(Vpc.class, vpcId);
            if (vpc != null) {
                return vpc.getZoneId();
            }
        } else if (networkId != null) {
            Network ntwk = _entityMgr.findById(Network.class, networkId);
            if (ntwk != null) {
                return ntwk.getDataCenterId();
            }
        }
        throw new InvalidParameterValueException(
                "Unable to figure out zone for public IPv6 import. Please specify either zoneId, networkId, or vpcId");
    }

    @Override
    public boolean isDisplay() {
        // Grandfather system imports default to not displayed to end users
        return display != null && display;
    }

    @Override
    public long getEntityOwnerId() {
        Account caller = CallContext.current().getCallingAccount();
        if (accountName != null && domainId != null) {
            Account account = _accountService.finalizeOwner(caller, accountName, domainId, null);
            return account.getId();
        }
        return caller.getAccountId();
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_PUBLIC_IPV6_ASSIGN;
    }

    @Override
    public String getEventDescription() {
        return "importing public IPv6 " + ip6Address + " in zone " + (zoneId != null ? zoneId : "(derived)");
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public void execute() {
        try {
            if (ip6Address == null || !NetUtils.isValidIp6(ip6Address)) {
                throw new InvalidParameterValueException("Invalid IPv6 address: " + ip6Address);
            }

            Account owner = _accountService.getAccount(getEntityOwnerId());
            long resolvedZoneId = resolveZoneId();
            CallContext.current().setEventDetails(
                    "Public IPv6 address: " + ip6Address + " zoneId: " + resolvedZoneId);

            UserPublicIpv6Address result = publicIpv6AddressManager.importAllocated(
                    resolvedZoneId, owner, ip6Address, networkId, vpcId, true, isDisplay());

            if (result == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR,
                        "Failed to import public IPv6 address");
            }

            PublicIpv6AddressResponse response = _responseGenerator.createPublicIpv6AddressResponse(result);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (InvalidParameterValueException ex) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, ex.getMessage());
        } catch (ConcurrentOperationException ex) {
            logger.warn("Concurrent operation during public IPv6 import: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.PublicIpv6Address;
    }
}
