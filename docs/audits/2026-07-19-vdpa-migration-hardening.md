# 2026-07-19 — vDPA Migration Hardening (Architecture / Tracker)

> **Status of this document:** ARCHITECTURE + TRACKER. Docs-only. No production code
> edits, no version bump, no build, no live API or infrastructure mutation in this
> phase. This file is the **authoritative, compaction-surviving tracker** for the
> vDPA migration-hardening workstream. Every later phase MUST update the checklist
> at the bottom (status / evidence / commit / blockers / decisions) instead of
> spawning a parallel tracker.
>
> **Phase A (Architecture / tracker) — COMPLETED** at commit `1975addd90`
> (local + Aragog `main` aligned). Phases B–E pending.

Target release line: **`4.24.1.33-SNAPSHOT`** (confirmed).
Baseline pom at audit time: `pom.xml` line 32 = `4.24.1.32-SNAPSHOT`.
Baseline Marvin stamp at audit time: `tools/marvin/setup.py` `VERSION = "4.24.1.22"` (stale; see Version bump checklist).

Scope guardrails: **CloudStack/CMK ownership only.** No manual OVS / OVN / SQL /
libvirt writes. No access to `baremetal-v2`, NYC, or `gryffindor-*`. All Ansible
only via Foreman REX (not relevant to this docs task). All build/test on Aragog.

---

## 1. Baseline and live inventory

| Item | Value | Source / evidence |
|---|---|---|
| Repo | `/Users/farchanjo/dev/cloudstack` | git worktree list: single worktree on `main` |
| Local HEAD at audit | `ecfeddce2f4acf6ca6f1ceaf663bd1dd3439567a` | `git rev-parse HEAD` |
| Aragog HEAD at audit | `ecfeddce2f4acf6ca6f1ceaf663bd1dd3439567a` | ssh `git rev-parse HEAD` on `root@aragog.slytherin.eonf.ltd` |
| Aragog dirty paths | ` M tools/marvin/setup.py` (known build-generated version stamp) | ssh `git status --porcelain` |
| Aragog branch | `main`, single worktree | ssh |
| Local branch | `main`, working tree clean before this commit | `git status` |
| Remote | `aragog root@aragog.slytherin.eonf.ltd:/root/cloudstack`, `origin git@github.com:farchanjo/cloudstack.git` | `git remote -v` |
| Workload VMs | **19**, all vDPA | live inventory (operator-provided; CMK is source of truth post-fix) |
| System VMs (TAP) | **2** | live inventory |
| Shared storage | Ceph RBD (shared, cluster-scoped) | operator-provided |
| Fluffy | empty (no workload VMs) | operator-provided |
| K8s control nodes | 3+3 with anti-affinity | operator-provided |
| `requested-chassis` on LSPs | **not set** (no pinning) | `OvnNetworkElement.applyLspOptions` only emits when resolved value is non-blank |
| Mgmt version at audit | `4.24.1.32-SNAPSHOT` fat JAR | `pom.xml` line 32 |
| Agent plugin | KVM hypervisor plugin — whether the vDPA/OVN KVM agent plugin jar changes is decided in build phase (see Build/deploy plan) | TBD by build |
| HA host flag | `hahost=false` on LAX cluster (operator-provided); `vm.ha.enabled` default `true` but fencing not configured | operator + `HighAvailabilityManagerImpl` |
| OOB / fencing | not available / not tested | operator-provided |

**Prior-claim check (fail-closed honesty):** the user asked to review existing
docs for a prior claim that "cold relocation is GO despite VF pool not exposed."
A repo-wide grep of `docs/` for `cold relocate`, `cold relocation`, `cold migrate`,
`cold-relocate`, `relocate.*GO`, `cold.*safe`, `cold.*works`, `GO.*cold`,
`cold.*vDPA` returned **no matches**. No prior GO claim exists to contradict. The
tracker therefore does **not** inherit a "cold is GO" assumption; instead it treats
cold relocate as **conditionally safe** only after the preflight invariants in
§5 pass, and explicitly **not proven** until the canary in §8 passes.

---

## 2. Source findings (anchored)

Each finding cites the file/line that proves the current behavior. These are the
gaps the hardening workstream must close.

