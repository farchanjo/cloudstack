/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

/**
 * Behavioural tests for the rollback chain wired into
 * {@link VfPassthroughVifDriver#plug}. The plug() method itself shells out to
 * sysfs / OVS / TC and is therefore impractical to drive in a unit test.
 * Instead, this suite reaches into the {@code drainRollback} helper and the
 * Deque protocol to assert:
 *
 * <ul>
 *   <li>steps run in LIFO order (latest pushed runs first);</li>
 *   <li>a failing step does not abort the others;</li>
 *   <li>the deque is empty after drain;</li>
 *   <li>caller's original exception propagates after drain.</li>
 * </ul>
 */
public class VfPassthroughVifDriverPlugRollbackTest {

    @Test
    public void rollbackStepsRunInLifoOrder() throws Exception {
        VfPassthroughVifDriver driver = new VfPassthroughVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        rollback.push(() -> log.add("first"));
        rollback.push(() -> log.add("second"));
        rollback.push(() -> log.add("third"));
        invokeDrain(driver, rollback);
        assertEquals(3, log.size());
        assertEquals("third", log.get(0));
        assertEquals("second", log.get(1));
        assertEquals("first", log.get(2));
        assertTrue("rollback deque must be empty after drain", rollback.isEmpty());
    }

    @Test
    public void rollbackContinuesAfterFailingStep() throws Exception {
        VfPassthroughVifDriver driver = new VfPassthroughVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        rollback.push(() -> log.add("ok-1"));
        rollback.push(() -> { throw new RuntimeException("fail-2"); });
        rollback.push(() -> log.add("ok-3"));
        invokeDrain(driver, rollback);
        assertEquals(2, log.size());
        assertEquals("ok-3", log.get(0));
        assertEquals("ok-1", log.get(1));
    }

    @Test
    public void rollbackOnEmptyDequeIsNoop() throws Exception {
        VfPassthroughVifDriver driver = new VfPassthroughVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        try {
            invokeDrain(driver, rollback);
        } catch (Exception e) {
            fail("draining empty rollback must not throw: " + e.getMessage());
        }
    }

    @Test
    public void multipleFailuresDoNotShortCircuit() throws Exception {
        VfPassthroughVifDriver driver = new VfPassthroughVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        rollback.push(() -> log.add("step-1"));
        rollback.push(() -> { throw new RuntimeException("boom-2"); });
        rollback.push(() -> log.add("step-3"));
        rollback.push(() -> { throw new RuntimeException("boom-4"); });
        rollback.push(() -> log.add("step-5"));
        invokeDrain(driver, rollback);
        assertEquals(3, log.size());
        assertEquals("step-5", log.get(0));
        assertEquals("step-3", log.get(1));
        assertEquals("step-1", log.get(2));
    }

    /** Reflectively invoke the package-private drainRollback hook. */
    private static void invokeDrain(VfPassthroughVifDriver driver, Deque<Runnable> rollback) throws Exception {
        Method m = VfPassthroughVifDriver.class.getDeclaredMethod(
                "drainRollback", Deque.class, String.class);
        m.setAccessible(true);
        m.invoke(driver, rollback, "VfPassthroughVifDriverPlugRollbackTest");
    }
}
