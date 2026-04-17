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
package com.cloud.network.router;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CreateSfAnswer;
import com.cloud.agent.api.CreateSfCommand;
import com.cloud.agent.api.DestroySfCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.network.router.dao.SriovSfPoolDao;
import com.cloud.utils.component.ManagerBase;

@Component
public class SfPoolManagerImpl extends ManagerBase implements SfPoolManager {

    private static final Logger LOGGER = LogManager.getLogger(SfPoolManagerImpl.class);
    private static final int MAX_SF_PER_PF = 64;

    @Inject
    private SriovSfPoolDao sfPoolDao;
    @Inject
    private AgentManager agentMgr;

    @Override
    public boolean configure(String name, java.util.Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    @Override
    public SriovSfPoolVO allocate(long hostId, long nicId) throws InsufficientCapacityException {
        // Try to find an existing VDPA_READY SF
        SriovSfPoolVO sf = sfPoolDao.allocate(hostId, nicId);
        if (sf != null) {
            LOGGER.info("Allocated existing SF sfIndex={} vdpa={} on host {} for NIC {}",
                    sf.getSfIndex(), sf.getVdpaDevice(), hostId, nicId);
            return sf;
        }

        // No pre-created SF available — create one on-demand
        int pfIndex = 0;
        int sfIndex = sfPoolDao.getNextAvailableSfIndex(hostId, pfIndex);
        if (sfIndex >= MAX_SF_PER_PF) {
            pfIndex = 1;
            sfIndex = sfPoolDao.getNextAvailableSfIndex(hostId, pfIndex);
            if (sfIndex >= MAX_SF_PER_PF) {
                throw new InsufficientServerCapacityException(
                        "No SF capacity on host " + hostId + " (max " + MAX_SF_PER_PF + " per PF)", Host.class, hostId);
            }
        }

        String pfPci = (pfIndex == 0) ? "0000:01:00.0" : "0000:01:00.1";
        String mac = String.format("02:04:00:%02x:%02x:%02x",
                (int)(Math.random() * 256), (int)(Math.random() * 256), (int)(Math.random() * 256));

        CreateSfCommand cmd = new CreateSfCommand(pfPci, pfIndex, sfIndex, mac);
        CreateSfAnswer answer;
        try {
            answer = (CreateSfAnswer) agentMgr.send(hostId, cmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new InsufficientServerCapacityException(
                    "Agent unavailable on host " + hostId + " for SF creation: " + e.getMessage(), Host.class, hostId);
        }

        if (answer == null || !answer.getResult()) {
            throw new InsufficientServerCapacityException(
                    "Failed to create SF on host " + hostId + ": " + (answer != null ? answer.getDetails() : "null answer"),
                    Host.class, hostId);
        }

        SriovSfPoolVO newSf = new SriovSfPoolVO(hostId, pfIndex, sfIndex);
        newSf.setDevlinkPortHandle(answer.getDevlinkPortHandle());
        newSf.setSfNetdevName(answer.getSfNetdevName());
        newSf.setRepresentorName(answer.getRepresentorName());
        newSf.setVdpaDevice(answer.getVdpaDevice());
        newSf.setState(SriovSfPoolVO.State.ALLOCATED);
        newSf.setAllocatedToNicId(nicId);
        sfPoolDao.persist(newSf);

        LOGGER.info("Created and allocated SF sfIndex={} pf={} vdpa={} rep={} on host {} for NIC {}",
                sfIndex, pfIndex, answer.getVdpaDevice(), answer.getRepresentorName(), hostId, nicId);
        return newSf;
    }

    @Override
    public boolean release(long sfPoolId) {
        SriovSfPoolVO sf = sfPoolDao.findById(sfPoolId);
        if (sf == null) {
            return false;
        }
        return destroyAndRelease(sf);
    }

    @Override
    public boolean releaseByNicId(long nicId) {
        return sfPoolDao.releaseByNicId(nicId);
    }

    @Override
    public int countAvailable(long hostId) {
        int used = sfPoolDao.countByHostAndState(hostId, SriovSfPoolVO.State.ALLOCATED.name());
        return (MAX_SF_PER_PF * 2) - used;
    }

    private boolean destroyAndRelease(SriovSfPoolVO sf) {
        try {
            DestroySfCommand cmd = new DestroySfCommand(
                    sf.getDevlinkPortHandle(), sf.getVdpaDevice(), sf.getRepresentorName());
            agentMgr.send(sf.getHostId(), cmd);
        } catch (Exception e) {
            LOGGER.warn("Failed to destroy SF {} on host {}: {}", sf.getSfIndex(), sf.getHostId(), e.getMessage());
        }
        sfPoolDao.remove(sf.getId());
        LOGGER.info("Released and destroyed SF sfIndex={} on host {}", sf.getSfIndex(), sf.getHostId());
        return true;
    }
}
