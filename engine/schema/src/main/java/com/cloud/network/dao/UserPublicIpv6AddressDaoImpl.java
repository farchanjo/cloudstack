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

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.network.UserPublicIpv6Address;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

@Component
public class UserPublicIpv6AddressDaoImpl extends GenericDaoBase<UserPublicIpv6AddressVO, Long>
        implements UserPublicIpv6AddressDao {

    protected final SearchBuilder<UserPublicIpv6AddressVO> AllFieldsSearch;

    public UserPublicIpv6AddressDaoImpl() {
        AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("id", AllFieldsSearch.entity().getId(), Op.EQ);
        AllFieldsSearch.and("dataCenterId", AllFieldsSearch.entity().getDataCenterId(), Op.EQ);
        AllFieldsSearch.and("address", AllFieldsSearch.entity().getAddress(), Op.EQ);
        AllFieldsSearch.and("state", AllFieldsSearch.entity().getState(), Op.EQ);
        AllFieldsSearch.and("accountId", AllFieldsSearch.entity().getAccountId(), Op.EQ);
        AllFieldsSearch.and("networkId", AllFieldsSearch.entity().getNetworkId(), Op.EQ);
        AllFieldsSearch.and("vpcId", AllFieldsSearch.entity().getVpcId(), Op.EQ);
        AllFieldsSearch.done();
    }

    @Override
    public UserPublicIpv6AddressVO findByZoneAndAddress(long dataCenterId, String address) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("dataCenterId", dataCenterId);
        sc.setParameters("address", address);
        return findOneBy(sc);
    }

    @Override
    public List<UserPublicIpv6AddressVO> listByAccount(long accountId) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("accountId", accountId);
        return listBy(sc);
    }

    @Override
    public List<UserPublicIpv6AddressVO> listByZone(long dataCenterId) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("dataCenterId", dataCenterId);
        return listBy(sc);
    }

    @Override
    public List<UserPublicIpv6AddressVO> listByZoneAndState(long dataCenterId, UserPublicIpv6Address.State state) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("dataCenterId", dataCenterId);
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public List<UserPublicIpv6AddressVO> listByNetwork(long networkId) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("networkId", networkId);
        return listBy(sc);
    }

    @Override
    public List<UserPublicIpv6AddressVO> listByVpc(long vpcId) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("vpcId", vpcId);
        return listBy(sc);
    }

    @Override
    public List<UserPublicIpv6AddressVO> listByAccountAndZone(long accountId, long dataCenterId) {
        SearchCriteria<UserPublicIpv6AddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("dataCenterId", dataCenterId);
        return listBy(sc);
    }
}
