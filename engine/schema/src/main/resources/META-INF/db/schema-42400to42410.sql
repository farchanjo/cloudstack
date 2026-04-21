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
-- Schema upgrade from 4.24.0.0 to 4.24.1.0
--;
-- Drift reconciler: idempotently adds any missing columns/tables from the
-- 4.24.0.0 feature set (SR-IOV VF + SF/vDPA) that may have been introduced
-- after the original 4.23→4.24 migration was applied, and recreates
-- network_offering_view to expose hw_offload_enabled in list API responses.
--;

-- ============================================================
-- SR-IOV VF pool tables + columns (idempotent)
-- ============================================================

CREATE TABLE IF NOT EXISTS `cloud`.`sriov_vf_pool` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `host_id` bigint unsigned NOT NULL,
  `pci_address` varchar(17) NOT NULL COMMENT 'PCI address of the VF, format: dddd:bb:ss.f',
  `pf_name` varchar(32) NOT NULL COMMENT 'Physical Function netdev name (e.g. dx6p0)',
  `representor_name` varchar(32) NULL COMMENT 'Switchdev representor for this VF',
  `state` varchar(32) NOT NULL DEFAULT 'FREE',
  `allocated_to_nic_id` bigint unsigned NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sriov_vf_pool__host_pci` (`host_id`, `pci_address`),
  UNIQUE KEY `uk_sriov_vf_pool__uuid` (`uuid`),
  KEY `idx_sriov_vf_pool__state` (`state`),
  KEY `idx_sriov_vf_pool__host_state` (`host_id`, `state`),
  CONSTRAINT `fk_sriov_vf_pool__host_id` FOREIGN KEY (`host_id`)
    REFERENCES `cloud`.`host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sriov_vf_pool__nic_id` FOREIGN KEY (`allocated_to_nic_id`)
    REFERENCES `cloud`.`nics`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `vf_pci_address` varchar(17) NULL COMMENT "SR-IOV VF PCI address. NULL = NIC uses traditional TAP/bridge.";

ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `vf_pool_id` bigint unsigned NULL COMMENT "Soft reference to sriov_vf_pool.id.";

ALTER TABLE `cloud`.`network_offerings`
    ADD COLUMN IF NOT EXISTS `hw_offload_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT "Enable hardware TC flower offload via SR-IOV VF passthrough. 0=SW VR, 1=HW VR.";

ALTER TABLE `cloud`.`domain_router`
    ADD COLUMN IF NOT EXISTS `hw_offload_active` tinyint(1) NOT NULL DEFAULT 0 COMMENT "Tracks if this VR was provisioned with HW offload (VFs).";

CREATE TABLE IF NOT EXISTS `cloud`.`vr_hw_offload_intent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `router_id` bigint unsigned NOT NULL,
  `host_id` bigint unsigned NOT NULL,
  `intent_json` mediumtext NOT NULL,
  `intent_version` bigint unsigned NOT NULL DEFAULT 1,
  `received` datetime NOT NULL,
  `applied` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vr_hw_offload_intent__router_id` (`router_id`),
  KEY `idx_vr_hw_offload_intent__host_id` (`host_id`),
  CONSTRAINT `fk_vr_hw_offload_intent__router_id` FOREIGN KEY (`router_id`)
    REFERENCES `cloud`.`domain_router`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vr_hw_offload_intent__host_id` FOREIGN KEY (`host_id`)
    REFERENCES `cloud`.`host`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `cloud`.`configuration`
  (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`)
VALUES
  ('Advanced', 'DEFAULT', 'management-server',
   'router.template.kvm.hwoffload', NULL,
   'KVM systemvm template UUID for VRs with hardware offload. NULL = use default router.template.kvm.', NULL),
  ('Advanced', 'DEFAULT', 'management-server',
   'vr.hw.offload.enabled', 'false',
   'Master toggle for VR hardware offload feature.', 'false'),
  ('Advanced', 'DEFAULT', 'management-server',
   'vr.hw.offload.intent.api.port', '9999',
   'TCP port the host agent listens on (cloud0 link-local) for VR HW offload intent API.', '9999');

-- ============================================================
-- SR-IOV SF pool + vDPA tables + columns (idempotent)
-- ============================================================

CREATE TABLE IF NOT EXISTS `cloud`.`sriov_sf_pool` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `host_id` bigint unsigned NOT NULL,
  `pf_index` int NOT NULL,
  `sf_index` int NOT NULL,
  `devlink_port_handle` varchar(64) NULL,
  `sf_netdev_name` varchar(32) NULL,
  `representor_name` varchar(32) NULL,
  `vdpa_device` varchar(64) NULL,
  `state` varchar(32) NOT NULL DEFAULT 'FREE',
  `allocated_to_nic_id` bigint unsigned NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sriov_sf_pool__host_pf_sf` (`host_id`, `pf_index`, `sf_index`),
  UNIQUE KEY `uk_sriov_sf_pool__uuid` (`uuid`),
  KEY `idx_sriov_sf_pool__state` (`state`),
  KEY `idx_sriov_sf_pool__host_state` (`host_id`, `state`),
  CONSTRAINT `fk_sriov_sf_pool__host_id` FOREIGN KEY (`host_id`)
    REFERENCES `cloud`.`host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sriov_sf_pool__nic_id` FOREIGN KEY (`allocated_to_nic_id`)
    REFERENCES `cloud`.`nics`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `sf_pool_id` bigint unsigned NULL COMMENT "Soft reference to sriov_sf_pool.id.";

ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `vdpa_device` varchar(64) NULL COMMENT "vDPA device path assigned to this NIC.";

ALTER TABLE `cloud`.`network_offerings`
    ADD COLUMN IF NOT EXISTS `sf_vdpa_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT "Enable SR-IOV Sub-Function with vDPA.";

