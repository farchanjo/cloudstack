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
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Test;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.host.dao.HostDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.router.VfPoolManager;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

public class MigrationVfPreflightSpringContextTest {

    private static final String CONTEXT_RESOURCE =
            "META-INF/cloudstack/core/spring-engine-orchestration-core-context.xml";
    private static final String PREFLIGHT_BEAN_NAME = "migrationVfPreflight";

    @Test
    public void productionContextCreatesPreflightAndInjectsItIntoConsumer() {
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource(CONTEXT_RESOURCE));
        assertEquals(MigrationVfPreflight.class.getName(),
                factory.getBeanDefinition(PREFLIGHT_BEAN_NAME).getBeanClassName());

        registerDependencies(factory);
        factory.registerBeanDefinition("migrationPreflightServiceImpl", BeanDefinitionBuilder
                .genericBeanDefinition(MigrationPreflightServiceImpl.class)
                .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR)
                .getBeanDefinition());
        final MigrationPreflightServiceImpl consumer = factory.getBean("migrationPreflightServiceImpl",
                MigrationPreflightServiceImpl.class);

        assertSame(factory.getBean(PREFLIGHT_BEAN_NAME),
                ReflectionTestUtils.getField(consumer, "preflight"));
    }

    private static void registerDependencies(final DefaultListableBeanFactory factory) {
        factory.registerSingleton("vfPoolManager", mock(VfPoolManager.class));
        factory.registerSingleton("networkDao", mock(NetworkDao.class));
        factory.registerSingleton("networkOfferingDao", mock(NetworkOfferingDao.class));
        factory.registerSingleton("nicDao", mock(NicDao.class));
        factory.registerSingleton("hostDao", mock(HostDao.class));
        factory.registerSingleton("vmInstanceDao", mock(VMInstanceDao.class));
        factory.registerSingleton("serviceOfferingDao", mock(ServiceOfferingDao.class));
        factory.registerSingleton("networkOrchestrationService", mock(NetworkOrchestrationService.class));
    }
}
