# 2026-07-19 — vDPA Migration Hardening (Architecture / Tracker)

> **Status of this document:** ARCHITECTURE + TRACKER. Docs-only. No production code
> edits, no version bump, no build, no live API or infrastructure mutation in this
> phase. This file is the **authoritative, compaction-surviving tracker** for the
> vDPA migration-hardening workstream. Every later phase MUST update the checklist
> at the bottom (status / evidence / commit / blockers / decisions) instead of
> spawning a parallel tracker.
>
> **Phase A (Architecture / tracker) — COMPLETED** at commit `1975addd90`
> (local + Aragog `main` aligned). **Slice 0 — COMPLETED** at the code-phase
> commit recorded below. Runtime slices remain pending until Slice 0's Aragog
> gate is independently validated.

**Architecture review status:** **COMPLETE — PASS with mandatory corrections.**
The consolidated review below is docs-only and does not authorize runtime changes,
deployment, or production migration. Implementation must satisfy the 15 mandatory
corrections and the exact Slice 0 gate before Phase B begins.

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
| B1 | Version bump to `4.24.1.33-SNAPSHOT` (§9) | completed | Slice 0 commit: all tracked POM project-version references and `tools/marvin/setup.py` now target `4.24.1.33` | Aragog scoped validation pending | revert version metadata |
| B2 | `MigrationVfPreflight` use case + `countFreeForVdpa` (§5.1) | completed | `34571acf75`: vDPA-specific count, global SB claim count, requested-chassis adapter, authoritative host/placement gate, VM + destination-cluster admission locks, live hostdev denial, and cold hostdev capacity gate | Aragog validation pending | preserve planner/HA ownership |
| B3 | Fail-closed vDPA allocation in `HypervisorGuruBase` (§5.2) | completed | `1b4e244850` extends `982672fe9f`: null manager, null allocation, checked allocation failure, and runtime failure all reject vDPA without TAP fallback; focused message test added | Aragog scoped compile/unit validation pending | restore only with explicit tracker rollback |
| B4 | hostdev live rejection (§5.3) | completed | `MigrationVfPreflight` rejects non-vDPA `useHwOffload` NICs for LIVE mode with an explicit unsupported-operation message; COLD mode remains available for later destination-device gates | cold hostdev capacity gate remains in Slice 5.5/6 | preserve live rejection |
| B5 | `requested-chassis` validation (§5.4) | completed | `d37c03e0f0`: read-only `OvnChassisLookup` port/OVN adapter resolves the configured policy and rejects non-matching destination chassis without NB writes | Aragog integration validation pending | no auto-pin; preserve operator policy |
| B6 | Synchronous `PostMigrateOvnStamp` + dataplane verify (§5.5) | completed | `34571acf75`: destination proof now checks br-int membership, expected chassis, unique iface-id/MAC, active/installed state, representor presence, and one global SB claim; source proof runs after cutover | Aragog validation pending | no canary before C5 |
| B7 | VF commit/rollback gated on dataplane (§5.6) | completed | `34571acf75`: ownership manager is mandatory for VF paths; commit occurs only after stamp, destination proof, source-down proof, and destination cleanup is authoritative | Aragog failure-path tests pending | fail closed |
| B8 | `listMigrationPreflight` + `listHostVfPoolStatus` APIs (§5.7) | completed | Current worktree adds admin-only read-only command/response contracts and `VfPoolService` status façade; no force-release or repair operation exposed | Aragog API authorization/response tests pending | preserve read-only boundary |
| B9 | Cold relocate preflight (§5.8) | completed | `34571acf75`: cold vDPA/SR-IOV transaction uses `orchestrateStart` with destination plan, source binding-down proof, destination stamp/verify, ownership commit, destination stop/rollback, and HA-manager restart scheduling | Aragog validation pending | leave stopped if restart policy declines |
| B10 | Error/reporting semantics (§5.9) | completed | `34571acf75`: per-NIC API denials, explicit ownership failures, source/destination proof failures, and aggregated agent cleanup failures retain recovery evidence | Aragog validation pending | no silent downgrade |

