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
package com.cloud.agent.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.cloud.serializer.GsonHelper;
import com.google.gson.Gson;

public class HostVfPurgeOrphansSerializationTest {

    private final Gson gson = GsonHelper.getGson();

    @Test
    public void goldenLegacyCommandPayloadWithoutTargetsDeserializesFailClosed() {
        final HostVfPurgeOrphansCommand command = gson.fromJson(readGolden("host-vf-purge-old-command.json"),
                HostVfPurgeOrphansCommand.class);

        assertTrue(command.getTargetPciBdfs().isEmpty());
        assertTrue(command.getExpectedMacsByPciBdf().isEmpty());
        assertTrue(command.isPurgeVdpa());
    }

    @Test
    public void goldenLegacyAggregateAnswerCannotBeMistakenForPerTargetReleaseEvidence() {
        final HostVfPurgeOrphansAnswer answer = gson.fromJson(readGolden("host-vf-purge-old-answer.json"),
                HostVfPurgeOrphansAnswer.class);

        assertTrue(answer.getResult());
        assertEquals(1, answer.getVdpaDeleted());
        assertTrue(answer.getTargetResults().isEmpty());
    }

    @Test
    public void newPayloadCarriesExplicitTargetsAndSafeLegacyFlags() {
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        command.setTargetPciBdfs(Collections.singleton("0000:01:00.2"));
        command.setExpectedMacsByPciBdf(Collections.singletonMap("0000:01:00.2", "02:00:00:00:00:02"));

        final HostVfPurgeOrphansCommand copy = gson.fromJson(
                gson.toJson(command), HostVfPurgeOrphansCommand.class);

        assertEquals(Collections.singleton("0000:01:00.2"), copy.getTargetPciBdfs());
        assertEquals("02:00:00:00:00:02", copy.getExpectedMacsByPciBdf().get("0000:01:00.2"));
        assertFalse(copy.isPurgeVdpa());
        assertFalse(copy.isRebindPassthroughVfs());
        assertFalse(copy.isPurgeStaleOvsReps());
    }

    @Test
    public void downlevelCommandFixtureIgnoresNewFieldsAndSeesAllBroadFlagsFalse() {
        final HostVfPurgeOrphansCommand command = new HostVfPurgeOrphansCommand();
        command.setTargetPciBdfs(Collections.singleton("0000:01:00.2"));
        command.setExpectedMacsByPciBdf(Collections.singletonMap(
                "0000:01:00.2", "02:00:00:00:00:02"));

        final LegacyCommandShape legacy = gson.fromJson(gson.toJson(command), LegacyCommandShape.class);

        assertFalse(legacy.purgeVdpa);
        assertFalse(legacy.rebindPassthroughVfs);
        assertFalse(legacy.purgeStaleOvsReps);
    }

    private String readGolden(final String name) {
        final String path = "/com/cloud/agent/api/" + name;
        final InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Missing golden fixture " + path);
        }
        return gson.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
                com.google.gson.JsonElement.class).toString();
    }

    private static final class LegacyCommandShape {
        private boolean purgeVdpa;
        private boolean rebindPassthroughVfs;
        private boolean purgeStaleOvsReps;
    }
}
