-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.

-- Durable, normalized cold VF/vDPA migration checkpoints.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_phase',
    'varchar(32) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_mode',
    'varchar(32) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_recovery_lease_token',
    'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_generation',
    'bigint unsigned NOT NULL DEFAULT 0');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_vm_uuid',
    'varchar(40) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_source_host_id',
    'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_destination_host_id',
    'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_recovery_lease_owner',
    'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_recovery_lease_expires_at',
    'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_recovery_lease_version',
    'bigint unsigned NOT NULL DEFAULT 0');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_work', 'migration_recovery_lease_heartbeat',
    'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_CREATE_UNIQUE_INDEX`(
    'uk_op_it_work_migration_generation', 'cloud.op_it_work',
    '(`instance_id`, `migration_mode`, `migration_generation`)'
);

CREATE TABLE IF NOT EXISTS `cloud`.`op_it_migration_nic` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `work_id` varchar(40) NOT NULL,
    `generation` bigint unsigned NOT NULL,
    `nic_id` bigint unsigned NOT NULL,
    `lsp_id` varchar(128) NOT NULL,
    `source_vf_pool_id` bigint unsigned DEFAULT NULL,
    `destination_vf_pool_id` bigint unsigned DEFAULT NULL,
    `source_bdf` varchar(64) DEFAULT NULL,
    `destination_bdf` varchar(64) DEFAULT NULL,
    `source_vdpa_name` varchar(128) DEFAULT NULL,
    `destination_vdpa_name` varchar(128) DEFAULT NULL,
    `source_vdpa_device` varchar(128) DEFAULT NULL,
    `destination_vdpa_device` varchar(128) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_op_it_migration_nic_identity` (`work_id`, `generation`, `nic_id`),
    KEY `i_op_it_migration_nic_work` (`work_id`, `generation`),
    CONSTRAINT `fk_op_it_migration_nic_work` FOREIGN KEY (`work_id`)
        REFERENCES `cloud`.`op_it_work` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'vm_id', 'bigint unsigned NOT NULL DEFAULT 0');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'vm_uuid', 'varchar(40) NOT NULL DEFAULT ''''');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'nic_uuid', 'varchar(40) NOT NULL DEFAULT ''''');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'nic_kind', 'varchar(32) NOT NULL DEFAULT ''UNKNOWN''');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'mac_address', 'varchar(17) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'vlan', 'varchar(32) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_host_id', 'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_host_id', 'bigint unsigned DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_driver', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_driver', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_representor', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_representor', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_representor_phys_port', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_representor_phys_port', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_representor_bdf', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_representor_bdf', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_pf', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_pf', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_vf_id', 'int DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_vf_id', 'int DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'ovs_bridge', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'ovs_port', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'ovs_interface', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'ovs_external_ids', 'text DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_bridge_uuid', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_bridge_uuid', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_port_uuid', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_port_uuid', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_interface_uuid', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_interface_uuid', 'varchar(64) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'ovn_port_binding', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'ovn_chassis', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'libvirt_alias', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'libvirt_target', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'libvirt_source', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'libvirt_type', 'varchar(32) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'libvirt_model', 'varchar(32) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'tc_expectation', 'text DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'fdb_expectation', 'text DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'offload_expectation', 'text DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'identity_availability', 'varchar(32) NOT NULL DEFAULT ''AVAILABLE''');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'terminal', 'tinyint(1) NOT NULL DEFAULT 0');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_bridge', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_bridge', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_port', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_port', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_interface', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_interface', 'varchar(128) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'source_ovs_external_ids', 'text DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.op_it_migration_nic', 'destination_ovs_external_ids', 'text DEFAULT NULL');
