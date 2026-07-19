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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * Inspect or clean explicitly targeted host-level VF bindings on a KVM agent.
 *
 * <p><b>Fail closed:</b> {@link #targetPciBdfs} is the destructive scope.
 * When it is absent or empty, a compatible agent performs no cleanup even
 * when a legacy management server sends broad flags or empty keep sets. This
 * protects the agent during mixed-version operation, but does not make an old
 * management server's DB-side release behavior safe. Management safety gates
 * must be deployed and left disabled before agent rollout.
 *
 * <p>A present target is mutated only when the observed nonzero MAC exactly
 * matches the expected MAC, or when management supplies a cryptographically
 * bound lifecycle authorization containing the BDF, expected MAC, operation
 * id, and purpose. Active domain references always block cleanup. An inactive
 * migration-stage domain additionally requires a {@code STAGE_ROLLBACK}
 * lifecycle purpose.
 *
 * <p>{@code dryRun} reports without mutating.
 *
 * <p>Wire-compat: agents predating the matching wrapper return
 * {@code Unsupported command}; the management caller logs the warning
 * and keeps going (DB-only release is the legacy semantic). Agents that
 * know the command but pre-date the OVS path simply skip that step.
 */
public class HostVfPurgeOrphansCommand extends Command {

    /** vdpa-net device names that must NOT be deleted. */
    private Set<String> keepVdpaNames = new HashSet<>();

    /** PCI BDFs (e.g. {@code 0000:01:04.3}) that must NOT be rebound. */
    private Set<String> keepPciBdfs = new HashSet<>();

    /** Exact PCI BDFs that the agent is authorized to inspect and clean. */
    private Set<String> targetPciBdfs = new HashSet<>();

    /** Optional expected guest MAC per target BDF; mismatches fail closed. */
    private Map<String, String> expectedMacsByPciBdf = new HashMap<>();

    /** Exact representor expected for each target; never delete a shared port. */
    private Map<String, String> expectedRepresentorsByPciBdf = new HashMap<>();

    /** Exact OVN iface-id expected for each target representor. */
    private Map<String, String> expectedInterfaceIdsByPciBdf = new HashMap<>();

    /** Operation id used to bind explicit lifecycle authorization per BDF. */
    private Map<String, String> ownerOperationIdsByPciBdf = new HashMap<>();

    /** Authorization purpose per BDF, for example RECONCILE or STAGE_ROLLBACK. */
    private Map<String, String> ownerPurposesByPciBdf = new HashMap<>();

    /** SHA-256 authorization bound to BDF, MAC, operation id, and purpose. */
    private Map<String, String> ownerTokensByPciBdf = new HashMap<>();

    /** When {@code true}, only report what would be cleaned. */
    private boolean dryRun;

    /** When {@code true}, run the vDPA purge step. */
    private boolean purgeVdpa;

    /** When {@code true}, run the VF passthrough rebind step. */
    private boolean rebindPassthroughVfs;

    /**
     * When {@code true}, free OVS external_ids/del-port on FREE VF
     * representors that still carry iface-id (residual Chaos B).
     */
    private boolean purgeStaleOvsReps;

    /** No-arg constructor for serialization frameworks. */
    public HostVfPurgeOrphansCommand() {
        // No-op.
    }

    public HostVfPurgeOrphansCommand(final Set<String> keepVdpaNames, final Set<String> keepPciBdfs,
                                     final boolean dryRun) {
        this.keepVdpaNames = keepVdpaNames == null ? new HashSet<>() : new HashSet<>(keepVdpaNames);
        this.keepPciBdfs = keepPciBdfs == null ? new HashSet<>() : new HashSet<>(keepPciBdfs);
        this.dryRun = dryRun;
    }

    @Override
    public boolean executeInSequence() {
        // This command mutates VF, vDPA and OVS state. Serializing it per
        // agent prevents two lifecycle/reconciliation cleanups from both
        // acting on the same ownership evidence concurrently.
        return true;
    }

    public Set<String> getKeepVdpaNames() {
        return keepVdpaNames == null ? Collections.emptySet() : new HashSet<>(keepVdpaNames);
    }

    public void setKeepVdpaNames(final Set<String> keepVdpaNames) {
        this.keepVdpaNames = keepVdpaNames == null ? new HashSet<>() : new HashSet<>(keepVdpaNames);
    }

    public Set<String> getKeepPciBdfs() {
        return keepPciBdfs == null ? Collections.emptySet() : new HashSet<>(keepPciBdfs);
    }

    public void setKeepPciBdfs(final Set<String> keepPciBdfs) {
        this.keepPciBdfs = keepPciBdfs == null ? new HashSet<>() : new HashSet<>(keepPciBdfs);
    }

    public Set<String> getTargetPciBdfs() {
        return targetPciBdfs == null ? Collections.emptySet() : new HashSet<>(targetPciBdfs);
    }

    public void setTargetPciBdfs(final Set<String> targetPciBdfs) {
        this.targetPciBdfs = targetPciBdfs == null ? new HashSet<>() : new HashSet<>(targetPciBdfs);
    }

    public Map<String, String> getExpectedMacsByPciBdf() {
        return expectedMacsByPciBdf == null ? Collections.emptyMap() : new HashMap<>(expectedMacsByPciBdf);
    }

    public void setExpectedMacsByPciBdf(final Map<String, String> expectedMacsByPciBdf) {
        this.expectedMacsByPciBdf = expectedMacsByPciBdf == null
                ? new HashMap<>() : new HashMap<>(expectedMacsByPciBdf);
    }

    public Map<String, String> getExpectedRepresentorsByPciBdf() {
        return expectedRepresentorsByPciBdf == null ? Collections.emptyMap() : new HashMap<>(expectedRepresentorsByPciBdf);
    }

    public void setExpectedRepresentorsByPciBdf(final Map<String, String> values) {
        expectedRepresentorsByPciBdf = values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public Map<String, String> getExpectedInterfaceIdsByPciBdf() {
        return expectedInterfaceIdsByPciBdf == null ? Collections.emptyMap() : new HashMap<>(expectedInterfaceIdsByPciBdf);
    }

    public void setExpectedInterfaceIdsByPciBdf(final Map<String, String> values) {
        expectedInterfaceIdsByPciBdf = values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public String getExpectedRepresentor(final String bdf) {
        return valueForBdf(getExpectedRepresentorsByPciBdf(), bdf);
    }

    public String getExpectedInterfaceId(final String bdf) {
        return valueForBdf(getExpectedInterfaceIdsByPciBdf(), bdf);
    }

    public Map<String, String> getOwnerOperationIdsByPciBdf() {
        return ownerOperationIdsByPciBdf == null ? Collections.emptyMap() : new HashMap<>(ownerOperationIdsByPciBdf);
    }

    public void setOwnerOperationIdsByPciBdf(final Map<String, String> values) {
        ownerOperationIdsByPciBdf = values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public Map<String, String> getOwnerPurposesByPciBdf() {
        return ownerPurposesByPciBdf == null ? Collections.emptyMap() : new HashMap<>(ownerPurposesByPciBdf);
    }

    public void setOwnerPurposesByPciBdf(final Map<String, String> values) {
        ownerPurposesByPciBdf = values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public Map<String, String> getOwnerTokensByPciBdf() {
        return ownerTokensByPciBdf == null ? Collections.emptyMap() : new HashMap<>(ownerTokensByPciBdf);
    }

    public void setOwnerTokensByPciBdf(final Map<String, String> values) {
        ownerTokensByPciBdf = values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public static String createOwnerToken(final String bdf, final String expectedMac,
                                          final String operationId, final String purpose) {
        return DigestUtils.sha256Hex(String.join("|", "VF_OWNER_V1", normalize(bdf),
                normalize(expectedMac), normalize(operationId), normalize(purpose)));
    }

    public boolean hasValidOwnerToken(final String bdf, final String expectedMac) {
        if (expectedMac == null || expectedMac.trim().isEmpty()
                || "00:00:00:00:00:00".equalsIgnoreCase(expectedMac)) {
            return false;
        }
        final String operationId = valueForBdf(getOwnerOperationIdsByPciBdf(), bdf);
        final String purpose = valueForBdf(getOwnerPurposesByPciBdf(), bdf);
        final String token = valueForBdf(getOwnerTokensByPciBdf(), bdf);
        return operationId != null && !operationId.trim().isEmpty()
                && purpose != null && !purpose.trim().isEmpty()
                && token != null && token.equalsIgnoreCase(createOwnerToken(bdf, expectedMac, operationId, purpose));
    }

    public String getOwnerPurpose(final String bdf) {
        return valueForBdf(getOwnerPurposesByPciBdf(), bdf);
    }

    public String getOwnerOperationId(final String bdf) {
        return valueForBdf(getOwnerOperationIdsByPciBdf(), bdf);
    }

    public String getOwnerToken(final String bdf) {
        return valueForBdf(getOwnerTokensByPciBdf(), bdf);
    }

    private static String valueForBdf(final Map<String, String> values, final String bdf) {
        if (values == null || bdf == null) {
            return null;
        }
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            if (bdf.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(final boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isPurgeVdpa() {
        return purgeVdpa;
    }

    public void setPurgeVdpa(final boolean purgeVdpa) {
        this.purgeVdpa = purgeVdpa;
    }

    public boolean isRebindPassthroughVfs() {
        return rebindPassthroughVfs;
    }

    public void setRebindPassthroughVfs(final boolean rebindPassthroughVfs) {
        this.rebindPassthroughVfs = rebindPassthroughVfs;
    }

    public boolean isPurgeStaleOvsReps() {
        return purgeStaleOvsReps;
    }

    public void setPurgeStaleOvsReps(final boolean purgeStaleOvsReps) {
        this.purgeStaleOvsReps = purgeStaleOvsReps;
    }
}
