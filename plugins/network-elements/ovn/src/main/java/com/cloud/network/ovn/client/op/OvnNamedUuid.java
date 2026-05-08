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
package com.cloud.network.ovn.client.op;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates the {@code <named-uuid>} placeholders used in multi-row
 * transactions (RFC 7047 §5.2.1). When a single transaction inserts a row
 * and references it from another row inserted in the same transaction, the
 * first row's {@code uuid-name} field acts as a forward reference.
 *
 * <p>OVSDB requires named-uuids to start with a letter and contain only
 * letters / digits / underscores. We prefix with {@code cs} to namespace the
 * scope and append a strictly increasing counter so concurrent transactions
 * never share an id.
 */
public final class OvnNamedUuid {

    private static final AtomicLong COUNTER = new AtomicLong(0L);

    private OvnNamedUuid() {
    }

    public static String next(final String role) {
        return "cs_" + role + "_" + COUNTER.incrementAndGet();
    }
}
