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
 * Reverse-lookup table from a CloudStack id (namespaced by {@link Kind}) to
 * the OVN UUID of the OVN entity the plugin created on its behalf.
 *
 * <p>The table is the source of truth for cleanup: when CloudStack deletes a
 * VPC / network / NIC, the plugin can resolve the OVN UUID without having to
 * walk the NB DB. The unique constraint
 * {@code (cs_kind, cs_id, controller_id)} keeps the same id space free per
 * OVN deployment.
 */
@Entity
@Table(name = "ovn_logical_id_map")
public class OvnLogicalIdMapVO implements InternalIdentity {

    /** Namespaces a CloudStack id so {@code VPC}, {@code NETWORK}, and
     *  {@code NIC} ids never collide. */
    public enum Kind {
        VPC, NETWORK, NIC, STATIC_NAT, SOURCE_NAT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "cs_kind", nullable = false, length = 32)
    private String csKind;

    @Column(name = "cs_id", nullable = false)
    private long csId;

    @Column(name = "controller_id", nullable = false)
    private long controllerId;

    @Column(name = "ovn_uuid", nullable = false, length = 64)
    private String ovnUuid;

    @Column(name = "ovn_name")
    private String ovnName;

    @Column(name = "created", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();

    public OvnLogicalIdMapVO() {
    }

    public OvnLogicalIdMapVO(final Kind kind, final long csId, final long controllerId, final String ovnUuid, final String ovnName) {
        this.csKind = kind.name();
        this.csId = csId;
        this.controllerId = controllerId;
        this.ovnUuid = ovnUuid;
        this.ovnName = ovnName;
    }

    @Override
    public long getId() {
        return id;
    }

    public Kind getKind() {
        return Kind.valueOf(csKind);
    }

    public void setKind(final Kind kind) {
        this.csKind = kind.name();
    }

    public String getCsKind() {
        return csKind;
    }

    public long getCsId() {
        return csId;
    }

    public void setCsId(final long csId) {
        this.csId = csId;
    }

    public long getControllerId() {
        return controllerId;
    }

    public void setControllerId(final long controllerId) {
        this.controllerId = controllerId;
    }

    public String getOvnUuid() {
        return ovnUuid;
    }

    public void setOvnUuid(final String ovnUuid) {
        this.ovnUuid = ovnUuid;
    }

    public String getOvnName() {
        return ovnName;
    }

    public void setOvnName(final String ovnName) {
        this.ovnName = ovnName;
    }

    public Date getCreated() {
        return created;
    }
}