| # | Finding | Anchor | Severity |
|---|---|---|---|
| F1 | vDPA live migration code path exists end-to-end (mgmt `orchestrateMigrate` → `hvGuru.implement` allocates dest VF → `PrepareForMigrationCommand` → `LibvirtMigrateCommandWrapper.replaceVdpaInterfaces` → `PostMigrateOvnStamp`) but has **no E2E continuity proof** in tests or production canary. | `engine/orchestration/.../VirtualMachineManagerImpl.java:3234-3417`; `plugins/hypervisors/kvm/.../wrapper/LibvirtMigrateCommandWrapper.java:232-239` | HIGH |
| F2 | **No destination VF capacity planner preflight.** `VfPoolManager.countFree(hostId)` exists but is not called by `orchestrateMigrate` or `PrepareForMigrationCommand` before dispatch. Capacity is discovered only when `HypervisorGuruBase.implement` calls `allocateForVdpa` and that returns null. | `server/.../VfPoolManagerImpl.java:753`; `server/.../HypervisorGuruBase.java:759-770`; not referenced in `VirtualMachineManagerImpl.orchestrateMigrate` | HIGH |
| F3 | **Allocation can silently fall back to TAP.** When `allocateForVdpa` returns null, `HypervisorGuruBase` logs a warning and `return`s without setting `useVdpa=true`; the NIC then routes to a TAP vif driver on the destination. This is a silent vDPA→TAP downgrade on migration. | `server/.../HypervisorGuruBase.java:767-770` (`No free VF for vDPA on host {}; NIC {} will use bridge/TAP fallback`) | HIGH |
| F4 | **PostMigrateOvnStamp is best-effort.** `dispatchPostMigrateOvnStamp` uses `_agentMgr.easySend` and swallows all failures to a `logger.warn`; the migration is not rolled back if the iface-id stamp fails. | `engine/orchestration/.../VirtualMachineManagerImpl.java:3428-3459` | MED |
| F5 | **`requested-chassis` gate absent for migration.** `applyLspOptions` only emits `requested-chassis` when the resolved value is non-blank; with no operator config and no per-VM detail, OVN may float the Port_Binding to a different chassis on a transient claim during/after migration. | `plugins/network-elements/ovn/.../OvnNetworkElement.java:1371-1387`; `OvnNicConfig.OVN_REQUESTED_CHASSIS` | MED |
| F6 | **No E2E migration coverage.** No Marvin/E2E test migrates a vDPA VM cold or live and asserts dataplane continuity + VF ownership transition. Unit tests cover `allocateForVdpa` null/capacity but not the migration orchestration. | `server/src/test/.../VfPoolManagerVdpaTest.java:100-105` (unit only) | HIGH |
| F7 | **hostdev (VF passthrough) live migration unsupported.** vDPA hot-attach/detach throw `UnsupportedOperationException`; `LibvirtPlugNicCommandWrapper` explicitly notes VF passthrough cannot be hot-plugged. Live migration of a hostdev-passthrough NIC has no code path. | `plugins/hypervisors/kvm/.../OvnVdpaVifDriver.java:279,284`; `.../wrapper/LibvirtPlugNicCommandWrapper.java:63` | HIGH |
| F8 | **HA crash-restart unavailable.** `hahost=false` on the cluster + no OOB/fencing means `HighAvailabilityManagerImpl` cannot guarantee a crashed host's VMs are fenced before restart. `scheduleRestart` is called on migration timeout, but without fencing the restart path is unsafe and must not be relied on. | `server/.../HighAvailabilityManagerImpl.java:662-693` (fence-off required before stop), `:729` (`!ForceHA && !vm.isHaEnabled()` gate) | HIGH |

---

## 3. Non-negotiable invariants

These invariants MUST hold before, during, and after every migration in this
workstream. A migration that would violate any invariant MUST fail closed.

1. **CloudStack/CMK ownership only.** No manual OVS / OVN / SQL / libvirt writes
   to reach a "migrated" state. Operators use CMK APIs only.
2. **No duplicate MAC / iface-id.** A NIC's MAC and `external_ids:iface-id`
   (`lsp-<nic-uuid>`) are globally unique; never two Port_Bindings claim the same
   LSP at once.
3. **Source inactive before cold destination active.** For cold relocate, the
   source domain is confirmed stopped (and source VF released) before the
   destination domain is started and its VF committed.
4. **VF states transactionally clean.** Every VF touched by a migration ends in
   a deterministic `FREE` / `RESERVED` / `ASSIGNED` state on exactly one host;
   `commitOwnershipForVm` / `rollbackReservationsForVm` are the only transitions.
5. **No silent vDPA→TAP downgrade.** A vDPA NIC must either migrate as vDPA or
   fail closed. F3's TAP fallback is forbidden for vDPA-tagged NICs.
6. **Same NIC UUID / MAC / IP / LSP.** The NIC identity is preserved across the
   migration; only the backing VF PCI address and `/dev/vhost-vdpa-N` path
   change (rewritten by `replaceVdpaInterfaces`).
7. **Security groups / VPC ACL / firewall / NAT / PF / CT LB / DSR preserved.**
   The OVN NB state (ACLs, NAT, PF, CT LB, DSR LB, HA chassis groups) is
   unchanged by the migration; only the Port_Binding chassis moves.
8. **One cluster member at a time.** Migrations / relocations are serialized per
   cluster member; no concurrent migration of two VMs that share a destination
   host's VF pool beyond its free capacity.
9. **Guest quorum / capacity maintained.** K8s control-plane anti-affinity (3+3)
   is not violated by a relocation; never co-locate two control-plane members on
   one host during a migration window.
