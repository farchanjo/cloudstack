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

import com.cloud.network.UserPublicIpv6Address;
import com.cloud.network.UserPublicIpv6AddressVO;
import com.cloud.utils.db.GenericDao;

public interface UserPublicIpv6AddressDao extends GenericDao<UserPublicIpv6AddressVO, Long> {

    UserPublicIpv6AddressVO findByZoneAndAddress(long dataCenterId, String address);

    List<UserPublicIpv6AddressVO> listByAccount(long accountId);

    List<UserPublicIpv6AddressVO> listByZone(long dataCenterId);

    List<UserPublicIpv6AddressVO> listByZoneAndState(long dataCenterId, UserPublicIpv6Address.State state);

    List<UserPublicIpv6AddressVO> listByNetwork(long networkId);

    List<UserPublicIpv6AddressVO> listByVpc(long vpcId);

    List<UserPublicIpv6AddressVO> listByAccountAndZone(long accountId, long dataCenterId);
}