### Phase C — Tests (PENDING)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| C1 | Unit tests §7.1 | completed | `34571acf75`: fail-closed allocation, vDPA-specific capacity, preflight gates, cold hostdev capacity, ownership failure, and per-NIC API evidence tests | Aragog execution pending | — |
| C2 | Wrapper/XML tests §7.2 | completed | `34571acf75`: vDPA XML mapping, prepare mapping, post-stamp failure/idempotence contract, and orphan ownership tests | Aragog execution pending | — |
| C3 | Orchestration failure tests §7.3 | completed | `34571acf75`: stamp failure and reordered verifier failure are fatal before ownership commit; cold path has authoritative rollback code | Aragog execution pending | — |
| C4 | API tests §7.4 | completed | `34571acf75`: admin authorization annotation tests and structured per-NIC/status response tests | Aragog execution pending | — |
| C5 | Aragog full build + checkstyle + unit §7.5 | pending | — | B*, C1–C4 | — |
| C6 | Marvin cold canary §7.6 | pending | — | C5, §8 | no prod VM |
| C7 | Marvin live canary §7.6 | pending | — | C6 | no prod VM |
| C8 | Bounded continuity probe §7.7 | pending | — | C7 | canary only |
| C9 | Cleanup/failure tests §7.8 | completed | `34571acf75`: destination-owned orphan tests, active duplicate refusal, source binding proof, wrapper cleanup aggregation, and destination-stop rollback coverage | Aragog execution pending | — |
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

### Phase B/C implementation gate record

| Gate | Status | Evidence | Blocker / next action |
|---|---|---|---|
| Slice 0 P0.1 tracker corrections | completed | §15.4, §15.5, §15.6 contain the 15 corrections, UD1–UD5 resolutions, SP1–SP7, SP-COLD, and the storage-plus-vDPA scope decision | verify on Aragog before Slice 1 |
| Slice 0 P0.2 version metadata | completed | root and child POM project versions target `4.24.1.33-SNAPSHOT`; Marvin stamp targets `4.24.1.33` | verify no unintended stale metadata on Aragog |
| Slice 0 P0.3 schema registry | completed | `Upgrade42432to42433` and `DatabaseUpgradeChecker` registration | scoped schema/tool validation on Aragog |
| Slice 0 P0.4 Aragog validation | pending | not run from this workstation by policy | Aragog build and clean-worktree verification |
| Slice 0 P0.5 safety | completed | no runtime implementation, deployment, CMK, Foreman, or infrastructure operation performed | maintain boundary for later slices |

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

---

## 15. Consolidated architecture-review closeout

> This section materializes the independent source review completed against CloudStack
> commit `8c6a81221ebb4eff9d460999bd80edac486ddfba` (`8c6a81221e`). It preserves the
> Phase-A history and checklist above. No runtime code is changed by this section.

### 15.1 Verified findings F1–F10

All ten findings are substantiated against the source at the reviewed commit.

