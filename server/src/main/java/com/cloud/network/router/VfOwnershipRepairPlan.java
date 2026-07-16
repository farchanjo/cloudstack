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
package com.cloud.network.router;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.cloud.utils.exception.CloudRuntimeException;

/** Immutable, deterministic, non-mutating VF ownership repair plan. */
public final class VfOwnershipRepairPlan {

    /** One-time internal repair scope reviewed on 2026-07-16; not generic authorization. */
    public static final String INCIDENT_PLAN_ID = "vf-ownership-incident-2026-07-16-v1";
    private static final List<String> INCIDENT_CANDIDATE_IDS = List.of(
            "NONCANONICAL_STALE:1427->833", "NONCANONICAL_STALE:1469->827",
            "NONCANONICAL_STALE:2435->833", "NONCANONICAL_STALE:2537->827",
            "NONCANONICAL_STALE:764->827", "NONCANONICAL_STALE:857->833",
            "NONCANONICAL_STALE:896->827", "NONCANONICAL_STALE:995->827",
            "WRONG_HOST_CANONICAL:1016->2468", "WRONG_HOST_CANONICAL:1625->1022",
            "WRONG_HOST_CANONICAL:818->749");

    public enum Kind {
        NONCANONICAL_STALE,
        WRONG_HOST_CANONICAL
    }

    public enum CandidateState {
        PENDING,
        QUARANTINED,
        COMPLETED,
        INVALID
    }

    public static final class Candidate {
        private final Kind kind;
        private final long vmId;
        private final long nicId;
        private final long currentPoolId;
        private final long stalePoolId;
        private final long currentHostId;
        private final long staleHostId;
        private final String currentBdf;
        private final String staleBdf;
        private final String mac;

        public Candidate(final Kind kind, final long vmId, final long nicId,
                         final long currentPoolId, final long stalePoolId,
                         final long currentHostId, final long staleHostId,
                         final String currentBdf, final String staleBdf, final String mac) {
            this.kind = kind;
            this.vmId = vmId;
            this.nicId = nicId;
            this.currentPoolId = currentPoolId;
            this.stalePoolId = stalePoolId;
            this.currentHostId = currentHostId;
            this.staleHostId = staleHostId;
            this.currentBdf = currentBdf;
            this.staleBdf = staleBdf;
            this.mac = mac;
        }

        public String getId() {
            return kind.name() + ":" + stalePoolId + "->" + currentPoolId;
        }

        public Kind getKind() {
            return kind;
        }

        public long getVmId() {
            return vmId;
        }

        public long getNicId() {
            return nicId;
        }

        public long getCurrentPoolId() {
            return currentPoolId;
        }

        public long getStalePoolId() {
            return stalePoolId;
        }

        public long getCurrentHostId() {
            return currentHostId;
        }

        public long getStaleHostId() {
            return staleHostId;
        }

        public String getCurrentBdf() {
            return currentBdf;
        }

        public String getStaleBdf() {
            return staleBdf;
        }

        public String getMac() {
            return mac;
        }

        String canonical() {
            return String.join("|", getId(), String.valueOf(vmId), String.valueOf(nicId),
                    String.valueOf(currentHostId), String.valueOf(staleHostId),
                    normalize(currentBdf), normalize(staleBdf), normalize(mac));
        }
    }

    private final List<Candidate> candidates;
    private final int allocatedBefore;
    private final int allocatedAfter;
    private final int freeBefore;
    private final int freeAfter;
    private final String hash;
    private final String approvalToken;

    public VfOwnershipRepairPlan(final List<Candidate> candidates, final int allocatedBefore,
                                 final int freeBefore) {
        final List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(Candidate::getId));
        this.candidates = Collections.unmodifiableList(sorted);
        this.allocatedBefore = allocatedBefore;
        this.freeBefore = freeBefore;
        final int noncanonical = (int) sorted.stream()
                .filter(candidate -> candidate.getKind() == Kind.NONCANONICAL_STALE).count();
        allocatedAfter = allocatedBefore - noncanonical;
        freeAfter = freeBefore + noncanonical;
        hash = sha256(canonical());
        approvalToken = sha256("VF_REPAIR_APPROVAL_V1|" + hash + "|" + getCandidateIds());
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    public int getCandidateCount() {
        return candidates.size();
    }

