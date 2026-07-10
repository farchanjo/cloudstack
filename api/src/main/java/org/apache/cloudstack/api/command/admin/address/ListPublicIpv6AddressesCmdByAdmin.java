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
package org.apache.cloudstack.api.command.admin.address;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.command.user.address.ListPublicIpv6AddressesCmd;
import org.apache.cloudstack.api.response.PublicIpv6AddressResponse;

import com.cloud.network.UserPublicIpv6Address;

@APICommand(name = ListPublicIpv6AddressesCmd.APINAME,
        description = "Lists public IPv6 addresses from the public IPv6 inventory",
        responseObject = PublicIpv6AddressResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        entityType = {UserPublicIpv6Address.class})
public class ListPublicIpv6AddressesCmdByAdmin extends ListPublicIpv6AddressesCmd implements AdminCmd {
}
