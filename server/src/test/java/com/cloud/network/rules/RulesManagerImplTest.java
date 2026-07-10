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
package com.cloud.network.rules;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.UserVmDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RulesManagerImpl#applyUserDataIfNeeded} — static NAT metadata refresh.
 */
@RunWith(MockitoJUnitRunner.class)
public class RulesManagerImplTest {

    private static final long VM_ID = 42L;
    private static final long TEMPLATE_ID = 7L;

    @Mock
    private NetworkModel _networkModel;

    @Mock
    private UserVmDao _vmDao;

    @Mock
    private VMTemplateDao _templateDao;

    @Mock
    private UserDataServiceProvider userDataProvider;

    @Mock
    private Network network;

    @Mock
    private Nic guestNic;

    @Mock
    private UserVmVO vm;

    @Mock
    private VMTemplateVO template;

    @Spy
    @InjectMocks
    private RulesManagerImpl rulesManager;

    @Test
    public void applyUserDataIfNeeded_runningConfigDrive_skipsSaveUserData() throws ResourceUnavailableException {
        when(_networkModel.getUserDataUpdateProvider(network)).thenReturn(userDataProvider);
        when(userDataProvider.getProvider()).thenReturn(Network.Provider.ConfigDrive);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);

        rulesManager.applyUserDataIfNeeded(VM_ID, network, guestNic);

        verify(userDataProvider, never()).saveUserData(any(Network.class), any(NicProfile.class), any(VirtualMachineProfile.class));
        verify(_templateDao, never()).findByIdIncludingRemoved(anyLong());
    }

    @Test
    public void applyUserDataIfNeeded_runningVirtualRouter_callsSaveUserData() throws ResourceUnavailableException {
        stubProviderAndRunningVm(Network.Provider.VirtualRouter);
        when(userDataProvider.saveUserData(any(Network.class), any(NicProfile.class), any(VirtualMachineProfile.class))).thenReturn(true);

        rulesManager.applyUserDataIfNeeded(VM_ID, network, guestNic);

        verify(userDataProvider).saveUserData(eq(network), any(NicProfile.class), any(VirtualMachineProfile.class));
    }

    @Test
    public void applyUserDataIfNeeded_stoppedConfigDrive_callsSaveUserData() throws ResourceUnavailableException {
        when(_networkModel.getUserDataUpdateProvider(network)).thenReturn(userDataProvider);
        // getProvider() not consulted when VM is Stopped (short-circuit on state check)
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(_networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(false);
        when(_networkModel.getNetworkTag(any(Hypervisor.HypervisorType.class), eq(network))).thenReturn(null);
        when(userDataProvider.saveUserData(any(Network.class), any(NicProfile.class), any(VirtualMachineProfile.class))).thenReturn(true);

        rulesManager.applyUserDataIfNeeded(VM_ID, network, guestNic);

        verify(userDataProvider).saveUserData(eq(network), any(NicProfile.class), any(VirtualMachineProfile.class));
    }

    @Test
    public void applyUserDataIfNeeded_saveUserDataThrows_doesNotPropagate() throws ResourceUnavailableException {
        stubProviderAndRunningVm(Network.Provider.VirtualRouter);
        when(userDataProvider.saveUserData(any(Network.class), any(NicProfile.class), any(VirtualMachineProfile.class)))
                .thenThrow(new CloudRuntimeException("Instance should to stopped to reset password"));

        // Must not throw — enableStaticNat already succeeded; metadata is best-effort.
        rulesManager.applyUserDataIfNeeded(VM_ID, network, guestNic);

        verify(userDataProvider).saveUserData(eq(network), any(NicProfile.class), any(VirtualMachineProfile.class));
    }

    @Test
    public void applyUserDataIfNeeded_userdataUnsupported_skipsQuietly() throws ResourceUnavailableException {
        when(_networkModel.getUserDataUpdateProvider(network)).thenThrow(new UnsupportedServiceException("no userdata"));

        rulesManager.applyUserDataIfNeeded(VM_ID, network, guestNic);

        verify(userDataProvider, never()).saveUserData(any(Network.class), any(NicProfile.class), any(VirtualMachineProfile.class));
        verify(_vmDao, never()).findById(anyLong());
    }

    private void stubProviderAndRunningVm(Network.Provider provider) throws ResourceUnavailableException {
        when(_networkModel.getUserDataUpdateProvider(network)).thenReturn(userDataProvider);
        when(userDataProvider.getProvider()).thenReturn(provider);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(_networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(false);
        when(_networkModel.getNetworkTag(any(Hypervisor.HypervisorType.class), eq(network))).thenReturn(null);
    }
}
