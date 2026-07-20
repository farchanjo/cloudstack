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
package com.cloud.vm;

import java.util.List;
import java.util.Optional;

import com.cloud.utils.db.GenericDao;
import com.cloud.vm.ItWorkVO.Step;
import com.cloud.vm.VirtualMachine.State;

public interface ItWorkDao extends GenericDao<ItWorkVO, String> {
    final class MigrationLeaseClaim {
        private final String workId;
        private final long generation;
        private final Long oldOwner;
        private final String oldToken;
        private final long oldVersion;
        private final Long oldExpiry;
        private final long newOwner;
        private final String newToken;
        private final long newVersion;
        private final long newExpiry;

        MigrationLeaseClaim(final String workId, final long generation, final Long oldOwner, final String oldToken,
                final long oldVersion, final Long oldExpiry, final long newOwner, final String newToken,
                final long newVersion, final long newExpiry) {
            this.workId = workId; this.generation = generation; this.oldOwner = oldOwner; this.oldToken = oldToken;
            this.oldVersion = oldVersion; this.oldExpiry = oldExpiry; this.newOwner = newOwner; this.newToken = newToken;
            this.newVersion = newVersion; this.newExpiry = newExpiry;
        }
        public String workId() { return workId; }
        public long generation() { return generation; }
        public Long oldOwner() { return oldOwner; }
        public String oldToken() { return oldToken; }
        public long oldVersion() { return oldVersion; }
        public Long oldExpiry() { return oldExpiry; }
        public long newOwner() { return newOwner; }
        public String newToken() { return newToken; }
        public long newVersion() { return newVersion; }
        public long newExpiry() { return newExpiry; }
    }
    /**
     * find a work item based on the instanceId and the state.
     *
     * @param instanceId vm instance id
     * @param state state
     * @return ItWorkVO if found; null if not.
     */
    ItWorkVO findByOutstandingWork(long instanceId, State state);

    /**
     * cleanup rows that are either Done or Cancelled and been that way
     * for at least wait time.
     */
    void cleanup(long wait);

    boolean updateStep(ItWorkVO work, Step step);

    List<ItWorkVO> listWorkInProgressFor(long nodeId);
    int expungeByVmList(List<Long> vmIds, Long batchSize);

    /**
     * Fenced checkpoint update for cold VF/vDPA relocation.  The update is
     * accepted only for the current generation and never moves a checkpoint
     * backwards.
     */
    boolean advanceMigrationPhase(String id, long generation, ItWorkVO.MigrationPhase expectedPhase,
            long ownerManagementId, String leaseToken, long leaseVersion, long leaseExpiry,
            ItWorkVO.MigrationPhase nextPhase);

    /** Mark an interrupted migration recoverable without claiming cleanup. */
    boolean markMigrationManualIntervention(ItWorkVO work);

    ItWorkVO findNonterminalColdMigrationForVm(long vmId);

    long nextColdMigrationGeneration(long vmId);

    List<ItWorkVO> listNonterminalColdMigrations();

    boolean claimMigrationLease(ItWorkVO work, long owner, String token, Long expectedOwner,
            String expectedToken, Long expectedExpiry, long now, long expiresAt);
    Optional<MigrationLeaseClaim> takeOverExpiredMigrationLease(String workId, long generation,
            long newOwner, String newToken, long now, long expiresAt);
    boolean terminalizeMigration(String id, long generation, long owner, String token, long version);
    boolean renewMigrationLease(ItWorkVO work, long owner, String token, long version,
            long now, long expiresAt);
    boolean releaseMigrationLease(ItWorkVO work, long owner, String token, long version);

    boolean updateMigrationWorkLeased(ItWorkVO work, long owner, String token, long version);

    boolean updateMigrationStepLeased(ItWorkVO work, Step expectedStep, Step nextStep,
            long owner, String token, long version);

}