10. **Failure leaves the VM safely running on one side or Stopped with
    deterministic restart.** A migration either succeeds (VM on destination,
    source cleaned) or fails with the VM still running on the source, or fails
    into a clean Stopped state. Partial / split-brain states are forbidden.
11. **Crash path requires fencing.** No crash-restart guarantee is claimed until
    OOB / fencing is available and tested (see §7).

---

## 4. Decision matrix

Each migration mode gets an explicit GO / NO-GO / CONDITIONAL decision. Default
is NO-GO until the preflight in §5 and canary in §8 pass.

| Mode | Decision | Preconditions | Failure semantics |
|---|---|---|---|
| virtio **live** | GO (unchanged upstream path) | standard `orchestrateMigrate`; no vDPA VF involved | timeout → `scheduleRestart` (HA-gated, §7) |
| virtio **cold** (stop + start, hostid change) | GO — current safe movement | standard stop/start; no VF allocation | VM Stopped on failure; deterministic restart |
| **vDPA live** | **CONDITIONAL — NO-GO until canary passes** | §5 preflight all green; §8 cold-then-live canary green | fail closed at preflight; rollback VF reservation; VM stays on source |
| **vDPA cold** (stop on src, relocate, start on dst) | **CONDITIONAL — NO-GO until cold canary passes** | §5 preflight all green; §8 cold canary green | fail closed at preflight or at destination start; VM Stopped, source VF released |
| **hostdev (VF passthrough) live** | **NO-GO** (F7 — unsupported; hot-plug throws) | none | reject at admission with explicit error |
| **hostdev cold** | CONDITIONAL — same gates as vDPA cold but with explicit hostdev preflight (device free on dest) | §5 preflight + hostdev free-VF check | fail closed; VM Stopped |
| **storage migration** (volume relocate, host unchanged) | GO for shared Ceph RBD (no host move) | standard `orchestrateMigrateWithStorage`; VF unchanged | volume rollback per upstream |
| **storage migration + vDPA host change** | CONDITIONAL — combine storage GO + vDPA cold canary | §5 + §8 | fail closed |
| **host crash / restart** | **NO-GO for auto-restart** (F8) until fencing live | OOB/fencing configured + tested | VM left on crashed host; manual recovery only |

---

## 5. Proposed minimal code changes (components + API contracts)

> Scope is **pragmatic hexagonal**: reuse existing `VfPoolManager` port and the
> migration orchestration seam. **No framework invention.** No new plugin. The
> OVN plugin stays inside the existing shaded `network-elements/ovn` artifact;
> no separate OVN plugin is introduced unless the build proves the KVM agent
> plugin jar changed (see §9).

### 5.1 Destination VF capacity-aware host admission

- **Component:** `VfPoolManager` (port) / `VfPoolManagerImpl` (adapter). Add
  `int countFreeForVdpa(long hostId, long nicId, int maxVqs)` (or reuse
  `countFree(hostId)` with a vDPA-kind filter) so a caller can ask
  "can host H satisfy N more vDPA NICs of this kind?" without allocating.
- **Caller (new preflight port owner):** a small `MigrationVfPreflight` use case
  in `engine/orchestration` (application layer) invoked by
  `VirtualMachineManagerImpl.orchestrateMigrate` and
  `orchestrateMigrateWithStorage` **before** `prepareNicForMigration`.
- **API contract:** `MigrationVfPreflight verify(VirtualMachineProfile profile,
  Host destHost)` → returns `ok` or `deny(reason)`. `deny` throws
  `InsufficientServerCapacityException` with a CMK-visible message listing the
  offending NIC, host, and free-count.

### 5.2 Fail-closed allocation (no TAP fallback for vDPA)

- **Component:** `HypervisorGuruBase` vDPA allocation block (line 763-779).
- **Change:** when the NIC's network offering has `vdpaEnabled=true` and
  `allocateForVdpa` returns null, **throw** `InsufficientCapacityException`
  instead of logging + returning (F3). The catch at line 789-794 already maps
  `InsufficientCapacityException` to a warn-and-fallback for the **HW-offload**
  branch; the vDPA branch must NOT share that fallback. Split the catch so vDPA
  propagates and HW-offload keeps its existing fallback behavior.
- **API contract:** a vDPA-tagged NIC that cannot get a VF fails the
  `implement(profile)` call, which fails `orchestrateMigrate` at the management
  layer before any agent command is dispatched.

### 5.3 Explicit rejection for unsupported hostdev live migration

- **Component:** `MigrationVfPreflight` (same use case as 5.1).
- **Change:** if any NIC is hostdev-passthrough (`useHwOffload=true` on a
  hostdev-kind offering) and the requested mode is **live**, throw
  `UnsupportedOperationException("hostdev VF passthrough live migration is
  not supported")` referencing F7. Cold hostdev is allowed subject to 5.4.
