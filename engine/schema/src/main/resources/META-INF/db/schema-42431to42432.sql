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
-- Schema upgrade 4.24.1.31 to 4.24.1.32.
-- Dual-stack DSR software LB: first-class lb_kind (default CT_LB), feature gate
-- (disabled by default), and persistent DSR desired-state contract for
-- health-gated BGP / Kubernetes ownership. Existing rules remain CT_LB.
--;

-- First-class LB datapath kind on inventory (CT_LB | DSR_SOFTWARE).
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`(
    'cloud.load_balancing_rules',
    'lb_kind',
    "VARCHAR(32) NOT NULL DEFAULT 'CT_LB' COMMENT 'LB datapath kind: CT_LB (OVN ct_lb) or DSR_SOFTWARE'"
);

-- Desired state for DSR rules (no OVN Load_Balancer row). external_ids-shaped
-- ownership tags for reconciler / guest BGP cutover coordination.
CREATE TABLE IF NOT EXISTS `cloud`.`dsr_lb_desired_state` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40) NOT NULL,
  `load_balancer_id` bigint unsigned NOT NULL,
  `vip_v4` varchar(40) DEFAULT NULL COMMENT 'IPv4 VIP /32 when present',
  `vip_v6` varchar(64) DEFAULT NULL COMMENT 'IPv6 VIP /128 when present',
  `public_port` int(10) NOT NULL,
  `protocol` varchar(40) NOT NULL DEFAULT 'tcp',
  `state` varchar(32) NOT NULL DEFAULT 'Pending'
      COMMENT 'Pending | Programmed | Migrating | Rollback | Revoked',
  `external_ids` text COMMENT 'JSON map of ownership tags (cs_lb_kind, cs_uuid, ...)',
  `backend_ready` tinyint(1) NOT NULL DEFAULT 0
      COMMENT '1 when guest lo VIP acceptance is ready (X9 gate)',
  `ct_withdrawn` tinyint(1) NOT NULL DEFAULT 0
      COMMENT '1 after CT_LB OVN/BGP withdraw for this VIP role',
  `last_error` varchar(1024) DEFAULT NULL,
  `created` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dsr_lb_desired_lb` (`load_balancer_id`),
  UNIQUE KEY `uk_dsr_lb_desired_uuid` (`uuid`),
  CONSTRAINT `fk_dsr_lb_desired_state__lb_id` FOREIGN KEY (`load_balancer_id`)
      REFERENCES `cloud`.`load_balancing_rules`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Feature gate: DSR create hard-fails when false (default).
INSERT INTO `cloud`.`configuration`
    (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`, `updated`, `scope`, `is_dynamic`)
VALUES
    ('Network', 'DEFAULT', 'DsrSoftwareLbConfig',
     'network.lb.dsr.software.enabled', 'false',
     'Feature gate for DSR_SOFTWARE load balancer kind. When false (default), create of lbkind=dsr_software is rejected. Enable only after acceptance suite is green.',
     'false', NOW(), 1, 1)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `default_value` = VALUES(`default_value`);
