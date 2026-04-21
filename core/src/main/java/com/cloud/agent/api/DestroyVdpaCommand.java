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
 * Request the agent to tear down a vDPA device previously created by
 * {@link CreateVdpaCommand}. The underlying VF auxiliary device rebinds
 * to mlx5_core automatically; no PCI hot-unplug is required.
 */
public class DestroyVdpaCommand extends Command {

    private String vdpaName; // "vdpa-dx6p0vf20"

    protected DestroyVdpaCommand() {
    }

    public DestroyVdpaCommand(final String vdpaName) {
        this.vdpaName = vdpaName;
    }

    public String getVdpaName() {
        return vdpaName;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
