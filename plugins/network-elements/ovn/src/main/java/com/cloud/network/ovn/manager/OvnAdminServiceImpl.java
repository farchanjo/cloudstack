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
package com.cloud.network.ovn.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.api.response.OvnControllerResponse;
import com.cloud.network.ovn.api.response.OvnLogicalIdResponse;
import com.cloud.network.ovn.api.response.OvnReconcileResultResponse;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnNbReader;
import com.cloud.network.ovn.client.OvnNbReader.Topology;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class OvnAdminServiceImpl implements OvnAdminService {

    private static final Logger LOGGER = LogManager.getLogger(OvnAdminServiceImpl.class);

    @Inject
    private OvnControllerDao controllerDao;
    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnVpcImporter vpcImporter;
    @Inject
    private OvnReconcilerService reconcilerService;

    @Override
    public OvnControllerResponse addController(final long zoneId, final String name,
                                               final String nbEndpoints, final String sbEndpoints) {
        if (controllerDao.findByZoneAndName(zoneId, name) != null) {
            throw new CloudRuntimeException("an OVN controller named '" + name + "' already exists in zone " + zoneId);
        }
        final OvnControllerVO row = new OvnControllerVO(zoneId, name, nbEndpoints, sbEndpoints);
        final OvnControllerVO persisted = controllerDao.persist(row);
        validateConnectivity(persisted);
        pluginManager.invalidate(zoneId);
        return toResponse(persisted);
    }

    @Override
    public void deleteController(final String uuid) {
        final OvnControllerVO row = controllerDao.findByUuid(uuid);
        if (row == null) {
            throw new CloudRuntimeException("no OVN controller with uuid=" + uuid);
        }
        controllerDao.remove(row.getId());
        pluginManager.invalidate(row.getZoneId());
    }

    @Override
    public List<OvnControllerResponse> listControllers(final Long zoneId) {
        final List<OvnControllerVO> rows;
        if (zoneId == null) {
            rows = new ArrayList<>(controllerDao.listAll());
        } else {
            rows = controllerDao.listByZone(zoneId);
        }
        final List<OvnControllerResponse> out = new ArrayList<>();
        for (final OvnControllerVO row : rows) {
            out.add(toResponse(row));
        }
        return out;
    }

    @Override
    public List<OvnLogicalIdResponse> importVpc(final long zoneId, final String ovnLrName, final String vpcName) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new CloudRuntimeException("no OVN controller registered in zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        if (!nb.ping()) {
            throw new CloudRuntimeException("OVN NB unreachable at [" + controller.getNbEndpoints() + "]");
        }
        // Snapshot the OVN topology BEFORE any CloudStack write.
        final OvnNbReader reader = pluginManager.nbReader(zoneId);
        final Topology topology = reader.findLogicalRouter(ovnLrName);
        if (topology == null) {
            throw new CloudRuntimeException("OVN logical router '" + ovnLrName + "' not found in zone " + zoneId);
        }
        return adoptTopology(topology, controller.getId(), vpcName);
    }

    /**
     * Validates + persists the parsed topology. Visible for unit tests so the
     * import flow can be exercised with a synthetic {@link Topology} without
     * standing up a real NB client.
     */
    public List<OvnLogicalIdResponse> adoptTopology(final Topology topology, final long controllerId,
                                                    final String vpcName) {
        try {
            final OvnImportValidator.Plan plan = OvnImportValidator.validate(topology);
            final List<OvnLogicalIdMapVO> rows = vpcImporter.adopt(plan, controllerId, vpcName);
            final List<OvnLogicalIdResponse> out = new ArrayList<>();
            for (final OvnLogicalIdMapVO row : rows) {
                out.add(toLogicalIdResponse(row));
            }
            LOGGER.info("importOvnVpc adopted lr={} -> {} mapping rows under controller id={}",
                    topology.lr.name, rows.size(), controllerId);
            return out;
        } catch (final OvnException oe) {
            // Translate OVN-specific errors so the caller sees a clean ServerApiException.
            throw new CloudRuntimeException(oe.getMessage());
        }
    }

    private OvnLogicalIdResponse toLogicalIdResponse(final OvnLogicalIdMapVO row) {
        final OvnLogicalIdResponse r = new OvnLogicalIdResponse();
        r.setKind(row.getCsKind());
        r.setCsId(row.getCsId());
        r.setOvnUuid(row.getOvnUuid());
        r.setOvnName(row.getOvnName());
        r.setObjectName("ovnlogicalid");
        return r;
    }

    private void validateConnectivity(final OvnControllerVO row) {
        try (OvnNbClient nb = OvnNbClient.fromCsv(row.getNbEndpoints())) {
            if (!nb.ping()) {
                LOGGER.warn("OVN NB ping failed for [{}]; controller persisted but unreachable",
                        row.getNbEndpoints());
            }
        } catch (final OvnException oe) {
            LOGGER.warn("OVN NB connectivity probe raised: {}", oe.getMessage());
        }
    }

    private OvnControllerResponse toResponse(final OvnControllerVO row) {
        final OvnControllerResponse r = new OvnControllerResponse();
        r.setUuid(row.getUuid());
        r.setName(row.getName());
        r.setZoneId(String.valueOf(row.getZoneId()));
        r.setNbEndpoints(row.getNbEndpoints());
        r.setSbEndpoints(row.getSbEndpoints());
        r.setObjectName("ovncontroller");
        return r;
    }

    @Override
    public OvnReconcileResultResponse runReconciler(final long zoneId, final boolean dryRun,
                                                     final boolean purgeUntagged) {
        return runReconciler(zoneId, dryRun, purgeUntagged, null, null);
    }

    @Override
    public OvnReconcileResultResponse runReconciler(final long zoneId, final boolean dryRun,
                                                     final boolean purgeUntagged, final String resourceKind,
                                                     final Long resourceId) {
        if ((resourceKind == null) != (resourceId == null)) {
            throw new CloudRuntimeException("resourcekind and resourceid must be supplied together");
        }
        if (resourceKind != null && purgeUntagged) {
            throw new CloudRuntimeException("purgeuntagged is not valid for scoped reconciliation");
        }
        try {
            final OvnReconcilerService.Result result;
            if (resourceKind == null) {
                result = reconcilerService.reconcileZone(zoneId, dryRun, purgeUntagged);
            } else {
                final ScopedKind scoped = parseScopedKind(resourceKind);
                result = reconcilerService.reconcileResource(zoneId, scoped.kind, resourceId, dryRun);
            }
            return toReconcileResponse(result);
        } catch (OvnException e) {
            throw new CloudRuntimeException("OVN reconciler failed for zone " + zoneId + ": " + e.getMessage());
        }
    }

    /** Internal (kind, api-label) pair so the API layer can surface the
     *  accepted resourcekind values (LOAD_BALANCER, VPC, OVS_POLICY) while
     *  the service layer dispatches on the internal {Kind} enum. OVS_POLICY
     *  maps to {Kind#NIC} because the service uses NIC as the resourcekind
     *  token for "host port sweep" (no new enum value is introduced); the
     *  host id is carried by {resourceId}. */
    private static final class ScopedKind {
        final Kind kind;
        ScopedKind(final Kind kind) { this.kind = kind; }
    }

    private ScopedKind parseScopedKind(final String value) {
        if (value == null) {
            throw new CloudRuntimeException("scoped OVN reconciliation requires resourcekind "
                    + "(LOAD_BALANCER | VPC | OVS_POLICY)");
        }
        final String token = value.trim().toUpperCase(Locale.ROOT);
        switch (token) {
            case "LOAD_BALANCER":
                return new ScopedKind(Kind.LOAD_BALANCER);
            case "VPC":
                return new ScopedKind(Kind.VPC);
            case "OVS_POLICY":
                // OVS_POLICY is keyed by host id (carried on resourceId); the
                // internal Kind.NIC token is the dispatch hook in
                // OvnReconcilerService.reconcileResource.
                return new ScopedKind(Kind.NIC);
            default:
                throw new CloudRuntimeException("scoped OVN reconciliation supports only resourcekind="
                        + "LOAD_BALANCER | VPC | OVS_POLICY");
        }
    }

    private OvnReconcileResultResponse toReconcileResponse(final OvnReconcilerService.Result result) {
        final OvnReconcileResultResponse response = new OvnReconcileResultResponse();
        response.setDryRun(result.isDryRun());
        response.setOrphansByTable(result.getOrphansByTable());
        response.setStaleMappingsByTable(result.getStaleMappingsByTable());
        response.setAcksByTable(result.getAcksByTable());
        response.setTotalOrphans(result.totalOrphans());
        response.setTotalStaleMappings(result.totalStaleMappings());
        response.setObjectName("ovnreconcile");
        return response;
    }
}
