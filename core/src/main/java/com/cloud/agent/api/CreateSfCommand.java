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
 * Requests the agent to create a Mellanox Sub-Function on a given PF,
 * bind it to the mlx5_vdpa driver, and wire its representor into OVS.
 *
 * <p>The agent responds with {@link CreateSfAnswer} containing the
 * devlink port handle, SF netdev name, representor name, and vhost-vdpa
 * device path.
 */
public class CreateSfCommand extends Command {

    private String pfPciAddress;
    private int pfIndex;
    private int sfIndex;
    private String macAddress;

    protected CreateSfCommand() {
    }

    public CreateSfCommand(final String pfPciAddress, final int pfIndex,
                           final int sfIndex, final String macAddress) {
        this.pfPciAddress = pfPciAddress;
        this.pfIndex = pfIndex;
        this.sfIndex = sfIndex;
        this.macAddress = macAddress;
    }

    public String getPfPciAddress() {
        return pfPciAddress;
    }

    public int getPfIndex() {
        return pfIndex;
    }

    public int getSfIndex() {
        return sfIndex;
    }

    public String getMacAddress() {
        return macAddress;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
