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

### 1.1 Production Kubernetes dependency and mandatory gate

Snape and Salazar are **production Kubernetes clusters running inside CloudStack
guest VMs in LAX Slytherin**. They are not disposable test workloads. Any physical
DX6/DAC/MLAG action, CloudStack KVM-agent rollout, CloudStack management rollout,
OVN/OVS action, cold migration, or live migration MUST capture a Kubernetes
pre/post baseline and abort on any degradation. Kubernetes health is a production
dependency of the CloudStack operation, not an optional application check.

The following gate is non-negotiable and is re-run after **every individual** KVM
or management step, and before and after the SP6/SP7 evidence windows for both cold
and live migration:

1. Both cluster APIs authenticate successfully and `/readyz` is healthy.
2. Every expected control-plane and worker node is `Ready`, with roles, versions,
   taints, readiness transitions, and CloudStack VM UUID recorded.
3. Control-plane and etcd quorum is healthy; no member is lost or degraded.
4. Critical `kube-system`, CNI, CSI, CoreDNS, ingress, and critical DaemonSet /
   Deployment workloads are healthy.
5. No new Pending, CrashLoopBackOff, Error, readiness regression, or restart storm
   exists relative to the pre-step baseline.
6. Both CT_LB API paths (`.35:6443` and `.33:6443`) are healthy.
7. All DSR public/accounting IPv4 and IPv6 endpoints and their expected backends
   are healthy; endpoint counts and VIPs are recorded.
8. CloudStack VM-to-hypervisor placement, tier/VPC, control-plane membership, and
   backend placement are recorded before and after the step.

The current read-only baseline is **K8S_PROD_BASELINE_NO_GO**: Salazar accounting
has an active `socket` CrashLoopBackOff and returns HTTPS 500, and Salazar's
`starrocks` Argo application is Degraded. No DX6 or CloudStack rollout may start
until a fresh baseline passes this gate.

The authoritative CloudStack LB baseline is **12 Active `dsr_software` rows plus
2 Active `ct_lb` rows**. The older 6+2 figure is stale. Count authoritative raw
CloudStack LB rows by `lbkind`, not address families: a dual-stack DSR service may
have IPv4 and IPv6 VIP/backend realizations while remaining one logical row per
programmed LB object. The two `ct_lb` rows are the CT_LB API paths; DSR remains a
software/direct-server-return path and must not be counted as CT_LB.

### 1.2 Failure-domain and rollout blockers

Enforce **one physical host, link, control-plane member, or migration at a time**.
Never act simultaneously on multiple hosts or links that contain Kubernetes
quorum members or production DSR/CT_LB backends. Crash-restart remains excluded
until OOB/fencing is available and tested.

The current rollout blocker is active incoming CRC growth on the Nagini and
Scabbers DX6 paths; no rollout has started. The strongest current fault-domain
hypothesis is the provider switch / MLAG / DAC physical path. Do not remotely
isolate an LACP member without switch-side evidence, capacity validation, and a
passing Kubernetes/CloudStack pre-gate. Any such action must be followed by the
individual-step Kubernetes gate above before proceeding.

For OVN 26.03.2 realization, use `Chassis_Private.nb_cfg`; `Chassis.nb_cfg` is
deprecated. The OVN Raft gate requires healthy leader-to-follower communication,
all **3 members** present, an identified leader, and **zero unapplied and zero
uncommitted backlog** before any rollout or migration action.

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
  `/dev/vhost-vdpa-N` for every vDPA interface; missing or partial mappings
  throw and never retain a source vDPA path.
- `LibvirtPrepareForMigrationCommandWrapperTest`: dest vDPA device captured
  into `vdpaInterfaceMapping` for each vDPA NIC.
- `LibvirtPostMigrateOvnStampCommandWrapperTest`: stamps `iface-id=lsp-<uuid>`
  on every dest tap; idempotent on retry.
- `LibvirtVerifySourceBindingDownCommandWrapperTest` and
  `DestinationDataplaneVerifierTest`: execute agent wrappers against exact
  local Interface, br-int, chassis, and SB Port_Binding identities.

### 7.3 Orchestration failure tests

- `VirtualMachineManagerMigrationFailClosedTest`: null `allocateForVdpa` →
  migration throws before `PrepareForMigrationCommand` dispatched; source VF
  untouched.