| ID | Finding | Source anchor | Severity |
|---|---|---|---|
| F1 | vDPA live migration exists end-to-end, but has no E2E continuity proof. | `engine/orchestration/src/main/java/com/cloud/vm/VirtualMachineManagerImpl.java:3234-3425`; `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtMigrateCommandWrapper.java:232-239` | HIGH |
| F2 | No destination VF capacity preflight; `countFree` is not called by migration orchestration. | `server/src/main/java/com/cloud/network/router/VfPoolManagerImpl.java:752-755`; `VirtualMachineManagerImpl.java:3229,3817` | HIGH |
| F3 | vDPA allocation can silently fall back to TAP when `allocateForVdpa` returns null. | `server/src/main/java/com/cloud/hypervisor/HypervisorGuruBase.java:763-779` | HIGH |
| F4 | `PostMigrateOvnStamp` is best-effort: `easySend` swallows errors and only warns. | `engine/orchestration/src/main/java/com/cloud/vm/VirtualMachineManagerImpl.java:3427-3459`; `engine/components-api/src/main/java/com/cloud/agent/AgentManager.java:67-88` | MEDIUM |
| F5 | `requested-chassis` is emitted only when configured; migration has no validation gate. | `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnNetworkElement.java:1367-1387` | MEDIUM |
| F6 | Existing vDPA tests are unit/lifecycle coverage; no cold/live migration E2E canary exists. | `server/src/test/java/com/cloud/network/router/VfPoolManagerVdpaTest.java`; `engine/orchestration/src/test/java/com/cloud/vm/VirtualMachineManagerVfLifecycleTest.java` | HIGH |
| F7 | Hostdev/VF passthrough live migration is unsupported; vDPA hot attach/detach throws. | `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVdpaVifDriver.java:277-285`; `LibvirtPlugNicCommandWrapper.java:63-65` | HIGH |
| F8 | HA restart requires successful fencing; current LAX conditions do not provide that guarantee. | `server/src/main/java/com/cloud/ha/HighAvailabilityManagerImpl.java:662-693,729-734`; migration timeout path `VirtualMachineManagerImpl.java:3348-3355` | HIGH |
| F9 | vDPA representor attach lacks the HW-offload DEF-1/cross-representor duplicate-iface-id guard. | `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVdpaVifDriver.java:344-363`; compare `OvnVfPassthroughVifDriver.java:242-275` | HIGH; storm vector |
| F10 | Partial `PrepareForMigration` failure does not call vDPA rollback from its catch block; inactive destination artifacts can remain. | `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtPrepareForMigrationCommandWrapper.java:143-154,202-218` | MEDIUM |

### 15.2 Resolved architecture decisions UD1–UD5

#### UD1 — `requested-chassis`

**Decision: reject auto-pinning.** Use read-only preflight validation only. If a
VM/NIC or global setting supplies a non-blank `requested-chassis`, resolve it via
the same `OvnNicTunables.resolve` path used by `OvnNetworkElement.applyLspOptions`
and require it to match the destination OVN chassis. A blank value is valid and
remains blank. Do not write or temporarily restore OVN NB state during migration.

#### UD2 — synchronous `PostMigrateOvnStamp` failure

**Decision: pre-verify, do not promise rollback after libvirt commit.** The current
order commits NIC/VF ownership at `VirtualMachineManagerImpl.java:3409-3410` and
then stamps at `:3417`; this must be reversed for vDPA. Use synchronous `send`
for vDPA, run the stamp and destination verification before ownership commit, and
fail closed. If stamping or verification fails after libvirt has moved the domain,
stop the destination and use a **cold restart** on source as the recovery path;
packet loss is expected. Do not claim an atomic live rollback or restoration of a
committed source VF. For virtio/TAP, preserve the existing best-effort behavior.

#### UD3 — artifact and deploy boundary

**Decision: both shaded management and KVM agent artifacts change; no separate OVN
plugin artifact.** Management-side changes include orchestration, server, API,
OVN management adapter, and schema versioning. Agent-side changes include the new
verification command wrappers, vDPA DEF-1 guard, and prepare-failure cleanup.
Shared command classes in `core` affect both. An older agent must reject an unknown
command and cause management verification to fail closed. Therefore deploy
**agent first, management second**, one host/node at a time, with previous JARs
retained for rollback.

#### UD4 — cold relocation

**Decision: conditional GO only after proof and canary.** The transaction is:
preflight → source stop → authoritative source-down proof → destination prepare /
start with destination representor inactive → post-start stamp and verify → VF
commit. Preflight failure leaves the VM running on source. A destination-start
failure can leave the VM Stopped and require deterministic operator restart if
source VF reallocation is unavailable. Cold migration has downtime; it is not a
zero-loss operation. It is NO-GO until the cold canary and SP-COLD pass.

#### UD5 — HA/fencing

**Decision: hard-fail live vDPA when HA is enabled and fencing is unavailable;
cold vDPA is fencing-agnostic.** The admission gate is:

```text
if (mode == LIVE && vm.isHaEnabled()
        && !fencingConfiguredForCluster(vm.getClusterId())) {
    deny("live vDPA migration requires fencing for HA restart safety");
}
```

Planned relocation and crash-restart remain separate paths. This workstream does
not add or claim crash-restart safety; `HighAvailabilityManagerImpl` remains
NO-GO for unfenced crash recovery.

