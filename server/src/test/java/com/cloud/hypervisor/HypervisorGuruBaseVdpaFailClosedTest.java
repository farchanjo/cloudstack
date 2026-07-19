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
package com.cloud.hypervisor;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.cloud.utils.exception.CloudRuntimeException;

public class HypervisorGuruBaseVdpaFailClosedTest {

    @Test
    public void nullAllocationMessageRejectsTapFallback() {
        final CloudRuntimeException failure = HypervisorGuruBase.vdpaCapacityFailure(7L, 11L, null);

        assertEquals("Insufficient vDPA VF capacity on host 7 for NIC 11; refusing TAP fallback",
                failure.getMessage());
        assertThrows(CloudRuntimeException.class, () -> { throw failure; });
    }
}
