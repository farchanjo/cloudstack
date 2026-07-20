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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import com.cloud.agent.api.routing.ObserveVdpaMigrationAnswer;
import com.cloud.agent.api.routing.ObserveVdpaMigrationCommand;

/** One crash-safe, host-local manifest for every migration generation. */
public final class MigrationIdentityFenceStore {
    private static final int SCHEMA_VERSION = 3;
    private static final String FENCE_DIRECTORY_PROPERTY = "cloudstack.kvm.migration-fence-dir";
    private static final Path DEFAULT_DIRECTORY = Path.of("/var/lib/cloudstack-agent/migration-fences");
    private final Path directory;
    private final String hostIdentity;

    public MigrationIdentityFenceStore(final Path directory) {
        this(directory, detectHostIdentity());
    }

    MigrationIdentityFenceStore(final Path directory, final String hostIdentity) {
        this.directory = directory;
        this.hostIdentity = Objects.requireNonNull(hostIdentity);
    }

    public static Path migrationFenceDirectory() {
        final String configured = System.getProperty(FENCE_DIRECTORY_PROPERTY);
        return configured == null || configured.isBlank() ? DEFAULT_DIRECTORY : Path.of(configured);
    }

    public String hostIdentity() {
        return hostIdentity;
    }

    public Manifest read(final String workId, final long generation) {
        detectLegacyFiles();
        final Path target = path(workId, generation);
        recoverTemporary(target);
        try {
            return Files.exists(target) ? Manifest.parse(Files.readString(target, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException e) {
            throw new ManualFenceException("migration fence manifest is unreadable", e);
        }
    }

    /** Non-mutating lookup used by retryable cleanup; corruption is never treated as absence. */
    public Lookup lookup(final String workId, final long generation) {
        try {
            final Manifest manifest = read(workId, generation);
            return manifest == null ? new Lookup(Status.ABSENT, null) : new Lookup(Status.PRESENT, manifest);
        } catch (ManualFenceException e) {
            return new Lookup(Status.LEGACY_OR_CORRUPT, null);
        }
    }

    public void install(final String workId, final long generation, final List<Fence> entries) {
        final Manifest candidate = Manifest.create(workId, generation, hostIdentity, entries);
        final Manifest current = read(workId, generation);
        if (current != null && !current.equals(candidate)) {
            throw new ManualFenceException("migration fence manifest belongs to another identity");
        }
        if (current == null) {
            write(candidate);
        }
    }

    public void adopt(final String workId, final long generation, final String oldToken, final long oldVersion,
            final List<Fence> requested) {
        final Manifest current = require(workId, generation);
        current.requireEntries(requested);
        if (requested.size() != current.entries().size()
                || current.entries().stream().anyMatch(entry -> requested.stream().noneMatch(entry::sameIdentity))) {
            throw new ManualFenceException("recovery fence NIC identity set is incomplete");
        }
        if (!Objects.equals(oldToken, current.leaseToken()) || oldVersion != current.leaseVersion()) {
            throw new ManualFenceException("previous recovery fence is not represented");
        }
        final Fence requestedLease = requested.get(0);
        if (requested.stream().anyMatch(entry -> !Objects.equals(requestedLease.leaseToken(), entry.leaseToken())
                || requestedLease.leaseVersion() != entry.leaseVersion()
                || requestedLease.leaseExpiry() != entry.leaseExpiry())) {
            throw new ManualFenceException("recovery lease tuple is not uniform");
        }
        final Manifest replacement = current.withLease(requestedLease.leaseToken(), requestedLease.leaseVersion(),
                requestedLease.leaseExpiry());
        write(replacement);
    }

    public void clear(final String workId, final long generation, final List<Fence> requested) {
        final Manifest current = require(workId, generation);
        current.requireEntries(requested);
        final List<Fence> remaining = new ArrayList<>(current.entries());
        remaining.removeAll(requested);
        if (remaining.isEmpty()) {
            delete(current);
        } else {
            write(current.withEntries(remaining));
        }
    }

    private Manifest require(final String workId, final long generation) {
        final Manifest manifest = read(workId, generation);
        if (manifest == null) {
            throw new ManualFenceException("migration fence manifest is absent");
        }
        return manifest;
    }

    private void write(final Manifest manifest) {
        try {
            Files.createDirectories(directory);
            final Path target = path(manifest.workId(), manifest.generation());
            final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, manifest.serialize(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new FenceWriteException("unable to persist migration identity manifest", e);
        }
    }

    private void delete(final Manifest manifest) {
        try {
            Files.deleteIfExists(path(manifest.workId(), manifest.generation()));
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new FenceWriteException("unable to remove migration identity manifest", e);
        }
    }

    private Path path(final String workId, final long generation) {
        return directory.resolve(encoded(workId + "\u0000" + generation + "\u0000" + hostIdentity) + ".manifest");
    }

    private void recoverTemporary(final Path target) {
        final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (!Files.exists(target) && Files.exists(temporary)) {
                Manifest.parse(Files.readString(temporary, StandardCharsets.UTF_8));
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } else if (Files.exists(target)) {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException e) {
            throw new ManualFenceException("migration fence crash recovery is unsafe", e);
        }
    }

    private void detectLegacyFiles() {
        try (java.util.stream.Stream<Path> files = Files.isDirectory(directory)
                ? Files.list(directory) : java.util.stream.Stream.<Path>empty()) {
            if (files.anyMatch(file -> file.toString().endsWith(".fence"))) {
                throw new ManualFenceException("legacy per-NIC migration fences require manual cleanup");
            }
        } catch (IOException e) {
            throw new ManualFenceException("unable to inspect migration fence directory", e);
        }
    }

    private static String detectHostIdentity() {
        final String configured = System.getProperty("cloudstack.kvm.host-identity");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try {
            return Files.readString(Path.of("/etc/machine-id")).trim();
        } catch (IOException e) {
            return "unknown-host";
        }
    }

    private static String encoded(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static ReentrantLock lockFor(final String workId, final long generation, final String hostIdentity) {
        return VfHostLifecycleLock.forManifest(workId + "\u0000" + generation + "\u0000" + hostIdentity);
    }

    public enum Status { PRESENT, ABSENT, LEGACY_OR_CORRUPT }

    public static final class Lookup {
        private final Status status;
        private final Manifest manifest;
        public Lookup(final Status status, final Manifest manifest) { this.status = status; this.manifest = manifest; }
        public Status status() { return status; }
        public Manifest manifest() { return manifest; }
    }

    public static final class Manifest {
        private final String workId;
        private final long generation;
        private final String hostIdentity;
        private final String leaseToken;
        private final long leaseVersion;
        private final long leaseExpiry;
        private final List<Fence> entries;

        public Manifest(final String workId, final long generation, final String hostIdentity, final String leaseToken,
                final long leaseVersion, final long leaseExpiry, final List<Fence> entries) {
            this.workId = workId; this.generation = generation; this.hostIdentity = hostIdentity;
            this.leaseToken = leaseToken; this.leaseVersion = leaseVersion; this.leaseExpiry = leaseExpiry; this.entries = entries;
        }
        public String workId() { return workId; } public long generation() { return generation; }
        public String hostIdentity() { return hostIdentity; } public String leaseToken() { return leaseToken; }
        public long leaseVersion() { return leaseVersion; } public long leaseExpiry() { return leaseExpiry; }
        public List<Fence> entries() { return entries; }

        /** The host identity is the durable owner of this host-local lease. */
        public String leaseOwner() {
            return hostIdentity;
        }

        static Manifest create(final String workId, final long generation, final String hostIdentity,
                final List<Fence> entries) {
            if (entries == null || entries.isEmpty()) {
                throw new IllegalArgumentException("migration fence manifest needs NIC entries");
            }
            final List<Fence> sorted = entries.stream().sorted(Comparator.comparing(Fence::key)).toList();
            if (sorted.stream().map(Fence::key).distinct().count() != sorted.size()
                    || sorted.stream().anyMatch(entry -> !workId.equals(entry.workId())
                    || generation != entry.generation())) {
                throw new IllegalArgumentException("migration fence entries are not a complete unique set");
            }
            final Fence first = sorted.get(0);
            if (sorted.stream().anyMatch(entry -> !Objects.equals(first.leaseToken(), entry.leaseToken())
                    || first.leaseVersion() != entry.leaseVersion() || first.leaseExpiry() != entry.leaseExpiry())) {
                throw new IllegalArgumentException("migration fence lease tuple is not uniform");
            }
            return new Manifest(workId, generation, hostIdentity, first.leaseToken(), first.leaseVersion(),
                    first.leaseExpiry(), List.copyOf(sorted));
        }

        Manifest withEntries(final List<Fence> replacement) {
            return create(workId, generation, hostIdentity, replacement);
        }

        Manifest withLease(final String token, final long version, final long expiry) {
            return create(workId, generation, hostIdentity,
                    entries.stream().map(entry -> entry.withLease(token, version, expiry)).toList());
        }

        void requireEntries(final List<Fence> requested) {
            if (requested == null || requested.isEmpty() || requested.stream().anyMatch(entry -> {
                final Fence stored = entries.stream().filter(candidate -> candidate.key().equals(entry.key()))
                        .findFirst().orElse(null);
                return stored == null || !stored.sameIdentity(entry);
            })) {
                throw new ManualFenceException("migration fence NIC identity set does not match manifest");
            }
        }

        String serialize() {
            final StringBuilder value = new StringBuilder("MIFM\n").append(SCHEMA_VERSION).append('\n')
                    .append(encoded(workId)).append('\n').append(generation).append('\n')
                    .append(encoded(hostIdentity)).append('\n').append(encoded(leaseToken)).append('\n')
                    .append(leaseVersion).append('\n').append(leaseExpiry).append('\n').append(entries.size()).append('\n');
            entries.forEach(entry -> value.append(encoded(entry.serialize())).append('\n'));
            return value.toString();
        }

        static Manifest parse(final String value) {
            final String[] fields = value.split("\\R", -1);
            if (fields.length < 10 || !"MIFM".equals(fields[0]) || Integer.parseInt(fields[1]) != SCHEMA_VERSION) {
                throw new IllegalStateException("malformed migration identity manifest");
            }
            final int count = Integer.parseInt(fields[8]);
            if (count <= 0 || fields.length != count + 10) {
                throw new IllegalStateException("incomplete migration identity manifest");
            }
            final List<Fence> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                entries.add(Fence.parse(new String(Base64.getUrlDecoder().decode(fields[9 + i]), StandardCharsets.UTF_8)));
            }
            return create(new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8),
                    Long.parseLong(fields[3]), new String(Base64.getUrlDecoder().decode(fields[4]), StandardCharsets.UTF_8), entries);
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Manifest)) {
                return false;
            }
            final Manifest manifest = (Manifest) other;
            return generation == manifest.generation && leaseVersion == manifest.leaseVersion
                    && leaseExpiry == manifest.leaseExpiry && Objects.equals(workId, manifest.workId)
                    && Objects.equals(hostIdentity, manifest.hostIdentity) && Objects.equals(leaseToken, manifest.leaseToken)
                    && Objects.equals(entries, manifest.entries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workId, generation, hostIdentity, leaseToken, leaseVersion, leaseExpiry, entries);
        }
    }

