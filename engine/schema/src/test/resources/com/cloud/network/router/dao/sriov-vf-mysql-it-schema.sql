-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership. The ASF licenses this file
-- to you under the Apache License, Version 2.0.

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS vf_it_deadlock;
DROP TABLE IF EXISTS sriov_vf_pool;
DROP TABLE IF EXISTS op_it_work;
DROP TABLE IF EXISTS nics;
DROP TABLE IF EXISTS vm_instance;
DROP TABLE IF EXISTS mshost;
DROP TABLE IF EXISTS host;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE host (
  id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE vm_instance (
  id BIGINT UNSIGNED NOT NULL,
  state VARCHAR(32) NOT NULL,
  host_id BIGINT UNSIGNED NULL,
  removed DATETIME NULL,
  PRIMARY KEY (id),
  KEY i_vm_host (host_id),
  CONSTRAINT fk_vf_it_vm_host FOREIGN KEY (host_id) REFERENCES host(id)
) ENGINE=InnoDB;

CREATE TABLE mshost (
  msid BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (msid)
) ENGINE=InnoDB;

CREATE TABLE nics (
  id BIGINT UNSIGNED NOT NULL,
  instance_id BIGINT UNSIGNED NULL,
  ip4_address VARCHAR(45) NULL,
  ip6_address VARCHAR(255) NULL,
  netmask VARCHAR(45) NULL,
  isolation_uri VARCHAR(255) NULL,
  ip_type VARCHAR(32) NULL,
  broadcast_uri VARCHAR(255) NULL,
  gateway VARCHAR(45) NULL,
  mac_address VARCHAR(32) NULL,
  mode VARCHAR(32) NULL,
  network_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
  state VARCHAR(32) NULL,
  reserver_name VARCHAR(255) NULL,
  reservation_id VARCHAR(255) NULL,
  device_id INT NOT NULL DEFAULT 0,
  update_time DATETIME NULL,
  default_nic TINYINT(1) NOT NULL DEFAULT 0,
  ip6_gateway VARCHAR(255) NULL,
  ip6_cidr VARCHAR(255) NULL,
  strategy VARCHAR(32) NULL,
  vm_type VARCHAR(32) NULL,
  removed DATETIME NULL,
  created DATETIME NULL,
  uuid VARCHAR(40) NOT NULL,
  secondary_ip TINYINT(1) NOT NULL DEFAULT 0,
  mtu INT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  vf_pci_address VARCHAR(17) NULL,
  vf_pool_id BIGINT UNSIGNED NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_vf_it_nic_uuid (uuid),
  KEY i_vf_it_nic_instance (instance_id),
  CONSTRAINT fk_vf_it_nic_vm FOREIGN KEY (instance_id) REFERENCES vm_instance(id)
) ENGINE=InnoDB;

CREATE TABLE sriov_vf_pool (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  uuid VARCHAR(40) NOT NULL,
  host_id BIGINT UNSIGNED NOT NULL,
  pci_address VARCHAR(17) NOT NULL,
  pf_name VARCHAR(32) NOT NULL,
  representor_name VARCHAR(32) NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'FREE',
  allocated_to_nic_id BIGINT UNSIGNED NULL,
  vdpa_kind VARCHAR(16) NOT NULL DEFAULT 'PASSTHROUGH',
  vdpa_name VARCHAR(64) NULL,
  vdpa_device VARCHAR(64) NULL,
  created DATETIME NOT NULL,
  updated DATETIME NULL,
  last_seen DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sriov_vf_pool__host_pci (host_id, pci_address),
  UNIQUE KEY uk_sriov_vf_pool__uuid (uuid),
  KEY idx_sriov_vf_pool__state (state),
  KEY idx_sriov_vf_pool__host_state (host_id, state),
  KEY idx_sriov_vf_pool__nic (allocated_to_nic_id),
  CONSTRAINT fk_sriov_vf_pool__host_id FOREIGN KEY (host_id)
    REFERENCES host(id) ON DELETE CASCADE,
  CONSTRAINT fk_sriov_vf_pool__nic_id FOREIGN KEY (allocated_to_nic_id)
    REFERENCES nics(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE op_it_work (
  id CHAR(40) NOT NULL,
  mgmt_server_id BIGINT UNSIGNED NULL,
  created_at BIGINT UNSIGNED NOT NULL,
  thread VARCHAR(255) NOT NULL,
  updated_at BIGINT UNSIGNED NOT NULL,
  instance_id BIGINT UNSIGNED NOT NULL,
  resource_id BIGINT UNSIGNED NULL,
  resource_type CHAR(32) NULL,
  type VARCHAR(32) NOT NULL,
  step VARCHAR(32) NOT NULL,
  vm_type VARCHAR(32) NULL,
  PRIMARY KEY (id),
  KEY i_vf_it_work_vm_generation (instance_id, updated_at, created_at, id),
  KEY i_vf_it_work_step (step),
  CONSTRAINT fk_vf_it_work_vm FOREIGN KEY (instance_id) REFERENCES vm_instance(id),
  CONSTRAINT fk_vf_it_work_mshost FOREIGN KEY (mgmt_server_id) REFERENCES mshost(msid)
) ENGINE=InnoDB;

CREATE TABLE vf_it_deadlock (
  id BIGINT NOT NULL,
  counter_value INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

INSERT INTO mshost(msid) VALUES (1);
