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

import org.junit.Test;

import com.cloud.network.rules.LoadBalancerContainer.LbKind;

public class LoadBalancerVOLbKindTest {

    @Test
    public void defaultKindIsCtLb() {
        LoadBalancerVO vo = new LoadBalancerVO();
        assertEquals(LbKind.CT_LB, vo.getLbKind());
    }

    @Test
    public void constructorDefaultsToCtLb() {
        LoadBalancerVO vo = new LoadBalancerVO("xid", "n", "d", 1L, 80, 8080, "roundrobin",
                10L, 1L, 1L, "tcp", null);
        assertEquals(LbKind.CT_LB, vo.getLbKind());
    }

    @Test
    public void setLbKindRoundTrip() {
        LoadBalancerVO vo = new LoadBalancerVO();
        vo.setLbKind(LbKind.DSR_SOFTWARE);
        assertEquals(LbKind.DSR_SOFTWARE, vo.getLbKind());
        vo.setLbKind(null);
        assertEquals(LbKind.CT_LB, vo.getLbKind());
    }
}
