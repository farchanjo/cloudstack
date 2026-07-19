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

/**
 * Public façade over the legacy VF host admin commands.
 *
 * <p>The {@code server} module's {@code VfPoolManager} carries the full DAO +
 * lifecycle surface that internal callers need; admin API commands only need
 * a thin slice (force-release on a host). Splitting that slice out as
 * {@code VfPoolService} keeps the API module from depending on {@code server}
 * just to wire one Cmd, while letting the impl ({@code VfPoolManagerImpl})
 * implement both interfaces.
 */
public interface VfPoolService {

    /**
     * Default-off legacy entry. When explicitly enabled it only quarantines
     * rows as {@code SUSPECT}; it never performs broad DB-only FREE updates.
     */
    int forceReleaseByHostId(long hostId);

    /**
     * Deactivated broad recovery entry. Returns zero; ownership repair must
     * use the leader/GlobalLock/exact-plan approval path.
     */
    int recoverByHostId(long hostId);

    /** Read-only status; this method never changes pool ownership. */
    VfPoolStatus getHostVfPoolStatus(long hostId);
}
