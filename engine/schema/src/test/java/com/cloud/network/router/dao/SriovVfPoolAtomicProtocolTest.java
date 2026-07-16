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
package com.cloud.network.router.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.cloud.network.router.SriovVfPoolVO;
import com.cloud.network.router.SriovVfPoolVO.State;
import com.cloud.utils.exception.CloudRuntimeException;

/** Transaction-protocol tests that do not require a live MySQL instance. */
public class SriovVfPoolAtomicProtocolTest {

    @Test
    public void multiNicValidationCompletesForEveryNicBeforeAnyCommitMutation() throws Exception {
        final SriovVfPoolDaoImpl dao = new SriovVfPoolDaoImpl();
        final Map<Long, Long> canonical = new LinkedHashMap<>();
        canonical.put(10L, 100L);
        canonical.put(20L, 200L);
        final SriovVfPoolVO sourceOne = row(100L, 1L, 10L, State.ALLOCATED);
        final SriovVfPoolVO destinationOne = row(101L, 2L, 10L, State.RESERVED);
        final SriovVfPoolVO sourceTwo = row(200L, 1L, 20L, State.ALLOCATED);
        final Map<Long, List<SriovVfPoolVO>> rows = new LinkedHashMap<>();
        rows.put(10L, Arrays.asList(sourceOne, destinationOne));
        rows.put(20L, Arrays.asList(sourceTwo));

        assertThrows(CloudRuntimeException.class,
                () -> dao.validateVmCommit(7L, 1L, 2L, canonical, rows));

        assertEquals(State.RESERVED.name(), destinationOne.getState());
        assertEquals(State.ALLOCATED.name(), sourceOne.getState());
        assertEquals(State.ALLOCATED.name(), sourceTwo.getState());
    }

    @Test
    public void deadlockRetryIsBoundedAndRetriesMysqlDeadlock() {
        final SriovVfPoolDaoImpl dao = new SriovVfPoolDaoImpl();
        final AtomicInteger attempts = new AtomicInteger();

        final String result = dao.executeWithDeadlockRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new CloudRuntimeException("deadlock",
                        new SQLException("deadlock", "40001", 1213));
            }
            return "committed";
        });

        assertEquals("committed", result);
        assertEquals(3, attempts.get());
    }

    private static SriovVfPoolVO row(final long id, final long hostId, final long nicId,
                                     final State state) throws Exception {
        final SriovVfPoolVO row = new SriovVfPoolVO(hostId, "0000:01:00.2", "pf0", "rep0");
        final Field idField = SriovVfPoolVO.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.setLong(row, id);
        row.setAllocatedToNicId(nicId);
        row.setState(state);
        return row;
    }
}
