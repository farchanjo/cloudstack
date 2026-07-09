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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

import com.cloud.vm.VirtualMachine;

/**
 * Guard tests for the VM-state pruning of ECMP static-route next-hops
 * ({@link OvnReconcilerService#filterRunningNextHops}). A stopped / destroyed
 * worker VM must be dropped from the programmed next-hop set (so the OVN gateway
 * stops black-holing 1/N of the CKS LB VIP traffic), while a Running worker and
 * any next-hop that does not resolve to a VM NIC (non-VM hop the plugin cannot
 * prove dead) are kept. The resolution DAO chain
 * ({@code NicDao}/{@code VMInstanceDao}) is intentionally abstracted behind the
 * {@code stateResolver} function so this policy is exercised purely, mirroring
 * the {@code planEcmp} planner-test style.
 */
public class OvnReconcilerEcmpVmStateTest {

    private static final String NH_RUNNING = "10.45.0.14";
    private static final String NH_STOPPED = "10.45.0.159";
    private static final String NH_UNKNOWN = "10.45.0.253";

    private static Function<String, VirtualMachine.State> resolver(final Map<String, VirtualMachine.State> byIp) {
        return byIp::get;
    }

    @Test
    public void keepsRunningDropsStoppedAndKeepsUnresolvedHop() {
        final List<String> hops = Arrays.asList(NH_RUNNING, NH_STOPPED, NH_UNKNOWN);
        final List<String> kept = OvnReconcilerService.filterRunningNextHops(hops, resolver(Map.of(
                NH_RUNNING, VirtualMachine.State.Running,
                NH_STOPPED, VirtualMachine.State.Stopped)));
        // Running kept, Stopped pruned, unknown (null state -> non-VM hop) kept.
        assertEquals(Arrays.asList(NH_RUNNING, NH_UNKNOWN), kept);
    }

    @Test
    public void keepsOnlyRunningWhenAllResolveToVms() {
        final List<String> hops = Arrays.asList(NH_RUNNING, NH_STOPPED);
        final List<String> kept = OvnReconcilerService.filterRunningNextHops(hops, resolver(Map.of(
                NH_RUNNING, VirtualMachine.State.Running,
                NH_STOPPED, VirtualMachine.State.Destroyed)));
        assertEquals(java.util.Collections.singletonList(NH_RUNNING), kept);
    }

    @Test
    public void nonRunningTransitionalStatesArePruned() {
        final List<String> hops = Arrays.asList(NH_RUNNING, NH_STOPPED, NH_UNKNOWN);
        final List<String> kept = OvnReconcilerService.filterRunningNextHops(hops, resolver(Map.of(
                NH_RUNNING, VirtualMachine.State.Running,
                NH_STOPPED, VirtualMachine.State.Stopping,
                NH_UNKNOWN, VirtualMachine.State.Starting)));
        // Only the Running hop survives; Stopping and Starting are both not Running.
        assertEquals(java.util.Collections.singletonList(NH_RUNNING), kept);
    }

    @Test
    public void keepsEveryHopWhenNoneResolveToAVm() {
        final List<String> hops = Arrays.asList(NH_RUNNING, NH_STOPPED, NH_UNKNOWN);
        // Empty map -> resolver returns null for every hop -> all treated as
        // non-VM hops and kept (zero regression when routes point at non-VM IPs).
        final List<String> kept = OvnReconcilerService.filterRunningNextHops(hops, resolver(Map.of()));
        assertEquals(hops, kept);
    }

    @Test
    public void emptyInputYieldsEmptyOutput() {
        final List<String> kept = OvnReconcilerService.filterRunningNextHops(
                java.util.Collections.emptyList(), resolver(Map.of()));
        assertTrue(kept.isEmpty());
    }
}
