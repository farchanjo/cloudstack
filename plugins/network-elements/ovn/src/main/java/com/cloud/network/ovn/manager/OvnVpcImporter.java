// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network.ovn.manager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbReader.LspRow;
import com.cloud.network.ovn.client.OvnNbReader.LsRow;
import com.cloud.network.ovn.client.OvnNbReader.NatRow;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.manager.OvnImportValidator.Plan;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;

/**
 * Persists the validated OVN topology into {@code ovn_logical_id_map}.
 *
 * <p>MVP scope (Phase I.5):
 * <ul>
 *   <li>Each OVN entity gets a deterministic synthetic CloudStack id
 *       derived from its OVN UUID, so re-running the import is a true
 *       no-op (the {@code (cs_kind, cs_id, controller_id)} unique key
 *       collides on re-insert).
 *   <li>Inserts run inside a single {@link Transaction#execute(TransactionCallback)}
 *       lambda so any failure rolls every row back. The OVN NB DB is
 *       NEVER mutated — this is purely an adoption record.
 *   <li>LSPs without {@code external_ids:cloudstack:vmId} are recorded as
 *       {@link Kind#ORPHAN_NIC}. The follow-up {@code adoptOvnNic}
 *       command (placeholder) converts them to {@link Kind#NIC} once the
 *       owning CloudStack VM is known.
 *   <li>{@code dnat_and_snat} NAT rules become {@link Kind#STATIC_NAT}
 *       rows. {@code snat} rules become {@link Kind#SOURCE_NAT} rows.
 *       {@code dnat}-only rules are tolerated as {@link Kind#STATIC_NAT}
 *       (rare in CloudStack VPC topologies).
 * </ul>
 *
 * <p>The synthetic id keeps the contract that
 * {@link OvnLogicalIdMapVO#getCsId()} is unique under
 * {@code (cs_kind, controller_id)}. A follow-up commit will swap the
 * synthetic ids for real CloudStack {@code Vpc.id} / {@code Network.id}
 * / {@code Nic.id} / {@code IPAddress.id} once the VPC service is wired
 * into the import flow.
 */
@Component
public class OvnVpcImporter {

    private static final Logger LOGGER = LogManager.getLogger(OvnVpcImporter.class);

    private static final long SYNTHETIC_ID_RANGE = 1_000_000_000L;
    private static final long SYNTHETIC_ID_MASK = 0x7FFFFFFFFFFFFFFFL;

    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;

    /**
     * Adopts the validated topology into {@code ovn_logical_id_map}. Idempotent.
     *
     * @param plan         the validated topology + role labels.
     * @param controllerId the OVN controller registration id (FK column).
     * @param vpcName      the CloudStack VPC name to record in the VPC row.
     * @return one report row per persisted (or already-present) entity.
     */
    public List<OvnLogicalIdMapVO> adopt(final Plan plan, final long controllerId, final String vpcName) {
        if (plan == null || plan.topology == null) {
            throw new OvnException("adopt called with null plan");
        }
        return Transaction.execute(new TransactionCallback<List<OvnLogicalIdMapVO>>() {
            @Override
            public List<OvnLogicalIdMapVO> doInTransaction(final TransactionStatus status) {
                return adoptInTx(plan, controllerId, vpcName);
            }
        });
    }

    private List<OvnLogicalIdMapVO> adoptInTx(final Plan plan, final long controllerId, final String vpcName) {
        final List<OvnLogicalIdMapVO> out = new ArrayList<>();
        out.add(persistVpcRow(plan, controllerId, vpcName));
        for (final LsRow ls : plan.tierLses) {
            out.add(persistNetworkRow(ls, controllerId));
            out.addAll(persistLspRows(plan, ls, controllerId));
        }
        if (plan.publicLs != null) {
            // The public LS itself is not a tier — it is recorded under NETWORK
            // kind so the operator can list it through ovn_logical_id_map but
            // NIC adoption skips its localnet port.
            out.add(persistNetworkRow(plan.publicLs, controllerId));
        }
        out.addAll(persistNatRows(plan, controllerId));
        LOGGER.info("OVN VPC adoption recorded {} ovn_logical_id_map rows under controller id={}",
                out.size(), controllerId);
        return out;
    }

