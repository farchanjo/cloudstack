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

/**
 * Schema version-row bump for the 4.24.1.33 release line.
 *
 * <p>No database objects change in this release step.  Keeping the upgrade
 * explicit makes the version transition visible to the database hierarchy
 * and preserves the normal upgrade contract for existing installations.</p>
 */
public class Upgrade42432to42433 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.32", "4.24.1.33"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.33";
    }

    @Override
    public boolean supportsRollingUpgrade() {
        return false;
    }
}
