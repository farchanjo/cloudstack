//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.OvnOvsPolicySweepAnswer;
import com.cloud.agent.api.OvnOvsPolicySweepCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.OvnNicTunableApplier;
import com.cloud.hypervisor.kvm.resource.OvnNicTunableApplier.SweepResult;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

/**
 * Drift-sweep handler for the OVN OVS policy reconciler. Walks every port
 * on the requested bridge, compares the current
 * {@code other_config:hairpin} value against the resolved default carried
 * by the command, and re-applies via the existing
 * {@link OvnNicTunableApplier#applyHairpin}. Also re-asserts the bridge-wide
 * {@code Open_vSwitch other_config:tc-policy} unconditionally (force mode)
 * so a manual {@code ovs-vsctl} session that wiped the value gets corrected
 * on the next reconcile pass.
 *
 * <p>Per-plug enforcement (running on every NIC plug via
 * {@code OvnNicTunableApplier} from the VIF drivers) is the canonical
 * correction; this wrapper is the one-shot drift collapser called by
 * {@code OvnReconcilerService.reassertOvsPolicy}.
 *
 * <p>Wire-compat: agents predating this wrapper return
 * {@code Unsupported command}; the management caller logs the warning and
 * carries on, leaving the per-plug path as the only correction surface.
 */
@ResourceWrapper(handles = OvnOvsPolicySweepCommand.class)
public final class LibvirtOvnOvsPolicySweepCommandWrapper extends
        CommandWrapper<OvnOvsPolicySweepCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOGGER = LogManager.getLogger(LibvirtOvnOvsPolicySweepCommandWrapper.class);

    @Override
    public Answer execute(final OvnOvsPolicySweepCommand cmd, final LibvirtComputingResource resource) {
        // Derive the real integration bridge from OVSDB (external_ids:ovn-bridge)
        // rather than trusting the command's hardcoded hint — this fleet runs
        // br-overlay, not br-int, so the sweep must target the actual bridge.
        final String bridge = OvnNicTunableApplier.resolveIntegrationBridge(cmd.getBridge());
        final Boolean hairpinDefault = cmd.getHairpinDefault();
        final String tcPolicy = cmd.getTcPolicy();
        final String regex = cmd.getPortRegex();
        final boolean dryRun = cmd.isDryRun();

        final OvnOvsPolicySweepAnswer answer = new OvnOvsPolicySweepAnswer(cmd, true, "ok");

        // tc-policy: force re-stamp (skips per-JVM latch). When in dry-run we
        // do not stamp; report the requested value so the caller still sees
        // what the agent would have written.
        if (StringUtils.isNotBlank(tcPolicy)) {
            answer.setTcPolicyValue(tcPolicy);
            if (!dryRun) {
                final boolean ok = OvnNicTunableApplier.applyTcPolicyForce(tcPolicy);
                answer.setTcPolicyApplied(ok);
            }
        }

        // hairpin: walk bridge, compare, re-apply.
        try {
            final SweepResult result = OvnNicTunableApplier.sweepHairpin(bridge, hairpinDefault, regex, dryRun);
            answer.setPortsScanned(result.portsScanned);
            answer.setHairpinDrifted(result.hairpinDrifted);
            answer.setHairpinFixed(result.hairpinFixed);
        } catch (RuntimeException re) {
            LOGGER.warn("LibvirtOvnOvsPolicySweep: sweep on bridge={} failed: {}", bridge, re.getMessage());
            return new OvnOvsPolicySweepAnswer(cmd, false, "sweep-failed: " + re.getMessage());
        }

        LOGGER.info("LibvirtOvnOvsPolicySweep: bridge={} scanned={} drifted={} fixed={} tcPolicy={} tcApplied={} dryRun={}",
                bridge, answer.getPortsScanned(), answer.getHairpinDrifted(), answer.getHairpinFixed(),
                answer.getTcPolicyValue(), answer.isTcPolicyApplied(), dryRun);
        return answer;
    }
}
