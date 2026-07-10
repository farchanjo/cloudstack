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
package com.cloud.network;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.cloud.utils.db.GenericDao;

/**
 * VO for {@code user_public_ipv6_address}. Address is stored as a canonical
 * String (same pattern as {@link UserIpv6AddressVO}), never as {@code Ip} long.
 */
@Entity
@Table(name = "user_public_ipv6_address")
public class UserPublicIpv6AddressVO implements UserPublicIpv6Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "public_ipv6_address")
    private String address;

    @Column(name = "data_center_id", updatable = false)
    private long dataCenterId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "domain_id")
    private Long domainId;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "state")
    private State state;

    @Column(name = "network_id")
    private Long networkId;

    @Column(name = "vpc_id")
    private Long vpcId;

    @Column(name = "allocated")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date allocatedTime;

    @Column(name = "is_system")
    private boolean system;

    @Column(name = "display", updatable = true, nullable = false)
    private boolean display = true;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    protected UserPublicIpv6AddressVO() {
        uuid = UUID.randomUUID().toString();
    }

    public UserPublicIpv6AddressVO(String address, long dataCenterId) {
        this.address = address;
        this.dataCenterId = dataCenterId;
        this.state = State.Free;
        this.uuid = UUID.randomUUID().toString();
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public long getDataCenterId() {
        return dataCenterId;
    }

    public void setDataCenterId(long dataCenterId) {
        this.dataCenterId = dataCenterId;
    }

    @Override
    public long getAccountId() {
        return accountId == null ? -1 : accountId;
    }

    public Long getAccountIdObject() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    @Override
    public long getDomainId() {
        return domainId == null ? -1 : domainId;
    }

    public Long getDomainIdObject() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void setState(State state) {
        this.state = state;
    }

    @Override
    public Long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(Long networkId) {
        this.networkId = networkId;
    }

    @Override
    public Long getVpcId() {
        return vpcId;
    }

    public void setVpcId(Long vpcId) {
        this.vpcId = vpcId;
    }

    @Override
    public Date getAllocatedTime() {
        return allocatedTime;
    }

    public void setAllocatedTime(Date allocatedTime) {
        this.allocatedTime = allocatedTime;
    }

    @Override
    public boolean isSystem() {
        return system;
    }

    public void setSystem(boolean system) {
        this.system = system;
    }

    @Override
    public boolean isDisplay() {
        return display;
    }

    public void setDisplay(boolean display) {
        this.display = display;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getRemoved() {
        return removed;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    @Override
    public Class<?> getEntityType() {
        return UserPublicIpv6Address.class;
    }

    @Override
    public String getName() {
        return address;
    }
}
