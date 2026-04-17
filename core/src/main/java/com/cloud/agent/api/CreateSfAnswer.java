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
 * Answer returned after a Mellanox Sub-Function has been successfully created,
 * activated, and bound to the vDPA driver on the host.
 *
 * <p>Contains the identifiers needed by the management server to track the SF
 * and later issue {@link DestroySfCommand} for cleanup.
 */
public class CreateSfAnswer extends Answer {

    private String devlinkPortHandle;
    private String sfNetdevName;
    private String representorName;
    private String vdpaDevice;

    protected CreateSfAnswer() {
    }

    public CreateSfAnswer(final Command command, final boolean success, final String details) {
        super(command, success, details);
    }

    public CreateSfAnswer(final Command command, final String devlinkPortHandle,
                          final String sfNetdevName, final String representorName,
                          final String vdpaDevice) {
        super(command, true, null);
        this.devlinkPortHandle = devlinkPortHandle;
        this.sfNetdevName = sfNetdevName;
        this.representorName = representorName;
        this.vdpaDevice = vdpaDevice;
    }

    public String getDevlinkPortHandle() {
        return devlinkPortHandle;
    }

    public String getSfNetdevName() {
        return sfNetdevName;
    }

    public String getRepresentorName() {
        return representorName;
    }

    public String getVdpaDevice() {
        return vdpaDevice;
    }
}
