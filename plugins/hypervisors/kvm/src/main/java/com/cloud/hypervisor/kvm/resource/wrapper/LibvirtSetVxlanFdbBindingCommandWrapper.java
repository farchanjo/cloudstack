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
import com.cloud.agent.api.routing.SetVxlanFdbBindingCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

/**
 * Install (or remove) a static FDB OF rule that pins {@code dl_dst=vmMac}
 * on the VXLAN tunnel egress port to {@code remoteStorageIp}. Short-circuits
 * OVS NORMAL FDB lookup on this host so unicast traffic to the remote VM
 * always reaches the correct VXLAN tunnel even when MAC learning is polluted
 * by hairpin-loop frames from the mesh.
 *
 * <p>If this host is itself the {@code remoteStorageIp} (i.e. the VM is
 * local) the command is a no-op — the local rule is already installed by
 * {@code VfPassthroughVifDriver.installLocalVmFdbRule} on plug.
 */
@ResourceWrapper(handles = SetVxlanFdbBindingCommand.class)
public final class LibvirtSetVxlanFdbBindingCommandWrapper extends
        CommandWrapper<SetVxlanFdbBindingCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOGGER = LogManager.getLogger(LibvirtSetVxlanFdbBindingCommandWrapper.class);

    @Override
    public Answer execute(final SetVxlanFdbBindingCommand cmd, final LibvirtComputingResource res) {
        final String vpc = cmd.getVpcUuid();
        final int vni = cmd.getVni();
        final String mac = cmd.getVmMac();
        final String remoteIp = cmd.getRemoteStorageIp();
        final boolean remove = cmd.isRemove();
        try {
            int ovsTag = VfPassthroughVifDriver.toOvsAccessTag(vni);
            String vxlanPort = String.format("vxlan_%d_%s", vni,
                    remoteIp == null ? "" : remoteIp.substring(remoteIp.lastIndexOf('.') + 1));
            // Always strip any stale rule first (idempotent). NOTE: must NOT use
            // --strict here — strict mode treats unspecified fields as priority=0
            // and would never match the priority=400 rule we want to clear. The
            // non-strict form wildcards everything except (table, dl_vlan, dl_dst)
            // which is exactly what we need to nuke any existing pin for this mac.
            Script.runSimpleBashScript(String.format(
                "ovs-ofctl del-flows br-bond \"table=0,dl_vlan=%d,dl_dst=%s\" 2>/dev/null",
                ovsTag, mac));
            if (remove) {
                LOGGER.info("SetVxlanFdbBinding remove: vpc={} vni={} mac={} (cleared)",
                    vpc, vni, mac);
                return new Answer(cmd, true, "removed");
            }
            // Resolve the vxlan port's ofport to validate it exists.
            String ofportStr = Script.runSimpleBashScript(String.format(
                "ovs-vsctl get interface %s ofport 2>/dev/null", vxlanPort));
            if (ofportStr == null || ofportStr.trim().isEmpty() || "-1".equals(ofportStr.trim())) {
                LOGGER.warn("SetVxlanFdbBinding: vxlan port {} not found on this host (vpc={} vni={} mac={})",
                    vxlanPort, vpc, vni, mac);
                return new Answer(cmd, false, "vxlan port not found");
            }
            int ofport = Integer.parseInt(ofportStr.trim());
            Script.runSimpleBashScript(String.format(
                "ovs-ofctl add-flow br-bond \"table=0,priority=400,dl_vlan=%d,dl_dst=%s,actions=output:%d\"",
                ovsTag, mac, ofport));
            LOGGER.info("SetVxlanFdbBinding pin: vpc={} vni={} mac={} -> {} (ofport={})",
                vpc, vni, mac, vxlanPort, ofport);
            return new Answer(cmd, true, "ok");
        } catch (RuntimeException e) {
            LOGGER.warn("SetVxlanFdbBinding failed: vpc={} vni={} mac={} remote={} err={}",
                vpc, vni, mac, remoteIp, e.getMessage());
            return new Answer(cmd, false, e.getMessage());
        }
    }
}
