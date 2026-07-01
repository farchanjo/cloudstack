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
**Status:** PASS — all 4 offload scenarios verified, Bug 29 found AND fixed (confirmed under
live concurrent load), one production incident during debugging (self-recovered)
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

## Bug 29 — FIXED (two attempts; the second is the real fix)

**Attempt 1 (WRONG, disproved by live testing):** hypothesized a brief commit-latency
race and shipped: (a) checking `RouterDeploymentDefinition.getGuestNetwork()` first as a
DB-round-trip-free fast path, (b) retrying `listByVpc()` up to 3x with a 200ms backoff when
it came back empty, (c) escalating the exception log to WARN. Built, unit-tested
(`NetworkHelperImplTest`, 6/6 pass, no regression), deployed to all 3 control nodes
(voldemort/bellatrix/barty), then **re-tested against the exact failure shape** — 3 VPCs,
tiers, and VMs created concurrently again. **Attempt 1 did not fix it**: all 3 VRs still
picked `systemvm-kvm-4.23.0-clean`. The management log showed why:
`getGuestNetwork()` is null on this call path — `VpcVirtualRouterElement.java:162-163`
builds the deployment definition with only `.setVpc().setDeployDestination().setAccountOwner().setParams()`,
no `.setGuestNetwork()` (only 3 other call sites in that file set it, and none of them are
the first-VR-creation path). And the retry loop's own WARN logs proved `listByVpc()` came
back **empty on every one of the 3 attempts**, not just the first — that is not the
signature of a race that resolves itself in milliseconds.

**Root cause (confirmed):** the ambient transaction the VR-creation job (`ClusteredVirtualMachineManagerImpl`)
runs inside almost certainly holds a MySQL InnoDB **REPEATABLE READ** snapshot taken
*before* the sibling tier's network-to-VPC row committed. No amount of re-querying
`_networkDao.listByVpc()` on that same connection can ever see a row committed after the
snapshot was taken — that's what REPEATABLE READ means. `Thread.sleep()` + retry only helps
races that resolve via a *different* connection eventually seeing the commit; it does
nothing when the reader itself is pinned to a stale snapshot.

**Attempt 2 (the fix):** replaced the retry loop with `Transaction.execute(...)`
(`framework/db/.../Transaction.java`), which opens a genuinely new `TransactionLegacy` —
new JDBC connection, no inherited snapshot — for the `listByVpc()` scan specifically. Kept
the `getGuestNetwork()` fast path (harmless even though null on this path; helps other
callers that do set it) and the WARN-level exception log. Built, deployed (rolling restart,
all 3 control nodes), **re-tested against the identical 3-concurrent-VPC scenario**: all 3
VMs came up `Running` (zero `Error` states, vs. all 3 `Error` before either fix), and all 3
VRs picked `systemvm-hwoffload-backports-v3` correctly — no retries needed, right on the
first read, every time. Commits: `f2d4d24806` (attempt 1, superseded), `ee2cfce9b1`
(attempt 2, the actual fix).

**Lesson:** don't assume "empty list where you expected results, under concurrent load"
implies a transient commit-latency race fixable by retrying on the same connection —
check what isolation level and transaction scope the read is actually running under first.
A live breakpoint or, as here, an actual concurrent-load re-test after each attempt is what
caught attempt 1 being wrong; a purely code-level fix without empirically re-triggering the
original failure would have shipped a no-op.

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

- All 4 test VMs/VR from the original verification matrix: still running in `offload-mix`
  (2 VPC-1 VMs) and `offload-vf-direct` (1 VM + VR) as of this doc — not cleaned up yet,
  available for further inspection if needed.
- `offload-race-check` VPC (first Bug 29 repro, pre-fix): destroyed VM/VR from the failed
  first attempt, redeployed clean VM+VR to confirm isolated-load success, then deleted
  entirely as part of cleanup (empty VPC, no lasting resources).
- `bug29-fix-verify-{1,2,3}` VPCs + `bug29-tier-{1,2,3}` networks + `bug29-fix-vm-{1,2,3}`
  (attempt 1 re-test, all 3 came up `Error`/wrong template) and `bug29-fix2-vm-{1,2,3}`
  (attempt 2 re-test, all 3 `Running`/correct template): all destroyed and deleted via `cmk`.
- voldemort: `JAVA_DEBUG` reverted to commented-out, `cloudstack-management` restarted
  clean, confirmed healthy. `/etc/default/cloudstack-management.bak.<timestamp>` backup left
  in place (matches this session's established backup-before-edit convention).
- All 3 control nodes (voldemort/bellatrix/barty): `cloudstack-management` running the
  attempt-2-fixed jar, md5 `8ee21890df2e16ce6b7c0daa86829a70`, all confirmed `active` +
  answering `HTTP 401` on `/client/api`. Old jars backed up with timestamp suffixes on each
  node (two generations: pre-Bug-29 and attempt-1, both preserved).
