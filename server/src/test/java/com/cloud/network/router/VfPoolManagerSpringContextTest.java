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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.poll.BackgroundPollManager;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.network.router.dao.SriovVfPoolDao;
import com.cloud.vm.ItWorkDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

public class VfPoolManagerSpringContextTest {

    private static final String CONTEXT_RESOURCE =
            "META-INF/cloudstack/core/spring-server-core-managers-context.xml";
    private static final String LEADER_BEAN_NAME = "vfPoolReconcileLeader";
    private static final String MANAGER_BEAN_NAME = "vfPoolManagerImpl";

    @Test
    public void productionContextDeclaresLeaderImmediatelyBeforeManager() {
        final DefaultListableBeanFactory factory = loadProductionBeanDefinitions();
        assertTrue(factory.containsBeanDefinition(LEADER_BEAN_NAME));
        assertEquals(VfPoolReconcileLeader.class.getName(),
                factory.getBeanDefinition(LEADER_BEAN_NAME).getBeanClassName());

        final List<String> beanNames = Arrays.asList(factory.getBeanDefinitionNames());
        assertEquals(beanNames.indexOf(MANAGER_BEAN_NAME) - 1, beanNames.indexOf(LEADER_BEAN_NAME));
    }

    @Test
    public void focusedProductionContextStartsWithLeaderInjectedIntoManager() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            final Set<String> infrastructureBeanNames =
                    new HashSet<>(Arrays.asList(context.getBeanFactory().getBeanDefinitionNames()));
            new XmlBeanDefinitionReader(context).loadBeanDefinitions(new ClassPathResource(CONTEXT_RESOURCE));
            retainVfPoolProductionBeans(context.getDefaultListableBeanFactory(), infrastructureBeanNames);
            context.registerBean(SriovVfPoolDao.class, () -> mock(SriovVfPoolDao.class));
            context.registerBean(AgentManager.class, () -> mock(AgentManager.class));
            context.registerBean(BackgroundPollManager.class, () -> mock(BackgroundPollManager.class));
            context.registerBean(NicDao.class, () -> mock(NicDao.class));
            context.registerBean(VMInstanceDao.class, () -> mock(VMInstanceDao.class));
            context.registerBean(ItWorkDao.class, () -> mock(ItWorkDao.class));
            context.registerBean(ManagementServerHostDao.class, () -> mock(ManagementServerHostDao.class));
            context.refresh();

            final VfPoolReconcileLeader leader = context.getBean(LEADER_BEAN_NAME, VfPoolReconcileLeader.class);
            final VfPoolManagerImpl manager = context.getBean(MANAGER_BEAN_NAME, VfPoolManagerImpl.class);
            assertSame(leader, ReflectionTestUtils.getField(manager, "reconcileLeader"));
            assertNotNull(manager.getConfigKeys());
        }
    }

    private static DefaultListableBeanFactory loadProductionBeanDefinitions() {
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource(CONTEXT_RESOURCE));
        return factory;
    }

    private static void retainVfPoolProductionBeans(final DefaultListableBeanFactory factory,
                                                     final Set<String> infrastructureBeanNames) {
        for (final String beanName : factory.getBeanDefinitionNames()) {
            if (!infrastructureBeanNames.contains(beanName)
                    && !LEADER_BEAN_NAME.equals(beanName) && !MANAGER_BEAN_NAME.equals(beanName)) {
                factory.removeBeanDefinition(beanName);
            }
        }
    }
}
