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

import java.util.List;
import java.util.Objects;

/** Read-only VF pool status exposed to administrative API callers. */
public final class VfPoolStatus {

    private final long hostId;
    private final int free;
    private final int vdpaFree;
    private final int reserved;
    private final int allocated;
    private final int suspect;
    private final List<VfDeviceStatus> devices;

    public VfPoolStatus(final long hostId, final int free, final int vdpaFree, final int reserved,
            final int allocated, final int suspect, final List<VfDeviceStatus> devices) {
        this.hostId = hostId;
        this.free = free;
        this.vdpaFree = vdpaFree;
        this.reserved = reserved;
        this.allocated = allocated;
        this.suspect = suspect;
        this.devices = devices;
    }

    public VfPoolStatus(final long hostId, final int free, final int vdpaFree,
            final int reserved, final int allocated, final int suspect) {
        this(hostId, free, vdpaFree, reserved, allocated, suspect, List.of());
    }

    public long hostId() { return hostId; }
    public int free() { return free; }
    public int vdpaFree() { return vdpaFree; }
    public int reserved() { return reserved; }
    public int allocated() { return allocated; }
    public int suspect() { return suspect; }
    public List<VfDeviceStatus> devices() { return devices; }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VfPoolStatus)) {
            return false;
        }
        final VfPoolStatus that = (VfPoolStatus) other;
        return hostId == that.hostId && free == that.free && vdpaFree == that.vdpaFree
                && reserved == that.reserved && allocated == that.allocated && suspect == that.suspect
                && Objects.equals(devices, that.devices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostId, free, vdpaFree, reserved, allocated, suspect, devices);
    }

    @Override
    public String toString() {
        return "VfPoolStatus[hostId=" + hostId + ", free=" + free + ", vdpaFree=" + vdpaFree
                + ", reserved=" + reserved + ", allocated=" + allocated + ", suspect=" + suspect
                + ", devices=" + devices + "]";
    }
}