- `MigrationRollbackOnStampFailureTest`: `PostMigrateOvnStamp` fails →
  `rollbackVfReservationsBestEffort` + `rollbackNicForMigration` called; VM
  restarted on source or left Stopped deterministically.

### 7.4 API tests

- `listMigrationPreflightCmdTest`: behavioral admin authorization plus
  structured per-NIC denial, requested-chassis, and hostdev-live fields.
- `listHostVfPoolStatusCmdTest`: behavioral admin authorization plus per-device
  PCI/NIC/state/kind status and host totals.

### 7.5 Real Aragog build / test

- All Maven + checkstyle + unit tests run **only on Aragog** (per `AGENTS.md`).
  No local builds. Full reactor `BUILD SUCCESS` required before any canary.

### 7.6 Marvin / E2E canary

- Cold canary: stop a dedicated canary vDPA VM on source, relocate to a
  destination host with free VF capacity, start, assert dataplane (DHCP, ICMP,
  TCP, iperf) + OVN Port_Binding claimed on destination + source VF `FREE`.
  Preserve and compare DSR/LB VIP reachability, router-chassis affinity,
  load-balancer/PF/NAT behavior, and network-policy invariants before and after.
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
| DSR LB VIP / router chassis affinity | VIP ownership and router affinity unchanged | unchanged except the intended VM Port_Binding chassis |

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
| B2 | `MigrationVfPreflight` use case + `countFreeForVdpa` (§5.1) | completed | `8f4a348f52`: VM NIC inventory/profile bijection, exact source-chassis claim proof, production OOB/fencing/affinity/quorum guard bean, VM + destination-cluster locks, and cold hostdev capacity gate | Aragog validation pending | preserve planner/HA ownership |
| B3 | Fail-closed vDPA allocation in `HypervisorGuruBase` (§5.2) | completed | `1b4e244850` extends `982672fe9f`: null manager, null allocation, checked allocation failure, and runtime failure all reject vDPA without TAP fallback; focused message test added | Aragog scoped compile/unit validation pending | restore only with explicit tracker rollback |
| B4 | hostdev live rejection (§5.3) | completed | `MigrationVfPreflight` rejects non-vDPA `useHwOffload` NICs for LIVE mode with an explicit unsupported-operation message; COLD mode remains available for later destination-device gates | cold hostdev capacity gate remains in Slice 5.5/6 | preserve live rejection |
| B5 | `requested-chassis` validation (§5.4) | completed | `d37c03e0f0`: read-only `OvnChassisLookup` port/OVN adapter resolves the configured policy and rejects non-matching destination chassis without NB writes | Aragog integration validation pending | no auto-pin; preserve operator policy |
| B6 | Synchronous `PostMigrateOvnStamp` + dataplane verify (§5.5) | completed | `8f4a348f52`: source proof compares exact source/destination chassis identities and rejects wrong/multiple claims; destination proof remains br-int/iface-id/MAC/installed/global-SB gated | Aragog validation pending | no canary before C5 |
| B7 | VF commit/rollback gated on dataplane (§5.6) | completed | `34571acf75`: ownership manager is mandatory for VF paths; commit occurs only after stamp, destination proof, source-down proof, and destination cleanup is authoritative | Aragog failure-path tests pending | fail closed |
| B8 | `listMigrationPreflight` + `listHostVfPoolStatus` APIs (§5.7) | completed | `8f4a348f52`: admin-only contracts expose requested/hostdev decisions, per-NIC evidence, and per-device PCI/NIC/state/kind ownership; no repair operation exposed | Aragog API authorization/response tests pending | preserve read-only boundary |
| B9 | Cold relocate preflight (§5.8) | completed | `34571acf75`: cold vDPA/SR-IOV transaction uses `orchestrateStart` with destination plan, source binding-down proof, destination stamp/verify, ownership commit, destination stop/rollback, and HA-manager restart scheduling | Aragog validation pending | leave stopped if restart policy declines |
| B10 | Error/reporting semantics (§5.9) | completed | `34571acf75`: per-NIC API denials, explicit ownership failures, source/destination proof failures, and aggregated agent cleanup failures retain recovery evidence | Aragog validation pending | no silent downgrade |

