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
-- Schema upgrade from 4.24.1.0 to 4.24.1.1
--;
-- Drops SR-IOV Sub-Function (SF) artifacts: ConnectX-6 Dx hardware does NOT
-- support SF+vDPA (VIRTIO_NET_EMULATION unavailable on adapter PSID
-- MT_0000000359). POC proved VF+vDPA is the viable path instead.
-- Renames network_offerings.sf_vdpa_enabled -> vdpa_enabled (now means
-- VF+vDPA on the VR guest NIC). nics.vdpa_device is preserved and reused
-- for the VF+vDPA path.
-- All changes are idempotent and backward-compatible on clusters that
-- never populated sriov_sf_pool (verified: row count == 0 in LA prod).
--;

-- ============================================================
-- Drop SF pool + SF NIC reference (never populated on CX-6 Dx)
-- ============================================================

-- nics.sf_pool_id is a "soft reference" (no explicit FK); safe to drop directly.
ALTER TABLE `cloud`.`nics`
    DROP COLUMN IF EXISTS `sf_pool_id`;

-- sriov_sf_pool has FKs only OUTBOUND (host_id, allocated_to_nic_id). No other
-- table references it, so a plain DROP TABLE is safe.
DROP TABLE IF EXISTS `cloud`.`sriov_sf_pool`;

-- ============================================================
-- Rename offering flag sf_vdpa_enabled -> vdpa_enabled
-- ============================================================
-- Idempotent rename: only rename if the old column exists AND the new one
-- doesn't (to tolerate re-runs on already-migrated clusters).

SET @needs_rename := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='network_offerings'
       AND COLUMN_NAME='sf_vdpa_enabled'
);
SET @has_new := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='network_offerings'
       AND COLUMN_NAME='vdpa_enabled'
);

SET @ddl := IF(@needs_rename=1 AND @has_new=0,
    'ALTER TABLE `cloud`.`network_offerings` CHANGE COLUMN `sf_vdpa_enabled` `vdpa_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT "Enable VF+vDPA on the VR guest NIC (hot-plug + future live-migration). 0=disabled, 1=enabled."',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- If for any reason the old column was already dropped but the new one
-- never created (partial prior run), ensure the column exists:
ALTER TABLE `cloud`.`network_offerings`
    ADD COLUMN IF NOT EXISTS `vdpa_enabled` tinyint(1) NOT NULL DEFAULT 0
    COMMENT 'Enable VF+vDPA on the VR guest NIC (hot-plug + future live-migration). 0=disabled, 1=enabled.';

-- ============================================================
-- Extend sriov_vf_pool with vDPA binding bookkeeping (idempotent)
-- ============================================================
-- When a VF is promoted to vDPA we remember the vhost-vdpa chardev and
-- the vDPA device name so release can issue the matching DestroyVdpa.

ALTER TABLE `cloud`.`sriov_vf_pool`
    ADD COLUMN IF NOT EXISTS `vdpa_device` varchar(64) NULL
    COMMENT 'vhost-vdpa chardev path bound to this VF, e.g. /dev/vhost-vdpa-0. NULL when VF is hostdev-only.';

ALTER TABLE `cloud`.`sriov_vf_pool`
    ADD COLUMN IF NOT EXISTS `vdpa_name` varchar(64) NULL
    COMMENT 'vDPA netlink device name (vdpa dev add name ...). NULL when VF is hostdev-only.';

-- ============================================================
-- Retire SF global config, introduce VF+vDPA config
-- ============================================================

DELETE FROM `cloud`.`configuration`
 WHERE `name` IN ('vm.sf.vdpa.enabled', 'vm.sf.pool.size.per.host');

INSERT IGNORE INTO `cloud`.`configuration`
  (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`)
VALUES
  ('Advanced', 'DEFAULT', 'management-server',
   'vr.vdpa.enabled', 'false',
   'Master toggle for VR VF+vDPA guest NIC support (requires hw_offload_enabled offering + vdpa_enabled offering flag).', 'false');

-- ============================================================
-- Recreate network_offering_view to expose vdpa_enabled
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
    `network_offerings`.`vdpa_enabled` AS `vdpa_enabled`,
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
