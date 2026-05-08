-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

--;
-- Schema upgrade 4.24.1.25 to 4.24.1.26.
-- Phase H.2: OVN orphan cleanup hardening.
--   * `ovn_pending_deletion`   Persistent retry queue for OVN NB DB rows that
--                              failed to delete during network/VPC destroy flows.
--                              Background processor retries each row until success
--                              or `ovn.pending.deletion.max.attempts` is reached.
-- All CREATE / INSERT statements are idempotent (IF NOT EXISTS / ON DUPLICATE KEY).
--;

CREATE TABLE IF NOT EXISTS `cloud`.`ovn_pending_deletion` (
    `id`              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'internal row id',
    `uuid`            VARCHAR(40) NOT NULL COMMENT 'application-generated UUID, unique per queue entry',
    `controller_id`   BIGINT NOT NULL COMMENT 'OVN controller owning the target NB DB; 0 = resolve at retry time',
    `zone_id`         BIGINT NULL COMMENT 'CloudStack zone id; set when controller_id=0 sentinel is used',
    `kind`            VARCHAR(64) NOT NULL COMMENT 'OvnLogicalIdMapVO.Kind enum name of the target row',
    `ovn_uuid`        VARCHAR(40) NOT NULL COMMENT 'UUID of the OVN NB DB row to delete',
    `cs_id`           BIGINT NULL COMMENT 'CloudStack entity id for diagnostics (network id, vpc id, etc.)',
    `attempts`        INT NOT NULL DEFAULT 0 COMMENT 'number of deletion attempts made so far',
    `last_attempt_at` DATETIME NULL COMMENT 'timestamp of last attempt; NULL = never tried',
    `last_error`      VARCHAR(2048) NULL COMMENT 'error message from last failed attempt',
    `created`         DATETIME NOT NULL COMMENT 'when this row was enqueued',
    `removed`         DATETIME NULL COMMENT 'soft-delete: set to NOW() on successful deletion; NULL = still pending',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_ovn_pending_deletion_uuid` (`uuid`),
    KEY `idx_ovn_pending_ctrl_kind_removed` (`controller_id`, `kind`, `removed`),
    KEY `idx_ovn_pending_removed_last_attempt` (`removed`, `last_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Persistent retry queue for failed OVN NB DB row deletions';

-- Configuration knobs for the pending-deletion processor.
-- Registered via INSERT ... ON DUPLICATE KEY UPDATE so re-runs are safe.
INSERT INTO `cloud`.`configuration`
    (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`, `updated`, `scope`, `is_dynamic`)
VALUES
    ('Network', 'DEFAULT', 'OvnPendingDeletionProcessor',
     'ovn.pending.deletion.interval.seconds', NULL,
     'Interval in seconds between pending-deletion processor runs.',
     '60', NOW(), 1, 0)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `default_value` = VALUES(`default_value`);

INSERT INTO `cloud`.`configuration`
    (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`, `updated`, `scope`, `is_dynamic`)
VALUES
    ('Network', 'DEFAULT', 'OvnPendingDeletionProcessor',
     'ovn.pending.deletion.batch.size', NULL,
     'Maximum pending OVN deletions processed per controller per processor run.',
     '50', NOW(), 1, 0)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `default_value` = VALUES(`default_value`);

INSERT INTO `cloud`.`configuration`
    (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`, `updated`, `scope`, `is_dynamic`)
VALUES
    ('Network', 'DEFAULT', 'OvnPendingDeletionProcessor',
     'ovn.pending.deletion.max.attempts', NULL,
     'Maximum deletion attempts before a pending-deletion row is abandoned with an ALERT.',
     '20', NOW(), 1, 0)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `default_value` = VALUES(`default_value`);
