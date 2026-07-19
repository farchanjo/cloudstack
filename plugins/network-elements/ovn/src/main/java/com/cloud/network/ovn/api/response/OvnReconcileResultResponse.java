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
package com.cloud.network.ovn.api.response;

import java.util.Map;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

/**
 * JSON shape returned by {@code runOvnReconciler}: per-table counts of
 * orphan NB rows + stale mapping rows the pass found / cleaned, plus the
 * dry-run flag so the caller can tell whether mutation actually happened.
 */
public class OvnReconcileResultResponse extends BaseResponse {

    @SerializedName("dryrun")
    @Param(description = "When true, the reconciler did not mutate any NB / DAO state — counts are advisory")
    private boolean dryRun;

    @SerializedName("totalorphans")
    @Param(description = "Sum of orphan NB rows across every walked table")
    private int totalOrphans;

    @SerializedName("totalstalemappings")
    @Param(description = "Sum of mapping rows pointing at NB UUIDs the OVN NB DB no longer holds")
    private int totalStaleMappings;

    @SerializedName("orphansbytable")
    @Param(description = "Per-table count of orphan NB rows")
    private Map<String, Integer> orphansByTable;

    @SerializedName("stalemappingsbytable")
    @Param(description = "Per-table count of stale mapping rows")
    private Map<String, Integer> staleMappingsByTable;

    /** Per-zone sweep ACK / status counters (hairpin-swept, tc-policy-swept,
     *  scoped force-SNAT action, scoped OVS_POLICY host sweep). Surfaced
     *  for operator visibility but do NOT contribute to {totalorphans}.
     *
     *  <p><b>Zero-value contract:</b> entries are inserted ONLY for
     *  true/1 values. Absent keys mean false/zero/distributed:
     *  <ul>
     *    <li>Absent {@code Logical_Router_ForceSnat:topology} = distributed</li>
     *    <li>Absent {@code Logical_Router_ForceSnat:applied} = no write performed (dry-run or no-change)</li>
     *  </ul> */
    @SerializedName("acksbytable")
    @Param(description = "Per-zone sweep ACK/status counters (hairpin/tc-policy/force-SNAT); "
            + "do not contribute to totalorphans. Zero-value contract: absent means false/zero/distributed.")
    private Map<String, Integer> acksByTable;

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(final boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getTotalOrphans() {
        return totalOrphans;
    }

    public void setTotalOrphans(final int totalOrphans) {
        this.totalOrphans = totalOrphans;
    }

    public int getTotalStaleMappings() {
        return totalStaleMappings;
    }

    public void setTotalStaleMappings(final int totalStaleMappings) {
        this.totalStaleMappings = totalStaleMappings;
    }

    public Map<String, Integer> getOrphansByTable() {
        return orphansByTable;
    }

    public void setOrphansByTable(final Map<String, Integer> orphansByTable) {
        this.orphansByTable = orphansByTable;
    }

    public Map<String, Integer> getStaleMappingsByTable() {
        return staleMappingsByTable;
    }

    public void setStaleMappingsByTable(final Map<String, Integer> staleMappingsByTable) {
        this.staleMappingsByTable = staleMappingsByTable;
    }

    public Map<String, Integer> getAcksByTable() {
        return acksByTable;
    }

    public void setAcksByTable(final Map<String, Integer> acksByTable) {
        this.acksByTable = acksByTable;
    }
}
