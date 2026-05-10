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
 * Answer returned by the destination KVM agent after processing a
 * {@link PostMigrateOvnStampCommand}.
 *
 * <p>{@code result=true} means all OVN TAP NICs were stamped successfully
 * (or there were no OVN TAP NICs to stamp, which is also success).
 * {@code result=false} indicates a stamping failure; {@code details} carries
 * the error message for logging on the management server.
 */
public class PostMigrateOvnStampAnswer extends Answer {

    protected PostMigrateOvnStampAnswer() {
    }

    public PostMigrateOvnStampAnswer(final PostMigrateOvnStampCommand cmd) {
        super(cmd, true, null);
    }

    public PostMigrateOvnStampAnswer(final PostMigrateOvnStampCommand cmd, final String errorDetail) {
        super(cmd, false, errorDetail);
    }
}
