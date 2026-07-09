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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

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
    private static final String GATEWAY_IP = "217.179.89.34";
    private static final long CONFIGURED_ASN = 24452L;

    // PARSEL-V6 — a dual-stack tier announce: tier /64, VPC public LRP v6 GUA
    // next-hop, chassis v6 anchor + v6 fabric gateway.
    private static final String V6_TIER = "2a13:8740:0:a::";
    private static final String V6_LRP_GUA = "2a13:8740:0:7::34";
    private static final String V6_LRP_MAC = "02:02:02:b3:59:22";
    private static final String V6_ANCHOR = "2a13:8740:0:7::2/64";
    private static final String V6_GW = "2a13:8740:0:7::1";

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

    @Test
    public void announceWithGatewayIpInstallsKernelRouteAndWritesNetwork() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", CONFIGURED_ASN, GATEWAY_IP);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeast(2));
            final java.util.List<String> issued = captor.getAllValues();
            Assert.assertTrue("kernel /32 route installed via the OVN LR public IP",
                    issued.stream().anyMatch(s ->
                            s.equals("ip route replace " + PUBLIC_IP + "/32 via " + GATEWAY_IP)));
            Assert.assertTrue("vtysh network line still written (BGP origination)",
                    issued.stream().anyMatch(s -> s.contains("\"network " + PUBLIC_IP + "/32\"")));
        }
    }

    @Test
    public void withdrawDeletesKernelRouteAndWritesNoNetwork() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_WITHDRAW,
                    "/usr/bin/vtysh", CONFIGURED_ASN, null);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeast(2));
            final java.util.List<String> issued = captor.getAllValues();
            Assert.assertTrue("kernel /32 route deleted by prefix",
                    issued.stream().anyMatch(s -> s.equals("ip route del " + PUBLIC_IP + "/32")));
            Assert.assertTrue("vtysh no-network line written",
                    issued.stream().anyMatch(s -> s.contains("\"no network " + PUBLIC_IP + "/32\"")));
        }
    }

    @Test
    public void announceWithoutGatewayIpIsAdvertiseOnlyNoKernelRoute() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            // 4-arg ctor → gatewayIp null → advertise-only fall-back.
            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE, "/usr/bin/vtysh", CONFIGURED_ASN);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeastOnce());
            Assert.assertTrue("no `ip route` command emitted when gatewayIp is absent",
                    captor.getAllValues().stream().noneMatch(s -> s.startsWith("ip route")));
        }
    }

    @Test
    public void routeInstallFailureDegradesToAdvertiseOnlyNotFatal() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // `ip route replace` returns a non-empty stderr (e.g. anchor not
            // provisioned yet → "Network is unreachable"). This is NON-fatal:
            // the announce still succeeds and the vtysh `network` line is still
            // written (advertise-only), never regressing below pre-datapath.
            scriptMock.when(() -> Script.executeCommand(anyString()))
                    .thenReturn(new Pair<>("", "RTNETLINK answers: Network is unreachable"));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", CONFIGURED_ASN, GATEWAY_IP);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue("route install failure must NOT fail the announce (advertise-only)",
                    answer.getResult());
            final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(captor.capture()), atLeast(2));
            final java.util.List<String> issued = captor.getAllValues();
            Assert.assertTrue("route install was attempted",
                    issued.stream().anyMatch(s -> s.equals("ip route replace " + PUBLIC_IP + "/32 via " + GATEWAY_IP)));
            Assert.assertTrue("vtysh network line still written despite route failure",
                    issued.stream().anyMatch(s -> s.contains("\"network " + PUBLIC_IP + "/32\"")));
        }
    }

    @Test
    public void announceWithAnchorCreatesPubAnchorPortAndAssignsDerivedAddress() {
        final String anchorCidr = "217.179.89.2/24";
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            // Bridge discovery + add-port + link-up run via runSimpleBashScript;
            // the mapping value is quoted exactly as ovs-vsctl returns it.
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("\"physnet1:br-cluster\"");
            // ip addr replace, the /32 route and vtysh run via executeCommand.
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                    "/usr/bin/vtysh", CONFIGURED_ASN, GATEWAY_IP, anchorCidr);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());

            final ArgumentCaptor<String> shell = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.runSimpleBashScript(shell.capture()), atLeastOnce());
            Assert.assertTrue("pub-anchor internal port created on the discovered bridge (no hardcode)",
                    shell.getAllValues().stream().anyMatch(s ->
                            s.contains("ovs-vsctl --may-exist add-port br-cluster pub-anchor")
                                    && s.contains("type=internal")));

            final ArgumentCaptor<String> exec = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(exec.capture()), atLeast(2));
            Assert.assertTrue("derived anchor address assigned on pub-anchor",
                    exec.getAllValues().stream().anyMatch(s ->
                            s.equals("ip addr replace " + anchorCidr + " dev pub-anchor")));
            Assert.assertTrue("/32 datapath route still installed after the anchor",
                    exec.getAllValues().stream().anyMatch(s ->
                            s.equals("ip route replace " + PUBLIC_IP + "/32 via " + GATEWAY_IP)));
        }
    }

    @Test
    public void withdrawNeverTouchesTheAnchorPort() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("\"physnet1:br-cluster\"");
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            // anchorCidr present but the op is withdraw → ensureAnchor is skipped
            // (the anchor is chassis-level, shared across FIPs).
            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_WITHDRAW,
                    "/usr/bin/vtysh", CONFIGURED_ASN, null, "217.179.89.2/24");
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            scriptMock.verify(() -> Script.runSimpleBashScript(
                    argThat(s -> s != null && s.contains("add-port") && s.contains("pub-anchor"))), never());
        }
    }

    @Test
    public void anchorCidrRoundTripsThroughTheCommand() {
        final OvnBgpAnnounceCommand withAnchor = new OvnBgpAnnounceCommand(
                PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE, "/usr/bin/vtysh",
                CONFIGURED_ASN, GATEWAY_IP, "217.179.89.2/24");
        Assert.assertEquals("217.179.89.2/24", withAnchor.getAnchorCidr());
        // 5-arg ctor leaves the anchor null (advertise-/route-only, pre-anchor behaviour).
        Assert.assertNull(new OvnBgpAnnounceCommand(PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE,
                "/usr/bin/vtysh", CONFIGURED_ASN, GATEWAY_IP).getAnchorCidr());
    }

    // ---------------------------------------------------------------- PARSEL-V6

    @Test
    public void announceIpv6WritesNetworkIntoIpv6UnicastAfWithV6RouteAndAnchor() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("\"physnet1:br-cluster\"");
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    V6_TIER, OvnBgpAnnounceCommand.OP_ANNOUNCE, "/usr/bin/vtysh",
                    CONFIGURED_ASN, V6_LRP_GUA, V6_ANCHOR, null, V6_GW);
            cmd.setPrefixLength(64);
            cmd.setGatewayMac(V6_LRP_MAC);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> exec = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(exec.capture()), atLeast(2));
            final java.util.List<String> issued = exec.getAllValues();
            Assert.assertTrue("v6 network originates in the IPv6 unicast AF",
                    issued.stream().anyMatch(s -> s.contains("\"address-family ipv6 unicast\"")
                            && s.contains("\"network " + V6_TIER + "/64\"")));
            Assert.assertTrue("v6 datapath route uses ip -6 via the VPC v6 GUA",
                    issued.stream().anyMatch(s ->
                            s.equals("ip -6 route replace " + V6_TIER + "/64 via " + V6_LRP_GUA)));
            Assert.assertTrue("permanent LRP neighbour pinned on pub-anchor (PARSEL-V6 ND fix)",
                    issued.stream().anyMatch(s ->
                            s.equals("ip -6 neigh replace " + V6_LRP_GUA + " lladdr " + V6_LRP_MAC
                                    + " dev pub-anchor nud permanent")));
            Assert.assertTrue("v6 datapath anchor held with ip -6 addr",
                    issued.stream().anyMatch(s -> s.equals("ip -6 addr replace " + V6_ANCHOR + " dev pub-anchor")));
            Assert.assertTrue("v6 fabric gateway held as /128 on pub-anchor",
                    issued.stream().anyMatch(s -> s.equals("ip -6 addr replace " + V6_GW + "/128 dev pub-anchor")));

            final ArgumentCaptor<String> shell = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.runSimpleBashScript(shell.capture()), atLeastOnce());
            Assert.assertTrue("v6 forwarding enabled on the gateway chassis",
                    shell.getAllValues().stream().anyMatch(s -> s.contains("net.ipv6.conf.all.forwarding=1")));
        }
    }

    @Test
    public void announceIpv6WithoutGatewayMacStillInstallsRouteButSkipsNeigh() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(anyString()))
                    .thenReturn("\"physnet1:br-cluster\"");
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    V6_TIER, OvnBgpAnnounceCommand.OP_ANNOUNCE, "/usr/bin/vtysh",
                    CONFIGURED_ASN, V6_LRP_GUA, V6_ANCHOR, null, V6_GW);
            cmd.setPrefixLength(64);
            // no setGatewayMac — wire-compat with older managers
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> exec = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(exec.capture()), atLeast(2));
            final java.util.List<String> issued = exec.getAllValues();
            Assert.assertTrue(issued.stream().anyMatch(s ->
                    s.equals("ip -6 route replace " + V6_TIER + "/64 via " + V6_LRP_GUA)));
            Assert.assertTrue("no neigh install without gatewayMac",
                    issued.stream().noneMatch(s -> s.contains("ip -6 neigh")));
        }
    }

    @Test
    public void withdrawIpv6DeletesV6RouteAndWritesNoNetworkInV6Af() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    V6_TIER, OvnBgpAnnounceCommand.OP_WITHDRAW, "/usr/bin/vtysh", CONFIGURED_ASN);
            cmd.setPrefixLength(64);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> exec = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(exec.capture()), atLeast(2));
            final java.util.List<String> issued = exec.getAllValues();
            Assert.assertTrue("v6 route deleted by prefix with ip -6",
                    issued.stream().anyMatch(s -> s.equals("ip -6 route del " + V6_TIER + "/64")));
            Assert.assertTrue("no-network line written in the IPv6 unicast AF",
                    issued.stream().anyMatch(s -> s.contains("\"address-family ipv6 unicast\"")
                            && s.contains("\"no network " + V6_TIER + "/64\"")));
        }
    }

    @Test
    public void ipv4AnnounceStaysInDefaultAfAndNeverUsesIpDashSix() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.executeCommand(anyString())).thenReturn(new Pair<>("", ""));

            final OvnBgpAnnounceCommand cmd = new OvnBgpAnnounceCommand(
                    PUBLIC_IP, OvnBgpAnnounceCommand.OP_ANNOUNCE, "/usr/bin/vtysh", CONFIGURED_ASN, GATEWAY_IP);
            final Answer answer = new LibvirtOvnBgpAnnounceCommandWrapper().execute(cmd, mockResource());

            Assert.assertTrue(answer.getResult());
            final ArgumentCaptor<String> exec = ArgumentCaptor.forClass(String.class);
            scriptMock.verify(() -> Script.executeCommand(exec.capture()), atLeast(2));
            final java.util.List<String> issued = exec.getAllValues();
            Assert.assertTrue("v4 keeps the plain ip route form",
                    issued.stream().anyMatch(s -> s.equals("ip route replace " + PUBLIC_IP + "/32 via " + GATEWAY_IP)));
            Assert.assertTrue("v4 never opens the IPv6 unicast AF (zero regression)",
                    issued.stream().noneMatch(s -> s.contains("address-family ipv6 unicast")));
            Assert.assertTrue("v4 never shells out to ip -6",
                    issued.stream().noneMatch(s -> s.contains("ip -6 ")));
        }
    }

    private static LibvirtComputingResource mockResource() {
        return org.mockito.Mockito.mock(LibvirtComputingResource.class);
    }
}
