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

# VF / vDPA / VR hardware-offload verification — mixed offerings, separate VPCs

**Date:** 2026-07-01
**Status:** MOSTLY PASS — one new bug found (Bug 29, below), one production incident (self-recovered)
**Scope:** cross-VPC verification that VF passthrough, vDPA, and VR-side hostdev promotion
all work correctly together, on the OVN family and the `VpcVirtualRouter`-based
`Tier-Offload-*` family.

---

## Test matrix

| VPC | VPC offering | Tier(s) | Offering | VM/host | Result |
|---|---|---|---|---|---|
| `offload-mix` | `vpc-default-ovn` | `offload-mix-vf` | `tier-vf-ovn-nolb` | `vf-vm` @ aragog | **PASS** — `<interface type='hostdev'>`, vfio |
| `offload-mix` | `vpc-default-ovn` | `offload-mix-vdpa` | `tier-vdpa-ovn-nolb` | `vdpa-vm` @ norbert | **PASS** — `<interface type='vdpa'>` |
| `offload-vf-direct` | `vpc-default-vr` | `offload-vf-direct-tier` | `Tier-Offload-NAT` | `vf-direct-vm2` @ fluffy | **PASS** — `<interface type='hostdev'>`, vfio |
| `offload-vf-direct` VR | — | — | — | `r-1208-VM` @ nagini | **PASS** — 2x `<interface type='hostdev'>` (guest-tier NIC + Public NIC, per Phase B/4) |

All 4 confirmed via `virsh dumpxml` on the actual hypervisor — not just CloudStack API state.
Two different VPCs, two different offering families (OVN vs `VpcVirtualRouter`), VF and vDPA
both exercised, VR itself confirmed hostdev on both its interfaces.

## Bug 29 (NEW) — `isHwOffloadDeployment()` race picks the wrong VR template under concurrent load

**Severity:** MEDIUM (silent misconfiguration, not a crash — but ships a VR without the
hw-offload kernel backports it needs)

While deploying the 3 VPCs above roughly concurrently, the FIRST `Tier-Offload-NAT` VR
(`r-1205-VM`) was created with template **`systemvm-kvm-4.23.0-clean`** — the generic
default — instead of **`systemvm-hwoffload-backports-v3`**, despite
`router.template.kvm.hwoffload` being correctly set in `configuration`
(confirmed via `cmk list configurations`) and the tier's offering
(`Tier-Offload-NAT`) having `hwoffloadenabled=true`.

That VR then failed to start: first attempt hit
`Unable to acquire lock on VMTemplateStoragePool: 63` (transient DB lock
contention from the concurrent deploys), the retry stalled ~180s with no
further progress and no clear error
(`com.cloud.utils.exception.CloudRuntimeException: Unable to orchestrate the
start of VM instance...`, stack trace bottoms out at
`VirtualMachineManagerImpl.orchestrateStart` with no visible cause — see
below on why `isHwOffloadDeployment`'s own exception handling can hide the
real cause).

**Root cause, code-level:**
`NetworkHelperImpl.isHwOffloadDeployment()`
(`server/src/main/java/com/cloud/network/router/NetworkHelperImpl.java:540-559`)
decides whether to pick the hw-offload template by calling
`_networkDao.listByVpc(vpc.getId())` and checking each network's offering
for `isHwOffloadEnabled()`. Confirmed via a live `jdb` breakpoint at line 549
(see incident section below) that `vpc.getId()` resolves correctly at the
point of the check — the mechanism itself is sound when it runs. The
practical failure mode is almost certainly a **transactional visibility
race**: when a VPC's VR is created in the same request wave as its tier
network (or under DB contention from sibling concurrent deploys), the
network→VPC association may not yet be visible to `listByVpc()` inside this
particular transaction/connection at the exact moment the check runs.

Compounding this: the `catch (RuntimeException e)` at line 555 only logs at
**DEBUG** level and silently defaults to `false` (non-hw-offload). If the
DAO call throws for any reason under contention (lock timeout, deadlock
victim, etc.), the failure is invisible unless DEBUG logging happens to be
enabled — which it is not by default in this deployment. This is the same
"silent failure defaults to the wrong behavior" pattern as Bug 28.

**Reproduction:**
1. First attempt (3 VPCs deploying concurrently): wrong template
   (`systemvm-kvm-4.23.0-clean`), VR failed to start after ~1 min (lock
   timeout), retry stalled ~3 min then failed with no clear cause.
2. Destroyed the broken VM + VR, redeployed the identical tier VM **in
   isolation** (no concurrent deploys running): correct template
   (`systemvm-hwoffload-backports-v3`) picked immediately, VR booted clean,
   both its guest-tier and Public NIC came up as `hostdev`/vfio as designed.