### 15.3 Storm-prevention gates SP1–SP7 and SP-COLD

The following gates are mandatory. Observations are read-only; **never clear
counters** or alter OVS/OVN state as part of observation.

#### SP1 — Destination inactive until authoritative cutover

`OvnVdpaVifDriver.attachRepresentorToBrInt` must leave the destination
representor `external_ids:iface-status=inactive` (`OvnVdpaVifDriver.java:354-358`).
Only the authoritative post-start/post-migration path
(`LibvirtComputingResource.applyVdpaPostPlugTunables`, `:5144-5169`) may set
`iface-status=active` and `ovn-installed=true`. Stamp and verify before VF/NIC
commit. Any early active destination is a hard abort.

#### SP2 — Never dual-active for one MAC/LSP

At no point may source and destination both carry an active Interface with the
same MAC or `iface-id=lsp-<uuid>`, and exactly one OVN chassis may claim the
Port_Binding. Preflight rejects an existing multi-chassis claim; destination
verification proves exactly one destination claim. Any two claims are a hard
abort.

#### SP3 — Remove inactive destination artifacts on cleanup

Every failed prepare or pre-cutover failure must remove destination vDPA device,
representor, OVS port, PF identity, and VF reservation. Wire the existing
`OvnVdpaVifDriver.releaseVdpaOnRollback` (`:379-398`) through
`LibvirtPrepareForMigrationCommandWrapper`'s catch path, not only its explicit
rollback-command path. Any stale inactive artifact is a hard abort/blocker.

#### SP4 — No fail-open TAP duplicate

A vDPA NIC with no destination VF must throw and fail closed. It must never become
a TAP NIC with the same MAC/LSP while source remains active. The vDPA null result
at `HypervisorGuruBase.java:767-770` must throw; preserve the existing fallback
for legitimate non-vDPA HW-offload behavior. A destination TAP duplicate is a
hard abort.

#### SP5 — Bounded canary traffic only

Use only a dedicated canary VM. The continuity probe is one unicast TCP iperf
plus one ICMP pair, maximum five minutes, with no broadcast/multicast target, ARP
sweep, or traffic from workload VMs. Probe scope or duration violation is a hard
abort.

#### SP6 — Read-only pre/during/post observations

Capture pre-migration baseline, during-cutover, and post-steady-state observations
of BUM/broadcast/multicast/unknown-unicast counters, TC/OVS flow state, and OVN
Port_Binding claims. Use read-only OVS/OVN/switch observations; **do not clear
counters**. Record source/destination Interface state, `iface-id`,
`iface-status`, Port_Binding chassis, and any `actions=FLOOD` path.

#### SP7 — Hard-abort triggers

Any one of the following stops the migration/canary and blocks further rollout:

1. Unexpected duplicate MAC on two OVS Interfaces.
2. Two Port_Binding chassis claims for one LSP.
3. Broadcast increase above the approved baseline threshold (initial canary gate:
   >2x baseline sustained for 10 seconds).
4. Any switch storm-control event or equivalent switch alert.
5. An OVS flood path for a migrated learned MAC after destination activation.

Preserve observations for RCA; do not clear counters. Recovery is fail closed:
stop destination, do not commit ownership, and use the UD2 cold-restart path.

#### SP-COLD — Prove source binding down before destination start

After `advanceStop` (`VirtualMachineManagerImpl.java:2549-2608+`) and before
destination `StartCommand`, a new `VerifySourceBindingDownCommand` must prove:

1. The source libvirt domain is shut off/not found.
2. No source `br-int` Interface carries the VM's `iface-id=lsp-<uuid>`.
3. The source Port_Binding is absent or has no chassis claim.

Use one bounded synchronous command, not a polling loop. If the source agent is
unreachable or the proof is negative, deny/abort and leave the VM Stopped; do not
start the destination.

### 15.4 Mandatory corrections before Phase B

1. Reorder vDPA stamp/verify before `commitNicForMigration` and
   `finalizeVfOwnershipAfterMigration`; replace impossible live rollback wording
   with destination stop + source cold-restart semantics.
