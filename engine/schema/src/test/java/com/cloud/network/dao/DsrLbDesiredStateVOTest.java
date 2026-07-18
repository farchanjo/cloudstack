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
package com.cloud.network.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.junit.Test;

/**
 * Regression: {@code updated} must be {@code @Temporal(TIMESTAMP)} so
 * GenericDaoBase binds the Date on INSERT (2026-07-17 DSR canary failure).
 */
public class DsrLbDesiredStateVOTest {

    @Test
    public void updatedFieldHasTemporalTimestamp() throws Exception {
        Field f = DsrLbDesiredStateVO.class.getDeclaredField("updated");
        Temporal temporal = f.getAnnotation(Temporal.class);
        assertNotNull("@Temporal required on updated for GenericDaoBase Date binding", temporal);
        assertEquals(TemporalType.TIMESTAMP, temporal.value());
    }

    @Test
    public void constructorSetsUpdatedNonNull() {
        DsrLbDesiredStateVO vo = new DsrLbDesiredStateVO(1L, "1.2.3.4", "2a13:8740:0:7::101",
                80, "tcp", "{\"cs_lb_kind\":\"DSR_SOFTWARE\"}");
        assertNotNull(vo.getUpdated());
        assertNotNull(vo.getCreated());
        assertEquals(DsrLbDesiredStateVO.STATE_PENDING, vo.getState());
        assertEquals("2a13:8740:0:7::101", vo.getVipV6());
    }

    @Test
    public void setStateRefreshesUpdated() throws InterruptedException {
        DsrLbDesiredStateVO vo = new DsrLbDesiredStateVO(2L, null, "2a13:8740:0:7::101",
                443, "tcp", "{}");
        long t0 = vo.getUpdated().getTime();
        Thread.sleep(5L);
        vo.setState(DsrLbDesiredStateVO.STATE_PROGRAMMED);
        assertTrue(vo.getUpdated().getTime() >= t0);
        assertEquals(DsrLbDesiredStateVO.STATE_PROGRAMMED, vo.getState());
    }
}
