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
package com.cloud.network.ovn;

import java.util.Map;

/** Read-only management-side port for resolving a CloudStack host's OVN chassis. */
public interface OvnChassisLookup {

    /** @return the registered chassis UUID, or {@code null} when unregistered. */
    String findChassisUuid(long hostId);

    /** Returns active SB Port_Binding claims for one LSP, or -1 when unavailable. */
    default int countActiveClaims(final long dataCenterId, final String lspName) {
        return -1;
    }

    default boolean hasExactActiveClaim(final long dataCenterId, final String lspName,
            final String chassisUuid) {
        return false;
    }

    /** Resolves the configured requested-chassis policy without writing OVN state. */
    default String resolveRequestedChassis(final Map<String, String> vmDetails) {
        return vmDetails == null ? null : vmDetails.get("ovn.requested_chassis");
    }
}
