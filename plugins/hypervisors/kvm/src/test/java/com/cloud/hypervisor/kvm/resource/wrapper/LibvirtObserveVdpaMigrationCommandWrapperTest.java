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

import org.junit.Test;

import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;

public class LibvirtObserveVdpaMigrationCommandWrapperTest {
    @Test
    public void acceleratedPartialIdentityRequiresExplicitDiscovery() {
        final ObserveVdpaMigrationCommand.NicIdentity identity = acceleratedPartialIdentity();
        assertFalse(LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(identity));
        assertTrue(LibvirtObserveVdpaMigrationCommandWrapper.discoveryContractValid(identity));
    }

    @Test
    public void discoveryDoesNotRelaxNormalContract() {
        final ObserveVdpaMigrationCommand.NicIdentity identity = acceleratedPartialIdentity();
        assertFalse(LibvirtObserveVdpaMigrationCommandWrapper.identityContractValid(identity));
    }

    @Test
    public void discoveryAcceptsExplicitUntaggedVfState() {
        final ObserveVdpaMigrationCommand.NicIdentity identity = new ObserveVdpaMigrationCommand.NicIdentity(
                1L, "lsp-1", "VF_PASSTHROUGH", "0000:01:00.2", null, null,
                "02:00:00:00:00:01", null, null, "rep0", null, null, null, null, null, null);
        identity.setExpectedNicUuid("nic-1");
        identity.setExpectedPf("pf0");
        assertTrue(LibvirtObserveVdpaMigrationCommandWrapper.discoveryContractValid(identity));
    }

    private static ObserveVdpaMigrationCommand.NicIdentity acceleratedPartialIdentity() {
        final ObserveVdpaMigrationCommand.NicIdentity identity = new ObserveVdpaMigrationCommand.NicIdentity(
                1L, "lsp-1", "VF_PASSTHROUGH", "0000:01:00.2", null, null,
                "02:00:00:00:00:01", "100", null, "rep0", null, null, null, null, null, null);
        identity.setExpectedNicUuid("nic-1");
        identity.setExpectedPf("pf0");
        return identity;
    }
}
