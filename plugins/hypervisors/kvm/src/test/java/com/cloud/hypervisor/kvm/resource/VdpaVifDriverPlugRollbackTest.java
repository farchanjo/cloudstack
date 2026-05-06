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

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

/**
 * Behavioural tests for the rollback chain wired into
 * {@link VdpaVifDriver#plug}. Mirror of
 * {@code VfPassthroughVifDriverPlugRollbackTest} but for the vDPA driver:
 * order of inverse steps differs (vdpa dev del before rep del before PF MAC
 * reset) but the LIFO contract is identical.
 */
public class VdpaVifDriverPlugRollbackTest {

    @Test
    public void rollbackStepsRunInLifoOrder() throws Exception {
        VdpaVifDriver driver = new VdpaVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        // simulate plug ordering: configureVfOnPf -> vdpa dev add -> addRep
        rollback.push(() -> log.add("pf-mac-reset"));   // step 1 inverse
        rollback.push(() -> log.add("vdpa-del"));       // step 2 inverse
        rollback.push(() -> log.add("ovs-del-port"));   // step 3 inverse
        invokeDrain(driver, rollback);
        // LIFO drain: most recently pushed runs first.
        assertEquals(3, log.size());
        assertEquals("ovs-del-port", log.get(0));
        assertEquals("vdpa-del", log.get(1));
        assertEquals("pf-mac-reset", log.get(2));
        assertTrue("rollback deque must be empty after drain", rollback.isEmpty());
    }

    @Test
    public void rollbackContinuesAfterFailingStep() throws Exception {
        VdpaVifDriver driver = new VdpaVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        rollback.push(() -> log.add("pf-mac-reset"));
        rollback.push(() -> { throw new RuntimeException("vdpa-del-failed"); });
        rollback.push(() -> log.add("ovs-del-port"));
        invokeDrain(driver, rollback);
        assertEquals(2, log.size());
        assertEquals("ovs-del-port", log.get(0));
        assertEquals("pf-mac-reset", log.get(1));
    }

    @Test
    public void rollbackOnEmptyDequeIsNoop() throws Exception {
        VdpaVifDriver driver = new VdpaVifDriver();
        invokeDrain(driver, new ArrayDeque<>());
    }

    @Test
    public void multipleFailuresDoNotShortCircuit() throws Exception {
        VdpaVifDriver driver = new VdpaVifDriver();
        Deque<Runnable> rollback = new ArrayDeque<>();
        List<String> log = new ArrayList<>();
        rollback.push(() -> log.add("ok-1"));
        rollback.push(() -> { throw new RuntimeException("boom-2"); });
        rollback.push(() -> log.add("ok-3"));
        rollback.push(() -> { throw new RuntimeException("boom-4"); });
        rollback.push(() -> log.add("ok-5"));
        invokeDrain(driver, rollback);
        assertEquals(3, log.size());
        assertEquals("ok-5", log.get(0));
        assertEquals("ok-3", log.get(1));
        assertEquals("ok-1", log.get(2));
    }

    private static void invokeDrain(VdpaVifDriver driver, Deque<Runnable> rollback) throws Exception {
        Method m = VdpaVifDriver.class.getDeclaredMethod("drainRollback", Deque.class);
        m.setAccessible(true);
        m.invoke(driver, rollback);
    }
}
