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
package com.cloud.agent.api;

/**
 * Drift-sweep command sent by the OVN reconciler to every chassis owned by
 * the controller of a CloudStack zone. The receiving KVM agent enumerates
 * its OVN integration bridge ports, compares each port's
 * {@code other_config:hairpin} value against the resolved default, and
 * re-applies the bridge-wide {@code Open_vSwitch other_config:tc-policy}.
 *
 * <p>Designed to complement the per-plug enforcement performed by
 * {@code OvnNicTunableApplier}: per-plug covers freshly attached NICs;
 * this sweep covers ports that pre-date the current plugin version,
 * external drift (operator running raw {@code ovs-vsctl}), and any
 * post-restart inconsistencies on the OVS DB.
 *
 * <p>Wire-compat: agents predating the matching wrapper return
 * {@code Unsupported command}; the management caller logs the warning and
 * continues — the per-plug path stays the canonical correction.
 */
public class OvnOvsPolicySweepCommand extends Command {

    /** Bridge to scan; default is the OVN integration bridge {@code br-int}. */
    private String bridge;

    /** Resolved default hairpin value. {@code null} = skip hairpin sweep. */
    private Boolean hairpinDefault;

    /** Resolved default tc-policy value. {@code null}/blank = skip stamp. */
    private String tcPolicy;

    /** Regex applied to port names; ports not matching are skipped. */
    private String portRegex;

    /** When {@code true}, log/report drift but never mutate. */
    private boolean dryRun;

    /** No-arg constructor for serialization frameworks. */
    public OvnOvsPolicySweepCommand() {
        // No-op.
    }

    public OvnOvsPolicySweepCommand(final String bridge, final Boolean hairpinDefault,
                                    final String tcPolicy, final String portRegex,
                                    final boolean dryRun) {
        this.bridge = bridge;
        this.hairpinDefault = hairpinDefault;
        this.tcPolicy = tcPolicy;
        this.portRegex = portRegex;
        this.dryRun = dryRun;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public String getBridge() {
        return bridge;
    }

    public void setBridge(final String bridge) {
        this.bridge = bridge;
    }

    public Boolean getHairpinDefault() {
        return hairpinDefault;
    }

    public void setHairpinDefault(final Boolean hairpinDefault) {
        this.hairpinDefault = hairpinDefault;
    }

    public String getTcPolicy() {
        return tcPolicy;
    }

    public void setTcPolicy(final String tcPolicy) {
        this.tcPolicy = tcPolicy;
    }

    public String getPortRegex() {
        return portRegex;
    }

    public void setPortRegex(final String portRegex) {
        this.portRegex = portRegex;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(final boolean dryRun) {
        this.dryRun = dryRun;
    }
}
