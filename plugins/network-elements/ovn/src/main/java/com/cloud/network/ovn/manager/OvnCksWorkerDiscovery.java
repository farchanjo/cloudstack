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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.db.DB;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.net.NetUtils;

/**
 * Discovers CKS <b>worker</b> guest IPs on a CloudStack tier network from
 * inventory tables ({@code kubernetes_cluster_vm_map} + {@code nics}). No
 * Kubernetes API and no Spring dependency on the kubernetes-service module
 * (sibling contexts cannot inject those DAOs into OVN).
 */
public class OvnCksWorkerDiscovery {

    private static final Logger LOGGER = LogManager.getLogger(OvnCksWorkerDiscovery.class);

    /**
     * WORKER-only (control_node=0, etcd_node=0), Running VMs, NIC on the
     * requested network, not soft-deleted.
     */
    private static final String SQL_WORKER_IPS =
            "SELECT n.ip4_address, n.ip6_address "
                    + "FROM kubernetes_cluster_vm_map k "
                    + "JOIN kubernetes_cluster c ON c.id = k.cluster_id "
                    + "JOIN vm_instance v ON v.id = k.vm_id "
                    + "JOIN nics n ON n.instance_id = v.id AND n.removed IS NULL AND n.network_id = ? "
                    + "WHERE c.uuid = ? AND IFNULL(k.control_node,0) = 0 AND IFNULL(k.etcd_node,0) = 0 "
                    + "AND v.state = 'Running' AND v.removed IS NULL";

    /**
     * Running WORKER guest IPs (v4 and/or v6) on {@code networkId} for the CKS
     * cluster identified by UUID.
     */
    @DB
    public WorkerIps listWorkerGuestIps(final String clusterUuid, final long networkId) {
        if (StringUtils.isBlank(clusterUuid) || networkId < 1) {
            return WorkerIps.empty();
        }
        final Set<String> v4 = new LinkedHashSet<>();
        final Set<String> v6 = new LinkedHashSet<>();
        final TransactionLegacy txn = TransactionLegacy.currentTxn();
        try (PreparedStatement pstmt = txn.prepareAutoCloseStatement(SQL_WORKER_IPS)) {
            pstmt.setLong(1, networkId);
            pstmt.setString(2, clusterUuid.trim());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    final String ip4 = StringUtils.trimToNull(rs.getString(1));
                    if (ip4 != null && NetUtils.isValidIp4(ip4)) {
                        v4.add(ip4);
                    }
                    final String ip6 = StringUtils.trimToNull(rs.getString(2));
                    if (ip6 != null && NetUtils.isValidIp6(ip6)) {
                        try {
                            v6.add(NetUtils.standardizeIp6Address(ip6));
                        } catch (RuntimeException re) {
                            v6.add(ip6);
                        }
                    }
                }
            }
        } catch (final SQLException e) {
            LOGGER.warn("OvnCksWorkerDiscovery: SQL failed for cluster={} networkId={}: {}",
                    clusterUuid, networkId, e.getMessage());
            return WorkerIps.empty();
        }
        LOGGER.info("OvnCksWorkerDiscovery: cluster={} networkId={} workers v4={} v6={}",
                clusterUuid, networkId, v4.size(), v6.size());
        return new WorkerIps(new ArrayList<>(v4), new ArrayList<>(v6));
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
