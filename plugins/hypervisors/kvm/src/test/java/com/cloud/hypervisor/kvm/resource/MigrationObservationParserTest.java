// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MigrationObservationParserTest {
    @Test
    public void domainIdentityUsesExactAttributesNotSubstrings() {
        final String xml = "<domain><devices><interface type='bridge'><mac address='aa:bb:cc:dd:ee:ff'/>"
                + "<alias name='net0'/><source bridge='br-int'/><target dev='tap0'/><model type='virtio'/>"
                + "</interface></devices></domain>";
        assertEquals("tap0", MigrationObservationParser.domainInterface(xml, "aa:bb:cc:dd:ee:ff",
                "net0", "tap0").target());
        assertNull(MigrationObservationParser.domainInterface(xml, "aa:bb:cc:dd:ee:00", "net0", "tap0x"));
    }

    @Test
    public void vdpaAndOvsJsonRequireExactRows() {
        assertEquals("vdpa0", MigrationObservationParser.vdpaDevice(
                "[{\"name\":\"vdpa0\",\"device\":\"/dev/vhost-vdpa-0\"}]",
                "vdpa0", "/dev/vhost-vdpa-0").name());
        assertNull(MigrationObservationParser.vdpaDevice(
                "[{\"name\":\"vdpa00\",\"device\":\"/dev/vhost-vdpa-0\"}]",
                "vdpa0", "/dev/vhost-vdpa-0"));
        assertEquals("rep0", MigrationObservationParser.ovsInterface(
                "[{\"name\":\"rep0\",\"bridge\":\"br-int\",\"port\":\"p0\","
                        + "\"external_ids\":{\"iface-id\":\"lsp-1\"}}]",
                "rep0", "lsp-1", "br-int", "p0").name());
    }

    @Test
    public void vfTopologyRequiresTheExactVfRow() {
        final String json = "[{\"ifname\":\"pf0\",\"vfinfo_list\":["
                + "{\"vf\":2,\"mac\":{\"address\":\"aa:bb:cc:dd:ee:ff\"},\"vlan\":2025}]}]";
        assertEquals(2, MigrationObservationParser.vfTopology(json, "pf0", 2,
                "aa:bb:cc:dd:ee:ff", "2025").vfId().intValue());
        assertNull(MigrationObservationParser.vfTopology(json, "pf0", 20,
                "aa:bb:cc:dd:ee:ff", "2025"));
    }

    @Test
    public void vfTopologyDiscoversOneExactUntaggedVfAndRejectsAmbiguity() {
        final String unique = "[{\"ifname\":\"pf0\",\"vfinfo_list\":["
                + "{\"vf\":2,\"mac\":{\"address\":\"aa:bb:cc:dd:ee:ff\"},\"vlan\":0},"
                + "{\"vf\":3,\"mac\":{\"address\":\"aa:bb:cc:dd:ee:00\"},\"vlan\":0}]}]";
        assertEquals(2, MigrationObservationParser.vfTopology(unique, "pf0", null,
                "aa:bb:cc:dd:ee:ff", null).vfId().intValue());
        assertNull(MigrationObservationParser.vfTopology(unique, "pf0", null,
                "aa:bb:cc:dd:ee:ff", "2025"));

        final String ambiguous = "[{\"ifname\":\"pf0\",\"vfinfo_list\":["
                + "{\"vf\":2,\"mac\":{\"address\":\"aa:bb:cc:dd:ee:ff\"},\"vlan\":0},"
                + "{\"vf\":3,\"mac\":{\"address\":\"aa:bb:cc:dd:ee:ff\"},\"vlan\":0}]}]";
        assertNull(MigrationObservationParser.vfTopology(ambiguous, "pf0", null,
                "aa:bb:cc:dd:ee:ff", null));
    }

    @Test
    public void vdpaDeviceNormalizesManagementBdf() {
        assertEquals("0000:01:00.2", MigrationObservationParser.vdpaDevice(
                "[{\"name\":\"vdpa0\",\"device\":\"/dev/vhost-vdpa-0\","
                        + "\"mgmtdev\":\"pci/0000:01:00.2\"}]",
                "vdpa0", "/dev/vhost-vdpa-0").managementBdf());
    }

    @Test
    public void exactTcHandlesRejectUnscopedOrMalformedRows() {
        assertEquals(1, MigrationObservationParser.exactTcHandles(
                "[{\"pref\":10,\"handle\":42}]").size());
        assertTrue(MigrationObservationParser.exactTcHandles("[{\"pref\":10}]").isEmpty());
        assertTrue(MigrationObservationParser.jsonArrayEmpty("[]"));
    }
}
