// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may not use this file except in compliance
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.VerifySourceBindingDownCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.script.Script;

public class LibvirtVerifySourceBindingDownCommandWrapperTest {

    @Test
    public void rejectsSourceChassisStillClaimingPortBinding() {
        final LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScript(contains("virsh domstate"))).thenReturn("shut off");
            script.when(() -> Script.runSimpleBashScript(contains("find Interface"))).thenReturn("");
            script.when(() -> Script.runSimpleBashScript(contains("Port_Binding"))).thenReturn("[uuid, source-chassis]");

            final Answer answer = new LibvirtVerifySourceBindingDownCommandWrapper().execute(
                    new VerifySourceBindingDownCommand("i-1-VM", new String[]{"lsp-1"}, "source-chassis",
                            "destination-chassis"), resource);

            assertFalse(answer.getResult());
        }
    }

    @Test
    public void acceptsStoppedSourceWithNoLocalOrSourceChassisClaim() {
        final LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScript(contains("virsh domstate"))).thenReturn("shut off");
            script.when(() -> Script.runSimpleBashScript(contains("find Interface"))).thenReturn("");
            script.when(() -> Script.runSimpleBashScript(contains("Port_Binding"))).thenReturn("[uuid, destination-chassis]");

            final Answer answer = new LibvirtVerifySourceBindingDownCommandWrapper().execute(
                    new VerifySourceBindingDownCommand("i-1-VM", new String[]{"lsp-1"}, "source-chassis",
                            "destination-chassis"), resource);

            assertTrue(answer.getResult());
        }
    }

    @Test
    public void rejectsWrongAndMultipleRemainingChassisClaims() {
        final LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScript(contains("virsh domstate"))).thenReturn("shut off");
            script.when(() -> Script.runSimpleBashScript(contains("find Interface"))).thenReturn("");
            script.when(() -> Script.runSimpleBashScript(contains("Port_Binding")))
                    .thenReturn("[uuid, wrong-chassis, destination-chassis]");

            final Answer answer = new LibvirtVerifySourceBindingDownCommandWrapper().execute(
                    new VerifySourceBindingDownCommand("i-1-VM", new String[]{"lsp-1"}, "source-chassis",
                            "destination-chassis"), resource);

            assertFalse(answer.getResult());
        }
    }
}
