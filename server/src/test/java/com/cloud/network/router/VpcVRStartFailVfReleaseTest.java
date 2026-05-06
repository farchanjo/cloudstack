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
package com.cloud.network.router;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.VirtualMachine;

/**
 * Audit E4.2 regression test: when the VR transitions Starting -> Stopped via
 * a boot failure, {@code VfPoolManager.releaseByVmId} fires so pre-allocated
 * VFs do not leak.
 *
 * <p>The test reaches into {@link VpcVirtualNetworkApplianceManagerImpl}'s
 * private {@code releaseHwOffloadVfsOnBootFail} via reflection so we can
 * exercise the transition without spinning up the full Spring context.
 */
@RunWith(MockitoJUnitRunner.class)
public class VpcVRStartFailVfReleaseTest {

    private static final long VM_ID = 4242L;

    @Mock
    private VfPoolManager vfPoolManager;

    private VpcVirtualNetworkApplianceManagerImpl mgr;

    private Method releaseHwOffloadVfsOnBootFail;

    @Before
    public void setUp() throws Exception {
        mgr = new VpcVirtualNetworkApplianceManagerImpl();
        injectVfPoolManager(mgr, vfPoolManager);
        releaseHwOffloadVfsOnBootFail =
                VpcVirtualNetworkApplianceManagerImpl.class.getDeclaredMethod(
                        "releaseHwOffloadVfsOnBootFail",
                        StateMachine2.Transition.class, VirtualMachine.class);
        releaseHwOffloadVfsOnBootFail.setAccessible(true);
    }

    @Test
    public void releaseFiresOnStartingToStoppedOperationFailed() throws Exception {
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Starting,
                        VirtualMachine.Event.OperationFailed,
                        VirtualMachine.State.Stopped, null);
        when(vfPoolManager.releaseByVmId(VM_ID)).thenReturn(2);
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
        verify(vfPoolManager, times(1)).releaseByVmId(VM_ID);
    }

    @Test
    public void releaseFiresOnAgentReportStopped() throws Exception {
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Starting,
                        VirtualMachine.Event.AgentReportStopped,
                        VirtualMachine.State.Stopped, null);
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
        verify(vfPoolManager, times(1)).releaseByVmId(VM_ID);
    }

    @Test
    public void releaseFiresOnAgentReportShutdowned() throws Exception {
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Starting,
                        VirtualMachine.Event.AgentReportShutdowned,
                        VirtualMachine.State.Stopped, null);
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
        verify(vfPoolManager, times(1)).releaseByVmId(VM_ID);
    }

    @Test
    public void noReleaseOnCleanStop() throws Exception {
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Running,
                        VirtualMachine.Event.StopRequested,
                        VirtualMachine.State.Stopping, null);
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
        verify(vfPoolManager, never()).releaseByVmId(eq(VM_ID));
    }

    @Test
    public void noReleaseOnRunningToStoppedAfterStopRequested() throws Exception {
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Stopping,
                        VirtualMachine.Event.OperationSucceeded,
                        VirtualMachine.State.Stopped, null);
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
        verify(vfPoolManager, never()).releaseByVmId(eq(VM_ID));
    }

    @Test
    public void noReleaseForNonRouterVm() throws Exception {
        VirtualMachine vm = mockVm(VirtualMachine.Type.User);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Starting,
                        VirtualMachine.Event.OperationFailed,
                        VirtualMachine.State.Stopped, null);
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
        verify(vfPoolManager, never()).releaseByVmId(eq(VM_ID));
    }

    @Test
    public void noReleaseWhenVfPoolManagerIsAbsent() throws Exception {
        VpcVirtualNetworkApplianceManagerImpl barebones = new VpcVirtualNetworkApplianceManagerImpl();
        // No injection: _vfPoolManager stays null, the method must early-exit.
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Starting,
                        VirtualMachine.Event.OperationFailed,
                        VirtualMachine.State.Stopped, null);
        releaseHwOffloadVfsOnBootFail.invoke(barebones, transition, vm);
        verify(vfPoolManager, never()).releaseByVmId(eq(VM_ID));
    }

    @Test
    public void releaseSwallowsManagerException() throws Exception {
        when(vfPoolManager.releaseByVmId(VM_ID)).thenThrow(new RuntimeException("DB hiccup"));
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition =
                new StateMachine2.Transition<>(
                        VirtualMachine.State.Starting,
                        VirtualMachine.Event.OperationFailed,
                        VirtualMachine.State.Stopped, null);
        // No exception escapes — graceful logging.
        releaseHwOffloadVfsOnBootFail.invoke(mgr, transition, vm);
    }

    private static VirtualMachine mockVm(VirtualMachine.Type type) {
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getType()).thenReturn(type);
        when(vm.getInstanceName()).thenReturn("r-vr-" + VM_ID);
        return vm;
    }

    private static void injectVfPoolManager(VpcVirtualNetworkApplianceManagerImpl target,
                                            VfPoolManager vfPoolManager) throws Exception {
        java.lang.reflect.Field field = VpcVirtualNetworkApplianceManagerImpl.class
                .getDeclaredField("_vfPoolManager");
        field.setAccessible(true);
        field.set(target, vfPoolManager);
    }
}
