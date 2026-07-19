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
package com.cloud.network.router;

import java.util.Objects;

/** Per-device and per-NIC VF ownership evidence. */
public final class VfDeviceStatus {

    private final long vfPoolId;
    private final String pciAddress;
    private final Long nicId;
    private final String state;
    private final String vdpaKind;

    public VfDeviceStatus(final long vfPoolId, final String pciAddress, final Long nicId,
            final String state, final String vdpaKind) {
        this.vfPoolId = vfPoolId;
        this.pciAddress = pciAddress;
        this.nicId = nicId;
        this.state = state;
        this.vdpaKind = vdpaKind;
    }

    public long vfPoolId() { return vfPoolId; }
    public String pciAddress() { return pciAddress; }
    public Long nicId() { return nicId; }
    public String state() { return state; }
    public String vdpaKind() { return vdpaKind; }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VfDeviceStatus)) {
            return false;
        }
        final VfDeviceStatus that = (VfDeviceStatus) other;
        return vfPoolId == that.vfPoolId && Objects.equals(pciAddress, that.pciAddress)
                && Objects.equals(nicId, that.nicId) && Objects.equals(state, that.state)
                && Objects.equals(vdpaKind, that.vdpaKind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vfPoolId, pciAddress, nicId, state, vdpaKind);
    }

    @Override
    public String toString() {
        return "VfDeviceStatus[vfPoolId=" + vfPoolId + ", pciAddress=" + pciAddress + ", nicId=" + nicId
                + ", state=" + state + ", vdpaKind=" + vdpaKind + "]";
    }
}
