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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

    @Test
    public void testVcpuAndEmulatorPinAreInjected() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("16-63,80-127", "0-14,64-78", "", "", "");
        assertTrue(tuner.isEnabled());

        String result = tuner.transform(DOMAIN_XML);

        for (int i = 0; i < 4; i++) {
            assertTrue("Missing vcpupin for vcpu " + i,
                    result.contains("<vcpupin vcpu=\"" + i + "\" cpuset=\"16-63,80-127\""));
        }
        assertTrue(result.contains("<emulatorpin cpuset=\"0-14,64-78\""));
    }

    @Test
    public void testExistingCpuTuneElementsAreOverwrittenNotDuplicated() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("16-63,80-127", "0-14,64-78", "", "", "");

        String result = tuner.transform(DOMAIN_XML_WITH_CPUTUNE);

        assertEquals(1, countOccurrences(result, "<cputune"));
        assertEquals(1, countOccurrences(result, "<emulatorpin"));
        assertEquals(4, countOccurrences(result, "<vcpupin"));
        assertTrue(result.contains("<emulatorpin cpuset=\"0-14,64-78\""));
        assertTrue(result.contains("<vcpupin vcpu=\"0\" cpuset=\"16-63,80-127\""));
        assertFalse(result.contains("0-15,64-79"));
        assertFalse(result.contains("cpuset=\"0-127\""));
    }

    @Test
    public void testBridgeRemapAppliedWithoutCpuTune() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("", "", "cplane", "br-cluster", "2011");
        assertTrue(tuner.isEnabled());

        String result = tuner.transform(DOMAIN_XML);

        assertTrue(result.contains("bridge=\"br-cluster\""));
        assertTrue(result.contains("<tag id=\"2011\""));
        assertFalse(result.contains("<cputune"));
    }

    @Test
    public void testAllBlankConfigurationIsDisabled() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("", "", "", "", "");
        assertFalse(tuner.isEnabled());
    }

    @Test
    public void testTransformFailsOpenOnInvalidXml() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("16-63,80-127", "0-14,64-78", "", "", "");
        assertEquals(NOT_XML, tuner.transform(NOT_XML));
    }

    @Test
    public void testTransformReturnsInputUnchangedWhenRootIsNotDomain() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("16-63,80-127", "0-14,64-78", "", "", "");
        assertEquals(NOT_DOMAIN_XML, tuner.transform(NOT_DOMAIN_XML));
    }

    @Test
    public void testBridgeRemapDisabledWhenSourceEqualsTarget() {
        LibvirtVmXmlTuner tuner = new LibvirtVmXmlTuner("", "", "cplane", "cplane", "2011");
        assertFalse(tuner.isEnabled());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
