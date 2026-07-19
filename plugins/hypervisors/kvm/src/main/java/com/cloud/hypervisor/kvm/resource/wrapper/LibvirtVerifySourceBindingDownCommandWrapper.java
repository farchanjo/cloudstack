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
import com.cloud.agent.api.VerifySourceBindingDownAnswer;
import com.cloud.agent.api.VerifySourceBindingDownCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = VerifySourceBindingDownCommand.class)
public final class LibvirtVerifySourceBindingDownCommandWrapper
        extends CommandWrapper<VerifySourceBindingDownCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final VerifySourceBindingDownCommand command,
            final LibvirtComputingResource resource) {
        final String state = Script.runSimpleBashScript("virsh domstate " + command.getVmName()
                + " 2>/dev/null");
        if (StringUtils.containsIgnoreCase(state, "running")
                || StringUtils.containsIgnoreCase(state, "paused")) {
            return failure(command, "source domain is still active: " + state.trim());
        }
        for (final String lsp : command.getLspNames()) {
            if (StringUtils.isBlank(lsp)) {
                continue;
            }
            final String interfaces = Script.runSimpleBashScript(String.format(
                    "ovs-vsctl --no-headings --columns=name find Interface external_ids:iface-id=%s 2>/dev/null",
                    lsp));
            if (StringUtils.isNotBlank(interfaces)) {
                return failure(command, "source still carries iface-id " + lsp);
            }
        }
        return new VerifySourceBindingDownAnswer(command, true, "source vDPA bindings are down");
    }

    private Answer failure(final VerifySourceBindingDownCommand command, final String details) {
        logger.error("Source binding-down verification failed: {}", details);
        return new VerifySourceBindingDownAnswer(command, false, details);
    }
}
