//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.OvnBgpAnnounceAnswer;
import com.cloud.agent.api.OvnBgpAnnounceCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;

/**
 * Asserts the {@code vtysh} invocation shape for {@link OvnBgpAnnounceCommand}.
 * The wrapper builds a chained {@code -c} sequence ({@code configure terminal},
 * {@code router bgp <asn>}, {@code [no] network <ip>/32}); these tests pin
 * the command string + ASN auto-detect path.
 */
public class LibvirtOvnBgpAnnounceCommandWrapperTest {

    private static final String PUBLIC_IP = "217.179.89.42";
    private static final int CONFIGURED_ASN = 24452;

    @Test
    public void announceWithExplicitAsnSkipsAutoDetectAndChainsConfigureRouterBgpNetwork() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString()))
                    .thenReturn(new Pair<>("ok\n", ""));
            // Auto-detect probe must NOT be invoked; force a failure if it is.
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("THIS_PATH_SHOULD_NOT_RUN");

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", CONFIGURED_ASN);
            final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

            final Answer answer = wrapper.execute(cmd, mockResource());

            Assert.assertTrue(answer instanceof OvnBgpAnnounceAnswer);
            Assert.assertTrue(answer.getResult());

            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeastOnce());

            final String issued = captor.getValue();
            Assert.assertTrue("vtysh path", issued.contains("/usr/bin/vtysh"));
            Assert.assertTrue("configure terminal", issued.contains("\"configure terminal\""));
            Assert.assertTrue("router bgp uses configured ASN",
                    issued.contains("\"router bgp " + CONFIGURED_ASN + "\""));
            Assert.assertTrue("network <ip>/32",
                    issued.contains("\"network " + PUBLIC_IP + "/32\""));
        }
    }

    @Test
    public void withdrawEmitsNoNetworkLine() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString()))
                    .thenReturn(new Pair<>("", ""));
            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_WITHDRAW,
                    "/usr/bin/vtysh", CONFIGURED_ASN);
            final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

            final Answer answer = wrapper.execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());

            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeastOnce());
            Assert.assertTrue("withdraw uses 'no network'",
                    captor.getValue().contains("\"no network " + PUBLIC_IP + "/32\""));
        }
    }

    @Test
    public void asnAutoDetectFailureProducesFailedAnswer() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(anyString())).thenReturn(null);
            // No call to Script.executeCommand expected — should short-circuit.

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", 0L);
            final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

            final Answer answer = wrapper.execute(cmd, mockResource());

            Assert.assertFalse("ASN auto-detect failure must surface as a failed answer",
                    answer.getResult());
        }
    }

    @Test
    public void asnAutoDetectFromFrrModernShowOutputPicksAsnAndIgnoresVrfId() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // Modern FRR 10.x summary line shape — `local AS number 24452 VRF
            // default vrf-id 0`. The legacy fallback would have grabbed the
            // trailing `0` (vrf id) — the new regex must pick `24452`.
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("BGP router identifier 217.179.88.5, local AS number 24452 VRF default vrf-id 0");
            scriptMock.when(() -> Script.executeCommand(anyString()))
                    .thenReturn(new Pair<>("ok", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", 0L);
            final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

            final Answer answer = wrapper.execute(cmd, mockResource());

            Assert.assertTrue("auto-detected ASN must succeed", answer.getResult());
            Assert.assertTrue(answer instanceof OvnBgpAnnounceAnswer);
            Assert.assertEquals(Long.valueOf(24452), ((OvnBgpAnnounceAnswer) answer).getAsn());

            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeastOnce());
            Assert.assertTrue("router bgp uses auto-detected ASN",
                    captor.getValue().contains("\"router bgp 24452\""));
        }
    }

    @Test
    public void asnAutoDetectHandlesFourByteAsn() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // 4-byte private ASN (RFC 6996: 4200000000-4294967294) — used one
            // per host on eBGP fabrics. Overflows Integer.parseInt, which used
            // to make auto-detect silently fail on every such host.
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("BGP router identifier 217.179.88.37, local AS number 4200000002 VRF default vrf-id 0");
            scriptMock.when(() -> Script.executeCommand(anyString()))
                    .thenReturn(new Pair<>("ok", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", 0L);
            final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

            final Answer answer = wrapper.execute(cmd, mockResource());

            Assert.assertTrue("4-byte ASN must auto-detect successfully", answer.getResult());
            Assert.assertEquals(Long.valueOf(4200000002L), ((OvnBgpAnnounceAnswer) answer).getAsn());

            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeastOnce());
            Assert.assertTrue("router bgp uses the 4-byte ASN",
                    captor.getValue().contains("\"router bgp 4200000002\""));
        }
    }

    @Test
    public void asnAutoDetectFromFrrLegacyShowOutputPicksAsn() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // Older FRR shape without the `number` keyword.
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("BGP router identifier 1.2.3.4, local AS 65001 holdtime 90");
            scriptMock.when(() -> Script.executeCommand(anyString()))
                    .thenReturn(new Pair<>("ok", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", 0L);
            final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

            final Answer answer = wrapper.execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals(Long.valueOf(65001), ((OvnBgpAnnounceAnswer) answer).getAsn());
        }
    }

    @Test
    public void unknownOperationIsRejected() {
        final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                PUBLIC_IP, "weird-op", "/usr/bin/vtysh", CONFIGURED_ASN);
        final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

        final Answer answer = wrapper.execute(cmd, mockResource());

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails() != null && answer.getDetails().contains("unknown operation"));
    }

    @Test
    public void missingPublicIpIsRejected() {
        final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                null, OvnBgpAnnounceCommand.OP_ANNOUNCE, "/usr/bin/vtysh", CONFIGURED_ASN);
        final LibvirtOvnBgpAnnounceCommandWrapper wrapper = new LibvirtOvnBgpAnnounceCommandWrapper();

        final Answer answer = wrapper.execute(cmd, mockResource());

        Assert.assertFalse(answer.getResult());
    }

    private static LibvirtComputingResource mockResource() {
        return org.mockito.Mockito.mock(LibvirtComputingResource.class);
    }
}