- **API contract:** the API returns a clear error to the operator; no agent
  command is dispatched.

### 5.4 requested-chassis validation + (optional) pinning

- **Component:** `OvnNetworkElement.applyLspOptions` + a new
  `MigrationRequestedChassisValidator` (or a method on the existing element).
- **Change (validation, mandatory):** during migration preflight, assert that
  the destination host's OVN chassis name is resolvable and (if
  `ovn.requested_chassis` is set on the VM or globally) matches the destination.
  If the destination chassis differs from a pinned `requested-chassis`, fail
  closed with an explicit message (F5).
- **Change (pinning, optional / decision UD1):** decide whether to
  auto-set `requested-chassis=<dest-chassis>` for the migration window and
  restore the prior value after commit, OR require operators to set it
  explicitly. Default recommendation: **do not auto-pin** in this hardening
  pass; document that without a pin OVN may float the binding and operators
  must set `ovn.requested_chassis` if they want strict pinning.

### 5.5 Synchronous PostMigrateOvnStamp + destination dataplane verification

- **Component:** `VirtualMachineManagerImpl.dispatchPostMigrateOvnStamp`
  (line 3441) and a new `DestinationDataplaneVerifier` helper.
- **Change:** replace `_agentMgr.easySend` with `_agentMgr.send` (synchronous,
  throws on timeout) for vDPA migrations; on failure, **roll back the migration**
  (call `rollbackVfReservationsBestEffort` + `rollbackNicForMigration` + stop
  destination domain + restart on source) instead of logging a warning (F4).
- **Dataplane verification:** after the stamp, issue a
  `VerifyDestinationDataplaneCommand` (new agent command) that asserts, on the
  destination: (a) `ovs-vsctl list Interface <vnetN>` has
  `external_ids:iface-id=lsp-<uuid>`, (b) the representor is on `br-int` with
  `iface-status=active`, (c) `ovn-controller` has claimed the Port_Binding
  (`ovn-sbctl list Port_Binding | grep lsp-<uuid>` shows the dest chassis).
  Only then call `finalizeVfOwnershipAfterMigration(..., true, true, ...)`.

### 5.6 Destination / source VF ownership commit / rollback

- **Component:** `VfPoolManager.commitOwnershipForVm` /
  `rollbackReservationsForVm` (already exist) +
  `VirtualMachineManagerImpl.finalizeVfOwnershipAfterMigration` (line 1762).
- **Change:** make the commit **gated on** 5.5's dataplane verification. If
  verification fails, rollback instead of best-effort commit. The existing
  `commandConclusive && destinationAuthoritativelyVerified` gate (line 1767)
  becomes: `commandConclusive && destinationAuthoritativelyVerified &&
  dataplaneVerified`.
- **API contract:** on success, source VF is `FREE`, destination VF is
  `ASSIGNED` to the VM, exactly one host owns the VF.

### 5.7 CMK-visible migration preflight / VF pool status

- **Component:** new API commands `listMigrationPreflight` (admin) and
  `listHostVfPoolStatus` (admin), under
  `api/src/main/java/org/apache/cloudstack/api/command/admin/host/` (next to
  `ForceReleaseHostVfsCmd` / `RecoverHostVfsCmd`).
- **API contract:**
  - `listMigrationPreflight vm=<uuid> destHost=<id>` → `{ ok: bool,
    deniedNics: [{ nicId, reason, freeCount, required }], requestedChassisOk:
    bool, hostdevLiveRejected: bool }`.
  - `listHostVfPoolStatus host=<id>` → per-NIC free/used/reserved counts,
    vDPA-kind aware, so operators never need SQL to decide where a VM can go.
- **Adapter:** `VfPoolService` already exposes service-level methods; wire
  these two read-only APIs through it.

### 5.8 Safe cold relocation preflight

- **Component:** `MigrationVfPreflight` (5.1) + a cold-relocate branch.
- **Change:** for cold relocate, preflight additionally verifies: (a) source
  host agent is reachable for a clean stop, (b) destination has free VF
  capacity for every vDPA NIC, (c) K8s anti-affinity is not violated by placing
  the VM on the destination, (d) no concurrent migration holds the destination
  VF pool lock for the same NICs. Only then proceed to stop → relocate → start.
- **API contract:** cold relocate that fails preflight returns
  `InsufficientServerCapacityException` with the specific denial reason; the VM
  is **not stopped**.

### 5.9 Exact error / reporting semantics

- Every denial from 5.1–5.8 returns a structured message:
  `VM <uuid> NIC <nic-uuid> (vdpa) cannot migrate to host <dest>: <reason>;
  free=<n> required=<m> requestedChassisOk=<bool>`. Logged at WARN and returned
  in the API `details` field so CMK operators see it without server logs.

### 5.10 Ports / adapters / boundaries (pragmatic)

