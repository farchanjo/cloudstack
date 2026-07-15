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
-- Schema upgrade 4.24.1.30 to 4.24.1.31.
-- CKS auto ECMP next-hops + auto LB backends (ConfigKeys only; no DDL).
-- Empty default values preserve pre-upgrade behaviour until ops enables them.
-- INSERT ... ON DUPLICATE KEY UPDATE is idempotent for re-runs.
--;

INSERT INTO `cloud`.`configuration`
    (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`, `updated`, `scope`, `is_dynamic`)
VALUES
    ('Network', 'DEFAULT', 'OvnNetworkConfig',
     'ovn.lr.ecmp.auto.clusters', NULL,
     'Per-network auto ECMP next-hops from CKS worker VMs. Syntax: <network-uuid>=<cks-cluster-uuid>|<v4-prefix>|<v6-prefix>;... Blank prefix skips that family. Empty disables. Merged with ovn.lr.ecmp.static.routes (auto hops first).',
     '', NOW(), 1, 1)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `default_value` = VALUES(`default_value`);

INSERT INTO `cloud`.`configuration`
    (`category`, `instance`, `component`, `name`, `value`, `description`, `default_value`, `updated`, `scope`, `is_dynamic`)
VALUES
    ('Network', 'DEFAULT', 'OvnNetworkConfig',
     'ovn.lb.auto.cks', NULL,
     'Auto-refresh LB rule backends from CKS worker guest IPs. Syntax: <lb-rule-id>=<cks-cluster-uuid>:<dest-port>;... Empty disables. Rewrites load_balancer_vm_map then re-applies OVN LB.',
     '', NOW(), 1, 1)
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`), `default_value` = VALUES(`default_value`);
