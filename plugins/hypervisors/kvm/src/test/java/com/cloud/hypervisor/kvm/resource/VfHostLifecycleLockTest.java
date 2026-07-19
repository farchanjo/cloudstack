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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

public class VfHostLifecycleLockTest {

    @Test
    public void sameBdfSharesLockAndSerializesMutationBoundary() throws Exception {
        final ReentrantLock first = VfHostLifecycleLock.forBdf("0000:01:00.2");
        final ReentrantLock second = VfHostLifecycleLock.forBdf("0000:01:00.2");
        assertSame(first, second);
        first.lock();
        try {
            assertFalse(second.tryLock(50, TimeUnit.MILLISECONDS));
        } finally {
            first.unlock();
        }
        assertTrue(second.tryLock(50, TimeUnit.MILLISECONDS));
        second.unlock();
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonCanonicalBdfFailsClosedBeforeMutation() {
        VfHostLifecycleLock.forBdf("representor-remapped");
    }
}
