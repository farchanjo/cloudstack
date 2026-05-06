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
-- Schema upgrade 4.24.1.23 to 4.24.1.24.
-- Introduces the OVN network-element plugin (Phase I).
--   * `ovn_controller`          one row per OVN deployment per zone (the
--                               admin records the NB endpoints; the plugin
--                               picks the live one from the comma list).
--   * `ovn_chassis_map`         CloudStack `host` <-> OVN `chassis` UUID
--                               (`Open_vSwitch:external_ids:system-id` on
--                               the agent), populated at host registration.
--   * `ovn_logical_id_map`      CloudStack id (`VPC|NETWORK|NIC|STATIC_NAT
--                               |SOURCE_NAT`) <-> OVN UUID, so the plugin
--                               can reverse-resolve at delete time without
--                               re-walking the NB DB.
--   * `physical_network_service_providers`  one `Ovn` row per existing
--                               `physical_network`. Created `Disabled` so
--                               adopting the plugin is an explicit operator
--                               action; idempotent on re-run.
-- All DDL is idempotent (`IF NOT EXISTS`, `WHERE NOT EXISTS`).
--;

-- 1) OVN controller registration table.
CREATE TABLE IF NOT EXISTS `cloud`.`ovn_controller` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `uuid` VARCHAR(40) NOT NULL,
    `zone_id` BIGINT UNSIGNED NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `nb_endpoints` VARCHAR(2048) NOT NULL COMMENT 'comma-separated tcp:host:6641 list; client picks the live one',
    `sb_endpoints` VARCHAR(2048) DEFAULT NULL COMMENT 'comma-separated tcp:host:6642 list (read-only diagnostics)',
    `created` DATETIME NOT NULL,
    `removed` DATETIME DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uc_ovn_controller_uuid` (`uuid`),
    KEY `i_ovn_controller_zone_id` (`zone_id`),
    CONSTRAINT `fk_ovn_controller_zone_id` FOREIGN KEY (`zone_id`)
        REFERENCES `data_center` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) Host <-> chassis mapping. One row per host. The chassis UUID (system-id)
-- is the value the agent reports from `ovs-vsctl get open_vswitch . external_ids:system-id`.
CREATE TABLE IF NOT EXISTS `cloud`.`ovn_chassis_map` (
    `host_id` BIGINT UNSIGNED NOT NULL,
    `controller_id` BIGINT UNSIGNED NOT NULL,
    `chassis_uuid` VARCHAR(64) NOT NULL,
    `created` DATETIME NOT NULL,
    PRIMARY KEY (`host_id`),
    UNIQUE KEY `uc_ovn_chassis_uuid` (`chassis_uuid`),
    KEY `i_ovn_chassis_map_controller_id` (`controller_id`),
    CONSTRAINT `fk_ovn_chassis_map_host_id` FOREIGN KEY (`host_id`)
        REFERENCES `host` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ovn_chassis_map_controller_id` FOREIGN KEY (`controller_id`)
        REFERENCES `ovn_controller` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) CloudStack id <-> OVN UUID reverse-lookup table. `cs_kind` namespaces the
-- CloudStack id space so a `VPC` id never collides with a `NETWORK` id. The
-- `(cs_kind, cs_id, controller_id)` triple is unique per OVN deployment.
CREATE TABLE IF NOT EXISTS `cloud`.`ovn_logical_id_map` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `cs_kind` VARCHAR(32) NOT NULL COMMENT 'VPC | NETWORK | NIC | STATIC_NAT | SOURCE_NAT',
    `cs_id` BIGINT UNSIGNED NOT NULL,
    `controller_id` BIGINT UNSIGNED NOT NULL,
    `ovn_uuid` VARCHAR(64) NOT NULL,
    `ovn_name` VARCHAR(255) DEFAULT NULL,
    `created` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uc_ovn_lim_cs` (`cs_kind`, `cs_id`, `controller_id`),
    KEY `i_ovn_lim_uuid` (`ovn_uuid`),
    CONSTRAINT `fk_ovn_lim_controller_id` FOREIGN KEY (`controller_id`)
        REFERENCES `ovn_controller` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) Provider catalog: register `Ovn` as a network service provider on every
-- existing physical network. `Disabled` so the plugin is opt-in. The
-- `WHERE NOT EXISTS` predicate keeps the insert idempotent on re-run.
INSERT INTO `cloud`.`physical_network_service_providers` (
    `uuid`, `physical_network_id`, `provider_name`,
    `state`, `destination_physical_network_id`, `vpn_service_provided`,
    `dhcp_service_provided`, `dns_service_provided`, `gateway_service_provided`,
    `firewall_service_provided`, `source_nat_service_provided`,
    `load_balance_service_provided`, `static_nat_service_provided`,
    `port_forwarding_service_provided`, `user_data_service_provided`,
    `security_group_service_provided`, `networkacl_service_provided`
)
SELECT UUID(), `physical_network`.`id`, 'Ovn', 'Disabled', NULL, 0,
       1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1
  FROM `cloud`.`physical_network`
 WHERE NOT EXISTS (
    SELECT 1 FROM `cloud`.`physical_network_service_providers` `p2`
     WHERE `p2`.`physical_network_id` = `physical_network`.`id`
       AND `p2`.`provider_name` = 'Ovn'
 );
