/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.hypervisor.kvm.resource;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.script.Script;

public class OvnVdpaVifDriverOrphanRepTest {

    @Test
    public void unplug_foreignNetType_isNoOp() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defHostdevNet("0000:01:00.6", "fa:16:3e:00:02:99", 0);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            driver.unplug(iface, true);
            scriptMock.verify(() -> Script.runSimpleBashScript(org.mockito.ArgumentMatchers.anyString()), never());
        }
    }

    @Test
    public void unplug_failedPciLookup_attachedMacHit_removesOrphanRep() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defVdpaNet("/dev/vhost-vdpa-3", "fa:16:3e:00:02:10", 4);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("dx6p1vf6");
            scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class))).thenReturn(0);

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.executeCommandForExitValue(
                    "ovs-vsctl", "--if-exists", "clear", "Interface", "dx6p1vf6", "external_ids"), times(1));
            scriptMock.verify(() -> Script.executeCommandForExitValue(
                    "ovs-vsctl", "--if-exists", "del-port", "dx6p1vf6"), times(1));
        }
    }

    @Test
    public void unplug_failedPciLookup_noAttachedMacHit_isNoOp() {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final LibvirtVMDef.InterfaceDef iface = new LibvirtVMDef.InterfaceDef();
        iface.defVdpaNet("/dev/vhost-vdpa-4", "fa:16:3e:00:02:11", 4);

        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("");

            driver.unplug(iface, true);

            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("clear Interface")), never());
        }
    }

    @Test
    public void freeRepresentorOnOvs_passesArgumentVector() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class))).thenReturn(0);

            OvnVifDriver.freeRepresentorOnOvs(
                    org.apache.logging.log4j.LogManager.getLogger(OvnVdpaVifDriverOrphanRepTest.class),
                    "test", "dx6p0vf9");

            scriptMock.verify(() -> Script.executeCommandForExitValue(
                    "ovs-vsctl", "--if-exists", "clear", "Interface", "dx6p0vf9", "external_ids"), times(1));
            scriptMock.verify(() -> Script.executeCommandForExitValue(
                    "ovs-vsctl", "--if-exists", "del-port", "dx6p0vf9"), times(1));
        }
    }

    @Test
    public void activeDuplicateIsNotDeleted() throws Exception {
        final OvnVdpaVifDriver driver = new OvnVdpaVifDriver();
        final var method = OvnVdpaVifDriver.class.getDeclaredMethod(
                "clearOrphanRepsForLspName", String.class, String.class);
        method.setAccessible(true);
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(contains("find Interface")))
                    .thenReturn("source-rep");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("get Interface")))
                    .thenReturn("{iface-status=active}");
            try {
                method.invoke(driver, "lsp-1", "destination-rep");
                org.junit.Assert.fail("active duplicate must fail closed");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                // expected fail-closed CloudRuntimeException
            }
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
        }
    }
}
