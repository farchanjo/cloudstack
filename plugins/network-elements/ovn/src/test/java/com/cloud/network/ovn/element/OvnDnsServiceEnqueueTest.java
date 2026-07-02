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
package com.cloud.network.ovn.element;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.cloud.network.Network;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Verifies that OvnDnsService.removeTierDns deletes DNS rows via
 * {@link OvnNbClient#deleteDnsRowDirect(String)} rather than emptying them
 * with {@code updateDnsRecords} — DNS is a root NB table so an emptied,
 * unreferenced row is never garbage-collected and leaks forever.
 */
public class OvnDnsServiceEnqueueTest {

    private static final long ZONE_ID = 7L;
    private static final long CONTROLLER_ID = 11L;
    private static final long NETWORK_ID = 100L;
    private static final String DNS_UUID = "dns-uuid-aaa";

    private OvnPluginManager pluginManager;
    private OvnLogicalIdMapDao logicalIdMapDao;
    private OvnNbClient nbClient;
    private OvnControllerVO controller;
    private Network network;
    private OvnDnsService service;

    @Before
    public void setUp() throws Exception {
        pluginManager = mock(OvnPluginManager.class);
        logicalIdMapDao = mock(OvnLogicalIdMapDao.class);
        nbClient = mock(OvnNbClient.class);
        controller = mock(OvnControllerVO.class);
        network = mock(Network.class);

        when(controller.getId()).thenReturn(CONTROLLER_ID);
        when(pluginManager.findControllerForZone(ZONE_ID)).thenReturn(controller);
        when(pluginManager.nbClient(ZONE_ID)).thenReturn(nbClient);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(nbClient.findUuidsByExternalIds(anyString(), anyString(), anyString())).thenReturn(List.of());

        service = new OvnDnsService();
        inject(service, "pluginManager", pluginManager);
        inject(service, "logicalIdMapDao", logicalIdMapDao);
    }

    /**
     * Dead-LS-mapping branch: tier LS is already gone, so the DNS row must be
     * deleted directly instead of emptied via {@code updateDnsRecords}.
     */
    @Test
    public void removeTierDns_deadLsMapping_deletesDnsRowDirect() {
        final OvnLogicalIdMapVO dnsMapping = dnsMappingFor(DNS_UUID);
        when(logicalIdMapDao.findByCsId(eq(Kind.DNS_RECORDS), eq(NETWORK_ID), eq(CONTROLLER_ID)))
                .thenReturn(dnsMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID)))
                .thenReturn(null);

        service.removeTierDns(network);

        verify(nbClient, times(1)).deleteDnsRowDirect(DNS_UUID);
        verify(nbClient, never()).updateDnsRecords(anyString(), anyMapArg());
        verify(logicalIdMapDao, times(1)).remove(dnsMapping.getId());
    }

    /**
     * Live-LS-mapping branch: tier LS still exists, so the mapped delete path
     * ({@code deleteDnsRecords}) is used instead of the direct one.
     */
    @Test
    public void removeTierDns_liveLsMapping_usesMappedDelete() {
        final OvnLogicalIdMapVO dnsMapping = dnsMappingFor(DNS_UUID);
        final OvnLogicalIdMapVO lsMapping = mock(OvnLogicalIdMapVO.class);
        when(lsMapping.getOvnUuid()).thenReturn("ls-uuid-bbb");
        when(logicalIdMapDao.findByCsId(eq(Kind.DNS_RECORDS), eq(NETWORK_ID), eq(CONTROLLER_ID)))
                .thenReturn(dnsMapping);
        when(logicalIdMapDao.findByCsId(eq(Kind.NETWORK), eq(NETWORK_ID), eq(CONTROLLER_ID)))
                .thenReturn(lsMapping);

        service.removeTierDns(network);

        verify(nbClient, times(1)).deleteDnsRecords("ls-uuid-bbb", DNS_UUID);
        verify(nbClient, never()).deleteDnsRowDirect(anyString());
        verify(logicalIdMapDao, times(1)).remove(dnsMapping.getId());
    }

    /** Orphan sweep: each orphan UUID is deleted directly, never emptied. */
    @Test
    public void removeTierDns_orphanSweep_deletesEachOrphanDirectly() {
        when(logicalIdMapDao.findByCsId(eq(Kind.DNS_RECORDS), eq(NETWORK_ID), eq(CONTROLLER_ID)))
                .thenReturn(null);
        when(nbClient.findUuidsByExternalIds(eq("DNS"), anyString(), eq(String.valueOf(NETWORK_ID))))
                .thenReturn(List.of("orphan-1", "orphan-2"));

        service.removeTierDns(network);

        verify(nbClient, times(1)).deleteDnsRowDirect("orphan-1");
        verify(nbClient, times(1)).deleteDnsRowDirect("orphan-2");
        verify(nbClient, never()).updateDnsRecords(anyString(), anyMapArg());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, String> anyMapArg() {
        return org.mockito.ArgumentMatchers.any(Map.class);
    }

    private OvnLogicalIdMapVO dnsMappingFor(final String ovnUuid) {
        final OvnLogicalIdMapVO m = mock(OvnLogicalIdMapVO.class);
        when(m.getId()).thenReturn(200L);
        when(m.getOvnUuid()).thenReturn(ovnUuid);
        return m;
    }

    private static void inject(final Object target, final String fieldName, final Object value) throws Exception {
        Field f;
        try {
            f = target.getClass().getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            f = target.getClass().getSuperclass().getDeclaredField(fieldName);
        }
        f.setAccessible(true);
        f.set(target, value);
    }
}