    public static final class Fence {
        private final String workId; private final long generation; private final String nicUuid; private final Long vfRowId;
        private final String bdf; private final String leaseToken; private final long leaseVersion; private final long leaseExpiry;
        private final int schemaVersion; private final String identityPayload;

        public Fence(final String workId, final long generation, final String nicUuid, final Long vfRowId, final String bdf,
                final String leaseToken, final long leaseVersion, final long leaseExpiry, final int schemaVersion,
                final String identityPayload) {
            this.workId = workId; this.generation = generation; this.nicUuid = nicUuid; this.vfRowId = vfRowId;
            this.bdf = bdf; this.leaseToken = leaseToken; this.leaseVersion = leaseVersion; this.leaseExpiry = leaseExpiry;
            this.schemaVersion = schemaVersion; this.identityPayload = identityPayload;
        }
        public String workId() { return workId; } public long generation() { return generation; }
        public String nicUuid() { return nicUuid; } public Long vfRowId() { return vfRowId; } public String bdf() { return bdf; }
        public String leaseToken() { return leaseToken; } public long leaseVersion() { return leaseVersion; }
        public long leaseExpiry() { return leaseExpiry; } public int schemaVersion() { return schemaVersion; }
        public String identityPayload() { return identityPayload; }

        public Fence(final String workId, final long generation, final String nicUuid, final Long vfRowId,
                final String bdf, final String leaseToken) {
            this(workId, generation, nicUuid, vfRowId, bdf, leaseToken, 0L, 0L, 1, null);
        }

