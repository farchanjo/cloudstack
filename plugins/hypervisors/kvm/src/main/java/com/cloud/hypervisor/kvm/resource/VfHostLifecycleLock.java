/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.resource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes every CloudStack mutation of one VF BDF on a host.
 *
 * <p>The key is the canonical BDF, not the mutable representor or vDPA name.
 * Callers must hold this lock from identity observation through the final
 * mutation and postcondition. Missing or non-canonical BDFs are rejected by
 * callers before a lock can be acquired. This is a CloudStack-agent lifecycle
 * fence only; it does not fence arbitrary external root actors, so callers
 * must retain atomic host-side checks and fail closed when identity is not
 * provable.
 */
public final class VfHostLifecycleLock {

    private static final ConcurrentMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ReentrantLock> MANIFEST_LOCKS = new ConcurrentHashMap<>();

    private VfHostLifecycleLock() {
    }

    public static ReentrantLock forBdf(final String bdf) {
        if (bdf == null || !bdf.trim().matches("[0-9a-fA-F]{4}:[0-9a-fA-F]{2}:[0-9a-fA-F]"
                + "{2}\\.[0-9a-fA-F]")) {
            throw new IllegalArgumentException("VF lifecycle requires a canonical PCI BDF");
        }
        return LOCKS.computeIfAbsent(bdf.trim().toLowerCase(java.util.Locale.ROOT), ignored -> new ReentrantLock());
    }

    public static boolean isHeldByCurrentThread(final String bdf) {
        return forBdf(bdf).isHeldByCurrentThread();
    }

    public static ReentrantLock forManifest(final String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("migration manifest lock requires an identity");
        }
        return MANIFEST_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
    }
}
