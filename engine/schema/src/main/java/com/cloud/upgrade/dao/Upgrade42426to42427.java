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
 * Schema upgrade 4.24.1.26 -&gt; 4.24.1.27. Fixes the vdpa_enabled API response
 * gap: recreates {@code network_offering_view} to project the
 * {@code vdpa_enabled} column that was re-added to {@code network_offerings}
 * by schema-42422to42423.sql but was never included in the view, so
 * {@code listNetworkOfferings} responses always returned {@code vdpaenabled=false}.
 */
public class Upgrade42426to42427 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.26", "4.24.1.27"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.27";
    }

    @Override
    public boolean supportsRollingUpgrade() {
        return false;
    }

    @Override
    public InputStream[] getPrepareScripts() {
        final String scriptFile = "META-INF/db/schema-42426to42427.sql";
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