### Phase C — Tests (PENDING)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| C1 | Unit tests §7.1 | completed | `34571acf75`: fail-closed allocation, vDPA-specific capacity, preflight gates, cold hostdev capacity, ownership failure, and per-NIC API evidence tests | Aragog execution pending | — |
| C2 | Wrapper/XML tests §7.2 | completed | `34571acf75`: vDPA XML mapping, prepare mapping, post-stamp failure/idempotence contract, and orphan ownership tests | Aragog execution pending | — |
| C3 | Orchestration failure tests §7.3 | completed | `34571acf75`: stamp failure and reordered verifier failure are fatal before ownership commit; cold path has authoritative rollback code | Aragog execution pending | — |
| C4 | API tests §7.4 | completed | `34571acf75`: admin authorization annotation tests and structured per-NIC/status response tests | Aragog execution pending | — |
| C5 | Aragog full build + checkstyle + unit §7.5 | pending | Runtime/profile, rollback, wrapper, and per-NIC preflight fixes are landed locally; build execution remains outstanding | B*, C1–C4 | — |
| C6 | Marvin cold canary §7.6 | pending | Live/canary evidence not collected | C5, §8 | no prod VM |
| C7 | Marvin live canary §7.6 | pending | Live/canary evidence not collected | C6 | no prod VM |
| C8 | Bounded continuity probe §7.7 | pending | Live/canary evidence not collected | C7 | canary only |
| C9 | Cleanup/failure tests §7.8 | completed | Local negative coverage now includes NIC omission, null/empty vDPA mapping, and fail-closed cold rollback restart gating; execution remains part of C5 | Aragog execution pending | — |
| C10 | Network invariant snapshots §7.9 | pending | Live/canary evidence not collected | C6 | — |

### Phase D — Deploy (PENDING)

| ID | Task | Status | Evidence | Blockers | Decision / rollback |
|---|---|---|---|---|---|
| D1 | Determine if KVM agent plugin jar changed (§8.4) | pending | Build execution required before artifact comparison | C5 | — |
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
| SP7 live evidence | pending | Code hard-abort triggers and tests are present; no Aragog/canary observation has been performed | C5, then dedicated canary only |
| SP6 live evidence | pending | Read-only observation plan is documented; no observation window has been performed | C5, then dedicated canary only |

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

Capture and pass the full §1.1 Kubernetes gate immediately before the SP6 window
and immediately after it. For every individual KVM-agent or management-server
step, re-run the Kubernetes gate before the next step; do not batch those checks.

Capture pre-migration baseline, during-cutover, and post-steady-state observations
of BUM/broadcast/multicast/unknown-unicast counters, TC/OVS flow state, and OVN
Port_Binding claims. Use read-only OVS/OVN/switch observations; **do not clear
counters**. Record source/destination Interface state, `iface-id`,
`iface-status`, Port_Binding chassis, and any `actions=FLOOD` path.

#### SP7 — Hard-abort triggers

The §1.1 Kubernetes gate is also mandatory immediately before and after SP7
evidence. Any Kubernetes degradation is an SP7 hard abort even when the vDPA,
OVS, or OVN-specific trigger below is not present.

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

## 16. 2026-07-19 production gate and accouting GitOps course correction

> **Production dependency warning:** Snape and Salazar are production Kubernetes
> clusters running inside the 19 CloudStack workload VMs in LAX. Kubernetes is a
> hard pre/post gate around **every** agent restart, management restart, canary
> admission, migration leg, rollback, and cleanup. No CloudStack rollout mutation
> is permitted while either cluster gate, Argo gate, or the accounting gate is
> not proven green.

The fresh Salazar observation used a newly retrieved CloudStack cluster config (no
credential material recorded). It proved all 13 nodes Ready, all three etcd pods
Running/Ready, critical CNI/CSI/CoreDNS/ingress and StarRocks workloads healthy,
and the socket CloneSet at 3/3 Ready with unchanged pod UIDs during the GitOps
comparison work. The application nevertheless remained `OutOfSync` with only
`CloneSet/socket` reported by Argo; `accouting` was `Healthy`, but not
`Synced/Healthy/Succeeded`, so the rollout gate remained NO_GO.

