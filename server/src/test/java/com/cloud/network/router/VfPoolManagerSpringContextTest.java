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

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import org.apache.cloudstack.poll.BackgroundPollManager;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.cloud.agent.AgentManager;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.vm.ItWorkDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

public class VfPoolManagerSpringContextTest {

    @Test
    public void springContextWiresManagerLeaderAndDefaultOffConfigSurface() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SriovVfPoolDao.class, () -> mock(SriovVfPoolDao.class));
            context.registerBean(AgentManager.class, () -> mock(AgentManager.class));
            context.registerBean(BackgroundPollManager.class, () -> mock(BackgroundPollManager.class));
            context.registerBean(NicDao.class, () -> mock(NicDao.class));
            context.registerBean(VMInstanceDao.class, () -> mock(VMInstanceDao.class));
            context.registerBean(ItWorkDao.class, () -> mock(ItWorkDao.class));
            context.registerBean(ManagementServerHostDao.class, () -> mock(ManagementServerHostDao.class));
            context.register(VfPoolReconcileLeader.class, VfPoolManagerImpl.class);
            context.refresh();

            final VfPoolManagerImpl manager = context.getBean(VfPoolManagerImpl.class);
            assertNotNull(manager);
            assertNotNull(context.getBean(VfPoolReconcileLeader.class));
            assertNotNull(manager.getConfigKeys());
        }
    }
}
