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
package com.cloud.network.ovn.dao;

import java.util.List;

import com.cloud.utils.db.GenericDao;

/**
 * DAO for {@link OvnPendingDeletionVO}. Manages the persistent retry queue
 * for OVN NB DB rows that failed to delete during network/VPC destroy flows.
 */
public interface OvnPendingDeletionDao extends GenericDao<OvnPendingDeletionVO, Long> {

    /**
     * List up to {@code limit} rows that are still pending (removed IS NULL)
     * for the given controller, ordered so rows never attempted come first
     * (NULLS FIRST on last_attempt_at), then oldest attempt.
     *
     * <p>Sentinel rows ({@code controller_id = 0}) are intentionally excluded
     * from this query — they are returned exclusively by
     * {@link #findAllSentinels(int)} and resolved by a dedicated phase in
     * {@link com.cloud.network.ovn.manager.OvnPendingDeletionProcessor}.
     *
     * @param controllerId the controller whose NB DB will be targeted (must not be 0)
     * @param limit        maximum rows to return per batch
     */
    List<OvnPendingDeletionVO> findPendingByController(long controllerId, int limit);

    /**
     * List pending rows that were queued with the zone-sentinel
     * ({@code controller_id = 0}, {@code zone_id = zoneId}).
     */
    List<OvnPendingDeletionVO> findPendingSentinelByZone(long zoneId, int limit);

    /**
     * List up to {@code limit} pending rows across ALL zones that carry the
     * controller-sentinel ({@code controller_id = 0}). Used by the processor
     * to process sentinels independently of whether a controller is already
     * known in the current tick's controller list.
     */
    List<OvnPendingDeletionVO> findAllSentinels(int limit);

    /**
     * Mark a row as failed: increment attempt counter, record timestamp and
     * error message. Rows are updated in place without removing them.
     */
    void markFailed(long id, String error);

    /**
     * Soft-delete: set {@code removed = NOW()} to indicate the deletion
     * succeeded. Row remains for forensics.
     */
    void markSucceeded(long id);

    /**
     * Check whether a row with the given OVN UUID and kind is already queued
     * (i.e. exists with {@code removed IS NULL}). Prevents duplicate enqueue.
     */
    boolean isPendingByOvnUuid(String ovnUuid, String kind);

    /**
     * Soft-delete the pending row matched by OVN UUID + kind. Called after a
     * synchronous NB delete succeeds so the processor does not retry a no-op.
     * No-op when no pending row exists for the given UUID+kind.
     */
    void markSucceededByOvnUuid(String ovnUuid, String kind);
}