The following GitOps-only attempts were made on `backend/accouting.git` main:

| Commit | Action | Evidence / outcome |
|---|---|---|
| `8764188` | Replaced the broad CloneSet annotation ignore with a name-scoped jq expression | Live Application spec was not changed because `infra/salazar/kustomization.yaml` does not render `argocd/application.yaml`; hard refresh left `CloneSet/socket` OutOfSync. |
| `621ce39` | Fallback: declared the exact live CloneSet metadata tracking ID (`accouting:apps.kruise.io/CloneSet:accouting/socket`) on `socket` | Hard refresh and automated reconciliation still left `accouting` OutOfSync; pod UIDs stayed unchanged, but Kruise reported revision `socket-5986cd48b8` instead of the required `socket-68d9f8479c`. |
| `dd484a9` | Reverted the fallback to preserve the required workload identity and avoid further drift | Rollback pushed; no imperative workload patch, force sync, prune, replace, or pod operation was performed. |

The live-only identity observed was exactly
`accouting:apps.kruise.io/CloneSet:accouting/socket`. The remaining comparison
path requires an Argo-version-specific diff inspection or a controlled GitOps
application-definition ownership fix; it must not be worked around by broad
annotation ignores or an imperative child-workload patch.

### 16.1 Cluster-consistency hard gate

**Availability is insufficient.** At every mutation checkpoint, capture a
comparable named **BEFORE** snapshot and **AFTER** snapshot for both production
clusters, Snape and Salazar. The AFTER snapshot must be compared field-by-field
with BEFORE; printing a healthy current status without comparison is not a gate.

The consistency tuple is:

| Domain | Required comparable evidence | Immediate abort condition |
|---|---|---|
| etcd / control plane | Member and cluster IDs, membership set, endpoint health/status, quorum `3/3`, leader present, alarms, and raft/applied-index values where exposed | Any membership/ID change, missing leader, quorum loss, alarm, or unexpected raft/applied-index divergence |
| Kubernetes API | API endpoint status and all three control-plane API members responding | Any API member loss, identity change, or endpoint inconsistency |
| Nodes / placement | Node names, UIDs, readiness/taints, and authoritative CloudStack VM-to-host placement | Any node transition, UID/name change, placement drift, or unexpected reschedule |
| Production VM set | Exact 19 Kubernetes VM identities, states, host placement, and NIC identity | Any VM not `Running`, duplicate/missing VM, host/NIC identity change, or unintended migration |
| Controllers / workloads | Desired = current = ready for controllers, DaemonSets, StatefulSets, CloneSets, and critical workloads; pod names/UIDs, restart counts, and revisions | Any restart, reschedule, revision change, replica mismatch, or readiness divergence |
| GitOps / applications | Argo Applications `Synced` + `Healthy`, including `accouting` and StarRocks; workload identities/revisions stable | Any OutOfSync/Unhealthy state or identity/revision drift |
| Accounting / StarRocks | Accounting three endpoints and actuator v4/v6; StarRocks FE/CN identities, revisions, endpoints, and Secret hashes | Any endpoint, identity, revision, or secret-hash change not explicitly part of the step |
| CloudStack / dataplane | Host/VM/NIC state, OVN Raft/northd/chassis authoritative state (`Chassis_Private`), OVS bridges/bonds/OpenFlow/representors/vDPA identity, CT_LB `.35/.33`, all dual-stack DSR endpoints | Any dataplane, binding, quorum, placement, or endpoint inconsistency |
| Physical / CRC exception | Timestamped raw counters for every physical host, bond slave, and relevant representor before/after each phase | CRC increments alone are observe/report; link-down, carrier/LACP loss, storm/BUM, or accelerating discard/error rate is immediate abort |

Evidence must retain timestamps, source command/API, stable IDs, and the
BEFORE→AFTER delta. A mutation may proceed only when every tuple is unchanged
except for the explicitly expected identity/state transition documented for that
step. Any membership, identity, placement, revision, restart, quorum, or
dataplane inconsistency is an immediate rollback/`NO_GO`, even when HTTP/API
endpoints still answer.

### 16.2 Mandatory serial evidence matrix before rollout resumes

