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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

/**
 * Public JSON shape for an OVN controller registration.
 */
public class OvnControllerResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "OVN controller registration UUID")
    private String uuid;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "operator-assigned name")
    private String name;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "Zone the OVN deployment serves")
    private String zoneId;

    @SerializedName("nbendpoints")
    @Param(description = "comma-separated OVSDB-NB endpoint list (tcp:host:port)")
    private String nbEndpoints;

    @SerializedName("sbendpoints")
    @Param(description = "comma-separated OVSDB-SB endpoint list (read-only diagnostics)")
    private String sbEndpoints;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(final String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(final String zoneId) {
        this.zoneId = zoneId;
    }

    public String getNbEndpoints() {
        return nbEndpoints;
    }

    public void setNbEndpoints(final String nbEndpoints) {
        this.nbEndpoints = nbEndpoints;
    }

    public String getSbEndpoints() {
        return sbEndpoints;
    }

    public void setSbEndpoints(final String sbEndpoints) {
        this.sbEndpoints = sbEndpoints;
    }
}
