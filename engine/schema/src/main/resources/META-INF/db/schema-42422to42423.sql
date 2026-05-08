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
-- Schema upgrade 4.24.1.22 to 4.24.1.23.
-- Re-introduces vDPA orchestration as a CloudStack offering. Originally added
-- in fork prototype, retired by schema-42410to42411.sql.
--
-- All ALTER statements go through `cloud.IDEMPOTENT_ADD_COLUMN`, which swallows
-- MySQL error 1060 (duplicate column). MySQL 8.0 does NOT support the
-- `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` extension (MariaDB only), so the
-- procedure is the portable form. Defined in
-- `META-INF/db/procedures/cloud.idempotent_add_column.sql`, loaded by
-- DatabaseUpgradeChecker before any upgrade script runs.
--;

-- Re-add the vDPA opt-in flag on network_offerings, immediately after the
-- existing `hw_offload_enabled` column so the two fork-specific flags sit
-- side-by-side in the row layout.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.network_offerings',
    'vdpa_enabled',
    "TINYINT(1) NOT NULL DEFAULT 0 AFTER `hw_offload_enabled`"
);

-- Re-add per-NIC vDPA fields. The agent populates vdpa_device at plug time
-- (host-side /dev/vhost-vdpa-N path). Mgmt server reads it back for state
-- queries and for live-migration patching of the destination domain XML.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.nics',
    'vdpa_device',
    "VARCHAR(64) NULL DEFAULT NULL COMMENT 'host-side /dev/vhost-vdpa-N path for this nic'"
);

-- Pool-side bookkeeping: record which VF is currently bound as a vDPA
-- mgmt device, with what name, and which character device the agent created
-- on top of it. Different from the SR-IOV passthrough fields that already
-- exist on sriov_vf_pool.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.sriov_vf_pool',
    'vdpa_kind',
    "VARCHAR(16) NOT NULL DEFAULT 'PASSTHROUGH' COMMENT 'PASSTHROUGH | VDPA'"
);
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.sriov_vf_pool',
    'vdpa_name',
    "VARCHAR(64) NULL DEFAULT NULL COMMENT 'vdpa dev name (e.g. vdpa-vmA2) when vdpa_kind=VDPA'"
);
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.sriov_vf_pool',
    'vdpa_device',
    "VARCHAR(64) NULL DEFAULT NULL COMMENT '/dev/vhost-vdpa-N when vdpa_kind=VDPA'"
);

-- Master toggles via the configuration table. ON DUPLICATE KEY UPDATE keeps
-- existing operator overrides untouched on a re-run; only fresh installs
-- pick up the defaults below.
INSERT INTO `cloud`.`configuration` (
    `category`, `instance`, `component`, `name`, `value`,
    `description`, `default_value`, `updated`, `scope`
) VALUES
    ('Advanced', 'DEFAULT', 'management-server',
     'vr.vdpa.enabled', 'false',
     'Enable vDPA for VPC virtual routers (and any user VM whose offering opts in).',
     'false', NOW(), 0),
    ('Advanced', 'DEFAULT', 'management-server',
     'vm.vdpa.enabled', 'false',
     'Master switch for user-VM vDPA path. Per-offering vdpa_enabled flag still required.',
     'false', NOW(), 0),
    ('Advanced', 'DEFAULT', 'management-server',
     'vm.vdpa.max_vqs', '33',
     '16 RX + 16 TX + 1 control queue. Override per-host with hwoffload.vdpa.max_vqs.',
     '33', NOW(), 0)
ON DUPLICATE KEY UPDATE `value` = `value`;
