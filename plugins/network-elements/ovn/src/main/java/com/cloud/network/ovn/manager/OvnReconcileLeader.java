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

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;

/**
 * Shared leader election for the OVN plugin's periodic loops
 * ({@link OvnBgpReconcileTask}, {@link OvnPendingDeletionProcessor}): the Up
 * management server with the lowest {@code msid} is the leader; every other
 * node skips its tick. Without this every node in a multi-node management
 * cluster ran every pass — triplicate agent commands, concurrent NB rewrites,
 * and duplicate pending-deletion rows (TOCTOU on the sentinel promotion path).
 *
 * <p><b>Fail-closed</b>: when the {@code ms_host} Up view is empty or
 * unreadable NO node considers itself leader. Electing everyone in that state
 * would reproduce the exact duplicate-work storm this class exists to prevent,
 * and every gated loop is DB-driven anyway — if {@code ms_host} cannot be
 * read, the loop's own queries are equally unavailable. The skipped tick is
 * retried on the next interval.
 */
@Component
public class OvnReconcileLeader {

    private static final Logger LOGGER = LogManager.getLogger(OvnReconcileLeader.class);

    @Inject
    private ManagementServerHostDao msHostDao;

    /** Last observed leadership so transitions are logged exactly once. */
    private volatile Boolean lastLeaderState;

    public boolean isLeader() {
        boolean leader;
        try {
            final List<ManagementServerHostVO> up = msHostDao.listBy(ManagementServerHost.State.Up);
            if (up == null || up.isEmpty()) {
                logTransition(false, "ms_host Up view is empty");
                return false;
            }
            long min = Long.MAX_VALUE;
            for (final ManagementServerHostVO ms : up) {
                min = Math.min(min, ms.getMsid());
            }
            leader = ManagementServerNode.getManagementServerId() == min;
        } catch (RuntimeException re) {
            logTransition(false, "leader check failed: " + re.getMessage());
            return false;
        }
        logTransition(leader, null);
        return leader;
    }

    private void logTransition(final boolean leader, final String reason) {
        final Boolean previous = lastLeaderState;
        if (previous == null || previous.booleanValue() != leader) {
            lastLeaderState = leader;
            LOGGER.info("OvnReconcileLeader: this node is {} the OVN reconcile leader{}",
                    leader ? "now" : "no longer", reason == null ? "" : " (" + reason + ")");
        }
    }
}
