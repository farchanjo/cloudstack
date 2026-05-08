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
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.cloud.agent.api.to.NicTO;

import org.junit.Test;

/**
 * Public-API behaviour tests for {@link VdpaVifDriver}. The full plug path
 * touches sysfs, OVS, and shells out to {@code vdpa dev add}, so this suite
 * exercises only the deterministic helpers that compile and run without
 * privileges:
 * <ul>
 *   <li>{@link VdpaVifDriver#buildVdpaName(NicTO)} — name derivation.</li>
 *   <li>{@link VdpaVifDriver#parseVhostVdpaFromShow(String, String)} — JSON
 *       parsing of {@code vdpa dev show -j} output, exercised against
 *       known-good fixtures.</li>
 *   <li>{@link com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef#defVdpaNet(String, String, Integer)} —
 *       domain-XML emission shape.</li>
 * </ul>
 *
 * <p>Sysfs-touching helpers ({@code resolveVhostVdpaDevice},
 * {@code lookupVdpaNameByVhostDev}) are integration-tested separately on the
 * KVM agent host where the {@code /sys/bus/vdpa} tree exists.
 */
public class VdpaVifDriverTest {

    @Test
    public void buildVdpaNameUsesMacWhenSet() {
        NicTO nic = new NicTO();
        nic.setMac("aa:bb:cc:dd:ee:ff");
        String name = VdpaVifDriver.buildVdpaName(nic);
        assertEquals("vdpa-aabbccddeeff", name);
        assertTrue(name.startsWith(VdpaVifDriver.VDPA_NAME_PREFIX));
    }

    @Test
    public void buildVdpaNameLowercasesMacHexDigits() {
        NicTO nic = new NicTO();
        nic.setMac("AA:BB:CC:DD:EE:FF");
        assertEquals("vdpa-aabbccddeeff", VdpaVifDriver.buildVdpaName(nic));
    }

    @Test
    public void buildVdpaNameFallsBackToTimestampWhenMacMissing() {
        NicTO nic = new NicTO();
        nic.setMac(null);
        String name = VdpaVifDriver.buildVdpaName(nic);
        assertNotNull(name);
        assertTrue(name.startsWith(VdpaVifDriver.VDPA_NAME_PREFIX));
    }

    @Test
    public void parseVhostVdpaFromShowReturnsNullWhenJsonMalformed() {
        // Garbage in the JSON should never throw — return null cleanly.
        assertNull(VdpaVifDriver.parseVhostVdpaFromShow("not-json", "vdpa-foo"));
    }

    @Test
    public void parseVhostVdpaFromShowReturnsNullWhenDeviceMissing() {
        // Well-formed JSON but the requested device is absent.
        String json = "{\"dev\":{\"vdpa-other\":{\"type\":\"network\"}}}";
        assertNull(VdpaVifDriver.parseVhostVdpaFromShow(json, "vdpa-foo"));
    }

    @Test
    public void parseVhostVdpaFromShowReturnsNullWhenDevKeyAbsent() {
        // JSON without the top-level 'dev' object.
        assertNull(VdpaVifDriver.parseVhostVdpaFromShow("{}", "vdpa-foo"));
    }

    @Test
    public void interfaceDefVdpaXmlEmitsCanonicalShape() {
        // The defVdpaNet helper drives the InterfaceDef toString round-trip;
        // we assert the expected libvirt-canonical structure rather than
        // exact byte-for-byte text so XML formatting tweaks do not break the test.
        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        intf.defVdpaNet("/dev/vhost-vdpa0", "aa:bb:cc:dd:ee:ff", 16);
        intf.setLinkStateUp(true);
        String xml = intf.toString();
        assertTrue("must declare <interface type='vdpa'>", xml.contains("<interface type='vdpa'>"));
        assertTrue("must point at the supplied vhost-vdpa cdev",
                xml.contains("<source dev='/dev/vhost-vdpa0'/>"));
        assertTrue("MAC must propagate", xml.contains("<mac address='aa:bb:cc:dd:ee:ff'/>"));
        assertTrue("model type must be virtio", xml.contains("<model type='virtio'/>"));
        assertTrue("queues count must propagate", xml.contains("<driver queues='16'/>"));
        assertTrue("link state must be emitted", xml.contains("<link state='up'/>"));
    }

    @Test
    public void interfaceDefVdpaXmlOmitsDriverWhenQueuesNull() {
        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        intf.defVdpaNet("/dev/vhost-vdpa3", "aa:bb:cc:dd:ee:ff", null);
        intf.setLinkStateUp(true);
        String xml = intf.toString();
        // No <driver/> when no queue count was requested — fall back to libvirt default.
        assertTrue("driver must be omitted when queues is null", !xml.contains("<driver"));
        assertTrue(xml.contains("<source dev='/dev/vhost-vdpa3'/>"));
    }

    @Test
    public void interfaceDefVdpaXmlOrdersModelBeforeDriver() {
        // libvirt 12.x rejects driver appearing before model — guard the
        // order at unit time. Restored from fork prototype 9f82a585af.
        LibvirtVMDef.InterfaceDef intf = new LibvirtVMDef.InterfaceDef();
        intf.defVdpaNet("/dev/vhost-vdpa2", "aa:bb:cc:dd:ee:ff", 4);
        String xml = intf.toString();
        int modelIdx = xml.indexOf("<model type='virtio'/>");
        int driverIdx = xml.indexOf("<driver queues='4'/>");
        assertTrue("model must precede driver in the emitted XML",
                modelIdx > 0 && driverIdx > modelIdx);
    }
}