- **Domain:** none new. VF pool state is data, not domain behavior.
- **Application (use cases):** `MigrationVfPreflight` (5.1, 5.3, 5.8),
  `DestinationDataplaneVerifier` (5.5). Both live in `engine/orchestration`,
  next to `VirtualMachineManagerImpl`, and depend on the `VfPoolManager` port
  and a new read-only `OvnChassisLookup` port (owned by the OVN plugin).
- **Outbound adapters:** `VfPoolManagerImpl` (already exists) implements VF
  queries; the OVN plugin implements `OvnChassisLookup` via the existing
  `OvnNbClient`. No new DB schema. No new plugin jar.
- **Inbound:** the two new API commands (5.7) are thin adapters calling the use
  cases.

---

## 6. HA / fencing (code vs environment)

**Statement:** No crash-restart guarantee can be claimed for vDPA (or any) VMs
on the LAX cluster until OOB / fencing is available and tested. The migration
hardening code changes do **not** add a crash-restart path; they only ensure the
planned-migration and cold-relocate paths are fail-closed. The crash path
remains **operator-driven manual recovery** until the environment items below
are delivered.

### 6.1 Code side (this workstream)

- `MigrationVfPreflight` rejects live vDPA migration if `vm.ha.enabled=true` but
  the cluster has no fencing configured (new check: `fencingConfiguredForCluster`
  via `HighAvailabilityManager`). Returns a clear message that HA restart is
  not guaranteed.
- No code change attempts to auto-restart a vDPA VM on a crashed host. The
  `scheduleRestart` call at `VirtualMachineManagerImpl:3351` is left as-is; the
  hardening ensures preflight denies migration that would rely on it.

### 6.2 Environment side (operator, NOT this workstream)

Required operator data / config (no credentials invented here):

- OOB driver choice: IPMI or Redfish (both plugins already ship:
  `plugins/outofbandmanagement-drivers/{ipmitool,redfish}`).
- Per-host OOB address + credentials stored in Foreman host params (per
  `AGENTS.md`, Foreman is the SoT for host params) — **not** in this repo.
- `FenceBuilder` implementation registered and tested per LAX host.
- `host.ha.enabled` / `vm.ha.enabled` / `ForceHA` reviewed against the cluster's
  actual fencing capability.

Until 6.2 is delivered and a fenced-restart drill is run on a canary host, the
tracker records **HA crash-restart = NO-GO**.

---

## 7. Test plan

> No production VM migration until the canary (§8) passes.

### 7.1 Focused unit tests

- `MigrationVfPreflightTest`: deny when `countFreeForVdpa < required`; deny
  hostdev live; deny when `requested-chassis` mismatches destination; ok when
  all green.
- `HypervisorGuruBaseVdpaFailClosedTest`: vDPA NIC with null
  `allocateForVdpa` → `InsufficientCapacityException` (not silent TAP).
- `VfPoolManagerImplTest`: `countFreeForVdpa` correctness; commit/rollback
  gated on `dataplaneVerified`.
- `DestinationDataplaneVerifierTest`: stubbed agent answers — ok / missing
  iface-id / unclaimed Port_Binding → rollback.

### 7.2 Wrapper / XML tests

- `LibvirtMigrateCommandWrapperTest`: `replaceVdpaInterfaces` rewrites dest
  `/dev/vhost-vdpa-N` for every vDPA interface; missing mapping leaves XML
  unchanged + warns (current behavior preserved).
- `LibvirtPrepareForMigrationCommandWrapperTest`: dest vDPA device captured
  into `vdpaInterfaceMapping` for each vDPA NIC.
- `LibvirtPostMigrateOvnStampCommandWrapperTest`: stamps `iface-id=lsp-<uuid>`
  on every dest tap; idempotent on retry.

### 7.3 Orchestration failure tests

- `VirtualMachineManagerMigrationFailClosedTest`: null `allocateForVdpa` →
  migration throws before `PrepareForMigrationCommand` dispatched; source VF
  untouched.
- `MigrationRollbackOnStampFailureTest`: `PostMigrateOvnStamp` fails →
  `rollbackVfReservationsBestEffort` + `rollbackNicForMigration` called; VM
  restarted on source or left Stopped deterministically.

### 7.4 API tests

- `listMigrationPreflightCmdTest`: returns structured denial for each failing
  NIC.
- `listHostVfPoolStatusCmdTest`: returns per-NIC free/used/reserved counts.

### 7.5 Real Aragog build / test

- All Maven + checkstyle + unit tests run **only on Aragog** (per `AGENTS.md`).
  No local builds. Full reactor `BUILD SUCCESS` required before any canary.

### 7.6 Marvin / E2E canary

- Cold canary: stop a dedicated canary vDPA VM on source, relocate to a
  destination host with free VF capacity, start, assert dataplane (DHCP, ICMP,
  TCP, iperf) + OVN Port_Binding claimed on destination + source VF `FREE`.
