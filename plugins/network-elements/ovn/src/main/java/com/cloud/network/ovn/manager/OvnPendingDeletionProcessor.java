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
package com.cloud.network.ovn.manager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.alert.AlertManager;
import org.apache.cloudstack.alert.AlertService;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionDaoImpl;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;

/**
 * Background processor that retries OVN NB DB row deletions that failed during
 * synchronous {@code destroy()} / {@code shutdownVpc()} flows. Rows are
 * persisted in {@code ovn_pending_deletion} and retried on each tick until
 * either the deletion succeeds or {@link #MaxAttempts} is exhausted.
 *
 * <h3>Two-phase tick design</h3>
 * <p>Each tick runs two sequential phases:
 * <ol>
 *   <li><b>Sentinel phase</b> — processes rows with {@code controller_id = 0}
 *       across all zones. These are enqueued by
 *       {@link com.cloud.network.ovn.element.OvnVpcElement#deleteLogicalRouterFor}
 *       when no controller was registered at destroy time.  For each sentinel row
 *       the processor calls {@link OvnPluginManager#findControllerForZone(long)};
 *       if a controller is now available it looks up the real OVN NB UUID via
 *       {@link OvnLogicalIdMapDao#findByCsId} and promotes the sentinel to a real
 *       pending-deletion row (keeping the sentinel marked succeeded so it is
 *       not re-processed). If no controller is registered yet, {@code attempts}
 *       is incremented and the sentinel waits for the next tick.</li>
 *   <li><b>Real-row phase</b> — processes rows with a concrete {@code controller_id}
 *       per registered controller. Each row's NB UUID is deleted via
 *       {@link OvnNbClient}. On success, the corresponding
 *       {@link com.cloud.network.ovn.dao.OvnLogicalIdMapVO} is removed.</li>
 * </ol>
 *
 * <p>Design invariants:
 * <ul>
 *   <li>Single-threaded per process — serialises all OVN NB writes for pending
 *       deletions to avoid concurrent delete-on-same-UUID races.</li>
 *   <li>Best-effort: an error on one row is logged + marked failed; the loop
 *       continues to the next row.</li>
 *   <li>Exhausted rows (attempts &ge; max) are logged at ERROR + an
 *       {@link AlertManager#ALERT_TYPE_NETWORK} alert is raised; the row is
 *       left with {@code removed} still null so a follow-up manual reconcile
 *       can pick it up.</li>
 *   <li>Promotion idempotency: before persisting the real row, the processor
 *       checks {@link OvnPendingDeletionDao#isPendingByOvnUuid} to prevent
 *       duplicate enqueue if a tick races with itself.</li>
 * </ul>
 */
@Component
public class OvnPendingDeletionProcessor implements Configurable {

    private static final Logger LOGGER = LogManager.getLogger(OvnPendingDeletionProcessor.class);

    // ------------------------------------------------------------------
    // ConfigKeys — registered via Configurable.getConfigComponentName().
    // ------------------------------------------------------------------

    public static final ConfigKey<Integer> IntervalSeconds = new ConfigKey<>(
            "Network",
            Integer.class,
            "ovn.pending.deletion.interval.seconds",
            "60",
            "Interval in seconds between pending-deletion processor runs.",
            false);

    public static final ConfigKey<Integer> BatchSize = new ConfigKey<>(
            "Network",
            Integer.class,
            "ovn.pending.deletion.batch.size",
            "50",
            "Maximum pending OVN deletions processed per controller per processor run.",
            false);

    public static final ConfigKey<Integer> MaxAttempts = new ConfigKey<>(
            "Network",
            Integer.class,
            "ovn.pending.deletion.max.attempts",
            "20",
            "Maximum deletion attempts before a pending-deletion row is abandoned with an ALERT.",
            false);

    @Inject
    private OvnControllerDao controllerDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private AlertManager alertManager;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> handle;

