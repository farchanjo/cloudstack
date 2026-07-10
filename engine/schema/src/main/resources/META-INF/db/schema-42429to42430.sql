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
-- Schema upgrade 4.24.1.29 to 4.24.1.30.
-- Sprint 3 gap fill: role permission for importPublicIpv6Address (Admin).
-- API is gated by @APICommand(authorized={RoleType.Admin}); Root Admin already
-- has rule='*' ALLOW — explicit row documents the API for audits / custom clones.
-- No DomainAdmin / User / ResourceAdmin grant (grandfather VIP import is Admin-only).
--;

INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), 1, 'importPublicIpv6Address', 'ALLOW',
       (SELECT IFNULL(MAX(`sort_order`), 0) + 1 FROM `cloud`.`role_permissions` WHERE `role_id` = 1)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `cloud`.`role_permissions`
    WHERE `role_id` = 1 AND `rule` = 'importPublicIpv6Address'
);
