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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.agent.api.VerifyDestinationDataplaneAnswer;
import com.cloud.agent.api.VerifyDestinationDataplaneCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.script.Script;

public class DestinationDataplaneVerifierTest {

    @Test
    public void unclaimedPortBindingAnswerIsFailure() {
        final VerifyDestinationDataplaneCommand command =
                new VerifyDestinationDataplaneCommand("i-1-VM", null, "chassis-1");
        final VerifyDestinationDataplaneAnswer answer =
                new VerifyDestinationDataplaneAnswer(command, false, "unclaimed Port_Binding");

        assertFalse(answer.getResult());
    }

    @Test
    public void executionAcceptsExactBrIntChassisAndClaimProof() {
        final NicTO nic = new NicTO();
        nic.setUseVdpa(true);
        nic.setUuid("nic-1");
        nic.setMac("02:00:00:00:00:01");
        nic.setVfRepName("rep1");
        final LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScript(anyString())).thenAnswer(invocation -> {
                final String command = invocation.getArgument(0);
                if (command.contains("find Interface external_ids:iface-id")) return "rep1";
                if (command.contains("attached-mac")) return "rep1";
                if (command.contains("get Interface")) return "{iface-status=active, ovn-installed=true}";
                if (command.contains("port-to-br")) return "br-int";
                if (command.contains("ip link show")) return "3: rep1: <UP>";
                if (command.contains("Port_Binding")) return "[uuid, chassis-1]";
                return "";
            });
            final Answer answer = new LibvirtVerifyDestinationDataplaneCommandWrapper().execute(
                    new VerifyDestinationDataplaneCommand("i-1-VM", new NicTO[]{nic}, "chassis-1"), resource);
            assertTrue(answer.getResult());
        }
    }
}
