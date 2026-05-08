// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnControllerVO;

public class OvnChassisListenerTest {

    private AgentManager agentManager;
    private OvnChassisRegistrationService registrationService;
    private OvnControllerDao controllerDao;
    private OvnChassisListener listener;

    @Before
    public void setUp() throws Exception {
        agentManager = mock(AgentManager.class);
        registrationService = mock(OvnChassisRegistrationService.class);
        controllerDao = mock(OvnControllerDao.class);

        listener = new OvnChassisListener();
        injectField(listener, "agentManager", agentManager);
        injectField(listener, "registrationService", registrationService);
        injectField(listener, "controllerDao", controllerDao);
    }

    @Test
    public void registersWithAgentManagerOnPostConstruct() {
        when(agentManager.registerForHostEvents(any(Listener.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(42);

        listener.afterPropertiesSet();

        verify(agentManager, times(1)).registerForHostEvents(listener, true, false, false);
        assertEquals(42, listener.getRegistrationId());
    }

    @Test
    public void unregistersWithAgentManagerOnPreDestroy() {
        when(agentManager.registerForHostEvents(any(Listener.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(7);

        listener.afterPropertiesSet();
        listener.beforeShutdown();

        verify(agentManager, times(1)).unregisterForHostEvents(7);
    }

    @Test
    public void processConnectInvokesRegistrationServiceWhenZoneHasController() {
        final Host host = mock(Host.class);
        when(host.getId()).thenReturn(99L);
        when(host.getDataCenterId()).thenReturn(1L);
        when(controllerDao.listByZone(1L)).thenReturn(List.of(mock(OvnControllerVO.class)));
        when(registrationService.registerByHostname(99L)).thenReturn(mock(OvnChassisMapVO.class));

        listener.processConnect(host, null, false);

        verify(registrationService, times(1)).registerByHostname(99L);
    }

    @Test
    public void processConnectSkipsWhenZoneHasNoController() {
        final Host host = mock(Host.class);
        when(host.getId()).thenReturn(99L);
        when(host.getDataCenterId()).thenReturn(2L);
        when(controllerDao.listByZone(2L)).thenReturn(Collections.emptyList());

        listener.processConnect(host, null, false);

        verify(registrationService, never()).registerByHostname(anyLong());
    }

    @Test
    public void processConnectSwallowsRegistrationFailures() {
        final Host host = mock(Host.class);
        when(host.getId()).thenReturn(123L);
        when(host.getDataCenterId()).thenReturn(7L);
        when(controllerDao.listByZone(7L)).thenReturn(List.of(mock(OvnControllerVO.class)));
        doThrow(new OvnException("NB unreachable")).when(registrationService).registerByHostname(123L);

        // Must not throw — agent connect path must NOT be blocked.
        listener.processConnect(host, null, false);

        verify(registrationService, times(1)).registerByHostname(123L);
    }

    @Test
    public void processDisconnectIsNoop() {
        // Disconnect must NOT remove the chassis mapping — the row is the
        // source of truth for cleanup decisions later.
        final boolean result = listener.processDisconnect(99L, Status.Disconnected);

        // Returns true to indicate handled (no-op handling counts as handled).
        assertEquals(true, result);
        verify(registrationService, never()).registerByHostname(anyLong());
    }

    @Test
    public void processConnectIgnoresNullHost() {
        listener.processConnect(null, null, false);

        verify(registrationService, never()).registerByHostname(anyLong());
        verify(controllerDao, never()).listByZone(anyLong());
    }

    @Test
    public void afterPropertiesSetWithoutAgentManagerIsNoop() throws Exception {
        final OvnChassisListener bare = new OvnChassisListener();
        // agentManager left null on purpose
        injectField(bare, "registrationService", registrationService);
        injectField(bare, "controllerDao", controllerDao);

        bare.afterPropertiesSet();

        verify(agentManager, never()).registerForHostEvents(any(Listener.class), anyBoolean(), anyBoolean(), anyBoolean());
        // Also: a subsequent shutdown must not blow up.
        bare.beforeShutdown();
    }

    @Test
    public void listenerSurfaceForUnusedHooksReturnsBenignDefaults() {
        assertEquals(false, listener.processAnswers(1L, 1L, null));
        assertEquals(false, listener.processCommands(1L, 1L, null));
        assertEquals(true, listener.processTimeout(1L, 1L));
        assertEquals(false, listener.isRecurring());
        assertEquals(-1, listener.getTimeout());
        // processControlCommand returns null
        assertEquals(null, listener.processControlCommand(1L, null));
    }

    @Test
    public void afterPropertiesSetUsesConnectionsTrueCommandsFalsePriorityFalse() {
        when(agentManager.registerForHostEvents(any(Listener.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(11);

        listener.afterPropertiesSet();

        // Exact contract: connections=true, commands=false, priority=false.
        // Other tuples would either compete with built-in resource state
        // listeners (priority=true) or pull command traffic the plugin
        // does not need (commands=true).
        verify(agentManager, times(1)).registerForHostEvents(listener, true, false, false);
    }

    private static void injectField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
