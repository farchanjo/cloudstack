<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Bug 21 — `cloudstack-agent` on `trevor` stayed in `failed` state for ~8 h without any alerting; cleanup blocked by stale `ovn-controller` pidfile

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** MEDIUM — host disconnected from CloudStack management for 8 h before operator noticed; no monitoring alert fired. VM allocation skipped trevor during the entire window. Manual remediation required to restart the agent and surface the underlying ovn-host failure.
**Fix commit:** _none yet_

---

## Symptom

`trevor` (10.182.0.26) presented `state=Disconnected` in `cmk list hosts type=Routing` for approximately 8 hours after a previous maintenance window. No alert fired in keepalived, no log scraper raised the condition, no operator dashboard surfaced the failure. The host's libvirt domains kept running, but no new VMs could be scheduled to trevor and no agent → mgmt heartbeats reached voldemort/bellatrix/barty.

## Evidence (trevor, 2026-05-11)

```bash
$ systemctl status cloudstack-agent
   Loaded: loaded (/usr/lib/systemd/system/cloudstack-agent.service)
   Active: failed (Result: exit-code) since 2026-05-10 21:xx UTC; ~8h ago

$ systemctl status ovn-host
   Loaded: loaded
   Active: failed (Result: exit-code)

$ ls /var/run/ovn/
   ovn-controller.pid     <-- stale (PID 2989309 not running)

$ ps -p 2989309
   no such process
```

The cascade:
1. `ovn-host` (which spawns `ovn-controller`) failed on a previous restart attempt because `/var/run/ovn/ovn-controller.pid` referenced an old PID (2989309) that was no longer running, but systemd considered the pidfile authoritative and refused to start a fresh controller.
2. `cloudstack-agent` declares an implicit dependency on `ovn-host` (via the OVN bridge availability) and started failing its readiness probe in a tight loop, then transitioned to `failed` and stopped retrying.
3. No watchdog → no alert → no auto-recovery.

Resolution applied during this session:
```
pkill -9 ovn-controller    # kill any zombie process holding the pidfile illusion
rm /var/run/ovn/ovn-controller.pid
systemctl reset-failed cloudstack-agent ovn-host
systemctl start cloudstack-agent ovn-host
```

After ~30 s, `cmk list hosts` showed trevor `state=Up resourcestate=Enabled` and the agent reconnected to all 3 mgmt controllers.

## Root cause

Two distinct defects converge:

1. **No alerting on agent-failed state.** The keepalived/dashboard stack monitors VIPs and ceph status but does NOT scrape `systemctl --failed` across the 6 data nodes. cloudstack-management has an internal host-state state machine that flips `Disconnected` after 90 s without heartbeat, but the operational tooling does not page on `Disconnected` transitions (only logs to mgmt logs).

2. **`ovn-controller` pidfile is not unit-managed.** `ovn-host.service` does NOT use `PIDFile=` semantics correctly. The pidfile is written by `ovn-controller` itself at startup, but the service unit's `Type=simple` (or `Type=forking` without PIDFile=) prevents systemd from cleaning up the pidfile on unclean stop. When the next start attempt examines the pidfile and finds it pointing to a dead PID, the controller bails out instead of clobbering.

## Files involved

| File | Role |
|---|---|
| `/etc/systemd/system/ovn-host.service` (or `/usr/lib/systemd/system/ovn-host.service`) | Service unit lacks `PIDFile=/var/run/ovn/ovn-controller.pid` + `Type=forking` + `ExecStartPre=/bin/rm -f /var/run/ovn/ovn-controller.pid` defensive line. |
| Monitoring stack (Prometheus / Grafana / Alertmanager — wherever the agent-status scraper lives) | No rule for `cloudstack_agent_systemd_active{host=~".*"} == 0`. |

## Fix surface (not implemented)

Option A — **defensive pidfile cleanup** in ovn-host.service:
```ini
[Service]
Type=forking
PIDFile=/var/run/ovn/ovn-controller.pid
ExecStartPre=-/bin/rm -f /var/run/ovn/ovn-controller.pid
ExecStart=/usr/share/openvswitch/scripts/ovn-ctl --no-monitor start_controller
ExecStop=/usr/share/openvswitch/scripts/ovn-ctl stop_controller
Restart=on-failure
RestartSec=5
```

Option B — **alerting rule** added to Prometheus:
```yaml
- alert: CloudstackAgentDown
  expr: node_systemd_unit_state{name="cloudstack-agent.service",state="failed"} == 1
  for: 2m
  labels:
    severity: page
  annotations:
    summary: "cloudstack-agent failed on {{ $labels.instance }}"
```

Both options are independent; deploy both. Option A prevents recurrence; Option B catches recurrence quickly when something new breaks.

## Verification (post-fix)

1. Force-restart ovn-controller: `systemctl stop ovn-host && kill -9 $(cat /var/run/ovn/ovn-controller.pid) && systemctl start ovn-host`. Service should come back active without manual pidfile rm.
2. Stop cloudstack-agent. Within 2 min the new Prometheus alert should fire (Page severity). Start agent back. Alert should resolve.
3. Run `for h in aragog norbert fluffy nagini scabbers trevor; do ssh root@$h "systemctl is-active cloudstack-agent ovn-host"; done` — all 12 should return `active`.

## Impact summary

While OPEN, any silent failure of cloudstack-agent or ovn-host on a data node remains undetected up to operator-driven discovery. Capacity headroom is preserved (5/6 nodes carry the load), but VM placement skews and the failed host accumulates orphan resources (stale VIFs, dead Ceph mounts) until manually reconciled.

## References

- `~/dev/cloudstack/docs/audits/README.md` (audit index).
- Related: Bug 20 (`2026-05-11-bug-20-ovs-setup-frr-restart-hang.md`) — operational defect surfaced during same maintenance window.
- Project policy `~/dev/dc/CLAUDE.md` — mgmt mutations via cmk only; SSH skill mandatory; en-US.
