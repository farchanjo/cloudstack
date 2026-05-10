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

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PostMigrateOvnStampAnswer;
import com.cloud.agent.api.PostMigrateOvnStampCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

/**
 * Handles {@link PostMigrateOvnStampCommand} on the destination KVM agent.
 *
 * <p>After a successful live migration, libvirt has assigned kernel tap device
 * names ({@code vnetN}) on the destination host. This wrapper calls
 * {@link LibvirtComputingResource#applyOvnPostPlugTunables} to resolve the
 * live domain XML on the destination and stamp each OVN TAP NIC with
 * {@code external_ids:iface-id=lsp-<uuid>} via {@code ovs-vsctl}, which
 * causes ovn-controller to claim the Port_Binding and restore offloaded flows.
 *
 * <p>The call is idempotent: if the tap has already been stamped (e.g. by a
 * retry), {@code ovs-vsctl set} is a no-op because the value is already set.
 */
@ResourceWrapper(handles = PostMigrateOvnStampCommand.class)
public final class LibvirtPostMigrateOvnStampCommandWrapper
        extends CommandWrapper<PostMigrateOvnStampCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final PostMigrateOvnStampCommand command,
                          final LibvirtComputingResource libvirtComputingResource) {
        final String vmName = command.getVmName();
        logger.info("PostMigrateOvnStamp: applying iface-id stamps on destination taps for VM {}", vmName);
        try {
            libvirtComputingResource.applyOvnPostPlugTunables(vmName, command.getNics());
            logger.info("PostMigrateOvnStamp: completed for VM {}", vmName);
            return new PostMigrateOvnStampAnswer(command);
        } catch (final Exception e) {
            logger.error("PostMigrateOvnStamp: failed for VM {}: {}", vmName, e.getMessage(), e);
            return new PostMigrateOvnStampAnswer(command, e.getMessage());
        }
    }
}
