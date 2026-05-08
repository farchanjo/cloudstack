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
package com.cloud.network.ovn.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Thin wrapper around the local {@code ovn-trace} CLI for diagnostic flow
 * tracing. Not part of the request-handling path; used only by the
 * (intentionally non-MVP) {@code traceLogicalFlow} entry point in the
 * CloudStack admin API surface.
 *
 * <p>The wrapper exists so the rest of the plugin can stay unaware of the
 * external process, and tests can mock it cleanly.
 */
public class OvnTraceClient {

    private static final Logger LOGGER = LogManager.getLogger(OvnTraceClient.class);

    private final String binary;
    private final long timeoutSeconds;

    public OvnTraceClient() {
        this("/usr/bin/ovn-trace", 30L);
    }

    public OvnTraceClient(final String binary, final long timeoutSeconds) {
        this.binary = binary;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Runs {@code ovn-trace <ds> <microflow>} and returns the stdout lines.
     *
     * @param datapath OVN datapath (LR or LS name)
     * @param microflow OpenFlow microflow expression
     * @return one entry per stdout line
     */
    public List<String> traceLogicalFlow(final String datapath, final String microflow) {
        try {
            final ProcessBuilder pb = new ProcessBuilder(binary, datapath, microflow);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final List<String> out = readLines(p);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new OvnException("ovn-trace timed out after " + timeoutSeconds + "s");
            }
            if (p.exitValue() != 0) {
                throw new OvnException("ovn-trace exited " + p.exitValue() + ": " + String.join("\n", out));
            }
            return out;
        } catch (final IOException ioe) {
            throw new OvnException("ovn-trace failed: " + ioe.getMessage(), ioe);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OvnException("ovn-trace interrupted", ie);
        }
    }

    private List<String> readLines(final Process p) throws IOException {
        final List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        LOGGER.trace("ovn-trace stdout: {}", lines);
        return lines;
    }
}
