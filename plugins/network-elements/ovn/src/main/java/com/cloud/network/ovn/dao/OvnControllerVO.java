// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ovn.dao;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.api.InternalIdentity;

/**
 * One row per OVN deployment per CloudStack zone. Holds the comma-separated
 * NB and SB endpoint lists; the plugin client picks the live endpoint at
 * runtime (RAFT leader detection + transparent fail-over).
 *
 * <p>The unique key on {@code uuid} matches the API surface used by
 * {@code AddOvnControllerCmd} / {@code DeleteOvnControllerCmd}; the unique
 * pair ({@code zone_id}, {@code name}) is enforced at the application level
 * (see {@code OvnControllerDao#findByZoneAndName}).
 */
@Entity
@Table(name = "ovn_controller")
public class OvnControllerVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "zone_id", nullable = false)
    private long zoneId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "nb_endpoints", nullable = false, length = 2048)
    private String nbEndpoints;

    @Column(name = "sb_endpoints", length = 2048)
    private String sbEndpoints;

    @Column(name = "created", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();

    @Column(name = "removed")
    @Temporal(TemporalType.TIMESTAMP)
    private Date removed;

    public OvnControllerVO() {
    }

    public OvnControllerVO(final long zoneId, final String name, final String nbEndpoints, final String sbEndpoints) {
        this.zoneId = zoneId;
        this.name = name;
        this.nbEndpoints = nbEndpoints;
        this.sbEndpoints = sbEndpoints;
    }

    @Override
    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(final String uuid) {
        this.uuid = uuid;
    }

    public long getZoneId() {
        return zoneId;
    }

    public void setZoneId(final long zoneId) {
        this.zoneId = zoneId;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getNbEndpoints() {
        return nbEndpoints;
    }

    public void setNbEndpoints(final String nbEndpoints) {
        this.nbEndpoints = nbEndpoints;
    }

    public String getSbEndpoints() {
        return sbEndpoints;
    }

    public void setSbEndpoints(final String sbEndpoints) {
        this.sbEndpoints = sbEndpoints;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    public void setRemoved(final Date removed) {
        this.removed = removed;
    }
}
