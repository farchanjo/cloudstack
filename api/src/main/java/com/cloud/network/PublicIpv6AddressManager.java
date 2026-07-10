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

import java.util.List;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.utils.component.Manager;

/**
 * IPAM for public IPv6 VIP/FIP inventory ({@code user_public_ipv6_address}).
 * Free pool host ids: {@code 0x1000}–{@code 0xFFFF}. Transport band
 * {@code 0x0000}–{@code 0x00FF} is reserved (GW, anchor, per-VPC LRP GUA).
 * Prefix is read from configuration {@code ovn.public.ipv6.prefix}.
 * <p>
 * Lives in the api module so API commands can inject it (same pattern as
 * {@link Ipv6Service} / {@link org.apache.cloudstack.network.RoutedIpv4Manager}).
 */
public interface PublicIpv6AddressManager extends Manager {

    String PUBLIC_IPV6_PREFIX_CONFIG = "ovn.public.ipv6.prefix";

    /** Inclusive Free-pool host-id lower bound ({@code ::1000}). */
    int FREE_POOL_HOST_MIN = 0x1000;

    /** Inclusive Free-pool host-id upper bound ({@code ::ffff}). */
    int FREE_POOL_HOST_MAX = 0xFFFF;

    /** Inclusive transport reserved host-id upper bound ({@code ::00ff}). */
    int TRANSPORT_HOST_MAX = 0x00FF;

    /**
     * Allocate the next free public IPv6 from the Free pool for {@code owner}.
     * Lazy-invents inventory rows; never draws transport host ids.
     */
    UserPublicIpv6Address allocate(long dataCenterId, Account owner, Long networkId, Long vpcId,
            boolean isSystem, Boolean display)
            throws InsufficientAddressCapacityException, ConcurrentOperationException;

    /**
     * Allocate a specific Free-pool address (must be in {@code 0x1000}–{@code 0xFFFF}).
     */
    UserPublicIpv6Address allocate(long dataCenterId, Account owner, String requestedAddress,
            Long networkId, Long vpcId, boolean isSystem, Boolean display)
            throws InsufficientAddressCapacityException, ConcurrentOperationException, InvalidParameterValueException;

    /**
     * Associate an already-allocated address with a network and/or VPC.
     */
    UserPublicIpv6Address associate(long id, Long networkId, Long vpcId)
            throws InvalidParameterValueException;

    /**
     * Release an allocated address back to Free (clears account/network/vpc).
     */
    boolean release(long id) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Import a grandfathered address (e.g. {@code ::100}/{@code ::101}) as Allocated.
     * May sit in the transport band; is never placed in Free by this path.
     */
    UserPublicIpv6Address importAllocated(long dataCenterId, Account owner, String address,
            Long networkId, Long vpcId, boolean isSystem, Boolean display)
            throws InvalidParameterValueException, ConcurrentOperationException;

    UserPublicIpv6Address findById(long id);

    UserPublicIpv6Address findByZoneAndAddress(long dataCenterId, String address);

    List<? extends UserPublicIpv6Address> listByAccount(long accountId);

    List<? extends UserPublicIpv6Address> listByZone(long dataCenterId);

    List<? extends UserPublicIpv6Address> listByAccountAndZone(long accountId, long dataCenterId);

    List<? extends UserPublicIpv6Address> listByNetwork(long networkId);

    List<? extends UserPublicIpv6Address> listByVpc(long vpcId);

    /** Resolved public prefix CIDR from configuration, or null if unset. */
    String getPublicIpv6Prefix();
}
