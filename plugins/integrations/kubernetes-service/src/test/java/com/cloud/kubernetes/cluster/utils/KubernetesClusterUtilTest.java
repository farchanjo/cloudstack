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
package com.cloud.kubernetes.cluster.utils;

import com.cloud.utils.Pair;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterUtilTest {

    private void executeThrowAndTestVersionMatch() {
        Pair<Boolean, String> resultPair = null;
        Pair<Boolean, String> result = KubernetesClusterUtil.clusterNodeVersionMatches(resultPair, "1.24.0");
        Assert.assertFalse(result.first());
    }

    private void executeAndTestVersionMatch(boolean status, String response, boolean expectedResult) {
        Pair<Boolean, String> resultPair = new Pair<>(status, response);
        Pair<Boolean, String> result = KubernetesClusterUtil.clusterNodeVersionMatches(resultPair, "1.24.0");
        Assert.assertEquals(expectedResult, result.first());
    }

    @Test
    public void testClusterNodeVersionMatches() {
        String v1233WorkerNodeOutput = "v1.23.3";
        String v1240WorkerNodeOutput = "v1.24.0";

        executeAndTestVersionMatch(true, v1240WorkerNodeOutput, true);

        executeAndTestVersionMatch(true, v1233WorkerNodeOutput, false);

        executeAndTestVersionMatch(false, v1240WorkerNodeOutput, false);

        executeAndTestVersionMatch(false, v1233WorkerNodeOutput, false);

        executeThrowAndTestVersionMatch();
    }

    @Test
    public void testClusterNodeVersionMatchesQuoteWrappedOutput() {
        // SshHelper may wrap the output in quotes with the newline inside.
        Pair<Boolean, String> resultPair = new Pair<>(true, "\"v1.24.0\n\"");
        Pair<Boolean, String> result = KubernetesClusterUtil.clusterNodeVersionMatches(resultPair, "1.24.0");
        Assert.assertTrue(result.first());
        Assert.assertEquals("v1.24.0", result.second());
    }

    @Test
    public void testSanitizeSshOutput() {
        Assert.assertEquals("0", KubernetesClusterUtil.sanitizeSshOutput("\"0\n\""));
        Assert.assertEquals("3", KubernetesClusterUtil.sanitizeSshOutput(" 3 \n"));
        Assert.assertNull(KubernetesClusterUtil.sanitizeSshOutput(null));
    }

    /**
     * Live failure shape: SshHelper merges kubectl's stderr after the
     * stdout payload, so the count line is followed by diagnostic lines.
     */
    private static final String STDERR_MERGED_COUNT_OUTPUT = "0\n"
            + "E0706 14:31:10.668051    2063 memcache.go:265] Unhandled Error err=couldn't get current"
            + " server API group list: Get \"http://localhost:8080/api?timeout=32s\": dial tcp"
            + " 127.0.0.1:8080: connect: connection refused\n"
            + "The connection to the server localhost:8080 was refused - did you specify the right host or port?";

    @Test
    public void testParseNodesCountFromSshOutput() {
        Assert.assertEquals(Integer.valueOf(0),
                KubernetesClusterUtil.parseNodesCountFromSshOutput(STDERR_MERGED_COUNT_OUTPUT));
        Assert.assertEquals(Integer.valueOf(11), KubernetesClusterUtil.parseNodesCountFromSshOutput("11\n"));
        Assert.assertEquals(Integer.valueOf(7), KubernetesClusterUtil.parseNodesCountFromSshOutput("\"7\n\""));
        Assert.assertEquals(Integer.valueOf(3), KubernetesClusterUtil.parseNodesCountFromSshOutput(" 3 \r\n"));
        // No numeric payload at all -> null (caller logs + retries).
        Assert.assertNull(KubernetesClusterUtil.parseNodesCountFromSshOutput(
                "The connection to the server localhost:8080 was refused"));
        Assert.assertNull(KubernetesClusterUtil.parseNodesCountFromSshOutput(""));
        Assert.assertNull(KubernetesClusterUtil.parseNodesCountFromSshOutput(null));
    }

    @Test
    public void testSshOutputContainsLine() {
        Assert.assertTrue(KubernetesClusterUtil.sshOutputContainsLine("node1\nE0706 noise", "node1"));
        Assert.assertTrue(KubernetesClusterUtil.sshOutputContainsLine("\"node1\n\"", "node1"));
        Assert.assertFalse(KubernetesClusterUtil.sshOutputContainsLine("node10\n", "node1"));
        Assert.assertFalse(KubernetesClusterUtil.sshOutputContainsLine(null, "node1"));
        Assert.assertFalse(KubernetesClusterUtil.sshOutputContainsLine("node1", null));
    }

    @Test
    public void testClusterNodeVersionMatchesWithMergedStderr() {
        Pair<Boolean, String> resultPair = new Pair<>(true, "v1.24.0\nE0706 some kubectl noise");
        Pair<Boolean, String> result = KubernetesClusterUtil.clusterNodeVersionMatches(resultPair, "1.24.0");
        Assert.assertTrue(result.first());
        Assert.assertEquals("v1.24.0", result.second());
    }
}
