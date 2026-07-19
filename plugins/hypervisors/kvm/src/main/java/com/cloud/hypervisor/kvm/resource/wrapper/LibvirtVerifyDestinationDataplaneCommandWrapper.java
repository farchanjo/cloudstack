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
package com.cloud.hypervisor.kvm.resource.wrapper;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.VerifyDestinationDataplaneAnswer;
import com.cloud.agent.api.VerifyDestinationDataplaneCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = VerifyDestinationDataplaneCommand.class)
public final class LibvirtVerifyDestinationDataplaneCommandWrapper
        extends CommandWrapper<VerifyDestinationDataplaneCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final VerifyDestinationDataplaneCommand command,
            final LibvirtComputingResource resource) {
        for (final NicTO nic : command.getNics()) {
            if (!nic.isUseVdpa()) {
                continue;
            }
            final String lsp = StringUtils.isBlank(nic.getUuid()) ? null : "lsp-" + nic.getUuid();
            if (StringUtils.isBlank(lsp)) {
                return failure(command, "vDPA NIC has no logical switch port identity");
            }
            final String interfaces = Script.runSimpleBashScript(String.format(
                    "ovs-vsctl --no-headings --columns=name find Interface external_ids:iface-id=%s 2>/dev/null",
                    lsp));
            final String[] claims = nonBlankLines(interfaces);
            if (claims.length != 1) {
                return failure(command, String.format("expected exactly one destination iface-id claim for %s, found %d",
                        lsp, claims.length));
            }
            if (StringUtils.isNotBlank(nic.getMac())) {
                final String macClaims = Script.runSimpleBashScript(String.format(
                        "ovs-vsctl --no-headings --columns=name find Interface external_ids:attached-mac=%s 2>/dev/null",
                        nic.getMac()));
                if (nonBlankLines(macClaims).length != 1) {
                    return failure(command, "expected exactly one destination MAC claim for " + nic.getMac());
                }
            }
            final String externalIds = Script.runSimpleBashScript(String.format(
                    "ovs-vsctl get Interface %s external_ids 2>/dev/null", claims[0]));
            if (!hasExternalId(externalIds, "iface-status", "active")
                    || !hasExternalId(externalIds, "ovn-installed", "true")) {
                return failure(command, "destination representor is not active and OVN-installed: " + claims[0]);
            }
            if (StringUtils.isNotBlank(nic.getVfRepName()) && Script.runSimpleBashScript(
                    "ip link show dev " + nic.getVfRepName() + " 2>/dev/null").isBlank()) {
                return failure(command, "destination representor is absent: " + nic.getVfRepName());
            }
            final String chassis = Script.runSimpleBashScript(String.format(
                    "ovn-sbctl --bare --no-heading --columns=chassis find Port_Binding logical_port=%s 2>/dev/null",
                    lsp));
            if (nonBlankLines(chassis).length != 1 || chassis.contains("[]")) {
                return failure(command, "expected exactly one destination Port_Binding chassis claim for " + lsp);
            }
        }
        return new VerifyDestinationDataplaneAnswer(command, true, "destination vDPA dataplane verified");
    }

    private Answer failure(final VerifyDestinationDataplaneCommand command, final String details) {
        logger.error("Destination dataplane verification failed: {}", details);
        return new VerifyDestinationDataplaneAnswer(command, false, details);
    }

    private String[] nonBlankLines(final String value) {
        if (StringUtils.isBlank(value)) {
            return new String[0];
        }
        return java.util.Arrays.stream(value.split("\\R"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .filter(line -> !"[]".equals(line))
                .toArray(String[]::new);
    }

    private boolean hasExternalId(final String externalIds, final String key, final String value) {
        return externalIds.contains(key + "=" + value)
                || externalIds.contains(key + "=\"" + value + "\"");
    }
}
