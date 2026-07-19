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

import com.cloud.network.router.VfPoolStatus;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;

public class VfPoolStatusResponse extends BaseResponse {

    @SerializedName("hostid")
    @Param(description = "Host ID")
    private String hostId;
    @SerializedName("free")
    @Param(description = "Free VF rows")
    private int free;
    @SerializedName("vdpafree")
    @Param(description = "Free vDPA-capable VF rows")
    private int vdpaFree;
    @SerializedName("reserved")
    @Param(description = "Reserved VF rows")
    private int reserved;
    @SerializedName("allocated")
    @Param(description = "Allocated VF rows")
    private int allocated;
    @SerializedName("suspect")
    @Param(description = "Suspect VF rows")
    private int suspect;

    public static VfPoolStatusResponse from(final VfPoolStatus status) {
        final VfPoolStatusResponse response = new VfPoolStatusResponse();
        response.hostId = String.valueOf(status.hostId());
        response.free = status.free();
        response.vdpaFree = status.vdpaFree();
        response.reserved = status.reserved();
        response.allocated = status.allocated();
        response.suspect = status.suspect();
        return response;
    }
}
