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
-- Schema upgrade 4.24.1.27 to 4.24.1.28.
-- Sprint 1 public IPv6 IPAM inventory (Option B):
--   NEW table user_public_ipv6_address — public VIP/FIP inventory independent of
--   guest user_ipv6_address (SLAAC) and of user_ip_address (IPv4 long/Ip).
-- Free pool host ids are documented as ::1000–::ffff inside ovn.public.ipv6.prefix;
-- transport band ::0–::255 is reserved and never drawn by new allocations.
--;

CREATE TABLE IF NOT EXISTS `cloud`.`user_public_ipv6_address` (
    `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `uuid`                  VARCHAR(40) NOT NULL,
    `public_ipv6_address`   VARCHAR(50) NOT NULL COMMENT 'Canonical compressed public IPv6 address string',
    `data_center_id`        BIGINT UNSIGNED NOT NULL COMMENT 'Zone owning this address',
    `account_id`            BIGINT UNSIGNED NULL COMMENT 'Owning account when allocated; NULL when Free',
    `domain_id`             BIGINT UNSIGNED NULL COMMENT 'Owning domain when allocated; NULL when Free',
    `state`                 VARCHAR(32) NOT NULL DEFAULT 'Free' COMMENT 'Free | Allocating | Allocated | Releasing',
    `network_id`            BIGINT UNSIGNED NULL COMMENT 'Optional associated guest/public network',
    `vpc_id`                BIGINT UNSIGNED NULL COMMENT 'Optional associated VPC',
    `allocated`             DATETIME NULL COMMENT 'When the address was allocated to an account',
    `is_system`             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 if system-owned (e.g. grandfather / infra)',
    `display`               TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1 if visible to end users',
    `created`               DATETIME NULL,
    `removed`               DATETIME NULL COMMENT 'Soft-delete timestamp; NULL = active',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uc_user_public_ipv6_address__uuid` (`uuid`),
    UNIQUE KEY `uc_user_public_ipv6_address__dc_addr` (`data_center_id`, `public_ipv6_address`),
    KEY `i_user_public_ipv6_address__account_id` (`account_id`),
    KEY `i_user_public_ipv6_address__domain_id` (`domain_id`),
    KEY `i_user_public_ipv6_address__network_id` (`network_id`),
    KEY `i_user_public_ipv6_address__vpc_id` (`vpc_id`),
    KEY `i_user_public_ipv6_address__state` (`state`),
    KEY `i_user_public_ipv6_address__removed` (`removed`),
    CONSTRAINT `fk_user_public_ipv6_address__data_center_id`
        FOREIGN KEY (`data_center_id`) REFERENCES `cloud`.`data_center` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_user_public_ipv6_address__account_id`
        FOREIGN KEY (`account_id`) REFERENCES `cloud`.`account` (`id`),
    CONSTRAINT `fk_user_public_ipv6_address__domain_id`
        FOREIGN KEY (`domain_id`) REFERENCES `cloud`.`domain` (`id`),
    CONSTRAINT `fk_user_public_ipv6_address__network_id`
        FOREIGN KEY (`network_id`) REFERENCES `cloud`.`networks` (`id`),
    CONSTRAINT `fk_user_public_ipv6_address__vpc_id`
        FOREIGN KEY (`vpc_id`) REFERENCES `cloud`.`vpc` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Public IPv6 VIP/FIP inventory (Option B; not guest SLAAC, not user_ip_address)';

-- Role permissions for public IPv6 inventory APIs (ResourceAdmin=2, DomainAdmin=3, User=4).
-- Root Admin (role_id=1) allows all rules by default.
INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 2, 'associatePublicIpv6Address', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 2)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 2 AND `rule` = 'associatePublicIpv6Address');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 2, 'listPublicIpv6Addresses', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 2)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 2 AND `rule` = 'listPublicIpv6Addresses');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 2, 'disassociatePublicIpv6Address', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 2)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 2 AND `rule` = 'disassociatePublicIpv6Address');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 3, 'associatePublicIpv6Address', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 3 AND `rule` = 'associatePublicIpv6Address');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 3, 'listPublicIpv6Addresses', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 3 AND `rule` = 'listPublicIpv6Addresses');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 3, 'disassociatePublicIpv6Address', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 3)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 3 AND `rule` = 'disassociatePublicIpv6Address');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 4, 'associatePublicIpv6Address', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 4)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 4 AND `rule` = 'associatePublicIpv6Address');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 4, 'listPublicIpv6Addresses', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 4)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 4 AND `rule` = 'listPublicIpv6Addresses');

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 4, 'disassociatePublicIpv6Address', 'ALLOW', (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 4)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 4 AND `rule` = 'disassociatePublicIpv6Address');