        public Fence(final String workId, final long generation, final String nicUuid, final Long vfRowId,
                final String bdf, final String leaseToken, final long leaseVersion) {
            this(workId, generation, nicUuid, vfRowId, bdf, leaseToken, leaseVersion, 0L, 1, null);
        }

        public Fence(final String workId, final long generation, final String nicUuid, final Long vfRowId,
                final String bdf, final String leaseToken, final long leaseVersion, final long leaseExpiry) {
            this(workId, generation, nicUuid, vfRowId, bdf, leaseToken, leaseVersion, leaseExpiry, 1, null);
        }

        public static Fence fromIdentity(final String workId, final long generation,
                final ObserveVdpaMigrationCommand.NicIdentity identity, final String token,
                final long version, final long expiry) {
            return new Fence(workId, generation, identity.getExpectedNicUuid(), identity.getExpectedVfRowId(),
                    identity.getExpectedBdf(), token, version, expiry, 2, identityPayload(identity));
        }

        public String key() {
            if (bdf != null) {
                return bdf;
            }
            final String[] fields = identityPayload == null ? new String[0] : identityPayload.split("\\u0001", -1);
            return fields.length > 3 ? fields[3] : identityPayload;
        }
        public boolean sameIdentity(final Fence other) {
            return workId.equals(other.workId) && generation == other.generation && Objects.equals(nicUuid, other.nicUuid)
                    && Objects.equals(vfRowId, other.vfRowId) && Objects.equals(bdf, other.bdf)
                    && Objects.equals(identityPayload, other.identityPayload);
        }
        Fence withLease(final String token, final long version, final long expiry) {
            return new Fence(workId, generation, nicUuid, vfRowId, bdf, token, version, expiry,
                    schemaVersion, identityPayload);
        }

