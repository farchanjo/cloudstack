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

import org.junit.Test;

import com.cloud.network.router.MigrationPreflightResult;
import com.cloud.network.router.VfPoolStatus;

public class MigrationPreflightResponseTest {

    @Test
    public void mapsStructuredDenialWithoutLosingCapacityEvidence() {
        final MigrationPreflightResponse response = MigrationPreflightResponse.from(
                new MigrationPreflightResult(false, 11L, 22L, 2, 1, "fencing unavailable"));

        assertFalse(response.isAllowed());
        assertEquals(11L, response.getVmId());
        assertEquals(2, response.getRequiredVdpaVfs());
        assertEquals(1, response.getFreeVdpaVfs());
        assertEquals("fencing unavailable", response.getDenialReason());
    }

    @Test
    public void mapsReadOnlyHostPoolStatus() {
        final VfPoolStatusResponse response = VfPoolStatusResponse.from(
                new VfPoolStatus(22L, 8, 4, 1, 3, 0));

        assertEquals("22", response.getHostId());
        assertEquals(4, response.getVdpaFree());
        assertEquals(3, response.getAllocated());
    }
}