The exact 19-VM placement and blast-radius table is still a prerequisite artifact,
not an inferred value. It must be captured from authoritative CloudStack inventory
before the first KVM restart; the approved placement constraints remain Fluffy
empty, Trevor workers-only, and Norbert/Nagini/Scabbers/Aragog carrying control
plane or multiple cluster members. The only permitted order remains
Fluffy → Norbert → Nagini → Scabbers → Trevor → Aragog unless fresh quorum and
placement evidence justifies a documented serial change.

| Step | Kubernetes / Argo | CloudStack | OVN / OVS / vDPA | DSR / CT_LB / CRC | Status |
|---|---|---|---|---|---|
| Every KVM-agent restart | Both clusters, 3/3 etcd, all nodes/workloads, `accouting` and StarRocks | 19 VMs Running and placement unchanged; host/VM/NIC connected | Chassis_Private/Raft/northd, bridges/bonds/flows/representors/vDPA | All dual-stack DSR, CT_LB `.35/.33`, raw per-host counters | **Not started — gate blocked** |
| Every management restart | Same full gate | Inventory and membership unchanged | Chassis_Private `nb_cfg`, no dangling OVN aliases | Same endpoints and CRC exception observation | **Not started — gate blocked** |
| Canary admission | Production clusters unchanged; canary non-Kubernetes only | Dedicated VM identity and placement | VF/representor/OVN identity baseline | DSR/CT and CRC baseline | **Not started — gate blocked** |
| Cold migration | Full pre/during/post gate; mandatory first | Source stop/fencing/cleanup and destination ownership | Destination binding, no duplicate/leak | Same dataplane and raw counters | **COLD_MIGRATION_PASS not earned** |
| Live migration SP6/SP7 | Unlocks only after `COLD_MIGRATION_PASS` | Same authoritative placement/quorum gate | Same SP1–SP7 hard aborts | Same DSR/CT/CRC gate | **Locked** |

**Current decision: NO_GO.** No KVM-agent restart, management restart, canary
creation, cold migration, live migration, physical remediation, or production
CloudStack mutation has been performed.

### 16.3 Follow-up comparison attempt

The next-generation correction removed the live name-scoped tracking-id
`jsonPointer` and retained only the exact name-scoped jq expression in commit
`9c7b8d8`. Because the Application manifest is not rendered by the workload
`kustomization.yaml`, the committed Application spec was applied through its
normal committed manifest path, then hard-refreshed once. The live Application
confirmed the jq expression, but remained `OutOfSync`; the CloneSet was the only
reported resource.

The required no-rollout comparison was not achieved. The BEFORE snapshot had
`socket-84b7778f58`, update revision `socket-68d9f8479c`, 3 replicas/2 ready,
and pod UIDs `313746ec…`, `df65a9e7…`, `ed95d565…`. After the hard refresh the
CloneSet returned to `socket-68d9f8479c` with the same pod UIDs, but restart
counts increased (`2,2,5` → `3,3,5`) and one pod was not Ready; the application
was `OutOfSync/Degraded`. This is an immediate consistency failure even though
the pods remained Running. Container termination was exit `143`, so no rollout
phase was authorized.

The Application-spec correction was rolled back for safety via backend commits
`9cf84b6` (revert of `9c7b8d8`) and the prior committed Application spec was
restored live. Final observed state was `accouting` `OutOfSync/Healthy`; no
child-workload patch, force sync, prune, replace, or restart was issued. The
remaining GitOps comparison blocker and unexpected restart delta are unresolved
and keep every operational phase locked.

### 16.4 Read-only RCA after rollback

The fresh RCA found that exit-143 events were graceful container termination
during Kruise in-place updates. Namespace events recorded
`SuccessfulUpdatePodInPlace`, image transitions through `621ce391`, `dd484a96`,
and `latest`, and repeated `Container socket definition changed, will be
restarted` events. Argo history recorded automated/admin syncs for revisions
`9c7b8d8` and `9cf84b6`; controller logs recorded auto-sync reconciliation.
The live Application apply plus automated Argo reconciliation triggered these
updates; a hard refresh was not a safe no-op under this automated policy. No pod
was imperatively deleted or restarted.

