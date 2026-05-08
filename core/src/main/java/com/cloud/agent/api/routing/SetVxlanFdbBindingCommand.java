//
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

package com.cloud.agent.api.routing;

import com.cloud.agent.api.Command;

/**
 * Tell a KVM agent that a user VM with {@code vmMac} on the given VXLAN
 * {@code vni} lives on remote storage IP {@code remoteStorageIp}. The agent
 * installs a high-priority static FDB OF rule that pins {@code dl_dst=vmMac}
 * on the VXLAN tunnel egress port to {@code remoteStorageIp}. This
 * short-circuits the OVS NORMAL FDB lookup, so even when MAC learning is
 * polluted by mesh hairpin loops, traffic destined to the remote VM is
 * always sent to the correct VXLAN tunnel.
 *
 * <p>Sent by mgmt to all peer hosts in the same Zone whenever a user VM
 * plugs/unplugs on a tier whose offering has {@code hwOffloadEnabled=true}.
 * Symmetric to {@link SetDvrGatewayMacCommand} which propagates the VR's MAC.
 *
 * <p>{@code remove=true} causes the agent to delete the rule (used on unplug
 * or VM migration source-host cleanup).
 */
public class SetVxlanFdbBindingCommand extends Command {
    private String vpcUuid;
    private int vni;
    private String vmMac;
    private String remoteStorageIp;
    private boolean remove;

    protected SetVxlanFdbBindingCommand() {
    }

    public SetVxlanFdbBindingCommand(String vpcUuid, int vni, String vmMac,
            String remoteStorageIp, boolean remove) {
        this.vpcUuid = vpcUuid;
        this.vni = vni;
        this.vmMac = vmMac;
        this.remoteStorageIp = remoteStorageIp;
        this.remove = remove;
    }

    public String getVpcUuid() {
        return vpcUuid;
    }

    public int getVni() {
        return vni;
    }

    public String getVmMac() {
        return vmMac;
    }

    public String getRemoteStorageIp() {
        return remoteStorageIp;
    }

    public boolean isRemove() {
        return remove;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
