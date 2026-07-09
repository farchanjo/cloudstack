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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.dao.OvnControllerDao;
import com.cloud.network.ovn.dao.OvnControllerVO;

/**
 * Single-threaded scheduler that walks every registered OVN controller and
 * drives {@link OvnBgpRedistributeManager#reconcileZone(long)} so that
 * gateway-chassis migration causes a re-announce on the new chassis and a
 * withdraw on the old. Interval is governed by
 * {@link OvnNetworkConfig#BgpReconcileIntervalSeconds}.
 *
 * <p>Pure best-effort: any per-zone error is logged and skipped — one zone's
 * NB outage must not block the others. The task is also a no-op when the
 * global redistribute toggle is off, so the scheduler can safely run
 * unconditionally.
 *
 * <p>The scheduler reads the interval at start; ConfigKey changes take
 * effect on the next process restart. A live reconfigure path could be
 * added by re-scheduling on each tick when the interval changes — left as a
 * follow-up because the cost of restart is low and the change is rare.
 */
@Component
public class OvnBgpReconcileTask {

    private static final Logger LOGGER = LogManager.getLogger(OvnBgpReconcileTask.class);

    @Inject
    private OvnControllerDao controllerDao;
    @Inject
    private OvnBgpRedistributeManager bgpRedistributeManager;
    @Inject
    private OvnReconcilerService reconcilerService;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> handle;

    @PostConstruct
    public void start() {
        final Integer intervalRaw = OvnNetworkConfig.BgpReconcileIntervalSeconds.value();
        final int interval = intervalRaw == null || intervalRaw.intValue() < 5
                ? 60
                : intervalRaw.intValue();
        executor = Executors.newSingleThreadScheduledExecutor(threadFactory());
        handle = executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.SECONDS);
        LOGGER.info("OvnBgpReconcileTask started (interval={}s)", interval);
    }

    @PreDestroy
    public void stop() {
        if (handle != null) {
            handle.cancel(false);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        LOGGER.info("OvnBgpReconcileTask stopped");
    }

    /**
     * One reconcile pass over every registered OVN controller. Surfaces any
     * exception as a warning; subsequent ticks must continue.
     */
    void tick() {
        try {
            final List<OvnControllerVO> controllers = controllerDao.listAll();
            if (controllers == null || controllers.isEmpty()) {
                return;
            }
            // Extra-CIDR port-security resync runs independent of the BGP
            // redistribute toggle: it self-heals guest LSPs (CKS pod / LB-VIP /
            // dual-stack v6) and is a no-op when its own ConfigKey is empty.
            resyncLspExtraPortSecurity(controllers);
            if (!Boolean.TRUE.equals(OvnNetworkConfig.BgpRedistributePublicIps.value())) {
                return;
            }
            for (final OvnControllerVO ctrl : controllers) {
                try {
                    bgpRedistributeManager.reconcileZone(ctrl.getZoneId());
                } catch (RuntimeException re) {
                    LOGGER.warn("OvnBgpReconcileTask: zone={} reconcile failed: {}",
                            ctrl.getZoneId(), re.getMessage());
                }
            }
        } catch (RuntimeException re) {
            LOGGER.warn("OvnBgpReconcileTask: tick failed: {}", re.getMessage());
        }
    }

    private void resyncLspExtraPortSecurity(final List<OvnControllerVO> controllers) {
        for (final OvnControllerVO ctrl : controllers) {
            try {
                reconcilerService.resyncLspExtraPortSecurityForZone(ctrl.getZoneId(), false);
            } catch (RuntimeException re) {
                LOGGER.warn("OvnBgpReconcileTask: zone={} LSP extra port-security resync failed: {}",
                        ctrl.getZoneId(), re.getMessage());
            }
        }
    }

    private static ThreadFactory threadFactory() {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, "ovn-bgp-reconcile-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
