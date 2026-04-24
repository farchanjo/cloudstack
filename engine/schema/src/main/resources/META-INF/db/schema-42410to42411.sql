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
-- Schema upgrade 4.24.1.0 to 4.24.1.1.
-- Retires the vDPA feature (both the historical SR-IOV Sub-Function path and
-- the VF+vDPA replacement). ConnectX-6 Dx does not support SF+vDPA; the
-- VF+vDPA prototype was validated but not carried forward (libvirt 10.0 on
-- noble blocks guest multi-queue, removing the main ROI). The feature is
-- removed from the fork.
-- All DROPs below are idempotent and safe on clusters that never populated
-- any of the vDPA artifacts.
--;

-- Drop SF pool and the SF reference on nics (never populated on CX-6 Dx)
ALTER TABLE `cloud`.`nics` DROP COLUMN IF EXISTS `sf_pool_id`;
ALTER TABLE `cloud`.`nics` DROP COLUMN IF EXISTS `vdpa_device`;
DROP TABLE IF EXISTS `cloud`.`sriov_sf_pool`;

-- Drop offering flag (both the old sf_vdpa_enabled and the renamed vdpa_enabled)
ALTER TABLE `cloud`.`network_offerings` DROP COLUMN IF EXISTS `sf_vdpa_enabled`;
ALTER TABLE `cloud`.`network_offerings` DROP COLUMN IF EXISTS `vdpa_enabled`;

-- Drop VF pool vDPA bookkeeping
ALTER TABLE `cloud`.`sriov_vf_pool` DROP COLUMN IF EXISTS `vdpa_device`;
ALTER TABLE `cloud`.`sriov_vf_pool` DROP COLUMN IF EXISTS `vdpa_name`;

-- Retire SF and VR vDPA master toggles
DELETE FROM `cloud`.`configuration`
 WHERE `name` IN ('vm.sf.vdpa.enabled', 'vm.sf.pool.size.per.host', 'vr.vdpa.enabled');
