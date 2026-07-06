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
        // The live failure shape: quotes around the payload, newline INSIDE
        // the quotes — trim-before-strip left "0\n" and parseInt threw.
        Assert.assertEquals("0", KubernetesClusterUtil.sanitizeSshOutput("\"0\n\""));
        Assert.assertEquals("11", KubernetesClusterUtil.sanitizeSshOutput("\"11\r\n\""));
        Assert.assertEquals("3", KubernetesClusterUtil.sanitizeSshOutput(" 3 \n"));
        Assert.assertEquals("node1", KubernetesClusterUtil.sanitizeSshOutput("\"node1\n\""));
        Assert.assertEquals("", KubernetesClusterUtil.sanitizeSshOutput("\"\n\""));
        Assert.assertNull(KubernetesClusterUtil.sanitizeSshOutput(null));
        Assert.assertEquals(7, Integer.parseInt(KubernetesClusterUtil.sanitizeSshOutput("\"7\n\"")));
    }
}
