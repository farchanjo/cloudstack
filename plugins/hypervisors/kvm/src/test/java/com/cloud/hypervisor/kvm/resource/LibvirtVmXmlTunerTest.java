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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Assertions on tuned XML re-parse the output into DOM instead of matching raw substrings:
 * the JDK serializer emits element attributes sorted by name, so substring checks that assume
 * a specific attribute order (e.g. vcpu before cpuset) are not reliable.
 */
public class LibvirtVmXmlTunerTest {

    private static final String DOMAIN_XML =
            "<domain type='kvm'><name>t</name><vcpu>4</vcpu><devices>"
            + "<interface type='bridge'><source bridge='cplane'/><model type='virtio'/></interface>"
            + "</devices></domain>";

    private static final String DOMAIN_XML_WITH_CPUTUNE =
            "<domain type='kvm'><name>t</name><vcpu>4</vcpu>"
            + "<cputune><emulatorpin cpuset='0-15,64-79'/><vcpupin vcpu='0' cpuset='0-127'/></cputune>"
            + "<devices><interface type='bridge'><source bridge='cplane'/><model type='virtio'/></interface>"
            + "</devices></domain>";

    private static final String NOT_XML = "not xml at all";
    private static final String NOT_DOMAIN_XML = "<notdomain/>";

    private static final String VCPU_POOL = "16-63,80-127";
    private static final String EMULATOR_POOL = "0-14,64-78";

    @Test
    public void testVcpuAndEmulatorPinAreInjected() throws Exception {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner(VCPU_POOL, EMULATOR_POOL, "", "", "");
        assertTrue(tuner.isEnabled());

        Document result = parseXml(tuner.transform(DOMAIN_XML));

        assertEquals(1, result.getElementsByTagName("cputune").getLength());
        assertEquals(4, result.getElementsByTagName("vcpupin").getLength());
        for (int i = 0; i < 4; i++) {
            Element pin = findVcpuPin(result, i);
            assertNotNull("Missing vcpupin for vcpu " + i, pin);
            assertEquals(VCPU_POOL, pin.getAttribute("cpuset"));
        }
        NodeList emulatorPins = result.getElementsByTagName("emulatorpin");
        assertEquals(1, emulatorPins.getLength());
        assertEquals(EMULATOR_POOL, ((Element) emulatorPins.item(0)).getAttribute("cpuset"));
    }

    @Test
    public void testExistingCpuTuneElementsAreOverwrittenNotDuplicated() throws Exception {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner(VCPU_POOL, EMULATOR_POOL, "", "", "");

        Document result = parseXml(tuner.transform(DOMAIN_XML_WITH_CPUTUNE));

        assertEquals(1, result.getElementsByTagName("cputune").getLength());
        NodeList emulatorPins = result.getElementsByTagName("emulatorpin");
        assertEquals(1, emulatorPins.getLength());
        assertEquals(EMULATOR_POOL, ((Element) emulatorPins.item(0)).getAttribute("cpuset"));
        assertEquals(4, result.getElementsByTagName("vcpupin").getLength());
        for (int i = 0; i < 4; i++) {
            Element pin = findVcpuPin(result, i);
            assertNotNull("Missing vcpupin for vcpu " + i, pin);
            assertEquals("Stale cpuset not overwritten for vcpu " + i, VCPU_POOL, pin.getAttribute("cpuset"));
        }
    }

    @Test
    public void testBridgeRemapAppliedWithoutCpuTune() throws Exception {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("", "", "cplane", "br-cluster", "2011");
        assertTrue(tuner.isEnabled());

        Document result = parseXml(tuner.transform(DOMAIN_XML));

        Element source = (Element) result.getElementsByTagName("source").item(0);
        assertNotNull(source);
        assertEquals("br-cluster", source.getAttribute("bridge"));
        NodeList tags = result.getElementsByTagName("tag");
        assertEquals(1, tags.getLength());
        assertEquals("2011", ((Element) tags.item(0)).getAttribute("id"));
        assertEquals(0, result.getElementsByTagName("cputune").getLength());
    }

    @Test
    public void testAllBlankConfigurationIsDisabled() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("", "", "", "", "");
        assertFalse(tuner.isEnabled());
    }

    @Test
    public void testTransformFailsOpenOnInvalidXml() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner(VCPU_POOL, EMULATOR_POOL, "", "", "");
        assertEquals(NOT_XML, tuner.transform(NOT_XML));
    }

    @Test
    public void testTransformReturnsInputUnchangedWhenRootIsNotDomain() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner(VCPU_POOL, EMULATOR_POOL, "", "", "");
        assertEquals(NOT_DOMAIN_XML, tuner.transform(NOT_DOMAIN_XML));
    }

    @Test
    public void testBridgeRemapDisabledWhenSourceEqualsTarget() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("", "", "cplane", "cplane", "2011");
        assertFalse(tuner.isEnabled());
    }

    private static Document parseXml(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element findVcpuPin(Document document, int index) {
        NodeList pins = document.getElementsByTagName("vcpupin");
        for (int i = 0; i < pins.getLength(); i++) {
            Element pin = (Element) pins.item(i);
            if (String.valueOf(index).equals(pin.getAttribute("vcpu"))) {
                return pin;
            }
        }
        return null;
    }
}