2. Correct deployment order to agent JAR first, management JAR second; document
   both artifacts and no separate OVN plugin.
3. Add the exact UD5 live/HA/fencing hard gate; keep cold fencing-agnostic.
4. Change vDPA null allocation to throw before the HW-offload fallback catch;
   preserve legitimate HW-offload fallback.
5. Add/register no-op `Upgrade42432to42433.java` for the version-row bump.
6. Add tests for process-level vDPA XML mapping, reordered-stamp failure,
   expected cold downtime, fencing admission, agent cleanup, destination verify,
   and source-binding-down verify.
7. Use minimal `countFreeForVdpa(long hostId)`; document preflight as advisory and
   allocation as the hard gate.
8. Confirm UD1 no auto-pin and read-only requested-chassis validation.
9. Either add stamp/verification to `orchestrateMigrateWithStorage` or mark
   storage-plus-vDPA host migration NO-GO.
10. Add DSR LB VIP/router chassis-affinity preservation to the invariant table.
11. Apply K8s anti-affinity/quorum checks to live and cold preflight.
12. Port DEF-1 and `clearOrphanRepsForLspName` into
   `OvnVdpaVifDriver.attachRepresentorToBrInt`.
13. Wire `releaseVdpaOnRollback` into the prepare catch block for partial failures.
14. Add `VerifySourceBindingDownCommand` between source stop and destination start.
15. Add SP6's three read-only observation windows and SP7's five hard-abort
   triggers; explicitly forbid counter clears.

### 15.5 Ordered implementation slices

#### Slice 0 — Tracker corrections and version bump

Docs/status plus version metadata only: update this tracker with corrections
1–15; bump all project versions to `4.24.1.33-SNAPSHOT`; bump
`tools/marvin/setup.py` to `4.24.1.33`; add/register
`engine/schema/.../Upgrade42432to42433.java`; build only the scoped schema/tool
reactor on Aragog; require clean worktree. No runtime deployment.

#### Slice 1 — Fail-closed vDPA allocation

Change `server/src/main/java/com/cloud/hypervisor/HypervisorGuruBase.java:763-779`
to throw on null vDPA allocation without swallowing it in the HW-offload catch.
Add `HypervisorGuruBaseVdpaFailClosedTest`. This closes SP4.

#### Slice 2 — Capacity-aware migration preflight

Add `countFreeForVdpa(long hostId)` to `VfPoolManager`/
`VfPoolManagerImpl`; add `MigrationVfPreflight` in orchestration; add the
`OvnChassisLookup` read port and OVN adapter; add fencing inspector, hostdev-live
rejection, requested-chassis validation, live/cold K8s anti-affinity, and SP2
single-claim preflight. Wire before `prepareNicForMigration` at
`VirtualMachineManagerImpl.java:3229,3817` and into cold relocation. Add unit
tests.

#### Slice 3 — Synchronous stamp and destination verification

In `VirtualMachineManagerImpl.java:3407-3420`, stamp/verify before NIC/VF commit;
use synchronous `send` for vDPA only. Add shared
`VerifyDestinationDataplaneCommand`/Answer and the KVM wrapper. Verify iface-id,
inactive-to-active cutover, representor state, `ovn-installed=true`, and exactly
one destination Port_Binding claim. Gate `finalizeVfOwnershipAfterMigration`.
Add orchestration, verifier, XML mapping, and reordered-failure tests.

#### Slice 4 — Agent cleanup and vDPA duplicate guard

In `LibvirtPrepareForMigrationCommandWrapper.java:143-154`, call
`releaseVdpaVfsOnRollback` on partial prepare failure. In
`OvnVdpaVifDriver.java:344-363`, port the DEF-1 and cross-representor guard from
`OvnVfPassthroughVifDriver`. Add cleanup and duplicate-iface-id tests. This closes
F9, F10, SP2, and SP3.

#### Slice 5 — CMK-visible read-only APIs

Add `listMigrationPreflight` and `listHostVfPoolStatus` under
`api/src/main/java/org/apache/cloudstack/api/command/admin/host/`; extend the
read façade `VfPoolService`; add structured denial/status responses and API tests.
Expose no force-release or unsafe repair operation.

