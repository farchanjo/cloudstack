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
package com.cloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.persistence.EntityExistsException;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.UserPublicIpv6Address.State;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class PublicIpv6AddressManagerImplTest {

    private static final String PREFIX = "2a13:8740:0:7::/64";
    private static final long ZONE_ID = 1L;
    private static final long ACCOUNT_ID = 42L;
    private static final long DOMAIN_ID = 7L;

    @Mock
    private UserPublicIpv6AddressDao userPublicIpv6AddressDao;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private AccountDao accountDao;

    @InjectMocks
    private PublicIpv6AddressManagerImpl manager;

    private AccountVO owner;

    @Before
    public void setUp() {
        owner = new AccountVO("test-account", DOMAIN_ID, "network-domain", Account.Type.NORMAL, "uuid-account");
        owner.setId(ACCOUNT_ID);
    }

    @Test
    public void freePoolHostIdBounds() {
        assertTrue(manager.isFreePoolHostId(PublicIpv6AddressManager.FREE_POOL_HOST_MIN));
        assertTrue(manager.isFreePoolHostId(PublicIpv6AddressManager.FREE_POOL_HOST_MAX));
        assertTrue(manager.isFreePoolHostId(0x1000));
        assertTrue(manager.isFreePoolHostId(0xABCD));
        assertFalse(manager.isFreePoolHostId(0x0FFF));
        assertFalse(manager.isFreePoolHostId(0x10000));
        assertFalse(manager.isFreePoolHostId(0x00FF));
        assertFalse(manager.isFreePoolHostId(0x0100));
    }

    @Test
    public void transportHostIdBounds() {
        assertTrue(manager.isTransportHostId(0));
        assertTrue(manager.isTransportHostId(0x00FF));
        assertFalse(manager.isTransportHostId(0x0100));
        assertFalse(manager.isTransportHostId(0x1000));
    }

    @Test
    public void addressForHostIdAndHostIdOfRoundTrip() {
        String addr1000 = manager.addressForHostId(PREFIX, 0x1000);
        String addrFfff = manager.addressForHostId(PREFIX, 0xFFFF);
        String addr100 = manager.addressForHostId(PREFIX, 0x100);
        String addr1 = manager.addressForHostId(PREFIX, 0x1);

        assertEquals(0x1000L, manager.hostIdOf(addr1000, PREFIX));
        assertEquals(0xFFFFL, manager.hostIdOf(addrFfff, PREFIX));
        assertEquals(0x100L, manager.hostIdOf(addr100, PREFIX));
        assertEquals(0x1L, manager.hostIdOf(addr1, PREFIX));

        // Canonical compression
        assertTrue(addr1000.contains("1000") || addr1000.endsWith(":1000"));
        assertEquals("2a13:8740:0:7::1000", addr1000);
        assertEquals("2a13:8740:0:7::ffff", addrFfff);
        assertEquals("2a13:8740:0:7::100", addr100);
    }

    @Test
    public void missingPrefixFailsOnAllocate() {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(null);
        try {
            manager.allocate(ZONE_ID, owner, null, null, false, true);
            fail("Expected CloudRuntimeException for missing prefix");
        } catch (CloudRuntimeException ex) {
            assertTrue(ex.getMessage().contains(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG));
        } catch (Exception ex) {
            fail("Unexpected exception type: " + ex);
        }
        verify(userPublicIpv6AddressDao, never()).persist(any());
    }

    @Test
    public void missingPrefixFailsOnImport() {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn("  ");
        try {
            manager.importAllocated(ZONE_ID, owner, "2a13:8740:0:7::100", null, null, true, false);
            fail("Expected CloudRuntimeException for missing prefix");
        } catch (CloudRuntimeException ex) {
            assertTrue(ex.getMessage().contains(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG));
        }
    }

    @Test
    public void allocateRejectsTransportBandRequestedAddress() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        // ::1 is host id 0x1 — inside hard transport band 0x0–0xff
        try {
            manager.allocate(ZONE_ID, owner, "2a13:8740:0:7::1", null, null, false, true);
            fail("Expected InvalidParameterValueException for transport band");
        } catch (InvalidParameterValueException ex) {
            assertTrue(ex.getMessage().toLowerCase().contains("transport")
                    || ex.getMessage().contains("importAllocated"));
        }
        // ::100 is host id 0x100 — grandfather-adjacent, still below Free pool
        try {
            manager.allocate(ZONE_ID, owner, "2a13:8740:0:7::100", null, null, false, true);
            fail("Expected InvalidParameterValueException for below-free-pool grandfather id");
        } catch (InvalidParameterValueException ex) {
            assertTrue(ex.getMessage().contains("importAllocated") || ex.getMessage().contains("Free pool"));
        }
        verify(accountDao, never()).acquireInLockTable(anyLong());
        verify(userPublicIpv6AddressDao, never()).persist(any());
    }

    @Test
    public void allocateRejectsHostIdBelowFreePool() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        try {
            // 0x200 is above transport max (0xff) but below free min (0x1000)
            manager.allocate(ZONE_ID, owner, "2a13:8740:0:7::200", null, null, false, true);
            fail("Expected InvalidParameterValueException outside free pool");
        } catch (InvalidParameterValueException ex) {
            assertTrue(ex.getMessage().contains("Free pool") || ex.getMessage().contains("0x1000"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allocateInventFromFreePoolFirstHostId() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);
        when(userPublicIpv6AddressDao.listByZoneAndState(ZONE_ID, State.Free)).thenReturn(Collections.emptyList());
        when(userPublicIpv6AddressDao.listByZone(ZONE_ID)).thenReturn(Collections.emptyList());
        when(userPublicIpv6AddressDao.persist(any(UserPublicIpv6AddressVO.class))).thenAnswer(inv -> {
            UserPublicIpv6AddressVO vo = inv.getArgument(0);
            // simulate identity generation
            return vo;
        });

        try (MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallbackWithException.class))).thenAnswer(inv -> {
                TransactionCallbackWithException<?, ?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });

            UserPublicIpv6AddressVO allocated = manager.allocate(ZONE_ID, owner, null, null, false, true);
            assertNotNull(allocated);
            assertEquals(State.Allocated, allocated.getState());
            assertEquals(ACCOUNT_ID, allocated.getAccountId());
            assertEquals(DOMAIN_ID, allocated.getDomainId());
            long hostId = manager.hostIdOf(allocated.getAddress(), PREFIX);
            assertTrue("host id must be in free pool, got " + Long.toHexString(hostId),
                    manager.isFreePoolHostId(hostId));
            assertEquals(PublicIpv6AddressManager.FREE_POOL_HOST_MIN, hostId);
            assertFalse(manager.isTransportHostId(hostId));
        }

        ArgumentCaptor<UserPublicIpv6AddressVO> captor = ArgumentCaptor.forClass(UserPublicIpv6AddressVO.class);
        verify(userPublicIpv6AddressDao).persist(captor.capture());
        assertEquals("2a13:8740:0:7::1000", captor.getValue().getAddress());
        verify(accountDao).releaseFromLockTable(ACCOUNT_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allocateSkipsExistingAndPicksNext() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);
        when(userPublicIpv6AddressDao.listByZoneAndState(ZONE_ID, State.Free)).thenReturn(Collections.emptyList());

        UserPublicIpv6AddressVO taken = new UserPublicIpv6AddressVO("2a13:8740:0:7::1000", ZONE_ID);
        taken.setState(State.Allocated);
        when(userPublicIpv6AddressDao.listByZone(ZONE_ID)).thenReturn(Collections.singletonList(taken));
        when(userPublicIpv6AddressDao.persist(any(UserPublicIpv6AddressVO.class))).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallbackWithException.class))).thenAnswer(inv -> {
                TransactionCallbackWithException<?, ?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });

            UserPublicIpv6AddressVO allocated = manager.allocate(ZONE_ID, owner, null, null, false, true);
            assertEquals("2a13:8740:0:7::1001", allocated.getAddress());
            assertEquals(0x1001L, manager.hostIdOf(allocated.getAddress(), PREFIX));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void allocateInventContinuesAfterEntityExistsRace() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);
        when(userPublicIpv6AddressDao.listByZoneAndState(ZONE_ID, State.Free)).thenReturn(Collections.emptyList());
        when(userPublicIpv6AddressDao.listByZone(ZONE_ID)).thenReturn(Collections.emptyList());
        // First free-pool host id races (unique key), second invent succeeds
        when(userPublicIpv6AddressDao.persist(any(UserPublicIpv6AddressVO.class)))
                .thenThrow(new EntityExistsException("Entity already exists"))
                .thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallbackWithException.class))).thenAnswer(inv -> {
                TransactionCallbackWithException<?, ?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });

            UserPublicIpv6AddressVO allocated = manager.allocate(ZONE_ID, owner, null, null, false, true);
            assertNotNull(allocated);
            assertEquals("2a13:8740:0:7::1001", allocated.getAddress());
            assertEquals(0x1001L, manager.hostIdOf(allocated.getAddress(), PREFIX));
            assertEquals(State.Allocated, allocated.getState());
        }

        verify(userPublicIpv6AddressDao, times(2)).persist(any(UserPublicIpv6AddressVO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importGrandfatherTransportVip() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);
        when(userPublicIpv6AddressDao.findByZoneAndAddress(eq(ZONE_ID), eq("2a13:8740:0:7::100"))).thenReturn(null);
        when(userPublicIpv6AddressDao.persist(any(UserPublicIpv6AddressVO.class))).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallbackWithException.class))).thenAnswer(inv -> {
                TransactionCallbackWithException<?, ?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });

            UserPublicIpv6AddressVO imported = manager.importAllocated(ZONE_ID, owner, "2a13:8740:0:7::100",
                    null, null, true, false);
            assertNotNull(imported);
            assertEquals(State.Allocated, imported.getState());
            assertEquals("2a13:8740:0:7::100", imported.getAddress());
            assertTrue(imported.isSystem());
            assertFalse(imported.isDisplay());
            // ::100 = host id 0x100 — grandfather-adjacent (above hard transport 0x00ff, below Free 0x1000)
            assertEquals(0x100L, manager.hostIdOf(imported.getAddress(), PREFIX));
            assertFalse(manager.isTransportHostId(manager.hostIdOf(imported.getAddress(), PREFIX)));
            assertFalse(manager.isFreePoolHostId(manager.hostIdOf(imported.getAddress(), PREFIX)));
        }

        ArgumentCaptor<UserPublicIpv6AddressVO> captor = ArgumentCaptor.forClass(UserPublicIpv6AddressVO.class);
        verify(userPublicIpv6AddressDao).persist(captor.capture());
        assertEquals(State.Allocated, captor.getValue().getState());
        // Must NOT be Free
        assertFalse(State.Free.equals(captor.getValue().getState()));
        verify(accountDao).releaseFromLockTable(ACCOUNT_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void importGrandfatherSnapeVip101() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);
        when(userPublicIpv6AddressDao.findByZoneAndAddress(eq(ZONE_ID), eq("2a13:8740:0:7::101"))).thenReturn(null);
        when(userPublicIpv6AddressDao.persist(any(UserPublicIpv6AddressVO.class))).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallbackWithException.class))).thenAnswer(inv -> {
                TransactionCallbackWithException<?, ?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });

            UserPublicIpv6AddressVO imported = manager.importAllocated(ZONE_ID, owner, "2A13:8740:0:7:0:0:0:101",
                    10L, 20L, true, true);
            assertEquals("2a13:8740:0:7::101", imported.getAddress());
            assertEquals(Long.valueOf(10L), imported.getNetworkId());
            assertEquals(Long.valueOf(20L), imported.getVpcId());
        }
    }

    @Test
    public void releaseReturnsToFree() throws Exception {
        UserPublicIpv6AddressVO allocated = new UserPublicIpv6AddressVO("2a13:8740:0:7::1000", ZONE_ID);
        // set id via reflection-free path: update uses getId which is 0 for new VO — mock lockRow
        allocated.setState(State.Allocated);
        allocated.setAccountId(ACCOUNT_ID);
        allocated.setDomainId(DOMAIN_ID);
        allocated.setNetworkId(5L);
        allocated.setVpcId(6L);

        when(userPublicIpv6AddressDao.lockRow(eq(99L), anyBoolean())).thenReturn(allocated);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);
        when(userPublicIpv6AddressDao.update(eq(0L), any(UserPublicIpv6AddressVO.class))).thenReturn(true);

        boolean ok = manager.release(99L);
        assertTrue(ok);
        assertEquals(State.Free, allocated.getState());
        assertEquals(-1L, allocated.getAccountId());
        assertEquals(null, allocated.getNetworkId());
        assertEquals(null, allocated.getVpcId());
        verify(accountDao).releaseFromLockTable(ACCOUNT_ID);
    }

    @Test(expected = InsufficientAddressCapacityException.class)
    @SuppressWarnings("unchecked")
    public void allocateRequestedAlreadyAllocatedThrows() throws Exception {
        when(configDao.getValue(PublicIpv6AddressManager.PUBLIC_IPV6_PREFIX_CONFIG)).thenReturn(PREFIX);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(owner);

        // Requested Free-pool address that is already Allocated
        UserPublicIpv6AddressVO taken = new UserPublicIpv6AddressVO("2a13:8740:0:7::2000", ZONE_ID);
        taken.setState(State.Allocated);
        when(userPublicIpv6AddressDao.findByZoneAndAddress(ZONE_ID, "2a13:8740:0:7::2000")).thenReturn(taken);
        when(userPublicIpv6AddressDao.lockRow(anyLong(), anyBoolean())).thenReturn(taken);

        try (MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallbackWithException.class))).thenAnswer(inv -> {
                TransactionCallbackWithException<?, ?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });
            manager.allocate(ZONE_ID, owner, "2a13:8740:0:7::2000", null, null, false, true);
        }
    }
}
