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

/**
 * Schema upgrade 4.24.1.19 -> 4.24.1.20. NO-OP on schema. Bumps version row
 * only so the cluster DatabaseUpgradeChecker accepts the new code version
 * without requiring single-mgmt mode.
 *
 * <p>The 4.24.1.20 release introduces the OVS-DPDK / DOCA representor
 * attachment path in {@link
 * com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver}. All changes
 * are agent-side Java; no DB schema, no view, no procedure mutated.
 *
 * <p>Idempotent and safe on a re-run.
 */
public class Upgrade42419to42420 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.19", "4.24.1.20"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.20";
    }

    @Override
    public InputStream[] getPrepareScripts() {
        return new InputStream[0];
    }

    @Override
    public InputStream[] getCleanupScripts() {
        return new InputStream[0];
    }
}