        public boolean matches(final ObserveVdpaMigrationCommand.NicIdentity identity,
                final ObserveVdpaMigrationAnswer.NicObservation observation) {
            return sameIdentity(fromIdentity(workId, generation, identity, leaseToken, leaseVersion, leaseExpiry))
                    && observation != null && observation.isExact() && observation.getNicId() > 0
                    && identity.getNicId() == observation.getNicId() && Objects.equals(identity.getLspId(), observation.getLspId())
                    && Objects.equals(identity.getExpectedNicUuid(), observation.getNicUuid());
        }

        private String serialize() {
            return String.join("\n", workId, Long.toString(generation), nullToEmpty(nicUuid),
                    vfRowId == null ? "" : vfRowId.toString(), nullToEmpty(bdf), nullToEmpty(leaseToken),
                    Long.toString(leaseVersion), Long.toString(leaseExpiry), Integer.toString(schemaVersion),
                    nullToEmpty(identityPayload));
        }
        static Fence parse(final String value) {
            final String[] fields = value.split("\\R", -1);
            if (fields.length != 10) {
                throw new IllegalStateException("malformed migration identity entry");
            }
            return new Fence(fields[0], Long.parseLong(fields[1]), emptyToNull(fields[2]),
                    fields[3].isBlank() ? null : Long.valueOf(fields[3]), emptyToNull(fields[4]), emptyToNull(fields[5]),
                    Long.parseLong(fields[6]), Long.parseLong(fields[7]), Integer.parseInt(fields[8]), emptyToNull(fields[9]));
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Fence)) {
                return false;
            }
            final Fence fence = (Fence) other;
            return generation == fence.generation && leaseVersion == fence.leaseVersion
                    && leaseExpiry == fence.leaseExpiry && schemaVersion == fence.schemaVersion
                    && Objects.equals(workId, fence.workId) && Objects.equals(nicUuid, fence.nicUuid)
                    && Objects.equals(vfRowId, fence.vfRowId) && Objects.equals(bdf, fence.bdf)
                    && Objects.equals(leaseToken, fence.leaseToken) && Objects.equals(identityPayload, fence.identityPayload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workId, generation, nicUuid, vfRowId, bdf, leaseToken, leaseVersion,
                    leaseExpiry, schemaVersion, identityPayload);
        }
        private static String identityPayload(final ObserveVdpaMigrationCommand.NicIdentity identity) {
            return String.join("\u0001", String.valueOf(identity.getNicId()), value(identity.getExpectedNicUuid()),
                    value(identity.getNicKind()), value(identity.getLspId()), value(identity.getExpectedMac()),
                    value(identity.getExpectedVlan()), value(identity.getExpectedDriver()), value(identity.getExpectedBdf()),
                    value(identity.getExpectedVdpaName()), value(identity.getExpectedVdpaDevice()), value(identity.getExpectedPf()),
                    value(identity.getExpectedVfId()), value(identity.getExpectedRepresentor()), value(identity.getExpectedRepresentorPhysPortName()),
                    value(identity.getExpectedRepresentorBdf()), value(identity.getExpectedOvsBridge()), value(identity.getExpectedOvsBridgeUuid()),
                    value(identity.getExpectedOvsPort()), value(identity.getExpectedOvsPortUuid()), value(identity.getExpectedOvsInterface()),
                    value(identity.getExpectedOvsInterfaceUuid()), value(identity.getExpectedOvsExternalIds()), value(identity.getExpectedOvnPortBinding()),
                    value(identity.getExpectedOvnChassis()), value(identity.getExpectedLibvirtAlias()), value(identity.getExpectedLibvirtTarget()),
                    value(identity.getExpectedLibvirtSource()), value(identity.getExpectedLibvirtType()), value(identity.getExpectedLibvirtModel()),
                    value(identity.getExpectedTcExpectation()), value(identity.getExpectedFdbExpectation()));
        }
        private static String value(final Object value) { return value == null ? "" : value.toString(); }
        private static String nullToEmpty(final String value) { return value == null ? "" : value; }
        private static String emptyToNull(final String value) { return value.isBlank() ? null : value; }
    }

    public static class ManualFenceException extends IllegalStateException {
        public ManualFenceException(final String message) { super(message); }
        public ManualFenceException(final String message, final Throwable cause) { super(message, cause); }
    }
    public static class FenceWriteException extends IllegalStateException {
        public FenceWriteException(final String message, final Throwable cause) { super(message, cause); }
    }
}
