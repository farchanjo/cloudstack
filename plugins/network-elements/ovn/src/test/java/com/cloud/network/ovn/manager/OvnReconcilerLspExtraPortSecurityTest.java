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
package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;

import java.util.Collections;

import org.junit.Test;

/**
 * Guard tests for {@link OvnReconcilerService#resyncLspExtraPortSecurity}. An
 * empty/{@code null} extras map must short-circuit before touching the NB
 * client, controller, or any DAO — proving the feature is a strict no-op (zero
 * regression) when {@code ovn.lsp.extra.port.security.cidrs} is unset. The
 * apply path (token composition + skip-vs-fix decision) is exercised through
 * the pure helper in {@code OvnLspAddressesTest}.
 */
public class OvnReconcilerLspExtraPortSecurityTest {

    @Test
    public void emptyMapIsNoOpAndTouchesNothing() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        // Passing nulls for nb + controller proves no dereference happens when
        // the map is empty.
        assertEquals(0, svc.resyncLspExtraPortSecurity(null, null, Collections.emptyMap(), false));
    }

    @Test
    public void nullMapIsNoOpAndTouchesNothing() {
        final OvnReconcilerService svc = new OvnReconcilerService();
        assertEquals(0, svc.resyncLspExtraPortSecurity(null, null, null, true));
    }
}
