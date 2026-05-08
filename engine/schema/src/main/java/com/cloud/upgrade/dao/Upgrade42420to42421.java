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
 * Schema upgrade 4.24.1.20 -> 4.24.1.21. NO-OP on schema. Bumps version row
 * only so the cluster DatabaseUpgradeChecker accepts the new code version
 * without requiring single-mgmt mode.
 *
 * <p>The 4.24.1.21 release patches {@link
 * com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver} to pass
 * dv_flow_en=2,dv_xmeta_en=4 explicitly in dpdk-devargs when adding a VF
 * representor as type=doca, required to match the shared mlx5_bond_0 LAG
 * context. Agent-side Java only; no DB schema, view or procedure mutated.
 *
 * <p>Idempotent and safe on a re-run.
 */
public class Upgrade42420to42421 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.20", "4.24.1.21"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.21";
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
