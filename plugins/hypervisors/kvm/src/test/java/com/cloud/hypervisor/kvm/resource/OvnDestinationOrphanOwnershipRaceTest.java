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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.script.Script;

public class OvnDestinationOrphanOwnershipRaceTest {

    private static final String BDF = "0000:01:00.4";
    private static final String REP = "dx6p0vf4";
    private static final String LSP = "lsp-race";

    @Test
    public void migrationOwnerChangeAfterCandidateSelectionBlocksCas() {
        assertOwnershipChangeBlocksCas("{migration-owner=destination, iface-status=inactive}",
                "{migration-owner=source, iface-status=inactive}");
    }

    @Test
    public void ifaceStatusChangeAfterCandidateSelectionBlocksCas() {
        assertOwnershipChangeBlocksCas("{migration-owner=destination, iface-status=inactive}",
                "{migration-owner=destination, iface-status=active}");
    }

    private void assertOwnershipChangeBlocksCas(final String candidateIds, final String currentIds) {
        try (MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor(REP)).thenReturn(BDF);
            scriptMock.when(() -> Script.runSimpleBashScript(contains("get Interface")))
                    .thenReturn(candidateIds, currentIds);
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), anyString())).thenReturn(LSP);

            // Candidate ownership is observed before lock selection; the helper
            // must repeat this read after acquiring the BDF lock.
            Script.runSimpleBashScript("ovs-vsctl get Interface " + REP + " external_ids");
            assertFalse(OvnVifDriver.freeDestinationOwnedRepresentor(
                    org.apache.logging.log4j.LogManager.getLogger(getClass()), "race", REP, LSP));
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), anyString(), anyString()), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), never());
        }
    }
}
