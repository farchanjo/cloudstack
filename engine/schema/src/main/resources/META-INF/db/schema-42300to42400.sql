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
-- Schema upgrade from 4.23.0.0 to 4.24.0.0
--;

-- ============================================================
-- Feature: SR-IOV VF passthrough for Virtual Routers (HW Offload)
-- All changes are strictly additive and backward-compatible.
-- Defaults preserve legacy behavior so existing VRs/networks are unaffected.
-- ============================================================

-- VF pool inventory: tracks each Virtual Function on each host
-- managed by CloudStack agent. Allocations are bound to a NIC; releases
-- happen via ON DELETE SET NULL when a NIC is removed.
CREATE TABLE IF NOT EXISTS `cloud`.`sriov_vf_pool` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `host_id` bigint unsigned NOT NULL,
  `pci_address` varchar(17) NOT NULL COMMENT 'PCI address of the VF, format: dddd:bb:ss.f',
  `pf_name` varchar(32) NOT NULL COMMENT 'Physical Function netdev name (e.g. dx6p0)',
  `representor_name` varchar(32) NULL COMMENT 'Switchdev representor for this VF (e.g. dx6p0r0). Null if no rep.',
  `state` varchar(32) NOT NULL DEFAULT 'FREE' COMMENT 'FREE | ALLOCATED | RESERVED | UNAVAILABLE',
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

-- NIC: optional binding to a VF. NULL preserves legacy bridge/TAP behavior.
ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `vf_pci_address` varchar(17) NULL
    COMMENT 'SR-IOV VF PCI address. NULL = NIC uses traditional TAP/bridge.';

ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `vf_pool_id` bigint unsigned NULL
    COMMENT 'Soft reference to sriov_vf_pool.id. NULL when vf_pci_address is NULL.';

-- Network offering: opt-in flag for HW offload.
ALTER TABLE `cloud`.`network_offerings`
    ADD COLUMN IF NOT EXISTS `hw_offload_enabled` tinyint(1) NOT NULL DEFAULT 0
    COMMENT 'Enable hardware TC flower offload via SR-IOV VF passthrough. 0=disabled (SW VR), 1=enabled (HW VR).';

CREATE INDEX IF NOT EXISTS `idx_network_offerings__hw_offload_enabled` ON `cloud`.`network_offerings` (`hw_offload_enabled`);

-- Domain router: tracking flag (provisioning was HW or SW). Read-only, for ops/troubleshoot.
ALTER TABLE `cloud`.`domain_router`
    ADD COLUMN IF NOT EXISTS `hw_offload_active` tinyint(1) NOT NULL DEFAULT 0
    COMMENT 'Tracks if this VR was provisioned with HW offload (VFs).';

-- HW offload intent cache: last intent (NAT/ACL/LB rules JSON) received per VR.
-- Used by host agent to reconcile state and to restore TC rules after host reboot.
CREATE TABLE IF NOT EXISTS `cloud`.`vr_hw_offload_intent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `router_id` bigint unsigned NOT NULL,
  `host_id` bigint unsigned NOT NULL,
  `intent_json` mediumtext NOT NULL COMMENT 'Full intent payload (NAT/ACL/LB rules) as received from VR',
  `intent_version` bigint unsigned NOT NULL DEFAULT 1 COMMENT 'Monotonic counter incremented per intent update',
  `received` datetime NOT NULL,
  `applied` datetime NULL COMMENT 'Last time intent was successfully applied to TC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vr_hw_offload_intent__router_id` (`router_id`),
  KEY `idx_vr_hw_offload_intent__host_id` (`host_id`),
  CONSTRAINT `fk_vr_hw_offload_intent__router_id` FOREIGN KEY (`router_id`)
    REFERENCES `cloud`.`domain_router`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vr_hw_offload_intent__host_id` FOREIGN KEY (`host_id`)
    REFERENCES `cloud`.`host`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Configuration entries (opt-in feature toggle and template selection).
INSERT IGNORE INTO `cloud`.`configuration`
  (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`)
VALUES
  ('Advanced', 'DEFAULT', 'management-server',
   'router.template.kvm.hwoffload', NULL,
   'KVM systemvm template UUID for VRs with hardware offload. NULL = use default router.template.kvm.', NULL),
  ('Advanced', 'DEFAULT', 'management-server',
   'vr.hw.offload.enabled', 'false',
   'Master toggle for VR hardware offload feature. When false, all HW offload code paths are inert (safe rollback).', 'false'),
  ('Advanced', 'DEFAULT', 'management-server',
   'vr.hw.offload.intent.api.port', '9999',
   'TCP port the host agent listens on (cloud0 link-local) for VR HW offload intent API.', '9999');

-- ============================================================
-- Feature: SR-IOV Sub-Function (SF) pool with vDPA support
-- SFs are dynamic (created/destroyed at runtime via devlink),
-- unlike VFs which are static (firmware-provisioned).
-- SFs support vDPA for live migration of the datapath.
-- All changes are strictly additive and backward-compatible.
-- ============================================================

-- SF pool inventory: tracks each Sub-Function on each host.
-- Lifecycle: FREE -> SF_CREATED -> VDPA_READY -> ALLOCATED -> DESTROYING
CREATE TABLE IF NOT EXISTS `cloud`.`sriov_sf_pool` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `host_id` bigint unsigned NOT NULL,
  `pf_index` int NOT NULL COMMENT 'Physical Function index (0 or 1)',
  `sf_index` int NOT NULL COMMENT 'SF number passed to devlink port function set',
  `devlink_port_handle` varchar(64) NULL COMMENT 'devlink port handle, e.g. pci/0000:01:00.0/32768',
  `sf_netdev_name` varchar(32) NULL COMMENT 'SF netdev name, e.g. dx6p0sf0',
  `representor_name` varchar(32) NULL COMMENT 'SF representor name (same as netdev after udev rename)',
  `vdpa_device` varchar(64) NULL COMMENT 'vDPA device path, e.g. /dev/vhost-vdpa-0. NULL until vDPA created.',
  `state` varchar(32) NOT NULL DEFAULT 'FREE' COMMENT 'FREE | SF_CREATED | VDPA_READY | ALLOCATED | DESTROYING',
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

-- NIC: optional binding to an SF pool entry and vDPA device.
ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `sf_pool_id` bigint unsigned NULL
    COMMENT 'Soft reference to sriov_sf_pool.id. NULL when NIC does not use SF.';

ALTER TABLE `cloud`.`nics`
    ADD COLUMN IF NOT EXISTS `vdpa_device` varchar(64) NULL
    COMMENT 'vDPA device path assigned to this NIC. NULL when NIC does not use vDPA.';

-- Network offering: opt-in flag for SF+vDPA.
ALTER TABLE `cloud`.`network_offerings`
    ADD COLUMN IF NOT EXISTS `sf_vdpa_enabled` tinyint(1) NOT NULL DEFAULT 0
    COMMENT 'Enable SR-IOV Sub-Function with vDPA for live-migratable HW datapath. 0=disabled, 1=enabled.';

-- Configuration entries for SF pool management.
INSERT IGNORE INTO `cloud`.`configuration`
  (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`)
VALUES
  ('Advanced', 'DEFAULT', 'management-server',
   'vm.sf.vdpa.enabled', 'false',
   'Master toggle for SR-IOV Sub-Function with vDPA support. When false, all SF/vDPA code paths are inert.', 'false'),
  ('Advanced', 'DEFAULT', 'management-server',
   'vm.sf.pool.size.per.host', '128',
   'Maximum number of Sub-Functions to pre-provision per host via devlink.', '128');
