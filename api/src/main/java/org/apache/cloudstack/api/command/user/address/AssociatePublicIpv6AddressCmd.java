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

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.PublicIpv6AddressResponse;
import org.apache.cloudstack.api.response.VpcResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.UserPublicIpv6Address;
import com.cloud.network.vpc.Vpc;
import com.cloud.projects.Project;
import com.cloud.user.Account;

/**
 * Acquires a public IPv6 address from the Free pool ({@code user_public_ipv6_address})
 * and optionally associates it with a network or VPC.
 */
@APICommand(name = AssociatePublicIpv6AddressCmd.APINAME,
        description = "Acquires and associates a public IPv6 address to an account. "
                + "Either zoneId, networkId, or vpcId is required.",
        responseObject = PublicIpv6AddressResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        entityType = {UserPublicIpv6Address.class})
public class AssociatePublicIpv6AddressCmd extends BaseAsyncCreateCmd implements UserCmd {
    public static final String APINAME = "associatePublicIpv6Address";
    private static final String s_name = "associatepublicipv6addressresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ACCOUNT,
            type = CommandType.STRING,
            description = "The account to associate with this public IPv6 address")
    private String accountName;

    @Parameter(name = ApiConstants.DOMAIN_ID,
            type = CommandType.UUID,
            entityType = DomainResponse.class,
            description = "The ID of the domain to associate with this public IPv6 address")
    private Long domainId;

    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "The ID of the zone to acquire a public IPv6 address from")
    private Long zoneId;

    @Parameter(name = ApiConstants.NETWORK_ID,
            type = CommandType.UUID,
            entityType = NetworkResponse.class,
            description = "The network this public IPv6 address should be associated to")
    private Long networkId;

    @Parameter(name = ApiConstants.PROJECT_ID,
            type = CommandType.UUID,
            entityType = ProjectResponse.class,
            description = "Project for the address")
    private Long projectId;

    @Parameter(name = ApiConstants.VPC_ID,
            type = CommandType.UUID,
            entityType = VpcResponse.class,
            description = "The VPC to associate the public IPv6 address with")
    private Long vpcId;

    @Parameter(name = ApiConstants.IP6_ADDRESS,
            type = CommandType.STRING,
            description = "Optional specific Free-pool public IPv6 address to allocate")
    private String ip6Address;

    @Parameter(name = ApiConstants.FOR_DISPLAY,
            type = CommandType.BOOLEAN,
            description = "Whether to display the address to the end user",
            authorized = {RoleType.Admin})
    private Boolean display;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getAccountName() {
        if (accountName != null) {
            return accountName;
        }
        return CallContext.current().getCallingAccount().getAccountName();
    }

    public long getDomainId() {
        if (domainId != null) {
            return domainId;
        }
        return CallContext.current().getCallingAccount().getDomainId();
    }

    private long getZoneId() {
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
                "Unable to figure out zone to assign public IPv6 to. Please specify either zoneId, networkId, or vpcId");
    }

    public Long getVpcId() {
        return vpcId;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public String getIp6Address() {
        return ip6Address;
    }

    @Override
    public boolean isDisplay() {
        return display == null || display;
    }

    @Override
    public long getEntityOwnerId() {
        Account caller = CallContext.current().getCallingAccount();
        if (accountName != null && domainId != null) {
            Account account = _accountService.finalizeOwner(caller, accountName, domainId, projectId);
            return account.getId();
        } else if (projectId != null) {
            Project project = _projectService.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by ID");
            }
            if (project.getState() != Project.State.Active) {
                throw new PermissionDeniedException(
                        "Can't add resources to the project with specified projectId in state=" + project.getState()
                                + " as it's no longer active");
            }
            return project.getProjectAccountId();
        } else if (networkId != null) {
            Network network = _networkService.getNetwork(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find Network by network id specified");
            }
            return network.getAccountId();
        } else if (vpcId != null) {
            Vpc vpc = _entityMgr.findById(Vpc.class, vpcId);
            if (vpc == null) {
                throw new InvalidParameterValueException("Can't find VPC by ID specified");
            }
            return vpc.getAccountId();
        }
        return caller.getAccountId();
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_PUBLIC_IPV6_ASSIGN;
    }

    @Override
    public String getEventDescription() {
        return "associating public IPv6 in zone " + getZoneId();
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public void create() throws ResourceAllocationException {
        try {
            Account owner = _accountService.getAccount(getEntityOwnerId());
            UserPublicIpv6Address addr;
            if (ip6Address != null) {
                addr = publicIpv6AddressManager.allocate(getZoneId(), owner, ip6Address, networkId, vpcId, false,
                        display);
            } else {
                addr = publicIpv6AddressManager.allocate(getZoneId(), owner, networkId, vpcId, false, display);
            }
            if (addr != null) {
                setEntityId(addr.getId());
                setEntityUuid(addr.getUuid());
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to allocate public IPv6 address");
            }
        } catch (ConcurrentOperationException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        } catch (InsufficientAddressCapacityException ex) {
            logger.info(ex);
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ex.getMessage());
        } catch (InvalidParameterValueException ex) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, ex.getMessage());
        }
    }

    @Override
    public void execute() throws ResourceUnavailableException, ResourceAllocationException,
            ConcurrentOperationException {
        CallContext.current().setEventDetails("Public IPv6 address ID: " + getEntityUuid());

        UserPublicIpv6Address result = publicIpv6AddressManager.findById(getEntityId());
        if (result == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to assign public IPv6 address");
        }

        // Re-associate if network/vpc were supplied after allocate-without-bind path
        if ((networkId != null || vpcId != null)
                && (result.getNetworkId() == null && result.getVpcId() == null
                        || (networkId != null && !networkId.equals(result.getNetworkId()))
                        || (vpcId != null && !vpcId.equals(result.getVpcId())))) {
            result = publicIpv6AddressManager.associate(getEntityId(), networkId, vpcId);
        }

        PublicIpv6AddressResponse response = _responseGenerator.createPublicIpv6AddressResponse(result);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.PublicIpv6Address;
    }
}
