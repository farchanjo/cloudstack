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
        try {
            final OvnReconcilerService.Result result = reconcilerService.reconcileZone(zoneId, dryRun, purgeUntagged);
            final OvnReconcileResultResponse r = new OvnReconcileResultResponse();
            r.setDryRun(result.isDryRun());
            r.setOrphansByTable(result.getOrphansByTable());
            r.setStaleMappingsByTable(result.getStaleMappingsByTable());
            r.setTotalOrphans(result.totalOrphans());
            r.setTotalStaleMappings(result.totalStaleMappings());
            r.setObjectName("ovnreconcile");
            return r;
        } catch (OvnException e) {
            throw new CloudRuntimeException("OVN reconciler failed for zone " + zoneId + ": " + e.getMessage());
        }
    }
}
