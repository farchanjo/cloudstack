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
package com.cloud.kubernetes.cluster.actionworkers;

import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.network.Ipv6AddressManager;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;

/**
 * Unit coverage for the IPv6 dual-stack rendering added to {@link KubernetesClusterStartWorker}.
 *
 * <p>The tests exercise the small pure seams the worker delegates to when building control-node
 * userdata. Key invariant: the control node's IPv6 is NEVER pre-reserved from an IPAM pool
 * (isolated/VPC guest tiers have none); it is auto-assigned as EUI-64 at deploy time. They assert:
 * <ul>
 *   <li>The control-node v6 acquisition seam always returns {@code null} and never touches the
 *       {@code Ipv6AddressManager}, regardless of network type.</li>
 *   <li>DualStack network: the dual-stack flag is derived from the network and drives the v6
 *       {@code --service-cidr} member and the kubelet {@code --node-ip} args; cert SANs stay v4-only
 *       because {@code controlNodeIp6} is {@code null}.</li>
 *   <li>REGRESSION (IPv4-only network): the cert SAN list is v4-only and the init/kubelet args are
 *       byte-identical to the pre-dual-stack output.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterStartWorkerTest {

    @Mock
    private KubernetesClusterDao kubernetesClusterDao;
    @Mock
    private KubernetesClusterDetailsDao kubernetesClusterDetailsDao;
    @Mock
    private KubernetesClusterVmMapDao kubernetesClusterVmMapDao;
    @Mock
    private KubernetesSupportedVersionDao kubernetesSupportedVersionDao;
    @Mock
    private KubernetesClusterManagerImpl kubernetesClusterManager;
    @Mock
    private Ipv6AddressManager ipv6AddressManager;
    @Mock
    private NetworkDao networkDao;

    private KubernetesClusterStartWorker worker;

    private static final String CONTROL_IP4 = "10.1.1.10";
    private static final String SERVER_IP4 = "172.16.0.5";
    private static final String CONTROL_IP6 = "fd00:cafe:1::10";
    private static final String V6_CIDR = "fd00:cafe:1::/64";

    @Before
    public void setUp() {
        kubernetesClusterManager.kubernetesClusterDao = kubernetesClusterDao;
        kubernetesClusterManager.kubernetesSupportedVersionDao = kubernetesSupportedVersionDao;
        kubernetesClusterManager.kubernetesClusterDetailsDao = kubernetesClusterDetailsDao;
        kubernetesClusterManager.kubernetesClusterVmMapDao = kubernetesClusterVmMapDao;

        KubernetesCluster kubernetesCluster = Mockito.mock(KubernetesCluster.class);
        Mockito.lenient().when(kubernetesCluster.getId()).thenReturn(1L);
        Mockito.lenient().when(kubernetesCluster.getNetworkId()).thenReturn(1L);

        worker = new KubernetesClusterStartWorker(kubernetesCluster, kubernetesClusterManager);
        worker.ipv6AddressManager = ipv6AddressManager;
        worker.networkDao = networkDao;
    }

    private Network dualStackNetwork() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(network.getIp6Cidr()).thenReturn(V6_CIDR);
        return network;
    }

    private Network ipv4OnlyNetwork() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(network.getIp6Cidr()).thenReturn(null);
        return network;
    }

    // ---- gate ----

    @Test
    public void testIsNetworkDualStackTrueWithValidIp6Cidr() {
        Assert.assertTrue(worker.isNetworkDualStack(dualStackNetwork()));
    }

    @Test
    public void testIsNetworkDualStackFalseWithoutIp6Cidr() {
        Assert.assertFalse(worker.isNetworkDualStack(ipv4OnlyNetwork()));
    }

    @Test
    public void testIsNetworkDualStackFalseForNullNetwork() {
        Assert.assertFalse(worker.isNetworkDualStack(null));
    }

    // ---- v6 control-node IP is NEVER pre-reserved (EUI-64 auto-assigned at deploy time) ----

    @Test
    public void testGetControlNodeIp6AddressNeverAcquiredForDualStackNetwork() {
        // Even on a dual-stack network the control node's v6 is not pulled from an IPAM pool
        // (there is none on isolated/VPC tiers): the seam returns null and never calls the manager.
        Network network = Mockito.mock(NetworkVO.class);

        String ip6 = worker.getKubernetesControlNodeIp6Address(network);

        Assert.assertNull(ip6);
        Mockito.verifyNoInteractions(ipv6AddressManager);
    }

    @Test
    public void testGetControlNodeIp6AddressNeverAcquiredForIpv4OnlyNetwork() {
        Network network = Mockito.mock(NetworkVO.class);

        String ip6 = worker.getKubernetesControlNodeIp6Address(network);

        Assert.assertNull(ip6);
        Mockito.verifyNoInteractions(ipv6AddressManager);
    }

    // ---- certificate SANs (issued cert) ----

    @Test
    public void testCertificateSansIncludeIp6WhenDualStack() {
        List<String> sans = worker.getControlNodeCertificateSans(CONTROL_IP4, SERVER_IP4, CONTROL_IP6);

        Assert.assertTrue(sans.contains(CONTROL_IP4));
        Assert.assertTrue(sans.contains(SERVER_IP4));
        Assert.assertTrue(sans.contains(CONTROL_IP6));
    }

    @Test
    public void testCertificateSansAreV4OnlyWhenIpv4Only() {
        List<String> sans = worker.getControlNodeCertificateSans(CONTROL_IP4, SERVER_IP4, null);

        Assert.assertEquals(List.of(CONTROL_IP4, SERVER_IP4), sans);
    }

    // ---- kubeadm-config certSANs YAML ----

    @Test
    public void testCertSansYamlAddsIp6LineWhenDualStack() {
        String yaml = worker.getControlNodeCertSansYaml(SERVER_IP4, CONTROL_IP6);

        Assert.assertTrue(yaml.startsWith("- " + SERVER_IP4));
        Assert.assertTrue(yaml.contains("- " + CONTROL_IP6));
    }

    @Test
    public void testCertSansYamlIsV4OnlyIdenticalWhenIpv4Only() {
        // Byte-identical to the previous rendering: String.format("- %s", serverIp)
        Assert.assertEquals("- " + SERVER_IP4, worker.getControlNodeCertSansYaml(SERVER_IP4, null));
    }

    // ---- apiserver-cert-extra-sans ----

    @Test
    public void testApiServerCertExtraSansAddsIp6WhenDualStack() {
        Assert.assertEquals(SERVER_IP4 + "," + CONTROL_IP6, worker.getApiServerCertExtraSans(SERVER_IP4, CONTROL_IP6));
    }

    @Test
    public void testApiServerCertExtraSansIsV4OnlyWhenIpv4Only() {
        Assert.assertEquals(SERVER_IP4, worker.getApiServerCertExtraSans(SERVER_IP4, null));
    }

    // ---- kubeadm --service-cidr ----

    @Test
    public void testServiceCidrInitArgDualStackContainsV4AndV6Members() {
        String arg = worker.getServiceCidrInitArg(true, "1.28.0");

        Assert.assertTrue(arg.contains("--service-cidr="));
        Assert.assertTrue(arg.contains(KubernetesClusterStartWorker.CLUSTER_DEFAULT_SERVICE_CIDR_V4));
        Assert.assertTrue(arg.contains(KubernetesClusterStartWorker.CLUSTER_DUALSTACK_SERVICE_CIDR_V6));
        // >= 1.21 is GA: no feature-gate.
        Assert.assertFalse(arg.contains("IPv6DualStack"));
    }

    @Test
    public void testServiceCidrInitArgEmptyWhenIpv4Only() {
        // Regression: v4 path appends nothing, kubeadm keeps its implicit default service CIDR.
        Assert.assertEquals("", worker.getServiceCidrInitArg(false, "1.28.0"));
    }

    @Test
    public void testServiceCidrInitArgAddsFeatureGateBeforeGaVersion() {
        String arg = worker.getServiceCidrInitArg(true, "1.20.5");

        Assert.assertTrue(arg.contains("--service-cidr="));
        Assert.assertTrue(arg.contains("--feature-gates=IPv6DualStack=true"));
    }

    @Test
    public void testDualStackFeatureGateNotRequiredAtGaVersion() {
        Assert.assertFalse(worker.isDualStackFeatureGateRequired("1.21.0"));
    }

    @Test
    public void testDualStackFeatureGateRequiredBeforeGaVersion() {
        Assert.assertTrue(worker.isDualStackFeatureGateRequired("1.20.0"));
    }

    // ---- dual-stack flag derived from the network (not from a pre-reserved v6) ----

    @Test
    public void testDualStackFlagDerivedFromNetworkDrivesServiceCidrButNotCertSans() {
        Network network = dualStackNetwork();
        boolean dualStack = worker.isNetworkDualStack(network);
        Assert.assertTrue(dualStack);

        // The derived flag still carries the v6 --service-cidr member ...
        String serviceCidrArg = worker.getServiceCidrInitArg(dualStack, "1.28.0");
        Assert.assertTrue(serviceCidrArg.contains(KubernetesClusterStartWorker.CLUSTER_DUALSTACK_SERVICE_CIDR_V6));

        // ... but with controlNodeIp6 == null (never acquired), the issued cert SANs stay v4-only.
        Assert.assertEquals(List.of(CONTROL_IP4, SERVER_IP4),
                worker.getControlNodeCertificateSans(CONTROL_IP4, SERVER_IP4, null));
        Assert.assertEquals(SERVER_IP4, worker.getApiServerCertExtraSans(SERVER_IP4, null));
    }

    // ---- kubelet --node-ip ----

    @Test
    public void testKubeletNodeIpArgsEmittedWhenDualStack() {
        NetworkVO network = (NetworkVO) dualStackNetwork();
        Mockito.when(networkDao.findById(1L)).thenReturn(network);

        String args = worker.getKubeletNodeIpArgs();

        Assert.assertTrue(args.contains("--node-ip="));
    }

    @Test
    public void testKubeletNodeIpArgsEmptyWhenIpv4Only() {
        // Regression: v4 path leaves KUBELET_EXTRA_ARGS byte-identical.
        NetworkVO network = (NetworkVO) ipv4OnlyNetwork();
        Mockito.when(networkDao.findById(1L)).thenReturn(network);

        Assert.assertEquals("", worker.getKubeletNodeIpArgs());
    }
}
