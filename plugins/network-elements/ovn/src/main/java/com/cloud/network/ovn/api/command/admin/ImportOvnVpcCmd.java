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
package com.cloud.network.ovn.api.command.admin;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VpcOfferingResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.network.ovn.api.response.OvnLogicalIdResponse;
import com.cloud.network.ovn.manager.OvnAdminService;

/**
 * Adopts an existing OVN topology (LR + tied LSes + LSPs + NAT) into the
 * CloudStack VPC reverse-lookup tables. The command is read-only with
 * respect to the OVN NB DB — it never mutates the live topology.
 *
 * <p>Phase I.5 scope:
 *
 * <ul>
 *   <li>Parses the LR + bound LSes + LSPs + NAT rules via {@code OvnNbReader}.
 *   <li>Validates: every regular LSP carries 1 MAC + 1 IPv4 (IPv6 rejected
 *       for MVP); exactly one attached LS is the public LS (single
 *       {@code localnet} LSP with VLAN tag + physnet).
 *   <li>Persists the adoption into {@code ovn_logical_id_map} inside a
 *       single SQL transaction. On any failure the whole adoption rolls
 *       back. Re-running the command on an already-imported VPC is a
 *       no-op (the unique key {@code (cs_kind, cs_id, controller_id)}
 *       collides on re-insert).
 *   <li>LSPs without {@code external_ids:cloudstack:vmId} are recorded as
 *       {@code ORPHAN_NIC}. A follow-up {@code adoptOvnNic} command
 *       converts them to {@code NIC} once the owning VM is known.
 * </ul>
 *
 * <p>Parameters (all optional except {@code zoneid}, {@code lrname},
 * {@code name}): {@code controllerid}, {@code displaytext},
 * {@code vpcofferingid}, {@code accountid}, {@code domainid}. These are
 * accepted now so the command's wire shape matches the eventual
 * full-VPC-create flow; current MVP only persists in
 * {@code ovn_logical_id_map}.
 */
@APICommand(name = ImportOvnVpcCmd.APINAME, description = "Import an existing OVN logical-router topology as a CloudStack VPC adoption record",
        responseObject = OvnLogicalIdResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.24.1.24")
public class ImportOvnVpcCmd extends BaseCmd {

    public static final String APINAME = "importOvnVpc";

    @Inject
    private OvnAdminService ovnAdminService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            required = true, description = "the zone the OVN topology lives in")
    private Long zoneId;

    @Parameter(name = "controllerid", type = CommandType.STRING,
            description = "OVN controller registration UUID; defaults to the only controller in the zone")
    private String controllerId;

    @Parameter(name = "lrname", type = CommandType.STRING, required = true,
            description = "OVN logical-router name to adopt (e.g. lr-test)")
    private String ovnLrName;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true,
            description = "CloudStack VPC name to record on the adoption row")
    private String vpcName;

    @Parameter(name = ApiConstants.DISPLAY_TEXT, type = CommandType.STRING,
            description = "Human-readable description for the adopted VPC")
    private String displayText;

    @Parameter(name = ApiConstants.VPC_OFF_ID, type = CommandType.UUID, entityType = VpcOfferingResponse.class,
            description = "VPC offering id for the eventual full-VPC creation; ignored by the MVP adoption record")
    private Long vpcOfferingId;

    @Parameter(name = ApiConstants.ACCOUNT_ID, type = CommandType.UUID, entityType = AccountResponse.class,
            description = "Account id that owns the adopted VPC")
    private Long accountId;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "Domain id of the owner account")
    private Long domainId;

    public Long getZoneId() {
        return zoneId;
    }

    public String getControllerId() {
        return controllerId;
    }

    public String getOvnLrName() {
        return ovnLrName;
    }

    public String getVpcName() {
        return vpcName;
    }

    public String getDisplayText() {
        return displayText;
    }

    public Long getVpcOfferingId() {
        return vpcOfferingId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getDomainId() {
        return domainId;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final List<OvnLogicalIdResponse> rows = ovnAdminService.importVpc(zoneId, ovnLrName, vpcName);
            final ListResponse<OvnLogicalIdResponse> response = new ListResponse<>();
            response.setResponses(new ArrayList<>(rows));
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (final RuntimeException re) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, re.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
