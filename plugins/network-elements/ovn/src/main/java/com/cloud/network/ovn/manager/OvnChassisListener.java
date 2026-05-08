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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.network.ovn.dao.OvnControllerDao;

/**
 * {@link Listener} that subscribes to {@link AgentManager} host-connect /
 * host-disconnect events and forwards them to {@link OvnChassisRegistrationService}
 * so the {@code (host_id, chassis_uuid, controller_id)} mapping stays in
 * sync with the live OVN deployment.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@code processConnect}: looks the host up in the SB DB by hostname
 *       and persists the mapping. Best-effort — exceptions are caught so
 *       a flaky NB / SB endpoint never blocks the agent registration path.
 *   <li>{@code processDisconnect}: leaves the mapping intact. The chassis row
 *       is the source of truth for cleanup later (e.g. host delete);
 *       transient agent disconnects must not bin it.
 *   <li>Skips entirely when no OVN controller is registered for the host's
 *       zone — the plugin is opt-in by the operator.
 * </ul>
 *
 * <p>The listener registers itself in {@link #afterPropertiesSet()} via
 * {@link AgentManager#registerForHostEvents} with {@code connections=true,
 * commands=false, priority=false}. It mirrors the pattern other network
 * plugins use ({@code TungstenElement}, {@code NsxElement}, {@code NetrisElement}).
 */
@Component
public class OvnChassisListener implements Listener {

    private static final Logger LOGGER = LogManager.getLogger(OvnChassisListener.class);

    @Inject
    private AgentManager agentManager;
    @Inject
    private OvnChassisRegistrationService registrationService;
    @Inject
    private OvnControllerDao controllerDao;

    private int registrationId = -1;

    @PostConstruct
    public void afterPropertiesSet() {
        if (agentManager == null) {
            LOGGER.debug("OvnChassisListener: AgentManager not injected; skipping registration");
            return;
        }
        registrationId = agentManager.registerForHostEvents(this, true, false, false);
        LOGGER.info("OvnChassisListener registered with AgentManager (id={})", registrationId);
    }

    @PreDestroy
    public void beforeShutdown() {
        if (agentManager != null && registrationId >= 0) {
            agentManager.unregisterForHostEvents(registrationId);
            LOGGER.info("OvnChassisListener unregistered (id={})", registrationId);
        }
    }

    @Override
    public void processConnect(final Host host, final StartupCommand cmd, final boolean forRebalance) {
        if (host == null) {
            return;
        }
        if (controllerDao.listByZone(host.getDataCenterId()).isEmpty()) {
            // Plugin not enabled in this zone — nothing to do.
            return;
        }
        try {
            registrationService.registerByHostname(host.getId());
        } catch (final RuntimeException re) {
            // Never block the agent connect path on a transient OVN error;
            // the listener will get another shot on the next reconnect.
            LOGGER.warn("OvnChassisListener: chassis registration for host id={} skipped: {}",
                    host.getId(), re.getMessage());
        }
    }

    @Override
    public boolean processDisconnect(final long agentId, final Status state) {
        // Leave the chassis row intact: a transient disconnect MUST NOT remove
        // mapping data the operator may still need for cleanup decisions.
        LOGGER.debug("OvnChassisListener: ignoring disconnect for host id={} state={}", agentId, state);
        return true;
    }

    @Override
    public boolean processAnswers(final long agentId, final long seq, final Answer[] answers) {
        return false;
    }

    @Override
    public boolean processCommands(final long agentId, final long seq, final Command[] commands) {
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(final long agentId, final AgentControlCommand cmd) {
        return null;
    }

    @Override
    public void processHostAdded(final long hostId) {
        // Handled in processConnect once the host transitions to Up.
    }

    @Override
    public void processHostAboutToBeRemoved(final long hostId) {
        // No-op: host removal goes through ResourceManager's delete path,
        // which the foreign key constraint handles via ON DELETE CASCADE.
    }

    @Override
    public void processHostRemoved(final long hostId, final long clusterId) {
        // No-op (see processHostAboutToBeRemoved).
    }

    @Override
    public boolean isRecurring() {
        return false;
    }

    @Override
    public int getTimeout() {
        return -1;
    }

    @Override
    public boolean processTimeout(final long agentId, final long seq) {
        return true;
    }

    /** Test-only seam: returns the AgentManager registration id. */
    public int getRegistrationId() {
        return registrationId;
    }
}