Current read-only state: CloneSet current/update revision
`socket-68d9f8479c`, replicas `3`, ready `3`, all three pod UIDs unchanged, and
restart counters `3,3,5`. The CloneSet has a `FailedUpdate` condition
(`object has been modified`) and Argo remains `OutOfSync/Healthy`, with
`CloneSet/socket` the only OutOfSync resource. Raw `kubectl diff` of the current
Git socket manifest against live was empty after excluding status/generated
metadata; the exact live tracking value is
`accouting:apps.kruise.io/CloneSet:accouting/socket`. The remaining drift is in
Argo's comparison/tracking path, not an unreviewed workload spec delta.

The single comparative snapshot found Snape API/nodes/etcd healthy but Argo
`istio-base` and `istiod` OutOfSync with ingress Applications Progressing.
Salazar had healthy critical pods/StarRocks but retained the `accouting`
OutOfSync gate. CloudStack inventory reported all 19 Kubernetes VMs Running
with the recorded host placement. Because the comparison gate, restart history,
CloneSet FailedUpdate condition, and Snape Argo drift are not clean, the exact
tracking annotation was **not** added to Git and no further live
Application/workload mutation was attempted. This remains `NO_GO`.

### 16.5 Subsequent comparative recovery check

Salazar recovery is currently stable at 3/3 ready with unchanged pod UIDs,
revision `socket-68d9f8479c`, image digest
`sha256:977fc0e74f17f45a91a23674ca2cffe66d19d499c82037888633ad3f6893d266`,
and restart counters `3,3,5`; no post-incident update event occurred after
13:04 UTC. The `FailedUpdate` condition is stale historical state: generation
and observedGeneration are both `110`, while the condition last transitioned
on 2026-07-13. However, Argo continues automated auto-heal operations against
`CloneSet/socket` and reports `OutOfSync/Healthy`, so the gate is not clean.

Snape's actual Istio control plane and ingress are healthy: istiod is 2/2,
both ingress Deployments are 3/3, all eight relevant pods are Ready with zero
restarts, and all Istio/DSR EndpointSlice addresses are Ready for both families.
The exact Argo drift was controller-owned webhook mutation: both validating
webhooks changed chart-desired `failurePolicy: Ignore` to live `Fail`, added the
istiod CA bundle, defaulted service port `443`, and defaulted rule scope `*`.
The minimal GitOps correction was pushed to `infra/cks-snape` as `838acea`,
scoping jq ignores to those exact webhook objects/fields. Argo then reported
`istio-base` and `istiod` `Synced/Healthy`; no Istio pod UID, revision, or restart
changed. Ingress Applications remain `Synced/Progressing` because their
LoadBalancer Service status is empty while the design intentionally uses
`externalIPs` with CCM disabled. This is a known cosmetic dataplane contract,
but it fails the explicit `Healthy` hard gate and was not weakened.

The comparative check retained all 19 Kubernetes VMs `Running` with the
recorded placement, all six cluster nodes Ready, and 3/3 etcd pods per cluster.
The attempted four VIP probes from the LAX control path did not establish
(`000`/timeout or reset), and CloudStack load-balancer enumeration did not
produce a complete authoritative result in the bounded read-only query; DSR/
CT_LB is therefore not proven green. No `accouting` auto-sync suspension or
tracking-metadata Git change was attempted because both cluster gates were not
clean. KVM, management, canary, cold, and live phases remain locked.

### 16.6 Suspended accounting comparison attempt

Canonical probes subsequently passed: CT_LB API `.35:6443/readyz` and
`.33:6443/readyz` returned HTTP 200; Snape public DSR v4/v6 returned 301 with
the canonical Host header; Salazar public and accounting v4/v6 returned the
expected 301/404 Istio responses. CloudStack inventory showed the expected two
Active `ct_lb` API rules and six Active `dsr_software` rules (14 raw dual-stack
rows). OVN ECMP configuration contained the Snape and Salazar dual-stack
worker next-hops. The raw reconciler command was not applied; it requires an
explicit zone id, so no mutation was attempted. These results classify the
Snape ingress `Synced/Progressing` status as the accepted externalIPs/CCM-
disabled Service-status limitation, with workloads/endpoints healthy.

