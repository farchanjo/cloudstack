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
     *  {@code NIC} ids never collide.
     *
     * <p>{@code ORPHAN_NIC} marks an OVN logical-switch port discovered by
     * {@code importOvnVpc} that has no {@code external_ids:cloudstack:vmId}
     * yet. Operators run a follow-up {@code adoptOvnNic} command to
     * convert it to a {@code NIC} kind once the owning CloudStack VM is
     * known. */
    public enum Kind {
        VPC, NETWORK, NIC, STATIC_NAT, SOURCE_NAT, NETWORK_ACL, LOAD_BALANCER, ORPHAN_NIC,
        /** DHCP_Options row keyed by tier network id (one per tier subnet). */
        DHCP_OPTIONS,
        /** DHCP_Options row for IPv6 — separate kind so v4/v6 coexist on the same tier. */
        DHCP_OPTIONS_V6,
        /** DNS row attached to a tier LS (one per tier). */
        DNS_RECORDS,
        /** QoS row keyed by CloudStack network-rate / detail id. */
        QOS,
        /** Logical_Router_Static_Route row keyed by VPC id (catch-all default). */
        STATIC_ROUTE,
        /** HA_Chassis_Group row keyed by zone id (one per zone). */
        HA_CHASSIS_GROUP,
        /** Load_Balancer row used as 1:1 port-forward, keyed by PF rule id. */
        PORT_FORWARDING,
        /** Zone-scope public Logical_Switch (one per zone). */
        PUBLIC_LS,
        /** Per-zone public-side LRP attached to PUBLIC_LS, keyed by VPC id. */
        PUBLIC_LRP,
        /** VPC-level SourceNAT row keyed by VPC id (one per VPC, mapping VPC
         *  parent CIDR to the VPC's source-NAT public IP). Distinct from
         *  {@link #SOURCE_NAT} which is per-tier (keyed by tier network id);
         *  the per-tier kind survives for callers that explicitly associate
         *  a public IP to a tier. */
        VPC_SOURCE_NAT,
        /** Public-side LRP attached to a VPC LR (router-patch into the
         *  per-zone public Logical_Switch), keyed by VPC id. Distinct from
         *  {@link #PUBLIC_LRP} which is reused by tier-LRPs (per network
         *  id) — the names overlap historically but the key spaces differ. */
        VPC_PUBLIC_LRP,
        /** Router-type Logical_Switch_Port peer of {@link #VPC_PUBLIC_LRP}
         *  (named {@code rsp-public-vpc<id>}), inserted into the shared
         *  per-zone public Logical_Switch's {@code ports} set, keyed by VPC
         *  id. Tracked separately from the LRP because
         *  {@code deleteLogicalRouterPort} does not cascade to this peer
         *  port — there is no OVSDB strong reference between an LRP and its
         *  peer LSP, only the {@code options:router-port} string, so the
         *  plugin must delete both rows explicitly on VPC unbind. */
        VPC_PUBLIC_RSP,
        /** BGP /32 announce bookkeeping. cs_id = IPAddressVO.id;
         *  ovn_uuid column is reused to hold the agent host id (as string)
         *  that last announced the route; ovn_name carries the bare public
         *  IP for cheap reverse lookup. Holds no actual OVN UUID — the kind
         *  exists to drive {@code OvnBgpRedistributeManager} reconciliation
         *  on gateway-chassis migration. */
        BGP_ANNOUNCE
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
