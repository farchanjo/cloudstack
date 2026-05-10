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

package com.cloud.agent.api;

import com.cloud.agent.api.to.NicTO;

/**
 * Dispatched to the destination KVM agent immediately after a successful live
 * migration to apply OVN iface-id stamps on the destination tap devices.
 *
 * <p>After {@code virDomainMigrate*} completes, libvirt has assigned kernel
 * tap device names ({@code vnetN}) on the destination.  Those names are not
 * known during {@code PrepareForMigrationCommand} — they appear only once the
 * domain is live on dest.  Sending this command to the dest agent lets
 * {@code LibvirtComputingResource#applyOvnPostPlugTunables} resolve the live
 * domain XML on the destination and stamp each OVN TAP NIC with
 * {@code external_ids:iface-id=lsp-<uuid>}, which causes ovn-controller to
 * claim the Port_Binding and restore offloaded flows.
 *
 * <p>Dispatch path: management server reads the dest host from the
 * {@code MigrateCommand} success path and sends this command via
 * {@code AgentManager#send(destHostId, PostMigrateOvnStampCommand)}.
 */
public class PostMigrateOvnStampCommand extends Command {

    private String vmName;
    private NicTO[] nics;

    protected PostMigrateOvnStampCommand() {
    }

    public PostMigrateOvnStampCommand(final String vmName, final NicTO[] nics) {
        this.vmName = vmName;
        this.nics = nics != null ? nics.clone() : new NicTO[0];
    }

    public String getVmName() {
        return vmName;
    }

    public NicTO[] getNics() {
        return nics != null ? nics.clone() : new NicTO[0];
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
