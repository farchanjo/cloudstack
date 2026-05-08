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
 * Answer to {@link OvnOvsPolicySweepCommand}. Carries per-host drift counts
 * so the reconciler can report aggregate numbers back to the admin API.
 */
public class OvnOvsPolicySweepAnswer extends Answer {

    /** Total ports inspected on the bridge. */
    private int portsScanned;

    /** Ports whose {@code other_config:hairpin} differed from the default. */
    private int hairpinDrifted;

    /** Ports actually re-stamped with the default (matches drift count when
     *  not in dry-run). */
    private int hairpinFixed;

    /** True when the agent applied the bridge-wide tc-policy on this pass. */
    private boolean tcPolicyApplied;

    /** Reflected back so the mgmt log can show the value the agent stamped. */
    private String tcPolicyValue;

    /** No-arg constructor for serialization frameworks. */
    public OvnOvsPolicySweepAnswer() {
        // No-op.
    }

    public OvnOvsPolicySweepAnswer(final Command command, final boolean success, final String details) {
        super(command, success, details);
    }

    public int getPortsScanned() {
        return portsScanned;
    }

    public void setPortsScanned(final int portsScanned) {
        this.portsScanned = portsScanned;
    }

    public int getHairpinDrifted() {
        return hairpinDrifted;
    }

    public void setHairpinDrifted(final int hairpinDrifted) {
        this.hairpinDrifted = hairpinDrifted;
    }

    public int getHairpinFixed() {
        return hairpinFixed;
    }

    public void setHairpinFixed(final int hairpinFixed) {
        this.hairpinFixed = hairpinFixed;
    }

    public boolean isTcPolicyApplied() {
        return tcPolicyApplied;
    }

    public void setTcPolicyApplied(final boolean tcPolicyApplied) {
        this.tcPolicyApplied = tcPolicyApplied;
    }

    public String getTcPolicyValue() {
        return tcPolicyValue;
    }

    public void setTcPolicyValue(final String tcPolicyValue) {
        this.tcPolicyValue = tcPolicyValue;
    }
}
