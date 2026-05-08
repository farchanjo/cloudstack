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

/**
 * Public façade over {@code VfPoolManager} for the API module. Phase H.1.
 *
 * <p>The {@code server} module's {@code VfPoolManager} carries the full DAO +
 * lifecycle surface that internal callers need; admin API commands only need
 * a thin slice (force-release on a host). Splitting that slice out as
 * {@code VfPoolService} keeps the API module from depending on {@code server}
 * just to wire one Cmd, while letting the impl ({@code VfPoolManagerImpl})
 * implement both interfaces.
 */
public interface VfPoolService {

    /**
     * Force every {@code ALLOCATED} or {@code SUSPECT} VF row on the host
     * back to {@code FREE}. Returns the number of rows released. Idempotent.
     */
    int forceReleaseByHostId(long hostId);

    /**
     * Recover the VF pool state for the host by re-binding every
     * {@code FREE} pool entry to the live NIC that still references it via
     * {@code nics.vf_pool_id}. Walks {@code nics} → {@code vm_instance}
     * filtering on {@code v.removed IS NULL AND v.state IN
     * ('Running','Starting','Stopping','Migrating')}, flips the matching
     * pool row to {@code ALLOCATED}, stamps {@code allocated_to_nic_id}
     * and refreshes {@code last_seen}. Idempotent.
     *
     * <p>Used after {@link #forceReleaseByHostId(long)} or any other event
     * that wiped pool ownership while VMs were still alive on the
     * hypervisor — e.g. after a mgmt-side reset, a schema upgrade rebuild,
     * or operator triage. Restores the pool ↔ NIC linkage without
     * touching the live qemu / libvirt domain XML, which keeps the
     * existing vfio-pci binding intact.
     *
     * <p>Returns the number of rows promoted from {@code FREE} to
     * {@code ALLOCATED}.
     */
    int recoverByHostId(long hostId);
}
