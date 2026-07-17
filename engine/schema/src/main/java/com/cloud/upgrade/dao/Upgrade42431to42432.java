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
 * Schema upgrade 4.24.1.31 -&gt; 4.24.1.32. Adds first-class
 * {@code load_balancing_rules.lb_kind} (default {@code CT_LB}),
 * {@code dsr_lb_desired_state} desired-state table, and the disabled-by-default
 * {@code network.lb.dsr.software.enabled} feature gate.
 */
public class Upgrade42431to42432 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.31", "4.24.1.32"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.32";
    }

    @Override
    public boolean supportsRollingUpgrade() {
        return false;
    }

    @Override
    public InputStream[] getPrepareScripts() {
        final String scriptFile = "META-INF/db/schema-42431to42432.sql";
        final InputStream script = Thread.currentThread().getContextClassLoader().getResourceAsStream(scriptFile);
        if (script == null) {
            throw new CloudRuntimeException("Unable to find " + scriptFile);
        }
        return new InputStream[]{script};
    }

    @Override
    public InputStream[] getCleanupScripts() {
        return new InputStream[0];
    }
}