- Live canary: live-migrate the same canary vDPA VM, assert zero packet loss
  during migration window + dataplane verification green.
- These run against the Aragog-managed test segment only; **no production VM**
  is a canary target until the cold canary passes.

### 7.7 Bounded continuity probe (dedicated canary only)

- A dedicated canary VM (not in the 19 workload VMs) runs a continuity probe:
  1 TCP iperf + 1 ICMP ping pair across the migration window. Probe is bounded
  (max 5 minutes) and runs only on the canary.

### 7.8 Cleanup / failure tests

- `MigrationCleanupTest`: failed migration leaves no `RESERVED` VF on
  destination; no `ASSIGNED` VF on source after rollback; no duplicate LSP.
- `VfPoolOrphanSweepTest`: a crashed-migration leaves no orphan VF rows.

### 7.9 Network invariant snapshots

- Before/after each canary migration, snapshot OVN NB (ACLs, NAT, PF, CT LB,
  DSR LB, HA chassis groups) and assert **no diff** except the Port_Binding
  chassis. Snapshot tooling: existing `ovn-nbctl dump` wrapped by a CMK
  read-only API (no manual OVN writes).

---

## 8. Build / deploy plan

1. Every code change from §5 is committed on `main` and pushed to `aragog main`.
   No feature branches, no worktrees (see §12).
2. All Maven / checkstyle / unit / wrapper / API tests run **only on Aragog**.
   No local `mvn` / `pytest`.
3. Produce the full **`4.24.1.33-SNAPSHOT`** fat management JAR on Aragog.
4. **Determine whether the KVM agent plugin jar changed.** If the §5 changes
   touch only `engine/orchestration` + `server` + `api` + the OVN plugin's
   management-side classes, the KVM agent plugin jar may be unchanged → only
   the management server needs a rolling redeploy. If any
   `plugins/hypervisors/kvm/**` or `OvnVdpaVifDriver` / wrapper class changed,
   the agent plugin jar changes → rolling agent redeploy required.
5. **No separate OVN plugin** if the OVN classes remain inside the existing
   shaded `network-elements/ovn` artifact. A separate plugin is introduced only
   if the build proves the shaded jar cannot carry the new read-only
  `OvnChassisLookup` adapter — that decision is deferred to the build phase.
6. Rolling deployment: management server first (CMK catalog gate green), then
   agent plugin only if §8.4 says it changed. Rolling per host, one cluster
   member at a time (invariant §3.8). Maintain a rollback JAR per step.
7. CMK catalog gates: the new `listMigrationPreflight` / `listHostVfPoolStatus`
   APIs must return green for every LAX host before any production migration is
   scheduled.

---

## 9. Version bump checklist

Target: `4.24.1.33-SNAPSHOT` everywhere version metadata appears. This docs
task does **not** perform the bump; the bump is the first code-phase commit.

- [ ] `pom.xml` line 32: `4.24.1.32-SNAPSHOT` → `4.24.1.33-SNAPSHOT`.
- [ ] Any child pom / `deps/` / `packaging/` version property referencing the
      project version (grep `4.24.1.32-SNAPSHOT` repo-wide).
- [ ] `tools/marvin/setup.py` `VERSION = "4.24.1.22"` → `"4.24.1.33"` so the
      Aragog build leaves the tracked worktree clean (the current stale stamp is
      the known dirty path on Aragog).
- [ ] Any `*.json` / `*.yaml` / `debian/` changelog version metadata referencing
      `4.24.1.32` or `4.24.1.22`.
- [ ] After bump: `git status` clean on Aragog (no `tools/marvin/setup.py` dirty
      residue); local main + Aragog main HEAD aligned.

---

## 10. Operational validation / rollback runbook (outline)

### 10.1 Before migration (preflight, per §5.1 + §5.8)

- `listMigrationPreflight vm=<uuid> destHost=<id>` → must return `ok`.
- `listHostVfPoolStatus host=<dest>` → free capacity ≥ required for every vDPA
  NIC.
- OVN NB snapshot taken (ACLs, NAT, PF, CT LB, DSR LB, HA chassis groups).
- K8s anti-affinity check: destination does not co-locate two control-plane
  members.
- HA check: if `vm.ha.enabled=true` and no fencing → operator acknowledges
  crash-restart is not guaranteed (sign-off recorded).

### 10.2 During migration (cold or live)

- Cold: stop source → confirm source VF `FREE` → start destination →
  `VerifyDestinationDataplaneCommand` green → commit ownership.
- Live: `orchestrateMigrate` with §5 preflight → `PostMigrateOvnStamp`
  synchronous → dataplane verification → commit ownership; on any failure,
  rollback and restart on source (or leave Stopped deterministically).

### 10.3 After migration (invariants)

- VF: exactly one `ASSIGNED` VF on destination; source VF `FREE`; no `RESERVED`.
- OVN: Port_Binding for `lsp-<nic-uuid>` claimed on destination chassis;
  `iface-id` stamped on dest tap; representor on `br-int` active.
