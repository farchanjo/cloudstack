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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.PublicIpv6AddressResponse;
import org.apache.cloudstack.api.response.VpcResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.UserPublicIpv6Address;
import com.cloud.user.Account;

/**
 * Lists public IPv6 VIP/FIP inventory addresses ({@code user_public_ipv6_address}).
 */
@APICommand(name = ListPublicIpv6AddressesCmd.APINAME,
        description = "Lists public IPv6 addresses from the public IPv6 inventory",
        responseObject = PublicIpv6AddressResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        entityType = {UserPublicIpv6Address.class})
public class ListPublicIpv6AddressesCmd extends BaseListProjectAndAccountResourcesCmd implements UserCmd {
    public static final String APINAME = "listPublicIpv6Addresses";
    private static final String s_name = "listpublicipv6addressesresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = PublicIpv6AddressResponse.class,
            description = "List by public IPv6 address ID")
    private Long id;

    @Parameter(name = ApiConstants.IP6_ADDRESS,
            type = CommandType.STRING,
            description = "List the specified public IPv6 address (requires zoneid)")
    private String ip6Address;

    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "List public IPv6 addresses by zone ID")
    private Long zoneId;

    @Parameter(name = ApiConstants.NETWORK_ID,
            type = CommandType.UUID,
            entityType = NetworkResponse.class,
            description = "List public IPv6 addresses associated with the network")
    private Long networkId;

    @Parameter(name = ApiConstants.VPC_ID,
            type = CommandType.UUID,
            entityType = VpcResponse.class,
            description = "List public IPv6 addresses belonging to the VPC")
    private Long vpcId;

    @Parameter(name = ApiConstants.STATE,
            type = CommandType.STRING,
            description = "List by state (Free, Allocating, Allocated, Releasing)")
    private String state;

    @Parameter(name = ApiConstants.ALLOCATED_ONLY,
            type = CommandType.BOOLEAN,
            description = "Limits results to allocated public IPv6 addresses")
    private Boolean allocatedOnly;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getIp6Address() {
        return ip6Address;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public Long getVpcId() {
        return vpcId;
    }

    public String getState() {
        return state;
    }

    public Boolean isAllocatedOnly() {
        return allocatedOnly;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public void execute() {
        List<? extends UserPublicIpv6Address> addresses = searchAddresses();
        addresses = applyFilters(addresses);

        ListResponse<PublicIpv6AddressResponse> response = new ListResponse<>();
        List<PublicIpv6AddressResponse> responses = new ArrayList<>();
        for (UserPublicIpv6Address addr : addresses) {
            PublicIpv6AddressResponse addrResponse = _responseGenerator.createPublicIpv6AddressResponse(addr);
            addrResponse.setObjectName(ApiConstants.PUBLIC_IPV6_ADDRESS);
            responses.add(addrResponse);
        }
        response.setResponses(responses, responses.size());
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    private List<? extends UserPublicIpv6Address> searchAddresses() {
        Account caller = CallContext.current().getCallingAccount();

        if (id != null) {
            UserPublicIpv6Address addr = publicIpv6AddressManager.findById(id);
            List<UserPublicIpv6Address> single = new ArrayList<>();
            if (addr != null) {
                _accountService.checkAccess(caller, null, true, addr);
                single.add(addr);
            }
            return single;
        }

        if (ip6Address != null && zoneId != null) {
            UserPublicIpv6Address addr = publicIpv6AddressManager.findByZoneAndAddress(zoneId, ip6Address);
            List<UserPublicIpv6Address> single = new ArrayList<>();
            if (addr != null) {
                _accountService.checkAccess(caller, null, true, addr);
                single.add(addr);
            }
            return single;
        }

        if (networkId != null) {
            return publicIpv6AddressManager.listByNetwork(networkId);
        }
        if (vpcId != null) {
            return publicIpv6AddressManager.listByVpc(vpcId);
        }

        long ownerId = resolveListOwnerId(caller);
        if (zoneId != null) {
            if (isAdmin(caller)) {
                // Admin listing a zone without account → all in zone (incl. Free); otherwise account+zone
                if (getAccountName() == null && getDomainId() == null && getProjectId() == null) {
                    return publicIpv6AddressManager.listByZone(zoneId);
                }
            }
            return publicIpv6AddressManager.listByAccountAndZone(ownerId, zoneId);
        }

        if (isAdmin(caller) && getAccountName() == null && getDomainId() == null && getProjectId() == null) {
            // No scope: for admin with no filters, require zone to avoid full-table scan
            // Fall back to caller's own account list
            return publicIpv6AddressManager.listByAccount(caller.getId());
        }
        return publicIpv6AddressManager.listByAccount(ownerId);
    }

    private long resolveListOwnerId(Account caller) {
        if (getAccountName() != null && getDomainId() != null) {
            Account account = _accountService.finalizeOwner(caller, getAccountName(), getDomainId(), getProjectId());
            return account.getId();
        }
        if (getProjectId() != null) {
            Account owner = _accountService.finalizeOwner(caller, null, null, getProjectId());
            return owner.getId();
        }
        return caller.getId();
    }

    private List<? extends UserPublicIpv6Address> applyFilters(List<? extends UserPublicIpv6Address> addresses) {
        Account caller = CallContext.current().getCallingAccount();
        return addresses.stream()
                .filter(addr -> {
                    try {
                        if (addr.getAccountId() > 0) {
                            _accountService.checkAccess(caller, null, true, addr);
                        } else if (!isAdmin(caller)) {
                            // Free pool rows have no owner — only admins list them
                            return false;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                    if (Boolean.TRUE.equals(allocatedOnly)
                            && addr.getState() != UserPublicIpv6Address.State.Allocated) {
                        return false;
                    }
                    if (state != null && (addr.getState() == null
                            || !state.equalsIgnoreCase(addr.getState().name()))) {
                        return false;
                    }
                    if (!addr.isDisplay() && !isAdmin(caller)) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private boolean isAdmin(Account account) {
        return account.getType() == Account.Type.ADMIN || account.getType() == Account.Type.DOMAIN_ADMIN
                || account.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.PublicIpv6Address;
    }
}
