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

/**
 * Request the agent to bind an already-provisioned SR-IOV VF as a vDPA
 * character device (/dev/vhost-vdpa-N) so it can be consumed by libvirt
 * through {@code <interface type='vdpa'>}.
 *
 * <p>On ConnectX-6 Dx this is the VF-based alternative to SF+vDPA
 * (which the adapter does not support): the mlx5_vdpa auxiliary driver
 * already exposes each VF as a vDPA mgmtdev; {@code vdpa dev add} binds
 * one of them as a vhost-vdpa chardev.
 */
public class CreateVdpaCommand extends Command {

    private String vfPciAddress; // "0000:01:02.6"
    private String pfName;       // "dx6p0"
    private String mac;          // "52:54:00:de:ad:20"

    protected CreateVdpaCommand() {
    }

    public CreateVdpaCommand(final String vfPciAddress, final String pfName, final String mac) {
        this.vfPciAddress = vfPciAddress;
        this.pfName = pfName;
        this.mac = mac;
    }

    public String getVfPciAddress() {
        return vfPciAddress;
    }

    public String getPfName() {
        return pfName;
    }

    public String getMac() {
        return mac;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
