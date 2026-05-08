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
package com.cloud.network.ovn.api.response;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

/**
 * Public JSON shape for the CloudStack id &harr; OVN UUID mapping rows. Used
 * by the import flow to surface each adopted entity to the operator.
 */
public class OvnLogicalIdResponse extends BaseResponse {

    @SerializedName("kind")
    @Param(description = "VPC | NETWORK | NIC | STATIC_NAT | SOURCE_NAT")
    private String kind;

    @SerializedName("csid")
    @Param(description = "CloudStack id (NIC id, network id, VPC id, etc)")
    private Long csId;

    @SerializedName("ovnuuid")
    @Param(description = "OVN UUID for the entity")
    private String ovnUuid;

    @SerializedName("ovnname")
    @Param(description = "OVN name (for human-readable cross-checks)")
    private String ovnName;

    public String getKind() {
        return kind;
    }

    public void setKind(final String kind) {
        this.kind = kind;
    }

    public Long getCsId() {
        return csId;
    }

    public void setCsId(final Long csId) {
        this.csId = csId;
    }

    public String getOvnUuid() {
        return ovnUuid;
    }

    public void setOvnUuid(final String ovnUuid) {
        this.ovnUuid = ovnUuid;
    }

    public String getOvnName() {
        return ovnName;
    }

    public void setOvnName(final String ovnName) {
        this.ovnName = ovnName;
    }
}
