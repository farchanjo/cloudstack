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
package org.apache.cloudstack.api.command.admin.host;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.junit.Test;

import com.cloud.network.router.MigrationPreflightResult;
import com.cloud.network.router.MigrationPreflightService;
import com.cloud.network.router.VfPoolService;
import com.cloud.network.router.VfPoolStatus;
import org.apache.cloudstack.api.response.MigrationPreflightResponse;

public class MigrationPreflightApiCommandTest {

    @Test
    public void listMigrationPreflightCmdIsAdminOnly() {
        final APICommand command = ListMigrationPreflightCmd.class.getAnnotation(APICommand.class);
        assertTrue(java.util.Arrays.asList(command.authorized()).contains(RoleType.Admin));
    }

    @Test
    public void listHostVfPoolStatusCmdIsAdminOnly() {
        final APICommand command = ListHostVfPoolStatusCmd.class.getAnnotation(APICommand.class);
        assertTrue(java.util.Arrays.asList(command.authorized()).contains(RoleType.Admin));
    }

    @Test
    public void listMigrationPreflightReturnsStructuredDenial() throws Exception {
        final MigrationPreflightService service = org.mockito.Mockito.mock(MigrationPreflightService.class);
        org.mockito.Mockito.when(service.preflight(11L, 22L)).thenReturn(
                new MigrationPreflightResult(false, 11L, 22L, 2, 1, "capacity"));
        final ListMigrationPreflightCmd cmd = new ListMigrationPreflightCmd();
        ReflectionTestUtils.setField(cmd, "preflightService", service);
        ReflectionTestUtils.setField(cmd, "vmId", 11L);
        ReflectionTestUtils.setField(cmd, "destinationHostId", 22L);

        cmd.execute();

        final MigrationPreflightResponse response = (MigrationPreflightResponse) cmd.getResponseObject();
        assertFalse(response.isAllowed());
        assertEquals("capacity", response.getDenialReason());
    }

    @Test
    public void listHostVfPoolStatusReturnsPerDeviceStatus() throws Exception {
        final VfPoolService service = org.mockito.Mockito.mock(VfPoolService.class);
        org.mockito.Mockito.when(service.getHostVfPoolStatus(22L)).thenReturn(
                new VfPoolStatus(22L, 2, 1, 0, 1, 0));
        final ListHostVfPoolStatusCmd cmd = new ListHostVfPoolStatusCmd();
        ReflectionTestUtils.setField(cmd, "vfPoolService", service);
        ReflectionTestUtils.setField(cmd, "hostId", 22L);

        cmd.execute();

        final org.apache.cloudstack.api.response.VfPoolStatusResponse response =
                (org.apache.cloudstack.api.response.VfPoolStatusResponse) cmd.getResponseObject();
        assertEquals("22", response.getHostId());
        assertEquals(1, response.getVdpaFree());
    }
}
