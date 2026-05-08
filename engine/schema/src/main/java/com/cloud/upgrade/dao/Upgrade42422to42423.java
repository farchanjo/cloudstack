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
package com.cloud.upgrade.dao;

import java.io.InputStream;

import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Schema upgrade 4.24.1.22 -&gt; 4.24.1.23. Re-introduces vDPA orchestration
 * as a CloudStack offering after fork commit
 * {@code 922b945450 chore(vdpa): remove vDPA feature} retired the original
 * implementation in {@link Upgrade42410to42411}.
 *
 * <p>The 4.24.1.23 release adds:
 * <ul>
 *   <li>{@code network_offerings.vdpa_enabled} — per-offering opt-in flag.
 *   <li>{@code nics.vdpa_device} — host-side {@code /dev/vhost-vdpa-N} path
 *       populated by the agent at plug.
 *   <li>{@code sriov_vf_pool.vdpa_kind|vdpa_name|vdpa_device} — pool-side
 *       bookkeeping for the SF mgmt device bound on top of the VF.
 *   <li>Configuration toggles: {@code vr.vdpa.enabled}, {@code vm.vdpa.enabled},
 *       {@code vm.vdpa.max_vqs}.
 * </ul>
 *
 * <p>All ALTER and INSERT statements are idempotent (IF NOT EXISTS, ON
 * DUPLICATE KEY UPDATE), so the script is safe on any DB whose state already
 * matches the target schema (e.g. a partial rollout, a re-run after the
 * version row already advanced).
 */
public class Upgrade42422to42423 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.22", "4.24.1.23"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.23";
    }

    @Override
    public InputStream[] getPrepareScripts() {
        final String scriptFile = "META-INF/db/schema-42422to42423.sql";
        final InputStream script = Thread.currentThread().getContextClassLoader().getResourceAsStream(scriptFile);
        if (script == null) {
            throw new CloudRuntimeException("Unable to find " + scriptFile);
        }
        return new InputStream[] {script};
    }

    @Override
    public InputStream[] getCleanupScripts() {
        return new InputStream[0];
    }
}
