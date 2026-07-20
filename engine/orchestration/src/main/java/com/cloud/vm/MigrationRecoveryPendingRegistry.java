// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.vm;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cache of DB-derived migration recovery barriers. The database remains authoritative. */
public final class MigrationRecoveryPendingRegistry {
    private final Map<Long, Set<Long>> vmIdsByHost = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> workIdsByHost = new ConcurrentHashMap<>();

    public void rebuild(final Iterable<ItWorkVO> works, final MigrationNicDao nicDao) {
        vmIdsByHost.clear();
        workIdsByHost.clear();
        for (final ItWorkVO work : works) {
            for (final MigrationNicVO nic : nicDao.listByWorkAndGeneration(work.getId(), work.getMigrationGeneration())) {
                add(nic.getSourceHostId(), nic.getVmId());
                add(nic.getDestinationHostId(), nic.getVmId());
                addWork(nic.getSourceHostId(), work.getId());
                addWork(nic.getDestinationHostId(), work.getId());
            }
        }
    }

    /** Add a non-terminal work item to the admission barrier immediately. */
    public void enqueue(final ItWorkVO work, final MigrationNicDao nicDao) {
        if (work == null || nicDao == null) {
            return;
        }
        for (final MigrationNicVO nic : nicDao.listByWorkAndGeneration(work.getId(), work.getMigrationGeneration())) {
            add(nic.getSourceHostId(), nic.getVmId());
            add(nic.getDestinationHostId(), nic.getVmId());
            addWork(nic.getSourceHostId(), work.getId());
            addWork(nic.getDestinationHostId(), work.getId());
        }
    }

    public boolean isPending(final long vmId) {
        return vmIdsByHost.values().stream().anyMatch(ids -> ids.contains(vmId));
    }

    public boolean isHostPending(final long hostId) {
        return vmIdsByHost.containsKey(hostId);
    }

    public boolean isHostPendingForOtherWork(final long hostId, final String workId) {
        return workIdsByHost.getOrDefault(hostId, Set.of()).stream().anyMatch(id -> !id.equals(workId));
    }

    public void clearTerminal(final Iterable<ItWorkVO> works, final MigrationNicDao nicDao) {
        rebuild(works, nicDao);
    }

    private void add(final Long hostId, final long vmId) {
        if (hostId != null) {
            vmIdsByHost.computeIfAbsent(hostId, ignored -> ConcurrentHashMap.newKeySet()).add(vmId);
        }
    }

    private void addWork(final Long hostId, final String workId) {
        if (hostId != null && workId != null) {
            workIdsByHost.computeIfAbsent(hostId, ignored -> ConcurrentHashMap.newKeySet()).add(workId);
        }
    }
}