- LB / firewall / NAT / PF: OVN NB snapshot diff = empty except Port_Binding
  chassis.
- K8s: control-plane quorum intact; no two members on one host.
- Guest: DHCP lease renewed; ICMP 0% loss; TCP established (canary probe).

### 10.4 Rollback

- Code rollback: redeploy previous fat management JAR (+ previous agent plugin
  jar if §8.4 said agent changed). Per-step rollback JARs kept from §8.6.
- Data rollback: VF pool rows are data; `rollbackReservationsForVm` restores
  `FREE` / `ASSIGNED`. No SQL writes by operators.
- OVN rollback: the Port_Binding moves back to the source chassis when the VM
  restarts on source (OVN controller claims it); no manual `ovn-sbctl` writes.
- If a migration left a duplicate LSP or a stale `iface-id`, the correct
  recovery is **restart the VM via CMK** (which re-runs plug + post-plug stamp),
  not a manual `ovs-vsctl`.

---

## 11. Exact before/after invariants

| Invariant | Before | After (successful migration) |
|---|---|---|
| LB | OVN NB LB rows unchanged | unchanged (snapshot diff empty) |
| Firewall / ACL | ACL rows unchanged | unchanged |
| OVN Port_Binding | claimed on source chassis | claimed on destination chassis |
| OVN `iface-id` | `lsp-<uuid>` on source tap | `lsp-<uuid>` on destination tap |
| VF pool (source) | `ASSIGNED` to VM | `FREE` |
| VF pool (destination) | `FREE` | `ASSIGNED` to VM |
| Kubernetes | 3+3 control-plane, anti-affinity | 3+3 control-plane, anti-affinity preserved |
| NIC UUID / MAC / IP | unchanged | unchanged |
| `requested-chassis` | (operator choice) | validated vs destination; unchanged unless operator set it |

---

## 12. Git hygiene checklist

- [ ] Work on `main` only. No feature branch, no worktree.
- [ ] No unmerged branches at end of workstream (`git branch` clean to `main`).
- [ ] No extra worktrees (`git worktree list` = single entry).
- [ ] Local `main` HEAD == Aragog `main` HEAD at every commit boundary.
- [ ] Clean tracked trees after Aragog build (only `tools/marvin/setup.py`
      version-stamp dirty is tolerated mid-build; the version bump §9 removes
      even that).
- [ ] Selective preservation: unrelated dirty paths on Aragog are stashed
      before push and reapplied after; only the intended commit is pushed.
- [ ] Final branch / worktree cleanup: prune any stray local branches, leave
      single `main` worktree.
- [ ] This docs commit touches only
      `docs/audits/2026-07-19-vdpa-migration-hardening.md` and
      `docs/audits/README.md`.

---

## 13. Detailed checklist (parent-todo mirror)

> This is the compaction-surviving status board. Update in place every phase.
> Statuses: `pending` / `in_progress` / `completed` / `blocked`. Every
> `completed` row must cite a commit SHA or artifact path. Every `blocked` row
> must cite the blocker and a rollback point.

### Phase A — Architecture / tracker (this document)

| ID | Task | Status | Evidence / commit / artifact | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| A1 | Survey repo + source findings F1–F8 | completed | this file §2 | — | — |
| A2 | Baseline + live inventory | completed | this file §1 | — | — |
| A3 | Invariants §3 + decision matrix §4 | completed | this file §3, §4 | — | — |
| A4 | Minimal code changes §5 + API contracts | completed | this file §5 | — | — |
| A5 | HA / fencing separation §6 | completed | this file §6 | — | UD1, UD2 |
| A6 | Test plan §7 | completed | this file §7 | — | — |
| A7 | Build/deploy plan §8 | completed | this file §8 | — | UD3 |
| A8 | Version bump checklist §9 | completed | this file §9 | — | — |
| A9 | Runbook + invariants §10, §11 | completed | this file §10, §11 | — | — |
| A10 | Git hygiene §12 | completed | this file §12 | — | — |
| A11 | Commit tracker + index on local main | completed | `1975addd90` `docs(ovn): plan vDPA migration hardening` | — | rollback: `git reset --hard ecfeddce2f` before push |
| A12 | Push to Aragog main (selective stash if needed) | completed | push `ecfeddce2f..1975addd90 main -> main`; aragog stash `b7fd8281` dropped clean, only `tools/marvin/setup.py` dirty (known stamp), stash list empty | — | — |
| A13 | Phase A close-out report | completed | this section + report to caller | — | — |