INSERT IGNORE INTO `cloud`.`configuration`
  (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`)
VALUES
  ('Advanced', 'DEFAULT', 'management-server',
   'vm.sf.vdpa.enabled', 'false',
   'Master toggle for SR-IOV Sub-Function with vDPA support.', 'false'),
  ('Advanced', 'DEFAULT', 'management-server',
   'vm.sf.pool.size.per.host', '128',
   'Maximum number of Sub-Functions to pre-provision per host via devlink.', '128');

-- ============================================================
-- Recreate network_offering_view to expose hw_offload_enabled
-- ============================================================

DROP VIEW IF EXISTS `cloud`.`network_offering_view`;

CREATE VIEW `cloud`.`network_offering_view` AS
SELECT
    `network_offerings`.`id` AS `id`,
    `network_offerings`.`uuid` AS `uuid`,
    `network_offerings`.`name` AS `name`,
    `network_offerings`.`unique_name` AS `unique_name`,
    `network_offerings`.`display_text` AS `display_text`,
    `network_offerings`.`nw_rate` AS `nw_rate`,
    `network_offerings`.`mc_rate` AS `mc_rate`,
    `network_offerings`.`traffic_type` AS `traffic_type`,
    `network_offerings`.`tags` AS `tags`,
    `network_offerings`.`system_only` AS `system_only`,
    `network_offerings`.`specify_vlan` AS `specify_vlan`,
    `network_offerings`.`service_offering_id` AS `service_offering_id`,
    `network_offerings`.`conserve_mode` AS `conserve_mode`,
    `network_offerings`.`created` AS `created`,
    `network_offerings`.`removed` AS `removed`,
    `network_offerings`.`default` AS `default`,
    `network_offerings`.`availability` AS `availability`,
    `network_offerings`.`dedicated_lb_service` AS `dedicated_lb_service`,
    `network_offerings`.`shared_source_nat_service` AS `shared_source_nat_service`,
    `network_offerings`.`sort_key` AS `sort_key`,
    `network_offerings`.`redundant_router_service` AS `redundant_router_service`,
    `network_offerings`.`state` AS `state`,
    `network_offerings`.`guest_type` AS `guest_type`,
    `network_offerings`.`elastic_ip_service` AS `elastic_ip_service`,
    `network_offerings`.`eip_associate_public_ip` AS `eip_associate_public_ip`,
    `network_offerings`.`elastic_lb_service` AS `elastic_lb_service`,
    `network_offerings`.`specify_ip_ranges` AS `specify_ip_ranges`,
    `network_offerings`.`inline` AS `inline`,
    `network_offerings`.`is_persistent` AS `is_persistent`,
    `network_offerings`.`internal_lb` AS `internal_lb`,
    `network_offerings`.`public_lb` AS `public_lb`,
    `network_offerings`.`egress_default_policy` AS `egress_default_policy`,
    `network_offerings`.`concurrent_connections` AS `concurrent_connections`,
    `network_offerings`.`keep_alive_enabled` AS `keep_alive_enabled`,
    `network_offerings`.`supports_streched_l2` AS `supports_streched_l2`,
    `network_offerings`.`supports_public_access` AS `supports_public_access`,
    `network_offerings`.`supports_vm_autoscaling` AS `supports_vm_autoscaling`,
    `network_offerings`.`for_vpc` AS `for_vpc`,
    `network_offerings`.`network_mode` AS `network_mode`,
    `network_offerings`.`service_package_id` AS `service_package_id`,
    `network_offerings`.`routing_mode` AS `routing_mode`,
    `network_offerings`.`specify_as_number` AS `specify_as_number`,
    `network_offerings`.`hw_offload_enabled` AS `hw_offload_enabled`,
    GROUP_CONCAT(DISTINCT(domain.id)) AS domain_id,
    GROUP_CONCAT(DISTINCT(domain.uuid)) AS domain_uuid,
    GROUP_CONCAT(DISTINCT(domain.name)) AS domain_name,
    GROUP_CONCAT(DISTINCT(domain.path)) AS domain_path,
    GROUP_CONCAT(DISTINCT(zone.id)) AS zone_id,
    GROUP_CONCAT(DISTINCT(zone.uuid)) AS zone_uuid,
    GROUP_CONCAT(DISTINCT(zone.name)) AS zone_name,
    `offering_details`.value AS internet_protocol
FROM
    `cloud`.`network_offerings`
        LEFT JOIN
    `cloud`.`domain` AS `domain` ON `domain`.id IN (SELECT value from `network_offering_details` where `name` = 'domainid' and `network_offering_id` = `network_offerings`.`id`)
        LEFT JOIN
    `cloud`.`data_center` AS `zone` ON `zone`.`id` IN (SELECT value from `network_offering_details` where `name` = 'zoneid' and `network_offering_id` = `network_offerings`.`id`)
        LEFT JOIN
    `cloud`.`network_offering_details` AS `offering_details` ON `offering_details`.`network_offering_id` = `network_offerings`.`id` AND `offering_details`.`name`='internetProtocol'
GROUP BY
    `network_offerings`.`id`;
