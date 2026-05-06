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
-- Schema upgrade 4.24.1.24 to 4.24.1.25.
-- Phase H.1: VF + vDPA lifecycle hardening.
--   * `sriov_vf_pool.last_seen`        DATETIME populated by agent inventory
--                                      sweeps. The mgmt-side reconciler flips
--                                      IN_USE rows to SUSPECT when this column
--                                      ages past `vf.pool.suspect.timeout.seconds`.
--   * Index on `last_seen`             so the periodic reconcile sweep is cheap
--                                      regardless of pool size.
--   * Configuration knobs              for the suspect timeout, the mgmt-side
--                                      reconcile cadence, and the agent-side
--                                      vdpa SF reconcile cadence.
-- All ALTER / CREATE / INSERT statements are idempotent.
-- ALTERs go through `cloud.IDEMPOTENT_ADD_COLUMN` because MySQL 8.0 does NOT
-- support the `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` extension; the
-- procedure swallows MySQL error 1060 (duplicate column).
--
-- Note: the Java enum `SriovVfPoolVO.State` already declares the new values
-- (SUSPECT, ORPHAN_MANUAL) — the column type is VARCHAR(32) so MySQL accepts
-- the new strings without further DDL.
--;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.sriov_vf_pool',
    'last_seen',
    "DATETIME NULL DEFAULT NULL COMMENT 'last time the agent confirmed this VF in its inventory; SUSPECT trigger source'"
);

-- Idempotent index creation. MySQL 8 supports `CREATE INDEX IF NOT EXISTS`
-- starting 8.0.x; `INFORMATION_SCHEMA.STATISTICS` lookup keeps the script
-- portable across patch versions where the syntax is missing.
SET @idx_exists := (
    SELECT COUNT(1) FROM `information_schema`.`STATISTICS`
     WHERE `table_schema` = 'cloud'
       AND `table_name`   = 'sriov_vf_pool'
       AND `index_name`   = 'i_sriov_vf_pool_last_seen'
);
SET @idx_sql := IF(@idx_exists = 0,
    'CREATE INDEX `i_sriov_vf_pool_last_seen` ON `cloud`.`sriov_vf_pool` (`last_seen`)',
    'SELECT 1');
PREPARE stmt FROM @idx_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Lifecycle hardening configuration knobs. Defaults are conservative: 15-min
-- SUSPECT timeout, 2-min mgmt reconcile cadence, 1-min agent vdpa sweep.
INSERT INTO `cloud`.`configuration` (
    `category`, `instance`, `component`, `name`, `value`,
    `description`, `default_value`, `updated`, `scope`
) VALUES
    ('Advanced', 'DEFAULT', 'management-server',
     'vf.pool.suspect.timeout.seconds', '900',
     'Mark VF pool entries as SUSPECT when last_seen is older than this many seconds (default 15 min).',
     '900', NOW(), 0),
    ('Advanced', 'DEFAULT', 'management-server',
     'vf.pool.reconcile.interval.seconds', '120',
     'How often the mgmt-side reconciler walks the VF pool against agent inventory.',
     '120', NOW(), 0),
    ('Advanced', 'DEFAULT', 'management-server',
     'vdpa.sf.reconcile.interval.seconds', '60',
     'How often each agent walks vdpa dev show against IntentReconciler state and reports orphans.',
     '60', NOW(), 0)
ON DUPLICATE KEY UPDATE `value` = `value`;