After that dataplane PASS, accounting automated sync was suspended by removing
only `spec.syncPolicy.automated`; the prior policy was `prune=true,
selfHeal=true`. The pre-suspend operation was already completed at 13:24:47 UTC.
The exact live tracking ID was read as
`accouting:apps.kruise.io/CloneSet:accouting/socket`. Git commit `7b10a69`
added only that value to CloneSet object metadata (not the pod template,
image, or spec) and was pushed. One hard refresh was issued while automated sync
was suspended. No new Argo operation, child apply, pod event, UID change,
restart, or revision change followed; the old operation timestamp remained
13:24:47 UTC and the CloneSet remained `socket-68d9f8479c`, 3/3 ready, UIDs
unchanged, restarts `3,3,5`.

Argo nevertheless remained `OutOfSync/Healthy` with only `CloneSet/socket`, and
the observed source revision remained `9cf84b6` rather than the pushed `7b10a69`.
No further Application/workload mutation was made. Automated sync is currently
suspended pending operator-controlled resolution of the stale Argo comparison;
the prior policy was not re-enabled because the required `Synced` gate was not
earned. All CloudStack rollout phases remain locked.

### 16.7 Source-resolution rollback failure

Read-only source checks proved `origin/main` contained `7b10a69` and the live
repo URL/path were correct. Argo's repo-server eventually resolved `7b10a69`,
but the Application-level suspension was not durable: the normal Application
owner reasserted automated sync, and Argo performed a child server-side apply.
The CloneSet image changed to `7b10a69f`, revision `socket-79d6cff88b`, and
restart counters increased from `3,3,5` to `4,4,6` while pod UIDs remained the
same. This violated the no-workload-change requirement.

The metadata commit was reverted as `6ad21bf`, and the Application was
temporarily pinned to the exact rollback commit with the prior automated policy
restored so the normal owner could roll back. The rollback operation succeeded,
but the live state is not stable: Application `OutOfSync/Degraded`, CloneSet
current `socket-79d6cff88b`, update `socket-68d9f8479c`, `3` replicas/`2` ready,
one pod still on `7b10a69f`, and restart counters `4,5,9`. The operation also
produced liveness/readiness failures during the in-place rollback. No direct pod
restart/delete or CloudStack host mutation was used.

This is a genuine unsafe `NO_GO`; KVM, management, canary, cold, and live
migration phases remain locked until the accounting owner completes a separately
controlled recovery and proves stable identities, revisions, restarts, and
readiness. The temporary exact source pin and automated policy state require
operator cleanup after recovery; no further mutation was attempted in this run.

### 16.8 Fluffy KVM `.33` canary RCA (read-only, 2026-07-19)

The two Fluffy plugin-only canaries are **NO_GO** and were rolled back to the
validated `.32` plugin. No Kubernetes-bearing host, management server, VM, or
migration was mutated by this investigation. The authoritative host row is
currently `id=22`, `status=Up`, `resource_state=Enabled`, `version=4.24.1.31-SNAPSHOT`,
`mgmt_server_id=266600964325576` (Bellatrix), `last_mgmt_server_id=41854098319389`
(Barty), `disconnected=2026-07-10 23:58:18`, and `last_ping=1742646478`
(`2025-03-22 12:27:58`); the stale ping value is retained as evidence and was
not repaired.

#### Authoritative failure chain

Bellatrix owned both observed connections. Its management log records:

* `13:56:56.667` — `NetworkOrchestrator` received Fluffy's startup connection
  and sent `CheckNetworkCommand`.
* `13:56:56.674` — `ClassCastException`: `Answer` cannot be cast to
  `CheckNetworkAnswer`, at `NetworkOrchestrator.processConnect:4388`, called
  from `AgentManagerImpl.sendReadyAndGetAttache:1388`.
* `13:56:56.675` — `AgentDisconnected`; `Unable to create attache for agent`.
* `14:04:57.259–14:04:57.271` — the same sequence and same exception on the
  second canary attempt. Retries at `14:06:02` reproduced it.

Fluffy's agent log confirms SSL handshake, startup response receipt, and Ready
processing before the management-side rejection. It also records the decisive
ABI failure: `14:04:57.264` `NoClassDefFoundError` initializing
`LibvirtRequestWrapper`, caused by `TypeNotPresentException` for
`com.cloud.agent.api.VerifyDestinationDataplaneCommand`. Therefore the earlier
absence of a visible NoClassDef report in the short canary summary was not a
clean run; the full agent log contains it.

