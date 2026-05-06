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
 * Schema upgrade 4.24.1.23 -&gt; 4.24.1.24. Introduces the OVN network-element
 * plugin (Phase I).
 *
 * <p>The 4.24.1.24 release adds:
 * <ul>
 *   <li>{@code ovn_controller} — one row per OVN deployment per zone, holding
 *       the comma-separated NB and SB endpoint lists. The plugin client
 *       picks the live endpoint at runtime (RAFT leader detection).
 *   <li>{@code ovn_chassis_map} — CloudStack {@code host} &harr; OVN
 *       {@code chassis} UUID, populated at host registration from the agent's
 *       {@code Open_vSwitch:external_ids:system-id}.
 *   <li>{@code ovn_logical_id_map} — CloudStack id &harr; OVN UUID, so deletes
 *       resolve without re-walking the NB DB.
 *   <li>{@code physical_network_service_providers} — one {@code Ovn} provider
 *       row per physical network (state {@code Disabled}; opt-in by the
 *       admin).
 * </ul>
 *
 * <p>The script is idempotent ({@code CREATE TABLE IF NOT EXISTS},
 * {@code INSERT ... WHERE NOT EXISTS}), so a re-run on a partially-applied DB
 * does not error.
 */
public class Upgrade42423to42424 extends DbUpgradeAbstractImpl implements DbUpgrade {

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.24.1.23", "4.24.1.24"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.24.1.24";
    }

    @Override
    public InputStream[] getPrepareScripts() {
        final String scriptFile = "META-INF/db/schema-42423to42424.sql";
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
