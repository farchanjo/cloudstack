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
 * Schema upgrade 4.24.1.24 -&gt; 4.24.1.25. Phase H.1 — VF + vDPA lifecycle
 * hardening: introduces the {@code last_seen} column on {@code sriov_vf_pool}
 * and the configuration knobs that drive the new mgmt-side reconciler
 * ({@code vf.pool.suspect.timeout.seconds}, {@code vf.pool.reconcile.interval.seconds})
 * and the agent-side vDPA sweep
 * ({@code vdpa.sf.reconcile.interval.seconds}).
 *
 * <p>The Java enum {@link com.cloud.network.router.SriovVfPoolVO.State} adds
 * {@code SUSPECT} and {@code ORPHAN_MANUAL} in the same release. The
 * {@code state} column is {@code VARCHAR(32)} so MySQL accepts the new strings
 * without further DDL.
 *
 * <p>The script is idempotent ({@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS},
 * {@code INFORMATION_SCHEMA}-guarded {@code CREATE INDEX}, {@code INSERT ...
 * ON DUPLICATE KEY UPDATE}) so a re-run on a partially-applied DB does not
 * error.
 */
public class Upgrade42424to42425 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.24", "4.24.1.25"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.25";
    }

    @Override
    public InputStream[] getPrepareScripts() {
        final String scriptFile = "META-INF/db/schema-42424to42425.sql";
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