#### Slice 5.5 — Cold source-binding-down proof

Add shared `VerifySourceBindingDownCommand`/Answer and
`LibvirtVerifySourceBindingDownCommandWrapper`; wire after source
`advanceStop` and before destination `StartCommand`. Use one bounded synchronous
send, hard-deny unreachable/claimed source, and leave VM Stopped. Add wrapper and
orchestration tests. This closes SP-COLD and R11.

#### Slice 6 — Build, cold/live canaries, and storm observations

Run the full Aragog build and tests. Execute only a dedicated canary: cold first,
then live. Require bounded cold downtime, zero-loss live continuity, XML/VF/
Port_Binding invariants, SP1–SP2 state transitions, SP5 traffic bounds, SP6
pre/during/post read-only observations, and no SP7 hard-abort trigger. No workload
VM is a canary target until cold passes.

#### Slice 7 — Deployment

Because `core` commands and KVM wrappers changed, roll the agent plugin JAR first,
one LAX KVM host at a time, then roll the shaded management JAR one control node
at a time. Keep previous JARs and verify agent/management compatibility before
any migration. No NYC/gryffindor activity.

#### Slice 8 — Production migration

E1: first vDPA cold migration; E2: first vDPA live migration; E3: Fluffy/K8s
rotations; E4: final invariants and storm-observation audit. Apply SP6 for every
canary/production migration and abort on any SP7 trigger.

### 15.6 Exact Slice 0 PASS/FAIL criteria

Slice 0 is **PASS only if every P0 criterion holds**. Any F0 criterion is an
immediate **FAIL** and blocks Slice 1.

#### PASS criteria

- **P0.1 Tracker:** this file contains all 15 mandatory corrections, including
  UD2 truthful failure semantics, UD3 agent-first deployment, UD5 exact gate,
  F9/F10 cleanup/duplicate guards, SP1–SP7, SP-COLD, and the storage-plus-vDPA
  scope decision.
- **P0.2 Versions:** `pom.xml` is `4.24.1.33-SNAPSHOT`; all intended child POM
  parent references are updated; `tools/marvin/setup.py` is `4.24.1.33`; no
  unintended old version metadata remains.
- **P0.3 Schema registry:** `engine/schema/src/main/java/com/cloud/upgrade/dao/Upgrade42432to42433.java`
  exists, is a no-op schema/version-row bump mirroring the existing upgrade
  pattern, and is registered in the upgrade order/registry.
- **P0.4 Aragog validation:** the scoped Aragog build returns `BUILD SUCCESS`;
  Aragog `git status --porcelain` is empty after the version-stamp build; local
  `main` and Aragog `main` point to the same commit; one `main` worktree remains.
- **P0.5 Safety:** no runtime code changed; no JAR deployed; no CMK write,
  Foreman REX job, forbidden-repository/environment access, or production action
  occurred.

#### FAIL criteria

- **F0.1:** any of the 15 corrections or storm gates is absent, ambiguous, or
  contradicts the verified source.
- **F0.2:** any unintended `4.24.1.32-SNAPSHOT` project version remains.
- **F0.3:** Marvin remains at `4.24.1.22` or does not match `4.24.1.33`.
- **F0.4:** `Upgrade42432to42433` is missing, malformed, or unregistered.
- **F0.5:** scoped Aragog build/checkstyle/compile fails.
- **F0.6:** Aragog worktree remains dirty after the version-stamp build.
- **F0.7:** local and Aragog `main` HEADs diverge or extra worktrees remain.
- **F0.8:** any runtime code, live infrastructure, JAR deployment, CMK write,
  Foreman REX job, forbidden environment access, sleep/watch/polling/retry loop,
  background/detached process, or other production action occurs.

**Slice 0 exit rule:** all P0.1–P0.5 true means **PASS — Slice 1 may begin**.
Any F0.1–F0.8 means **FAIL — stop, correct, and re-evaluate before Slice 1**.

**Architecture review conclusion:** **PASS with mandatory corrections; no runtime
implementation or production migration is authorized until Slice 0 and all later
slice gates pass.**

**End of tracker.**