This confirms the bug is load-dependent, not deterministic — exactly the
shape of a DB-visibility or lock-contention race.

**Suggested fix (not applied — flagging only, per this session's read-only-analysis-first
pattern for new bugs):**
- Re-check `isHwOffloadDeployment()` inside the same transaction/connection
  that will later be used to allocate the VR NICs, or move the check to run
  strictly after the tier network's VPC association is guaranteed committed.
- Escalate the `catch (RuntimeException e)` at line 555 to at least WARN
  (matching the "silent DEBUG-only catch masks real failures" lesson from
  Bug 28) so a future occurrence is visible without needing a live debugger
  session to catch it in the act.
- Consider a defensive re-check-and-recreate path: if a VR is later found to
  be running the wrong template for a hw-offload VPC, flag it (similar to
  how Bug 25/26 added `reconcileOvnInstalledOnStartup`-style self-healing
  for the KVM agent side).

## Incident — `cloudstack-management` on voldemort briefly went down during live debugging

Per the user's explicit standing instruction to use live JDI/JDWP breakpoints (not just log
reading) for this kind of investigation — same technique already used successfully for
Bug 28 — `cloudstack-management` on voldemort was restarted with
`JAVA_DEBUG="-agentlib:jdwp=...,server=y,suspend=n"` (loopback-only, non-blocking) to attach
`jdb` and inspect `NetworkHelperImpl.isHwOffloadDeployment()` live during a fresh VPC/VR
creation (user explicitly confirmed this specific action beforehand, given the higher blast
radius vs. a single data-node agent).

Breakpoint at `NetworkHelperImpl.java:549` was hit by an `API-Job-Executor-1` thread mid-way
through the new VPC's VR creation; `vpc.getId()` printed correctly (`774`). Clearing the 3
breakpoints and issuing `cont` was followed immediately by **`The application exited`** —
the JVM exited cleanly (`SpringContextShutdownHook` cascade visible in the log, not a crash
dump) but unexpectedly, `systemctl status` reported `code=exited, status=219/CGROUP`.

**Impact:** `cloudstack-management` on voldemort was down for the ~30s it took
`systemd`'s `Restart=on-failure` / `RestartSec=30` to bring it back. **bellatrix and barty
were unaffected and kept serving API traffic throughout** — this is a 3-node HA control
plane, not a single point of failure. Confirmed post-recovery: all 3 control nodes
`cloudstack-management active`, all 3 answer `HTTP 401` on `/client/api` (healthy signal).
The in-flight VPC (`offload-race-check`) was left in a clean `Enabled` state with no tier/VM
yet created — no orphaned or half-provisioned resources, no DB corruption observed. Deleted
via `cmk` as part of test cleanup.

**Root cause of the JVM exit: not determined.** Plausible candidates, not confirmed:
resuming a thread that was mid-way through a DB transaction inside a `jdb`-cleared
breakpoint context on a **production JVM already under lock contention** (this same
node had just processed the two earlier stuck/lock-timeout router-start failures) may have
tipped an already-stressed JVM over an edge (e.g. a watchdog/liveness mechanism, or an
uncaught error in a shutdown-triggering code path). Not reproduced a second time — the
retry (fresh isolated VPC deploy, no debugger attached) completed cleanly. Flagging as
open, not planning to chase further via repeated production JDWP attach given the risk.

**Lesson for future JDI sessions against `cloudstack-management` specifically (not
`cloudstack-agent`):** the management server is the 3-node HA control plane for the whole
zone, categorically higher blast radius than a single KVM agent. Prefer attaching when the
node is NOT already under load/contention from the same investigation, keep breakpoint
dwell time as short as possible, and treat any `cont`/`resume` after a live production
breakpoint as a genuine risk point — confirm service health immediately after, and revert
`JAVA_DEBUG` + do a final clean restart as soon as the inspection goal is met, exactly as
was done here.

## State left behind

- All 4 test VMs/VR from the verification matrix: not yet cleaned up as of this doc — still
  running in `offload-mix` (2 VPC-1 VMs) and `offload-vf-direct` (1 VM + VR), available for
  further inspection if needed.
- `offload-race-check` VPC (Bug 29 repro): destroyed VM/VR from the failed first attempt,
  redeployed clean VM+VR to confirm isolated-load success, then deleted entirely as part of
  cleanup (empty VPC, no lasting resources).
- voldemort: `JAVA_DEBUG` reverted to commented-out, `cloudstack-management` restarted
  clean, confirmed healthy. `/etc/default/cloudstack-management.bak.<timestamp>` backup left
  in place (matches this session's established backup-before-edit convention).
