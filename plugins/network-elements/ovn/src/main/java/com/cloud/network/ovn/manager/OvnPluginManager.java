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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.OvnNbReader;
import com.cloud.network.ovn.client.OvnSbClient;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnControllerVO;

/**
 * Composition root for the OVN plugin. Owns one {@link OvnNbClient} per
 * controller registration (keyed by zone id) and lazily opens connections.
 *
 * <p>Single point of authority for "give me the OVN client for zone X" so
 * the rest of the plugin (element / guru / NAT services) never reaches into
 * the DAO directly.
 */
@Component
public class OvnPluginManager {

    private static final Logger LOGGER = LogManager.getLogger(OvnPluginManager.class);

    @Inject
    private OvnControllerDao controllerDao;

    private final Map<Long, OvnNbClient> nbCache = new ConcurrentHashMap<>();
    private final Map<Long, OvnSbClient> sbCache = new ConcurrentHashMap<>();

    /**
     * Returns the NB client for the given zone, opening a new pool if needed.
     * Throws {@link OvnException} when the zone has no controller registered.
     */
    public OvnNbClient nbClient(final long zoneId) {
        return nbCache.computeIfAbsent(zoneId, this::openNbClient);
    }

    /**
     * Returns the SB client for the given zone (read-only diagnostics).
     */
    public OvnSbClient sbClient(final long zoneId) {
        return sbCache.computeIfAbsent(zoneId, this::openSbClient);
    }

    /**
     * Returns a fresh {@link OvnNbReader} sharing the NB client's connection
     * pool. Used by {@code importOvnVpc} to walk the existing topology
     * without holding a separate transport.
     */
    public OvnNbReader nbReader(final long zoneId) {
        return OvnNbReader.from(nbClient(zoneId));
    }

    /**
     * Returns the registered controller for the given zone, or {@code null}
     * when none is registered.
     */
    public OvnControllerVO findControllerForZone(final long zoneId) {
        final var rows = controllerDao.listByZone(zoneId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Drops cached clients for a controller. Called from the admin API on
     * removal so the next request observes the new state.
     */
    public void invalidate(final long zoneId) {
        final OvnNbClient nb = nbCache.remove(zoneId);
        if (nb != null) {
            nb.close();
        }
        final OvnSbClient sb = sbCache.remove(zoneId);
        if (sb != null) {
            sb.close();
        }
    }

    /**
     * Close every cached NB/SB client on bean shutdown. Without this a
     * graceful management-server stop / Spring context refresh leaked the
     * cached OVSDB TCP connections ({@link #invalidate} is zone-scoped and
     * only reachable from the admin API).
     */
    @PreDestroy
    public void shutdown() {
        for (final Long zoneId : nbCache.keySet()) {
            invalidate(zoneId);
        }
        for (final Long zoneId : sbCache.keySet()) {
            invalidate(zoneId);
        }
    }

    private OvnNbClient openNbClient(final long zoneId) {
        final OvnControllerVO controller = findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("no OVN controller registered for zone " + zoneId);
        }
        LOGGER.debug("opening OVN NB client for zone {} via [{}]", zoneId, controller.getNbEndpoints());
        return OvnNbClient.fromCsv(controller.getNbEndpoints());
    }

    private OvnSbClient openSbClient(final long zoneId) {
        final OvnControllerVO controller = findControllerForZone(zoneId);
        if (controller == null || controller.getSbEndpoints() == null || controller.getSbEndpoints().isEmpty()) {
            throw new OvnException("no OVN SB endpoints for zone " + zoneId);
        }
        return OvnSbClient.fromCsv(controller.getSbEndpoints());
    }
}