### Phase B — Code (PENDING; not started in this task)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| B1 | Version bump to `4.24.1.33-SNAPSHOT` (§9) | pending | — | — | revert pom + setup.py |
| B2 | `MigrationVfPreflight` use case + `countFreeForVdpa` (§5.1) | pending | — | — | — |
| B3 | Fail-closed vDPA allocation in `HypervisorGuruBase` (§5.2) | pending | — | — | restore F3 fallback |
| B4 | hostdev live rejection (§5.3) | pending | — | — | — |
| B5 | `requested-chassis` validation (§5.4) | pending | — | UD1 | — |
| B6 | Synchronous `PostMigrateOvnStamp` + dataplane verify (§5.5) | pending | — | — | restore easySend best-effort |
| B7 | VF commit/rollback gated on dataplane (§5.6) | pending | — | — | — |
| B8 | `listMigrationPreflight` + `listHostVfPoolStatus` APIs (§5.7) | pending | — | — | — |
| B9 | Cold relocate preflight (§5.8) | pending | — | — | — |
| B10 | Error/reporting semantics (§5.9) | pending | — | — | — |

### Phase C — Tests (PENDING)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| C1 | Unit tests §7.1 | pending | — | B2–B10 | — |
| C2 | Wrapper/XML tests §7.2 | pending | — | B6 | — |
| C3 | Orchestration failure tests §7.3 | pending | — | B2,B3,B6 | — |
| C4 | API tests §7.4 | pending | — | B8 | — |
| C5 | Aragog full build + checkstyle + unit §7.5 | pending | — | B*, C1–C4 | — |
| C6 | Marvin cold canary §7.6 | pending | — | C5, §8 | no prod VM |
| C7 | Marvin live canary §7.6 | pending | — | C6 | no prod VM |
| C8 | Bounded continuity probe §7.7 | pending | — | C7 | canary only |
| C9 | Cleanup/failure tests §7.8 | pending | — | B6,B7 | — |
| C10 | Network invariant snapshots §7.9 | pending | — | C6 | — |

### Phase D — Deploy (PENDING)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| D1 | Determine if KVM agent plugin jar changed (§8.4) | pending | — | C5 | — |
| D2 | Rolling mgmt redeploy + rollback JAR | pending | — | D1 | prev JAR |
| D3 | Rolling agent redeploy if D1 says changed | pending | — | D1 | prev agent JAR |
| D4 | CMK catalog gates green | pending | — | D2,D3 | — |
| D5 | HA/fencing env delivery (§6.2) — operator | pending | — | OOB not ready | NO-GO for crash-restart |
| D6 | Fenced-restart drill on canary host | pending | — | D5 | — |

### Phase E — Production migration (PENDING)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| E1 | Migrate first workload vDPA VM (cold) | pending | — | C6,D4 | rollback to source |
| E2 | Migrate first workload vDPA VM (live) | pending | — | C7,D4,E1 | rollback to source |
| E3 | Drain Fluffy / K8s node rotations | pending | — | E2 | per-node rollback |
| E4 | Final invariant audit (§11) | pending | — | E* | — |

---

## 14. Unresolved architecture decisions

- **UD1 — `requested-chassis` auto-pinning during migration window.** Options:
  (a) do not auto-pin; require operator to set `ovn.requested_chassis` for
  strict pinning (recommended default, smallest blast radius); (b) auto-pin to
  destination for the window, restore prior value after commit. Decision needed
  before B5.
- **UD2 — Failure semantics when `PostMigrateOvnStamp` fails on a live
  migration.** Options: (a) rollback (stop dest, restart source) — safest but
  disruptive; (b) leave VM on destination + alert operator to manual stamp
  (current behavior) — least disruptive but violates invariant §3.5 if the
  stamp truly failed. Decision needed before B6. Recommendation: (a) for vDPA,
  (b) for virtio TAP.
- **UD3 — Separate OVN plugin jar vs shaded.** Decision deferred to build
  phase (§8.5). If the shaded `network-elements/ovn` jar cannot carry the new
  `OvnChassisLookup` adapter cleanly, split; otherwise keep shaded.
- **UD4 — Cold relocate as the default safe movement.** This tracker treats
  cold relocate as the current safe movement (per parent instruction) but marks
  it CONDITIONAL until the cold canary (C6) passes. If the cold canary fails,
  the workstream falls back to "no vDPA movement; wait for fencing + live canary".
- **UD5 — HA restart gate.** Whether to hard-fail all vDPA migrations when
  `fencingConfiguredForCluster=false` (strict) or only warn (permissive).
  Recommendation: hard-fail live vDPA, warn cold vDPA (cold does not depend on
  restart). Decision needed before B2.

---

## 15. Out of scope (this docs task)

- No production code edits.
- No version bump.
- No build, no test run.
- No live API or infrastructure mutation.
- No access to `baremetal-v2`, NYC, or `gryffindor-*`.
- No Foreman REX job creation.
- No ADR file creation (this is a tracker, not an ADR; if an ADR is later
  required for UD1–UD5, it goes in `infra-base/docs/decisions/` per the
  arch-advisor skill, not here).

---

**End of tracker.**