    public String getCandidateIds() {
        return candidates.stream().map(Candidate::getId).collect(Collectors.joining(","));
    }

    public int getAllocatedBefore() {
        return allocatedBefore;
    }

    public int getAllocatedAfter() {
        return allocatedAfter;
    }

    public int getFreeBefore() {
        return freeBefore;
    }

    public int getFreeAfter() {
        return freeAfter;
    }

    public String getHash() {
        return hash;
    }

    public String getApprovalToken() {
        return approvalToken;
    }

    public boolean matchesApproval(final int count, final String ids, final String approvedHash,
                                   final String approvedToken) {
        return count == getCandidateCount() && getCandidateIds().equals(ids)
                && hash.equalsIgnoreCase(normalize(approvedHash))
                && approvalToken.equalsIgnoreCase(normalize(approvedToken));
    }

    public boolean isExactIncidentScope() {
        return allocatedBefore == 27 && freeBefore == 237 && candidates.size() == 11
                && allocatedAfter == 19 && freeAfter == 245
                && INCIDENT_CANDIDATE_IDS.equals(candidates.stream().map(Candidate::getId).toList())
                && candidates.stream().filter(c -> c.kind == Kind.NONCANONICAL_STALE).count() == 8
                && candidates.stream().filter(c -> c.kind == Kind.WRONG_HOST_CANONICAL).count() == 3;
    }

    /** Derives resumable progress only from the locked row predicates. */
    public CandidateState state(final Candidate candidate, final String currentState,
                                final Long currentOwner, final String staleState,
                                final Long staleOwner) {
        if (candidate.kind == Kind.NONCANONICAL_STALE) {
            if (!"ALLOCATED".equals(currentState) || !Long.valueOf(candidate.nicId).equals(currentOwner)) {
                return CandidateState.INVALID;
            }
            if ("ALLOCATED".equals(staleState) && Long.valueOf(candidate.nicId).equals(staleOwner)
                    || "SUSPECT".equals(staleState) && Long.valueOf(candidate.nicId).equals(staleOwner)) {
                return "SUSPECT".equals(staleState) ? CandidateState.QUARANTINED : CandidateState.PENDING;
            }
            if ("FREE".equals(staleState) && staleOwner == null) {
                return CandidateState.COMPLETED;
            }
            return CandidateState.INVALID;
        }
        if ("FREE".equals(staleState) && staleOwner == null
                && "ALLOCATED".equals(currentState) && Long.valueOf(candidate.nicId).equals(currentOwner)) {
            return CandidateState.COMPLETED;
        }
        if ("SUSPECT".equals(staleState) && Long.valueOf(candidate.nicId).equals(staleOwner)
                && "ALLOCATED".equals(currentState) && Long.valueOf(candidate.nicId).equals(currentOwner)) {
            return CandidateState.QUARANTINED;
        }
        if ("ALLOCATED".equals(staleState) && Long.valueOf(candidate.nicId).equals(staleOwner)
                && "FREE".equals(currentState) && currentOwner == null) {
            return CandidateState.PENDING;
        }
        return CandidateState.INVALID;
    }

    private String canonical() {
        final String transitions = candidates.stream().map(Candidate::canonical).collect(Collectors.joining("\n"));
        return String.format("allocated:%d->%d|free:%d->%d|count:%d\n%s",
                allocatedBefore, allocatedAfter, freeBefore, freeAfter, candidates.size(), transitions);
    }

    private static String sha256(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (final byte item : bytes) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CloudRuntimeException("SHA-256 is unavailable", e);
        }
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