The source state machine explains the apparently contradictory observations:
`AgentManagerImpl.notifyMonitorsOfConnection` invokes
`NetworkOrchestrator.processConnect` before the `Event.Ready` transition at
`AgentManagerImpl.java:854`. Any monitor exception takes the generic disconnect
path (`:784–787`), removes the attache, and never reaches the Ready-to-Up
transition. A successful SSL/startup response and an agent-side Ready log can
therefore coexist with an authoritative Alert/disconnected host. `Ping` cannot
restore the host because the attache is removed before the periodic
`PingRoutingCommand` path can run. The normal recovery path is
`agentStatusTransitTo(host, Event.Ping, ...)` only after a valid attached agent
is investigated as up (`:1157–1161`).

#### ABI and classpath evidence

Fluffy's runtime classpath is `/usr/share/cloudstack-agent/lib/*` followed by
`/usr/share/cloudstack-agent/plugins/*`. The active `.32` plugin hash is
`00052046894b1dc96848dbf6f2771dd0dd51f689617526c81e21fd99a9954798`.
The plugins directory still contains an old `.26` plugin and `.26` cloud-api,
but the lib directory precedes it. The active shared jars are
`cloud-agent`, `cloud-api`, and `cloud-core` `4.24.1.31-SNAPSHOT`; this explains
the agent implementation version and is an existing package/version skew, not
evidence that the `.33` plugin was not loaded. The `.33` attempt was loaded: its
new KVM wrapper's static Reflections initialization is the source of the
`VerifyDestinationDataplaneCommand` type-resolution failure.

The candidate artifact is internally consistent with the source, but is not a
complete agent payload. It adds/uses the vDPA destination/source proof wrappers
and shared command classes introduced by the migration hardening commits. The
candidate's proven identity is: size `1,111,000`, SHA256
`5f301d82774932b4c1e65f16c96ba7e466da4ec987a63deee32e67052f547811`, MD5
`7093364b8aeb309945df1f91fba00158`. The matching Aragog build artifacts already
available for a future, separately gated payload are:

| Artifact | Size | SHA256 | MD5 |
|---|---:|---|---|
| `api/target/cloud-api-4.24.1.33-SNAPSHOT.jar` | 2,963,465 | `a107f9e48e29ac60aaeeca5ecd9528ec144eab73e5a43dbec4b565cda5c62b4b` | `fa8f4d45c15830cb2bf6723a65eca702` |
| `core/target/cloud-core-4.24.1.33-SNAPSHOT.jar` | 723,332 | `aaa09c46dd54e4e3bc01e4197403b10ef730508745c7e3abded253d0091f5093` | `7766b44ee318686aa8944f1a289b26c0` |
| `agent/target/cloud-agent-4.24.1.33-SNAPSHOT.jar` | 90,975 | `e7803ea5018aaac397c7ecf0053ce3c4c7877807f1ae87c8221224a2d75ac5ec` | `577d39557cf0b8c7fb7744a9b9939076` |
| `plugins/hypervisors/kvm/target/cloud-plugin-hypervisor-kvm-4.24.1.33-SNAPSHOT.jar` | 1,111,000 | `5f301d82774932b4c1e65f16c96ba7e466da4ec987a63deee32e67052f547811` | `7093364b8aeb309945df1f91fba00158` |

The matching `cloud-core` contains `VerifyDestinationDataplaneCommand`; the
Fluffy `.31` core does not. This proves the failure was caused by an incomplete
plugin-only replacement, not by management-first ordering or a defective
`.33` KVM implementation. No source fix or regression test is warranted for
this RCA. A future rollout must use the complete matching agent payload above,
agent first, then the matching shaded management artifact, one host/control
node at a time, with fresh Kubernetes and CloudStack gates at every step.

#### Decision

**Exact decision: NO_GO.** Do not retry Fluffy while the mandatory Snape/Salazar
consistency gate remains blocked in §16.1–§16.7. No artifact was deployed by
this RCA, no host/API/DB write was made, and no Kubernetes or migration action
was attempted. Rollback evidence is the restored `.32` hash above and the
authoritative host state `Up`.

**End of tracker.**
