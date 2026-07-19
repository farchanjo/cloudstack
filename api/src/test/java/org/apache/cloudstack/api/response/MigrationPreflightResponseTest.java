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
package org.apache.cloudstack.api.response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cloud.network.router.MigrationPreflightResult;
import com.cloud.network.router.MigrationNicPreflightStatus;
import com.cloud.network.router.VfDeviceStatus;
import com.cloud.network.router.VfPoolStatus;

public class MigrationPreflightResponseTest {

    @Test
    public void mapsStructuredDenialWithoutLosingCapacityEvidence() {
        final MigrationPreflightResponse response = MigrationPreflightResponse.from(
                new MigrationPreflightResult(false, 11L, 22L, 2, 1, "fencing unavailable",
                        java.util.List.of(new MigrationNicPreflightStatus("nic-1", false, 2, 1,
                                "fencing unavailable")), false, true));

        assertFalse(response.isAllowed());
        assertEquals(11L, response.getVmId());
        assertEquals(2, response.getRequiredVdpaVfs());
        assertEquals(1, response.getFreeVdpaVfs());
        assertEquals("fencing unavailable", response.getDenialReason());
        assertEquals("nic-1", response.getNicStatuses().get(0).nicId());
        assertFalse(response.isRequestedChassisOk());
        assertTrue(response.isHostdevLiveRejected());
    }

    @Test
    public void mapsReadOnlyHostPoolStatus() {
        final VfPoolStatusResponse response = VfPoolStatusResponse.from(
                new VfPoolStatus(22L, 8, 4, 1, 3, 0,
                        java.util.List.of(new VfDeviceStatus(9L, "0000:01:00.1", 11L,
                                "ALLOCATED", "VDPA"))));

        assertEquals("22", response.getHostId());
        assertEquals(4, response.getVdpaFree());
        assertEquals(3, response.getAllocated());
        assertEquals("0000:01:00.1", response.getDevices().get(0).pciAddress());
    }
}
