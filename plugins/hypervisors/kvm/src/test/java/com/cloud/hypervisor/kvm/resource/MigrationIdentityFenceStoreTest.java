// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class MigrationIdentityFenceStoreTest {
    @Test
    public void resolvesConfiguredAndDefaultFenceDirectories() {
        final String previous = System.getProperty("cloudstack.kvm.migration-fence-dir");
        try {
            System.clearProperty("cloudstack.kvm.migration-fence-dir");
            assertEquals(Path.of("/var/lib/cloudstack-agent/migration-fences"),
                    MigrationIdentityFenceStore.migrationFenceDirectory());
            System.setProperty("cloudstack.kvm.migration-fence-dir", "/tmp/test-migration-fences");
            assertEquals(Path.of("/tmp/test-migration-fences"), MigrationIdentityFenceStore.migrationFenceDirectory());
        } finally {
            if (previous == null) {
                System.clearProperty("cloudstack.kvm.migration-fence-dir");
            } else {
                System.setProperty("cloudstack.kvm.migration-fence-dir", previous);
            }
        }
    }

    @Test
    public void installsCompleteSortedManifestAndRemovesOnlyRequestedEntries() throws Exception {
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(
                Files.createTempDirectory("migration-manifest-"), "host-a");
        final MigrationIdentityFenceStore.Fence first = fence("work", 4, "nic-a", "bdf-a");
        final MigrationIdentityFenceStore.Fence second = fence("work", 4, "nic-b", "bdf-b");
        store.install("work", 4, List.of(second, first));
        assertEquals(List.of("bdf-a", "bdf-b"), store.read("work", 4).entries().stream()
                .map(MigrationIdentityFenceStore.Fence::key).toList());
        assertThrows(MigrationIdentityFenceStore.ManualFenceException.class,
                () -> store.install("work", 4, List.of(first, fence("work", 4, "other", "bdf-b"))));
        store.clear("work", 4, List.of(first));
        assertEquals(1, store.read("work", 4).entries().size());
        store.clear("work", 4, List.of(second));
        assertEquals(null, store.read("work", 4));
    }

    @Test
    public void adoptRewritesOnlyTheLeaseTuple() throws Exception {
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(
                Files.createTempDirectory("migration-adopt-"), "host-a");
        final MigrationIdentityFenceStore.Fence old = fence("work", 4, "nic-a", "bdf-a");
        store.install("work", 4, List.of(old));
        store.adopt("work", 4, "old-token", 1, List.of(
                new MigrationIdentityFenceStore.Fence("work", 4, "nic-a", 1L, "bdf-a", "new-token", 2, 99, 2, "nic-a")));
        assertEquals("new-token", store.read("work", 4).leaseToken());
        assertEquals("bdf-a", store.read("work", 4).entries().get(0).key());
    }

    @Test
    public void legacyFilesAndIncompleteCrashArtifactsFailClosed() throws Exception {
        final java.nio.file.Path directory = Files.createTempDirectory("migration-legacy-");
        Files.writeString(directory.resolve("old.fence"), "legacy");
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(directory, "host-a");
        assertThrows(MigrationIdentityFenceStore.ManualFenceException.class, () -> store.read("work", 4));
        assertEquals(MigrationIdentityFenceStore.Status.LEGACY_OR_CORRUPT,
                store.lookup("work", 4).status());
    }

    @Test
    public void crashReplayCompletesAValidTemporaryManifest() throws Exception {
        final java.nio.file.Path directory = Files.createTempDirectory("migration-replay-");
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(directory, "host-a");
        store.install("work", 4, List.of(fence("work", 4, "nic-a", "bdf-a")));
        final java.nio.file.Path manifest;
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(directory)) {
            manifest = files.filter(path -> path.toString().endsWith(".manifest")).findFirst().orElseThrow();
        }
        Files.copy(manifest, manifest.resolveSibling(manifest.getFileName() + ".tmp"));
        Files.delete(manifest);
        assertEquals(1, store.read("work", 4).entries().size());
    }

    @Test
    public void writeFailureDoesNotCreatePartialManifest() throws Exception {
        final java.nio.file.Path notADirectory = Files.createTempFile("migration-write-failure-", ".file");
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(notADirectory, "host-a");
        assertThrows(MigrationIdentityFenceStore.FenceWriteException.class,
                () -> store.install("work", 4, List.of(fence("work", 4, "nic-a", "bdf-a"))));
    }

    @Test
    public void absentManifestIsDistinguishedForCleanupRetry() throws Exception {
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(
                Files.createTempDirectory("migration-cleanup-retry-"), "host-a");
        assertEquals(MigrationIdentityFenceStore.Status.ABSENT, store.lookup("work", 4).status());
    }

    @Test
    public void corruptManifestIsNeverClassifiedAsAbsent() throws Exception {
        final java.nio.file.Path directory = Files.createTempDirectory("migration-corrupt-");
        final MigrationIdentityFenceStore store = new MigrationIdentityFenceStore(directory, "host-a");
        store.install("work", 4, List.of(fence("work", 4, "nic-a", "bdf-a")));
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(directory)) {
            final java.nio.file.Path manifest = files.filter(path -> path.toString().endsWith(".manifest"))
                    .findFirst().orElseThrow();
            Files.writeString(manifest, "corrupt");
        }
        assertEquals(MigrationIdentityFenceStore.Status.LEGACY_OR_CORRUPT, store.lookup("work", 4).status());
    }

    private static MigrationIdentityFenceStore.Fence fence(final String work, final long generation,
            final String nic, final String bdf) {
        return new MigrationIdentityFenceStore.Fence(work, generation, nic, 1L, bdf,
                "old-token", 1, 10, 2, nic);
    }
}