    private OvnLogicalIdMapVO persistVpcRow(final Plan plan, final long controllerId, final String vpcName) {
        final long syntheticId = synthesise(plan.topology.lr.uuid, Kind.VPC);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC, syntheticId, controllerId);
        if (existing != null) {
            LOGGER.debug("OVN VPC row already adopted: cs_id={} ovn_uuid={}", syntheticId, existing.getOvnUuid());
            return existing;
        }
        final String label = vpcName != null && !vpcName.isEmpty() ? vpcName : plan.topology.lr.name;
        final OvnLogicalIdMapVO row = new OvnLogicalIdMapVO(
                Kind.VPC, syntheticId, controllerId, plan.topology.lr.uuid, label);
        return logicalIdMapDao.persist(row);
    }

    private OvnLogicalIdMapVO persistNetworkRow(final LsRow ls, final long controllerId) {
        final long syntheticId = synthesise(ls.uuid, Kind.NETWORK);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.NETWORK, syntheticId, controllerId);
        if (existing != null) {
            return existing;
        }
        final OvnLogicalIdMapVO row = new OvnLogicalIdMapVO(
                Kind.NETWORK, syntheticId, controllerId, ls.uuid, ls.name);
        return logicalIdMapDao.persist(row);
    }

    private List<OvnLogicalIdMapVO> persistLspRows(final Plan plan, final LsRow ls, final long controllerId) {
        final List<OvnLogicalIdMapVO> out = new ArrayList<>();
        final List<LspRow> lsps = plan.topology.lspsByLsUuid.getOrDefault(ls.uuid, new ArrayList<>());
        for (final LspRow lsp : lsps) {
            if ("router".equals(lsp.type) || "localnet".equals(lsp.type)) {
                continue;
            }
            final boolean ownerKnown = lsp.externalIds != null
                    && lsp.externalIds.containsKey("cloudstack:vmId")
                    && !lsp.externalIds.get("cloudstack:vmId").isEmpty();
            final Kind kind = ownerKnown ? Kind.NIC : Kind.ORPHAN_NIC;
            final long syntheticId = synthesise(lsp.uuid, kind);
            final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(kind, syntheticId, controllerId);
            if (existing != null) {
                out.add(existing);
                continue;
            }
            final OvnLogicalIdMapVO row = new OvnLogicalIdMapVO(kind, syntheticId, controllerId, lsp.uuid, lsp.name);
            out.add(logicalIdMapDao.persist(row));
        }
        return out;
    }

    private List<OvnLogicalIdMapVO> persistNatRows(final Plan plan, final long controllerId) {
        final List<OvnLogicalIdMapVO> out = new ArrayList<>();
        for (final NatRow nat : plan.topology.nats) {
            final Kind kind = "snat".equals(nat.type) ? Kind.SOURCE_NAT : Kind.STATIC_NAT;
            final long syntheticId = synthesise(nat.uuid, kind);
            final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(kind, syntheticId, controllerId);
            if (existing != null) {
                out.add(existing);
                continue;
            }
            final String label = labelFor(nat);
            final OvnLogicalIdMapVO row = new OvnLogicalIdMapVO(kind, syntheticId, controllerId, nat.uuid, label);
            out.add(logicalIdMapDao.persist(row));
        }
        return out;
    }

    private static String labelFor(final NatRow nat) {
        final StringBuilder sb = new StringBuilder();
        sb.append(nat.type);
        if (nat.externalIp != null) {
            sb.append(":").append(nat.externalIp);
        }
        if (nat.logicalIp != null) {
            sb.append("->").append(nat.logicalIp);
        }
        return sb.toString();
    }

    /**
     * Deterministic synthetic id per (OVN UUID, Kind). Stable so re-imports
     * collide on the {@code (cs_kind, cs_id, controller_id)} unique key.
     */
    static long synthesise(final String ovnUuid, final Kind kind) {
        final String mix = kind.name() + ":" + ovnUuid;
        final long hash = mix.hashCode() & SYNTHETIC_ID_MASK;
        return SYNTHETIC_ID_RANGE + (hash % SYNTHETIC_ID_RANGE);
    }
}
