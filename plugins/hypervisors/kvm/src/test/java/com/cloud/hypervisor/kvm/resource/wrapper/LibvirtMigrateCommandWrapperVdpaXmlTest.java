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
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.util.Map;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class LibvirtMigrateCommandWrapperVdpaXmlTest {

    @Test
    public void rewritesEveryVdpaSourceDeviceByMac() {
        final String xml = "<domain><devices>"
                + "<interface type='vdpa'><mac address='02:00:00:00:00:01'/><source dev='/dev/vhost-vdpa-old1'/></interface>"
                + "<interface type='vdpa'><mac address='02:00:00:00:00:02'/><source dev='/dev/vhost-vdpa-old2'/></interface>"
                + "</devices></domain>";

        final String rewritten = ReflectionTestUtils.invokeMethod(new LibvirtMigrateCommandWrapper(),
                "replaceVdpaInterfaces", xml, Map.of(
                        "02:00:00:00:00:01", "/dev/vhost-vdpa-new1",
                        "02:00:00:00:00:02", "/dev/vhost-vdpa-new2"));

        assertTrue(rewritten.contains("/dev/vhost-vdpa-new1"));
        assertTrue(rewritten.contains("/dev/vhost-vdpa-new2"));
    }

    @Test
    public void rejectsUnmappedVdpaSourceDevice() {
        final String xml = "<domain><devices><interface type='vdpa'>"
                + "<mac address='02:00:00:00:00:03'/><source dev='/dev/vhost-vdpa-old3'/></interface>"
                + "</devices></domain>";

        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                new LibvirtMigrateCommandWrapper(), "replaceVdpaInterfaces", xml, Map.of()));
    }

    @Test
    public void rejectsNullMappingWhenSourceContainsVdpa() {
        final String xml = "<domain><devices><interface type='vdpa'><mac address='02:00:00:00:00:03'/><source dev='/dev/vhost-vdpa-old3'/></interface></devices></domain>";
        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                new LibvirtMigrateCommandWrapper(), "replaceVdpaInterfaces", xml, null));
    }

    @Test
    public void acceptsEmptyMappingWhenSourceHasNoVdpa() {
        final String xml = "<domain><devices><interface type='bridge'><mac address='02:00:00:00:00:03'/></interface></devices></domain>";
        final String rewritten = ReflectionTestUtils.invokeMethod(new LibvirtMigrateCommandWrapper(),
                "replaceVdpaInterfaces", xml, Map.of());
        assertTrue(rewritten.contains("type=\"bridge\"") || rewritten.contains("type='bridge'"));
    }

    @Test
    public void rejectsPartialMultiNicVdpaMapping() {
        final String xml = "<domain><devices>"
                + "<interface type='vdpa'><mac address='02:00:00:00:00:01'/><source dev='/dev/vhost-vdpa-old1'/></interface>"
                + "<interface type='vdpa'><mac address='02:00:00:00:00:02'/><source dev='/dev/vhost-vdpa-old2'/></interface>"
                + "</devices></domain>";

        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                new LibvirtMigrateCommandWrapper(), "replaceVdpaInterfaces", xml,
                Map.of("02:00:00:00:00:01", "/dev/vhost-vdpa-new1")));
    }

    @Test
    public void rejectsExtraAndDuplicateDestinationMappings() {
        final String xml = "<domain><devices><interface type='vdpa'>"
                + "<mac address='02:00:00:00:00:01'/><source dev='/dev/vhost-vdpa-old'/></interface>"
                + "</devices></domain>";
        assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                new LibvirtMigrateCommandWrapper(), "replaceVdpaInterfaces", xml,
                Map.of("02:00:00:00:00:01", "/dev/vhost-vdpa-new",
                        "02:00:00:00:00:02", "/dev/vhost-vdpa-new")));
    }
}
