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

# Bug 20 — `ovs-setup.sh` hangs ~8 minutes on `systemctl try-restart frr` after OVS bridge setup completes

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** MEDIUM — host boots successfully but BGP/FRR convergence delayed up to `TimeoutStartSec=600`; manual remediation required to unblock follow-up units that depend on `ovs-setup.service`.
**Fix commit:** _none yet_

---

## Symptom

`/usr/local/bin/ovs-setup.sh start` runs as `ovs-setup.service` (Type=oneshot, TimeoutStartSec=600). The OVS bridge creation block (br-bond, br-int, cloud0, patch ports) finishes in <5 s. The final line of `start()` is:

```bash
# /usr/local/bin/ovs-setup.sh line 290
systemctl try-restart frr 2>/dev/null || true
```

That single line blocks for ~500 s until the systemctl subprocess is externally killed, at which point ovs-setup.service finally reports `Finished`.

## Evidence (norbert, 2026-05-11)

```
May 11 03:56:24 norbert ovs-setup.sh[354231]: [ovs-setup] OVS setup complete (host=norbert mode=kernel)
...
May 11 04:04:37 norbert ovs-setup.sh[354231]: /usr/local/bin/ovs-setup.sh: line 222:
        354739 Killed                  systemctl try-restart frr 2> /dev/null
May 11 04:04:37 norbert systemd[1]: Finished ovs-setup.service - Configure OVS bridge with bond and VLANs (kernel datapath).
```

Total elapsed: `03:56:24 → 04:04:37` = **8 min 13 s**. Resolution arrived only after operator manually executed `kill -9 354739` against the stuck `systemctl try-restart frr` subprocess.

`systemctl cat ovs-setup.service`:

```ini
[Unit]
Description=Configure OVS bridge with bond and VLANs (kernel datapath)
After=openvswitch-switch.service mlx-switchdev.service systemd-networkd.service
Wants=openvswitch-switch.service
Before=network.target frr.service
Wants=network.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/bin/ovs-setup.sh start
ExecStop=/usr/local/bin/ovs-setup.sh stop
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
```

## Root cause

`systemctl try-restart frr` blocks indefinitely waiting for the frr.service systemd job to settle. There are at least two failure modes converging on the same symptom:

1. **Reverse dependency cycle.** `ovs-setup.service` declares `Before=frr.service` AND runs `systemctl try-restart frr` from its `ExecStart`. When ovs-setup is invoked at boot before frr has reached `active`, `try-restart` issues an `ActivationRequest` against frr; systemctl then enqueues a stop+start job, which is gated on `ovs-setup.service` reaching `active` (because of the `Before=` ordering edge). Result: each unit waits for the other.

2. **Run-time invocation of `try-restart` while frr is mid-startup.** When ovs-setup runs after a manual `systemctl restart ovs-setup`, frr may already be transitioning state (operator triggered a parallel restart, or a previous ovs-setup execution is queued). `try-restart` waits for the in-flight job to finish before scheduling its own restart, but on a single-threaded systemd-bus client the wait is indefinite.

The fact that `kill -9` of the systemctl subprocess unblocks the script proves the wait is at the `systemctl` client layer, not inside frr itself. frr keeps serving (BGP sessions did not drop during the hang).

## Files involved

| File | Role |
|---|---|
| `/usr/local/bin/ovs-setup.sh` line 290 | Offending `systemctl try-restart frr` invocation. |
| `/etc/systemd/system/ovs-setup.service` | `Before=frr.service` + `TimeoutStartSec=600` (masks the symptom rather than addressing it). |

## Fix surface (not implemented)

Option A — **remove the `try-restart frr` invocation** from `ovs-setup.sh` start path entirely. Instead, declare in `frr.service` an `After=ovs-setup.service` + `Restart=on-failure` (or use a `[Unit].PartOf=ovs-setup.service` model) so the systemd dependency graph drives the restart, not an inline shell call. Loop closure is eliminated; ovs-setup never invokes systemctl back into the dependency it sits below.

Option B — **fire-and-forget restart**. Replace `systemctl try-restart frr 2>/dev/null || true` with `systemctl --no-block try-restart frr 2>/dev/null || true`. The `--no-block` flag returns immediately after queueing the job, not after the job completes. This breaks the wait-cycle even if the dependency graph remains as-is.

Option C — **drop `Before=frr.service`** from ovs-setup.service AND keep the inline `try-restart`. Without the ordering edge, systemctl no longer blocks on ovs-setup reaching active. Risk: frr may start before bridges exist on first boot; mitigate with `Requires=ovs-setup.service` + `After=ovs-setup.service` on frr.service side.

Preferred direction: Option B as a one-line hot-fix, then Option A as the structural change. Option C trades one race for another.

## Verification (post-fix, not yet executed)

1. `systemctl daemon-reload && systemctl restart ovs-setup.service` should return in <5 s.
2. Journal should NOT contain a `Killed systemctl try-restart frr` line.
3. `systemctl is-active frr` should remain `active` throughout.
4. `vtysh -c 'show bgp summary'` should show all 3 RR sessions Established within 30 s of the restart.

## Impact summary

While the bug is OPEN, every reboot or manual `systemctl restart ovs-setup.service` on a data node delays follow-up units (cloudstack-agent, ovn-host, libvirtd) by up to 10 min and requires operator intervention to unblock. Aggregated across the 6 data nodes during a rolling cluster restart, this added ~50 min of human-attended wait time to the maintenance window on 2026-05-11.

## References

- `~/dev/cloudstack/docs/audits/README.md` (audit index).
- Related: Bug 21 (`2026-05-11-bug-21-trevor-agent-silent-failure.md`) — separate operational defect surfaced during the same maintenance window.
- Project policy `~/dev/dc/CLAUDE.md` — SSH skill, MCP-only, en-US.
