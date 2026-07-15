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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Discovers CKS <b>worker</b> guest IPs on a CloudStack tier network from
 * inventory ({@code kubernetes_cluster_vm_map} + NICs). No Kubernetes API.
 *
 * <p>CKS DAOs live in the kubernetes-service Spring module (sibling of OVN). Field
 * injection often cannot see them, so they are resolved lazily via
 * {@link ComponentContext} (root context). When the plugin is absent, lists
 * stay empty (auto ECMP / auto LB no-ops).
 */
public class OvnCksWorkerDiscovery {

    private static final Logger LOGGER = LogManager.getLogger(OvnCksWorkerDiscovery.class);

    @Inject
    private NicDao nicDao;
    @Inject
    private VMInstanceDao vmInstanceDao;

    /**
     * Running WORKER guest IPs (v4 and/or v6) on {@code networkId} for the CKS
     * cluster identified by UUID.
     */
    public WorkerIps listWorkerGuestIps(final String clusterUuid, final long networkId) {
        if (StringUtils.isBlank(clusterUuid) || nicDao == null || vmInstanceDao == null) {
            return WorkerIps.empty();
        }
        final KubernetesClusterDao clusterDao = lookup(KubernetesClusterDao.class);
        final KubernetesClusterVmMapDao mapDao = lookup(KubernetesClusterVmMapDao.class);
        if (clusterDao == null || mapDao == null) {
            LOGGER.warn("OvnCksWorkerDiscovery: kubernetes DAOs unavailable; auto paths disabled");
            return WorkerIps.empty();
        }
        final KubernetesClusterVO cluster = clusterDao.findByUuid(clusterUuid.trim());
        if (cluster == null) {
            LOGGER.warn("OvnCksWorkerDiscovery: CKS cluster uuid={} not found", clusterUuid);
            return WorkerIps.empty();
        }
        final List<KubernetesClusterVmMapVO> workers =
                mapDao.listByClusterIdAndVmType(cluster.getId(), KubernetesClusterNodeType.WORKER);
        if (workers == null || workers.isEmpty()) {
            return WorkerIps.empty();
        }
        final Set<String> v4 = new LinkedHashSet<>();
        final Set<String> v6 = new LinkedHashSet<>();
        for (final KubernetesClusterVmMapVO map : workers) {
            if (map == null) {
                continue;
            }
            final VMInstanceVO vm = vmInstanceDao.findById(map.getVmId());
            if (vm == null || vm.getState() != VirtualMachine.State.Running) {
                continue;
            }
            final List<NicVO> nics = nicDao.listByVmId(map.getVmId());
            if (nics == null) {
                continue;
            }
            for (final NicVO nic : nics) {
                if (nic == null || nic.getNetworkId() != networkId) {
                    continue;
                }
                final String ip4 = StringUtils.trimToNull(nic.getIPv4Address());
                if (ip4 != null && NetUtils.isValidIp4(ip4)) {
                    v4.add(ip4);
                }
                final String ip6 = StringUtils.trimToNull(nic.getIPv6Address());
                if (ip6 != null && NetUtils.isValidIp6(ip6)) {
                    try {
                        v6.add(NetUtils.standardizeIp6Address(ip6));
                    } catch (RuntimeException re) {
                        v6.add(ip6);
                    }
                }
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("OvnCksWorkerDiscovery: cluster={} networkId={} workers v4={} v6={}",
                    clusterUuid, networkId, v4.size(), v6.size());
        }
        return new WorkerIps(new ArrayList<>(v4), new ArrayList<>(v6));
    }

    /**
     * Same as {@link #listWorkerGuestIps(String, long)} but resolves cluster by
     * numeric id (used by tests / call sites that already have the id).
     */
    public WorkerIps listWorkerGuestIpsByClusterId(final long clusterId, final long networkId) {
        final KubernetesClusterDao clusterDao = lookup(KubernetesClusterDao.class);
        if (clusterDao == null) {
            return WorkerIps.empty();
        }
        final KubernetesClusterVO cluster = clusterDao.findById(clusterId);
        if (cluster == null || StringUtils.isBlank(cluster.getUuid())) {
            return WorkerIps.empty();
        }
        return listWorkerGuestIps(cluster.getUuid(), networkId);
    }

    private static <T> T lookup(final Class<T> type) {
        try {
            final Map<String, T> map = ComponentContext.getComponentsOfType(type);
            if (map == null || map.isEmpty()) {
                return null;
            }
            return map.values().iterator().next();
        } catch (RuntimeException re) {
            LOGGER.debug("OvnCksWorkerDiscovery: lookup {} failed: {}", type.getSimpleName(), re.getMessage());
            return null;
        }
    }

    /** Dual-stack guest IPs of Running WORKER nodes on one tier. */
    public static final class WorkerIps {
        private final List<String> ipv4;
        private final List<String> ipv6;

        public WorkerIps(final List<String> ipv4, final List<String> ipv6) {
            this.ipv4 = ipv4 == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(ipv4));
            this.ipv6 = ipv6 == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(ipv6));
        }

        public static WorkerIps empty() {
            return new WorkerIps(Collections.emptyList(), Collections.emptyList());
        }

        public List<String> getIpv4() {
            return ipv4;
        }

        public List<String> getIpv6() {
            return ipv6;
        }

        public boolean isEmpty() {
            return ipv4.isEmpty() && ipv6.isEmpty();
        }
    }
}