    @PostConstruct
    public void start() {
        final int interval = resolveInterval();
        executor = Executors.newSingleThreadScheduledExecutor(threadFactory());
        handle = executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.SECONDS);
        LOGGER.info("OvnPendingDeletionProcessor started (interval={}s)", interval);
    }

    @PreDestroy
    public void stop() {
        if (handle != null) {
            handle.cancel(false);
        }
        if (executor != null) {
            executor.shutdown();
        }
        LOGGER.info("OvnPendingDeletionProcessor stopped");
    }

    // ------------------------------------------------------------------
    // Per-tick logic.
    // ------------------------------------------------------------------

    /**
     * Entry point called by the scheduler on each tick.
     *
     * <p>Processing order:
     * <ol>
     *   <li>Sentinel rows ({@code controller_id = 0}) across all zones — resolved
     *       to real pending-deletion rows when a controller becomes available.</li>
     *   <li>Real pending-deletion rows per registered controller — each row's
     *       NB UUID is deleted via the corresponding {@link OvnNbClient}.</li>
     * </ol>
     */
    void tick() {
        try {
            processSentinels();
            processRealRows();
        } catch (RuntimeException re) {
            LOGGER.warn("OvnPendingDeletionProcessor.tick: unexpected error: {}", re.getMessage(), re);
        }
    }

    private void processSentinels() {
        final int batch = resolveBatchSize();
        final List<OvnPendingDeletionVO> sentinels = pendingDeletionDao.findAllSentinels(batch);
        if (sentinels == null || sentinels.isEmpty()) {
            return;
        }
        for (final OvnPendingDeletionVO sentinel : sentinels) {
            try {
                resolveSentinelRow(sentinel);
            } catch (RuntimeException e) {
                LOGGER.warn("OvnPendingDeletionProcessor: sentinel id={} resolution error: {}",
                        sentinel.getId(), e.getMessage(), e);
                pendingDeletionDao.markFailed(sentinel.getId(),
                        "sentinel resolution failed: " + e.getMessage());
            }
        }
    }

    private void processRealRows() {
        final List<OvnControllerVO> controllers = controllerDao.listAll();
        if (controllers == null || controllers.isEmpty()) {
            return;
        }
        final int batch = resolveBatchSize();
        for (final OvnControllerVO controller : controllers) {
            final List<OvnPendingDeletionVO> rows =
                    pendingDeletionDao.findPendingByController(controller.getId(), batch);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            final OvnNbClient nb = pluginManager.nbClient(controller.getZoneId());
            for (final OvnPendingDeletionVO row : rows) {
                processOneRow(row, nb, controller.getId());
            }
        }
    }

    /**
     * Resolves a sentinel pending-deletion row ({@code controller_id = 0}) into
     * a real pending-deletion row once a controller is registered for the zone.
     *
     * <p>Resolution steps:
     * <ol>
     *   <li>Look up the controller for {@code row.getZoneId()} via
     *       {@link OvnPluginManager#findControllerForZone(long)}. If none is
     *       registered yet, bump {@code attempts} and leave the sentinel for the
     *       next tick — this is not counted as a hard failure.</li>
     *   <li>Look up the {@code OvnLogicalIdMapVO} by
     *       {@code (kind, csId, controllerId)}. If no mapping exists the OVN NB
     *       row was never created (or was already cleaned up) — mark succeeded.</li>
     *   <li>Otherwise, promote: persist a new real pending-deletion row with the
     *       resolved controller and real NB UUID, then mark the sentinel succeeded.
     *       The real row is processed by {@link #processRealRows()} on the next
     *       tick via the standard per-kind NB delete path.</li>
     * </ol>
     */
    private void resolveSentinelRow(final OvnPendingDeletionVO sentinel) {
        if (!isSentinelResolvable(sentinel)) {
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(sentinel.getZoneId());
        if (controller == null) {
            pendingDeletionDao.markFailed(sentinel.getId(),
                    "no controller registered for zone " + sentinel.getZoneId());
            return;
        }
        final OvnLogicalIdMapVO mapping =
                logicalIdMapDao.findByCsId(sentinel.getKind(), sentinel.getCsId(), controller.getId());
        if (mapping == null) {
            LOGGER.info("OvnPendingDeletionProcessor: sentinel id={} resolved with no mapping "
                    + "(kind={} csId={} zoneId={} controllerId={}); marking succeeded",
                    sentinel.getId(), sentinel.getKind(), sentinel.getCsId(),
                    sentinel.getZoneId(), controller.getId());
            pendingDeletionDao.markSucceeded(sentinel.getId());
            return;
        }
        promoteSentinelToRealRow(sentinel, controller, mapping);
    }

    /**
     * Guards {@link #resolveSentinelRow} against rows with missing
     * {@code zoneId} or {@code csId}: marks them succeeded (unresolvable)
     * and returns {@code false} so the caller can short-circuit.
     */
    private boolean isSentinelResolvable(final OvnPendingDeletionVO sentinel) {
        if (sentinel.getZoneId() == null) {
            LOGGER.warn("OvnPendingDeletionProcessor: sentinel id={} has null zoneId; marking succeeded",
                    sentinel.getId());
            pendingDeletionDao.markSucceeded(sentinel.getId());
            return false;
        }
        if (sentinel.getCsId() == null) {
            LOGGER.warn("OvnPendingDeletionProcessor: sentinel id={} has null csId; marking succeeded",
                    sentinel.getId());
            pendingDeletionDao.markSucceeded(sentinel.getId());
            return false;
        }
        return true;
    }

    /**
     * Persists a real pending-deletion row from a resolved sentinel and marks
     * the sentinel succeeded. The real row is picked up on the next processor
     * tick by {@link #processRealRows()}.
     */
    private void promoteSentinelToRealRow(final OvnPendingDeletionVO sentinel,
                                           final OvnControllerVO controller,
                                           final OvnLogicalIdMapVO mapping) {
        if (pendingDeletionDao.isPendingByOvnUuid(mapping.getOvnUuid(), sentinel.getKindRaw())) {
            // Idempotency guard: real row already queued (e.g. duplicate tick before sentinel removed).
            LOGGER.debug("OvnPendingDeletionProcessor: real pending row for ovnUuid={} kind={} already exists; "
                    + "skipping promotion of sentinel id={}",
                    mapping.getOvnUuid(), sentinel.getKindRaw(), sentinel.getId());
            pendingDeletionDao.markSucceeded(sentinel.getId());
            return;
        }
        final OvnPendingDeletionVO real = new OvnPendingDeletionVO(
                UUID.randomUUID().toString(),
                controller.getId(),
                sentinel.getZoneId(),
                sentinel.getKind(),
                mapping.getOvnUuid(),
                sentinel.getCsId());
        pendingDeletionDao.persist(real);
        pendingDeletionDao.markSucceeded(sentinel.getId());
        LOGGER.info("OvnPendingDeletionProcessor: promoted sentinel id={} -> real pending deletion "
                + "(kind={} ovnUuid={} controllerId={} csId={})",
                sentinel.getId(), real.getKind(), real.getOvnUuid(),
                controller.getId(), sentinel.getCsId());
    }

    private void processOneRow(final OvnPendingDeletionVO row, final OvnNbClient nb, final long resolvedControllerId) {
        final int maxAtt = resolveMaxAttempts();
        if (row.getAttempts() >= maxAtt) {
            handleExhausted(row);
            return;
        }
        try {
            deleteRowFromNb(nb, row);
            pendingDeletionDao.markSucceeded(row.getId());
            // Remove the corresponding mapping row so the reconciler no longer
            // tries to sweep it. Best-effort — the mapping may already be gone.
            removeCorrespondingMapping(row, resolvedControllerId);
            LOGGER.info("OvnPendingDeletionProcessor: deleted {} uuid={} (csId={}) after {} attempt(s)",
                    row.getKindRaw(), row.getOvnUuid(), row.getCsId(), row.getAttempts() + 1);
        } catch (RuntimeException e) {
            LOGGER.warn("OvnPendingDeletionProcessor: delete failed {} uuid={} attempt={}: {}",
                    row.getKindRaw(), row.getOvnUuid(), row.getAttempts() + 1, e.getMessage());
            pendingDeletionDao.markFailed(row.getId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // NB delete dispatch (mirrors OvnReconcilerService.deleteByTable).
    // ------------------------------------------------------------------

    private void deleteRowFromNb(final OvnNbClient nb, final OvnPendingDeletionVO row) {
        final String kind = row.getKindRaw();
        final String uuid = row.getOvnUuid();
        final OvnLogicalIdMapVO.Kind k = row.getKind();
        switch (k) {
            case DHCP_OPTIONS:
            case DHCP_OPTIONS_V6:
                nb.deleteDhcpOptions(uuid);
                break;
            case DNS_RECORDS:
                nb.deleteDnsRowDirect(uuid);
                break;
            case SOURCE_NAT:
            case VPC_SOURCE_NAT:
            case STATIC_NAT:
            case PORT_FORWARDING:
                nb.deleteNatRule(uuid);
                break;
            case NETWORK:
            case PUBLIC_LS:
                nb.deleteLogicalSwitch(uuid);
                break;
            case VPC:
            case NETWORK_LR:
                nb.deleteLogicalRouter(uuid);
                break;
            case NIC:
            case ORPHAN_NIC:
                nb.deleteLogicalSwitchPort(uuid);
                break;
            case PUBLIC_LRP:
            case VPC_PUBLIC_LRP:
            case NETWORK_GW_LRP:
            case ISOLATED_PUBLIC_LRP:
                nb.deleteLogicalRouterPort(uuid);
                break;
            case VPC_PUBLIC_RSP:
            case ISOLATED_PUBLIC_RSP:
                nb.deleteLogicalSwitchPort(uuid);
                break;
            case STATIC_ROUTE:
            case ISOLATED_STATIC_ROUTE:
                nb.deleteLogicalRouterStaticRouteDirect(uuid);
                break;
            case QOS:
                nb.deleteQosRowDirect(uuid);
                break;
            case NETWORK_ACL:
                nb.deleteAclByUuid(uuid);
                break;
            case LOAD_BALANCER:
                nb.deleteLoadBalancer(uuid);
                break;
            case HA_CHASSIS_GROUP:
                nb.destroyHaChassisGroup(uuid);
                break;
            default:
                LOGGER.warn("OvnPendingDeletionProcessor: no NB delete handler for kind={} uuid={}", kind, uuid);
                // Mark as succeeded so the stuck row does not block indefinitely.
                pendingDeletionDao.markSucceeded(row.getId());
                break;
        }
    }

    private void removeCorrespondingMapping(final OvnPendingDeletionVO row, final long controllerId) {
        if (row.getOvnUuid() == null || controllerId == OvnPendingDeletionDaoImpl.CONTROLLER_SENTINEL) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByOvnUuid(row.getOvnUuid());
        if (mapping != null) {
            logicalIdMapDao.remove(mapping.getId());
        }
    }

    // ------------------------------------------------------------------
    // Exhausted-row handling.
    // ------------------------------------------------------------------

    private void handleExhausted(final OvnPendingDeletionVO row) {
        LOGGER.error("OvnPendingDeletionProcessor: row id={} kind={} ovn_uuid={} cs_id={} exhausted after {} attempts; "
                + "manual cleanup required. Last error: {}",
                row.getId(), row.getKindRaw(), row.getOvnUuid(), row.getCsId(),
                row.getAttempts(), row.getLastError());
        alertManager.sendAlert(
                AlertService.AlertType.ALERT_TYPE_ROUTING,
                0L, 0L,
                "OVN pending deletion exhausted",
                String.format("OVN row kind=%s uuid=%s (cs_id=%s) failed to delete after %d attempts. "
                        + "Manual cleanup via ovn-nbctl required. Last error: %s",
                        row.getKindRaw(), row.getOvnUuid(), row.getCsId(),
                        row.getAttempts(), row.getLastError()));
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private int resolveInterval() {
        final Integer v = IntervalSeconds.value();
        return (v == null || v < 5) ? 60 : v;
    }

    private int resolveBatchSize() {
        final Integer v = BatchSize.value();
        return (v == null || v < 1) ? 50 : v;
    }

    private int resolveMaxAttempts() {
        final Integer v = MaxAttempts.value();
        return (v == null || v < 1) ? 20 : v;
    }

    private ThreadFactory threadFactory() {
        final AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            final Thread t = new Thread(r, "ovn-pending-del-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public String getConfigComponentName() {
        return OvnPendingDeletionProcessor.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{IntervalSeconds, BatchSize, MaxAttempts};
    }
}
