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

/** Read-only, bounded destination proof for vDPA cutover. */
public class VerifyDestinationDataplaneCommand extends Command {

    private String vmName;
    private NicTO[] nics;
    private String expectedChassis;

    protected VerifyDestinationDataplaneCommand() {
    }

    public VerifyDestinationDataplaneCommand(final String vmName, final NicTO[] nics,
            final String expectedChassis) {
        this.vmName = vmName;
        this.nics = nics == null ? new NicTO[0] : nics.clone();
        this.expectedChassis = expectedChassis;
        setWait(10);
    }

    public String getVmName() {
        return vmName;
    }

    public NicTO[] getNics() {
        return nics.clone();
    }

    public String getExpectedChassis() { return expectedChassis; }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
