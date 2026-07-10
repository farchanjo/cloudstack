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

import java.util.Date;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.Displayable;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

/**
 * Inventory entry for a public IPv6 VIP/FIP (Option B table
 * {@code user_public_ipv6_address}). Distinct from guest SLAAC
 * {@link UserIpv6Address} and from IPv4 {@link IpAddress}.
 */
public interface UserPublicIpv6Address extends ControlledEntity, Identity, InternalIdentity, Displayable {

    enum State {
        Free,       // Ready to be allocated from the Free pool
        Allocating, // Being claimed; not ready for use yet
        Allocated,  // Assigned to an account
        Releasing   // Being released; not ready for allocation
    }

    long getDataCenterId();

    String getAddress();

    State getState();

    void setState(State state);

    Long getNetworkId();

    Long getVpcId();

    Date getAllocatedTime();

    boolean isSystem();

    @Override
    boolean isDisplay();
}
