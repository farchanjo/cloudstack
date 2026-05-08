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

package com.cloud.hypervisor.kvm.resource.wrapper;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.routing.SetDvrGatewayMacCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

/**
 * Refresh the local DvrManager's VR gateway MAC for a VPC tier. Sent by
 * management after the VR is (re)started so the existing VMs on this host
 * get cross-tier shortcut flows pointing at the current VR MAC instead of
 * the stale one cached from the previous VR incarnation.
 */
@ResourceWrapper(handles = SetDvrGatewayMacCommand.class)
public final class LibvirtSetDvrGatewayMacCommandWrapper extends
        CommandWrapper<SetDvrGatewayMacCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOGGER = LogManager.getLogger(LibvirtSetDvrGatewayMacCommandWrapper.class);

    @Override
    public Answer execute(final SetDvrGatewayMacCommand cmd, final LibvirtComputingResource res) {
        final String vpc = cmd.getVpcUuid();
        final int vni = cmd.getVni();
        final String mac = cmd.getGatewayMac();
        if (res == null || res.dvrManager == null) {
            LOGGER.debug("SetDvrGatewayMac skip: DVR manager unavailable (vpc={} vni={})", vpc, vni);
            return new Answer(cmd, true, "noop");
        }
        try {
            res.dvrManager.registerGatewayMac(vpc, vni, mac);
            LOGGER.info("SetDvrGatewayMac applied: vpc={} vni={} gwMac={}", vpc, vni, mac);
            return new Answer(cmd, true, "ok");
        } catch (RuntimeException e) {
            LOGGER.warn("SetDvrGatewayMac failed: vpc={} vni={} gwMac={} err={}", vpc, vni, mac, e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }
}
