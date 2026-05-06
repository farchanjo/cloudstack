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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.element.ConnectivityProvider;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Tier-level network element. The OVN-managed L2 plumbing lives in the
 * guru ({@link OvnGuestNetworkGuru}); this element only declares the
 * services the plugin offers and acts as the entry point for
 * implement / prepare / release / shutdown / destroy.
 *
 * <p>Connectivity is supplied by OVN; SourceNat and StaticNat hand off to
 * the matching service classes (see Phase I.4 SourceNat / StaticNat).
 */
@Component
public class OvnNetworkElement extends AdapterBase implements ConnectivityProvider {

    private static final Logger LOGGER = LogManager.getLogger(OvnNetworkElement.class);

    private static final Map<Service, Map<Capability, String>> CAPABILITIES = buildCapabilities();

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public Provider getProvider() {
        return OvnNetworkProvider.OVN_PROVIDER;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        return super.configure(name, params);
    }

    @Override
    public boolean implement(final Network network, final NetworkOffering offering, final DeployDestination dest,
                             final ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        LOGGER.debug("OvnNetworkElement.implement {} (offering={})", network.getName(), offering.getName());
        return true;
    }

    @Override
    public boolean prepare(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                           final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        // L2 plumbing is the guru's responsibility; nothing to do here for MVP.
        return true;
    }

    @Override
    public boolean release(final Network network, final NicProfile nic, final VirtualMachineProfile vm,
                           final ReservationContext context) {
        return true;
    }

    @Override
    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) {
        return true;
    }

    @Override
    public boolean destroy(final Network network, final ReservationContext context) {
        return true;
    }

    @Override
    public boolean isReady(final PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(final PhysicalNetworkServiceProvider provider, final ReservationContext context) {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        // SourceNat / StaticNat / NetworkACL can be enabled independently.
        return true;
    }

    @Override
    public boolean verifyServicesCombination(final Set<Service> services) {
        // Connectivity is mandatory; the rest are optional.
        return services.contains(Service.Connectivity);
    }

    private static Map<Service, Map<Capability, String>> buildCapabilities() {
        final Map<Service, Map<Capability, String>> caps = new HashMap<>();
        caps.put(Service.Connectivity, null);
        caps.put(Service.SourceNat, null);
        caps.put(Service.StaticNat, null);
        caps.put(Service.PortForwarding, null);
        caps.put(Service.NetworkACL, null);
        return caps;
    }
}
