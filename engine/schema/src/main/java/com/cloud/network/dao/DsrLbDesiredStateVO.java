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
package com.cloud.network.dao;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.db.GenericDao;
import org.apache.cloudstack.api.InternalIdentity;

/**
 * Persistent desired state for {@code DSR_SOFTWARE} load balancer rules.
 * Never owns an OVN {@code Load_Balancer} row; carries external_ids-shaped
 * ownership tags for reconciler + health-gated BGP/Kubernetes cutover.
 */
@Entity
@Table(name = "dsr_lb_desired_state")
public class DsrLbDesiredStateVO implements InternalIdentity {

    public static final String STATE_PENDING = "Pending";
    public static final String STATE_PROGRAMMED = "Programmed";
    public static final String STATE_MIGRATING = "Migrating";
    public static final String STATE_ROLLBACK = "Rollback";
    public static final String STATE_REVOKED = "Revoked";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "load_balancer_id")
    private long loadBalancerId;

    @Column(name = "vip_v4")
    private String vipV4;

    @Column(name = "vip_v6")
    private String vipV6;

    @Column(name = "public_port")
    private int publicPort;

    @Column(name = "protocol")
    private String protocol = "tcp";

    @Column(name = "state")
    private String state = STATE_PENDING;

    @Column(name = "external_ids", length = 65535)
    private String externalIds;

    @Column(name = "backend_ready")
    private boolean backendReady;

    @Column(name = "ct_withdrawn")
    private boolean ctWithdrawn;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    @Column(name = "updated")
    private Date updated;

    public DsrLbDesiredStateVO() {
    }

    public DsrLbDesiredStateVO(long loadBalancerId, String vipV4, String vipV6, int publicPort, String protocol,
            String externalIds) {
        this.loadBalancerId = loadBalancerId;
        this.vipV4 = vipV4;
        this.vipV6 = vipV6;
        this.publicPort = publicPort;
        this.protocol = protocol == null ? "tcp" : protocol;
        this.externalIds = externalIds;
        this.state = STATE_PENDING;
        this.created = new Date();
        this.updated = this.created;
    }

    @Override
    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public long getLoadBalancerId() {
        return loadBalancerId;
    }

    public void setLoadBalancerId(long loadBalancerId) {
        this.loadBalancerId = loadBalancerId;
    }

    public String getVipV4() {
        return vipV4;
    }

    public void setVipV4(String vipV4) {
        this.vipV4 = vipV4;
    }

    public String getVipV6() {
        return vipV6;
    }

    public void setVipV6(String vipV6) {
        this.vipV6 = vipV6;
    }

    public int getPublicPort() {
        return publicPort;
    }

    public void setPublicPort(int publicPort) {
        this.publicPort = publicPort;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
        this.updated = new Date();
    }

    public String getExternalIds() {
        return externalIds;
    }

    public void setExternalIds(String externalIds) {
        this.externalIds = externalIds;
    }

    public boolean isBackendReady() {
        return backendReady;
    }

    public void setBackendReady(boolean backendReady) {
        this.backendReady = backendReady;
    }

    public boolean isCtWithdrawn() {
        return ctWithdrawn;
    }

    public void setCtWithdrawn(boolean ctWithdrawn) {
        this.ctWithdrawn = ctWithdrawn;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }
}
