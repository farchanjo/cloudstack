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

/** Bounded, read-only proof that a stopped source has released its bindings. */
public class VerifySourceBindingDownCommand extends Command {

    private String vmName;
    private String[] lspNames;
    private String sourceChassis;
    private String destinationChassis;

    protected VerifySourceBindingDownCommand() {
    }

    public VerifySourceBindingDownCommand(final String vmName, final String[] lspNames,
            final String sourceChassis, final String destinationChassis) {
        this.vmName = vmName;
        this.lspNames = lspNames == null ? new String[0] : lspNames.clone();
        this.sourceChassis = sourceChassis;
        this.destinationChassis = destinationChassis;
        setWait(10);
    }

    public String getVmName() { return vmName; }

    public String[] getLspNames() { return lspNames.clone(); }

    public String getSourceChassis() { return sourceChassis; }

    public String getDestinationChassis() { return destinationChassis; }

    @Override
    public boolean executeInSequence() { return false; }
}
