/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.hypervisor.kvm.resource;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;

/**
 * Regression coverage for Bug 23 (2026-05-11).
 *
 * <p>{@link LibvirtDomainXMLParser#parseDomainXML(String)} previously walked
 * every {@code <interface>} element, then matched only on {@code bridge},
 * {@code network}, {@code ethernet}, {@code vhostuser}, and {@code hostdev}
 * types, leaving {@code <interface type='vdpa'>} parsed into a phantom
 * {@link InterfaceDef} with {@code mac=null} and {@code netType=null}.
 *
 * <p>That phantom row leaked into every MAC-based libvirt lookup
 * ({@code SetupGuestNetworkCommand}, hot-plug correlation, post-migrate
 * stamp, NIC delete). Multi-tier VPC VR boots with mixed vDPA + VF + TAP
 * tier offerings deterministically failed with
 * {@code "Can not find nic with mac <vdpa-mac>"}, aborting the
 * {@code StartCommand} batch ({@code OnError.Stop}) and leaving the
 * non-HW-offload tier {@code PlugNicCommand} unprocessed (downstream
 * symptom captured in audit 2026-05-11-bug-22-vr-tier-nic-dropped.md).
 *
 * <p>This suite exercises the parser against canonical CloudStack vDPA
 * domain XML and asserts the returned list contains a real
 * {@link InterfaceDef} with the expected MAC, type, and source path.
 */
@RunWith(MockitoJUnitRunner.class)
public class LibvirtDomainXMLParserVdpaTest {

    private static final String VDPA_MAC = "02:04:02:53:00:17";
    private static final String VDPA_PATH = "/dev/vhost-vdpa-8";
    private static final String VF_MAC = "02:04:02:54:00:17";
    private static final String VF_PCI = "0000:01:02.5";
    private static final String BRIDGE_MAC = "0e:00:a9:fe:70:44";

    /**
     * Canonical CloudStack VR libvirt XML with three NICs:
     * Control bridge + tier-vDPA + tier-VF passthrough. Mirrors the
     * {@code virsh dumpxml r-1165-VM} output captured on aragog at
     * 2026-05-11 05:06 UTC.
     */
    private String vrDomainXml() {
        return "<domain type='kvm' id='42'>"
                + "<name>r-1165-VM</name>"
                + "<uuid>97cebb55-a68f-4b2e-8545-8f0cdf5f9087</uuid>"
                + "<memory unit='KiB'>262144</memory>"
                + "<vcpu placement='static'>1</vcpu>"
                + "<os><type arch='x86_64' machine='pc-i440fx-2.5'>hvm</type><boot dev='hd'/></os>"
                + "<devices>"
                + "  <interface type='bridge'>"
                + "    <mac address='" + BRIDGE_MAC + "'/>"
                + "    <source bridge='cloud0'/>"
                + "    <target dev='vnet5'/>"
                + "    <model type='virtio'/>"
                + "  </interface>"
                + "  <interface type='vdpa'>"
                + "    <source dev='" + VDPA_PATH + "'/>"
                + "    <mac address='" + VDPA_MAC + "'/>"
                + "    <model type='virtio'/>"
                + "    <driver queues='16'/>"
                + "  </interface>"
                + "  <interface type='hostdev' managed='yes'>"
                + "    <mac address='" + VF_MAC + "'/>"
                + "    <source>"
                + "      <address type='pci' domain='0x0000' bus='0x01' slot='0x02' function='0x5'/>"
                + "    </source>"
                + "  </interface>"
                + "</devices>"
                + "</domain>";
    }

    @Test
    public void parseDomainXmlExposesVdpaInterface() {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Assert.assertTrue("parseDomainXML must succeed", parser.parseDomainXML(vrDomainXml()));

        List<InterfaceDef> interfaces = parser.getInterfaces();
        Assert.assertEquals("expected 3 parsed interfaces", 3, interfaces.size());

        InterfaceDef vdpa = findByMac(interfaces, VDPA_MAC);
        Assert.assertNotNull("vDPA interface with MAC " + VDPA_MAC + " must be parsed", vdpa);
        Assert.assertEquals(InterfaceDef.GuestNetType.VDPA, vdpa.getNetType());
        Assert.assertEquals(VDPA_PATH, vdpa.getBrName());
        Assert.assertEquals(VDPA_MAC, vdpa.getMacAddress());
    }

    @Test
    public void macLookupResolvesVdpaInterface() {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Assert.assertTrue(parser.parseDomainXML(vrDomainXml()));

        InterfaceDef match = null;
        for (InterfaceDef def : parser.getInterfaces()) {
            if (def.getMacAddress() != null && def.getMacAddress().equalsIgnoreCase(VDPA_MAC)) {
                match = def;
                break;
            }
        }
        Assert.assertNotNull("SetupGuestNetwork MAC lookup must find the vDPA NIC", match);
        Assert.assertEquals(InterfaceDef.GuestNetType.VDPA, match.getNetType());
    }

    @Test
    public void parserDoesNotLeakPhantomNullMacEntries() {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Assert.assertTrue(parser.parseDomainXML(vrDomainXml()));
        for (InterfaceDef def : parser.getInterfaces()) {
            Assert.assertNotNull("no parsed interface should carry a null MAC", def.getMacAddress());
            Assert.assertNotNull("no parsed interface should carry a null netType", def.getNetType());
        }
    }

    @Test
    public void hostdevAndBridgeStillParseAlongsideVdpa() {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Assert.assertTrue(parser.parseDomainXML(vrDomainXml()));

        InterfaceDef bridge = findByMac(parser.getInterfaces(), BRIDGE_MAC);
        Assert.assertNotNull(bridge);
        Assert.assertEquals(InterfaceDef.GuestNetType.BRIDGE, bridge.getNetType());

        InterfaceDef hostdev = findByMac(parser.getInterfaces(), VF_MAC);
        Assert.assertNotNull(hostdev);
        Assert.assertEquals(InterfaceDef.GuestNetType.HOSTDEV, hostdev.getNetType());
        Assert.assertEquals(VF_PCI, hostdev.getPciAddress());
    }

    private InterfaceDef findByMac(List<InterfaceDef> interfaces, String mac) {
        for (InterfaceDef def : interfaces) {
            if (mac.equalsIgnoreCase(def.getMacAddress())) {
                return def;
            }
        }
        return null;
    }
}
