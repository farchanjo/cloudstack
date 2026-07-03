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
 * Answer to {@link OvnBgpAnnounceCommand}. Carries the FRR ASN actually
 * used (handy for diagnostics when the wrapper auto-detected) and any
 * stderr text from {@code vtysh} so the management log can surface FRR
 * errors directly.
 */
public class OvnBgpAnnounceAnswer extends Answer {

    private Long asn;

    /** No-arg constructor for serialization frameworks. */
    public OvnBgpAnnounceAnswer() {
        // No-op.
    }

    public OvnBgpAnnounceAnswer(final Command command, final boolean success, final String details) {
        super(command, success, details);
    }

    public OvnBgpAnnounceAnswer(final Command command, final boolean success, final String details, final Long asn) {
        super(command, success, details);
        this.asn = asn;
    }

    public Long getAsn() {
        return asn;
    }

    public void setAsn(final Long asn) {
        this.asn = asn;
    }
}
