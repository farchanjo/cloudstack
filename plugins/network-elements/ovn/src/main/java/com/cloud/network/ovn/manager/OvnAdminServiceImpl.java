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
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.api.response.OvnControllerResponse;
import com.cloud.network.ovn.api.response.OvnLogicalIdResponse;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class OvnAdminServiceImpl implements OvnAdminService {

    private static final Logger LOGGER = LogManager.getLogger(OvnAdminServiceImpl.class);

    @Inject
    private OvnControllerDao controllerDao;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPluginManager pluginManager;

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
        // MVP scope: validate input + verify the LR exists. Full NIC adoption
        // is deferred to a follow-up pass.
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new CloudRuntimeException("no OVN controller registered in zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        if (!nb.ping()) {
            throw new CloudRuntimeException("OVN NB unreachable at [" + controller.getNbEndpoints() + "]");
        }
        LOGGER.warn("importOvnVpc Phase I.5 stub: validated zone={} ovnLr={} vpcName={}; "
                + "full NIC adoption is deferred (TODO)", zoneId, ovnLrName, vpcName);
        // Until the full import flow lands, return an empty list so the
        // operator gets a clear "nothing was imported yet" signal.
        return Collections.emptyList();
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
}
