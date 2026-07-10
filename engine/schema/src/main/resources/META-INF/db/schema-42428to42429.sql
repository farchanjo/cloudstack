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
-- Schema upgrade 4.24.1.28 to 4.24.1.29.
-- Sprint 2 foundation: allow firewall_rules / LB rules to reference public
-- IPv6 inventory (user_public_ipv6_address) via a nullable side column.
-- Does NOT repurpose ip_address_id (stays IPv4 user_ip_address only).
-- Does NOT touch guest user_ipv6_address or user_ip_address.
--;

-- Idempotent add: column may already exist on re-run of a partial upgrade.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'firewall_rules'
      AND COLUMN_NAME = 'public_ipv6_address_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `cloud`.`firewall_rules`
        ADD COLUMN `public_ipv6_address_id` BIGINT UNSIGNED NULL
            COMMENT ''FK to user_public_ipv6_address.id for public IPv6 VIP/LB rules; NULL for IPv4-only rules''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'firewall_rules'
      AND INDEX_NAME = 'i_firewall_rules__public_ipv6_address_id'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE `cloud`.`firewall_rules`
        ADD KEY `i_firewall_rules__public_ipv6_address_id` (`public_ipv6_address_id`)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'firewall_rules'
      AND CONSTRAINT_NAME = 'fk_firewall_rules__public_ipv6_address_id'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @sql := IF(@fk_exists = 0,
    'ALTER TABLE `cloud`.`firewall_rules`
        ADD CONSTRAINT `fk_firewall_rules__public_ipv6_address_id`
            FOREIGN KEY (`public_ipv6_address_id`)
            REFERENCES `cloud`.`user_public_ipv6_address` (`id`)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
