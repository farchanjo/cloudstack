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
package org.apache.cloudstack.api.command.admin.address;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.PublicIpv6AddressResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.network.Network;
import com.cloud.network.PublicIpv6AddressManager;
import com.cloud.network.UserPublicIpv6Address;
import com.cloud.network.vpc.Vpc;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;

/**
 * Smoke unit tests for importPublicIpv6Address (no Spring context).
 */
public class ImportPublicIpv6AddressCmdTest {

    private static final long ZONE_ID = 1L;
    private static final long ACCOUNT_ID = 2L;
    private static final String ADDR = "2a13:8740:0:7::100";

    private ImportPublicIpv6AddressCmd cmd;
    private PublicIpv6AddressManager publicIpv6AddressManager;
    private AccountService accountService;
    private EntityManager entityMgr;
    private ResponseGenerator responseGenerator;
    private Account owner;
    private User user;

    @Before
    public void setUp() {
        cmd = new ImportPublicIpv6AddressCmd();
        publicIpv6AddressManager = mock(PublicIpv6AddressManager.class);
        accountService = mock(AccountService.class);
        entityMgr = mock(EntityManager.class);
        responseGenerator = mock(ResponseGenerator.class);
        owner = mock(Account.class);
        user = mock(User.class);

        when(owner.getId()).thenReturn(ACCOUNT_ID);
        when(owner.getAccountId()).thenReturn(ACCOUNT_ID);
        when(accountService.getAccount(ACCOUNT_ID)).thenReturn(owner);

        ReflectionTestUtils.setField(cmd, "publicIpv6AddressManager", publicIpv6AddressManager);
        ReflectionTestUtils.setField(cmd, "_accountService", accountService);
        ReflectionTestUtils.setField(cmd, "_entityMgr", entityMgr);
        ReflectionTestUtils.setField(cmd, "_responseGenerator", responseGenerator);

        CallContext.register(user, owner);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void resolveZoneId_usesZoneIdWhenSet() {
        ReflectionTestUtils.setField(cmd, "zoneId", ZONE_ID);
        assertEquals(ZONE_ID, cmd.resolveZoneId());
    }

    @Test
    public void resolveZoneId_derivesFromNetwork() {
        Network network = mock(Network.class);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(entityMgr.findById(Network.class, 9L)).thenReturn(network);
        ReflectionTestUtils.setField(cmd, "networkId", 9L);
        assertEquals(ZONE_ID, cmd.resolveZoneId());
    }

    @Test
    public void resolveZoneId_derivesFromVpc() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getZoneId()).thenReturn(ZONE_ID);
        when(entityMgr.findById(Vpc.class, 8L)).thenReturn(vpc);
        ReflectionTestUtils.setField(cmd, "vpcId", 8L);
        assertEquals(ZONE_ID, cmd.resolveZoneId());
    }

    @Test
    public void isDisplay_defaultsFalseForSystemImport() {
        assertFalse(cmd.isDisplay());
    }

    @Test
    public void isDisplay_trueWhenSet() {
        ReflectionTestUtils.setField(cmd, "display", true);
        assertTrue(cmd.isDisplay());
    }

    @Test
    public void execute_importsWithIsSystemTrue() throws Exception {
        ReflectionTestUtils.setField(cmd, "zoneId", ZONE_ID);
        ReflectionTestUtils.setField(cmd, "ip6Address", ADDR);

        UserPublicIpv6Address imported = mock(UserPublicIpv6Address.class);
        PublicIpv6AddressResponse response = new PublicIpv6AddressResponse();
        when(publicIpv6AddressManager.importAllocated(eq(ZONE_ID), eq(owner), eq(ADDR), isNull(), isNull(),
                eq(true), eq(false))).thenReturn(imported);
        when(responseGenerator.createPublicIpv6AddressResponse(imported)).thenReturn(response);

        cmd.execute();

        verify(publicIpv6AddressManager).importAllocated(eq(ZONE_ID), eq(owner), eq(ADDR), isNull(), isNull(),
                eq(true), eq(false));
        assertSame(response, cmd.getResponseObject());
        assertEquals(cmd.getCommandName(), response.getResponseName());
    }

    @Test(expected = ServerApiException.class)
    public void execute_rejectsInvalidIp6() {
        ReflectionTestUtils.setField(cmd, "zoneId", ZONE_ID);
        ReflectionTestUtils.setField(cmd, "ip6Address", "not-v6");
        cmd.execute();
    }

    @Test
    public void apiName_matchesCmk() {
        assertEquals("importPublicIpv6Address", ImportPublicIpv6AddressCmd.APINAME);
    }
}
