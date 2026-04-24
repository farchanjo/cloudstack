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
 * Tell a KVM agent the current real MAC of the VPC's VR on a given
 * guest-tier VNI. Sent when the VR is restarted or its NIC is reallocated
 * with a new VF (and thus a new MAC), so other hosts' DvrManager refreshes
 * its shortcut routing flows with the correct VR MAC.
 */
public class SetDvrGatewayMacCommand extends Command {
    private String vpcUuid;
    private int vni;
    private String gatewayMac;

    protected SetDvrGatewayMacCommand() {
    }

    public SetDvrGatewayMacCommand(String vpcUuid, int vni, String gatewayMac) {
        this.vpcUuid = vpcUuid;
        this.vni = vni;
        this.gatewayMac = gatewayMac;
    }

    public String getVpcUuid() {
        return vpcUuid;
    }

    public int getVni() {
        return vni;
    }

    public String getGatewayMac() {
        return gatewayMac;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
