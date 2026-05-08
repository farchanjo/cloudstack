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
package com.cloud.network.ovn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;

import com.cloud.network.guru.GuestNetworkGuru;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.element.NetworkElement;

/**
 * Confirms the plugin's Spring context exposes the OVN-specific
 * {@link NetworkGuru} bean ({@code OvnGuestNetworkGuru}) and the
 * {@link NetworkElement} bean ({@code Ovn}) so the management server's
 * {@code List<NetworkGuru>} / {@code List<NetworkElement>} autowire picks
 * them up when the plugin classpath is loaded. Bean classes are resolved
 * via {@link Class#forName(String)} (no instantiation) so the test is
 * self-contained and does not need the rest of the CloudStack Spring graph.
 */
public class OvnSpringContextWiringTest {

    private static final String CONTEXT_RESOURCE =
            "META-INF/cloudstack/network-element-ovn/spring-network-ovn-context.xml";

    @Test
    public void ovnGuestNetworkGuruIsRegisteredAsNetworkGuru() throws Exception {
        final DefaultListableBeanFactory factory = loadFactory();
        final String beanName = "ovnGuestNetworkGuru";
        assertTrue("expected bean " + beanName + " in plugin context", factory.containsBeanDefinition(beanName));
        final String className = factory.getBeanDefinition(beanName).getBeanClassName();
        assertEquals("com.cloud.network.ovn.element.OvnGuestNetworkGuru", className);
        final Class<?> beanType = Class.forName(className);
        assertTrue("OvnGuestNetworkGuru must implement NetworkGuru so the management "
                + "server's collection-autowire discovers it",
                NetworkGuru.class.isAssignableFrom(beanType));
        assertTrue("OvnGuestNetworkGuru is expected to extend GuestNetworkGuru so the "
                + "default canHandle / design pipeline applies",
                GuestNetworkGuru.class.isAssignableFrom(beanType));
    }

    @Test
    public void ovnNetworkElementIsRegisteredUnderProviderName() throws Exception {
        final DefaultListableBeanFactory factory = loadFactory();
        final String beanName = "Ovn";
        assertTrue("plugin must register a bean named 'Ovn' so PhysicalNetworkServiceProvider "
                + "lookups by provider name resolve to the OVN element",
                factory.containsBeanDefinition(beanName));
        final String className = factory.getBeanDefinition(beanName).getBeanClassName();
        assertEquals("com.cloud.network.ovn.element.OvnNetworkElement", className);
        final Class<?> beanType = Class.forName(className);
        assertTrue("OvnNetworkElement must implement NetworkElement",
                NetworkElement.class.isAssignableFrom(beanType));
    }

    @Test
    public void contextHasNoStrayDuplicateGuruBean() throws Exception {
        final DefaultListableBeanFactory factory = loadFactory();
        int guruCount = 0;
        for (final String name : factory.getBeanDefinitionNames()) {
            final String className = factory.getBeanDefinition(name).getBeanClassName();
            if (className == null) {
                continue;
            }
            final Class<?> beanType = Class.forName(className);
            if (NetworkGuru.class.isAssignableFrom(beanType)) {
                guruCount++;
            }
        }
        assertEquals("plugin context must declare exactly one NetworkGuru bean", 1, guruCount);
    }

    @Test
    public void chassisRegistrationServiceBeanIsPresent() {
        final DefaultListableBeanFactory factory = loadFactory();
        assertTrue(factory.containsBeanDefinition("ovnChassisRegistrationService"));
        final String className = factory.getBeanDefinition("ovnChassisRegistrationService").getBeanClassName();
        assertEquals("com.cloud.network.ovn.manager.OvnChassisRegistrationService", className);
    }

    @Test
    public void contextResourceFileIsOnClasspath() {
        final ClassPathResource resource = new ClassPathResource(CONTEXT_RESOURCE);
        assertTrue("plugin spring context resource must be packaged into the plugin jar",
                resource.exists());
        assertNotNull(resource.getDescription());
        assertFalse(resource.getDescription().isEmpty());
    }

    private static DefaultListableBeanFactory loadFactory() {
        final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        final XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(factory);
        reader.loadBeanDefinitions(new ClassPathResource(CONTEXT_RESOURCE));
        return factory;
    }
}
