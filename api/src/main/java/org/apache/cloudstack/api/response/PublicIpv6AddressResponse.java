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
package org.apache.cloudstack.api.response;

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.network.UserPublicIpv6Address;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

/**
 * API response for a public IPv6 VIP/FIP inventory row
 * ({@code user_public_ipv6_address}). Distinct from guest SLAAC
 * {@code user_ipv6_address} and from IPv4 {@link IPAddressResponse}.
 */
@EntityReference(value = UserPublicIpv6Address.class)
@SuppressWarnings("unused")
public class PublicIpv6AddressResponse extends BaseResponse implements ControlledEntityResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "Public IPv6 address ID")
    private String id;

    @SerializedName(ApiConstants.IP6_ADDRESS)
    @Param(description = "Public IPv6 address")
    private String ip6Address;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "The ID of the zone the public IPv6 address belongs to")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "The name of the zone the public IPv6 address belongs to")
    private String zoneName;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "The account the public IPv6 address is associated with")
    private String accountName;

    @SerializedName(ApiConstants.PROJECT_ID)
    @Param(description = "The project id of the address")
    private String projectId;

    @SerializedName(ApiConstants.PROJECT)
    @Param(description = "The project name of the address")
    private String projectName;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "The domain ID the public IPv6 address is associated with")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "The domain the public IPv6 address is associated with")
    private String domainName;

    @SerializedName(ApiConstants.DOMAIN_PATH)
    @Param(description = "Path of the domain to which the public IPv6 address belongs")
    private String domainPath;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "State of the public IPv6 address. Can be: Free, Allocating, Allocated, Releasing")
    private String state;

    @SerializedName(ApiConstants.VPC_ID)
    @Param(description = "VPC ID the address is associated with")
    private String vpcId;

    @SerializedName(ApiConstants.NETWORK_ID)
    @Param(description = "Network ID the address is associated with")
    private String networkId;

    @SerializedName("allocated")
    @Param(description = "Date the public IPv6 address was acquired")
    private Date allocated;

    @SerializedName(ApiConstants.IS_SYSTEM)
    @Param(description = "True if this is a system-owned address (e.g. grandfather / infra)")
    private Boolean isSystem;

    @Override
    public String getObjectId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setIp6Address(String ip6Address) {
        this.ip6Address = ip6Address;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    @Override
    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    @Override
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @Override
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @Override
    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    @Override
    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    @Override
    public void setDomainPath(String domainPath) {
        this.domainPath = domainPath;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public void setAllocated(Date allocated) {
        this.allocated = allocated;
    }

    public void setIsSystem(Boolean isSystem) {
        this.isSystem = isSystem;
    }
}
