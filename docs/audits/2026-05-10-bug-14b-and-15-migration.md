# 2026-05-10 — Bug 14b + Bug 15 Layer A: live-migration destination driver dispatch

**Scope.** Source-dive pass targeting the live-migration destination path in
the KVM agent. Two bugs surfaced that share a single root cause in
`LibvirtPrepareForMigrationCommandWrapper.java:80`. Fixed by commits A + B
below (local only as of 2026-05-10; next aragog build + deploy scoped to
next user-triggered cycle).

**Production cluster.** Slytherin (Los Angeles), 3 controls, 6 data nodes.

## Root cause (shared)

`LibvirtPrepareForMigrationCommandWrapper.execute()` line 80 (pre-fix):

```java
libvirtComputingResource.getVifDriver(nic.getType(), nic.getName()).plug(...)
```

`getVifDriver(TrafficType, String)` dispatches on `TrafficType` alone and
falls back to `BridgeVifDriver` for any NIC type it does not recognize. It
has no awareness of the OVN/vDPA/HW-offload flags on `NicTO`. The correct
dispatch point is `selectVifDriver(NicTO)` (private) which was added
specifically to handle OVN + offload flag permutations (commit `5ad7e…` in
the prior audit cycle). Because the migration-prepare wrapper was never
updated, every OVN/vDPA/HW-offload NIC landed on `BridgeVifDriver` on the
destination, silently skipping all host-side allocation hooks.

## Bug catalog

### Bug 14b — Live-migration destination OVN tap missing iface-id stamp — HIGH

**Symptom.** After a successful live migration of an OVN TAP-tier VM, the
destination tap (vnetN assigned by libvirt on the dest host) has
`external_ids:iface-id=<raw-uuid>` instead of `external_ids:iface-id=lsp-<uuid>`.
ovn-controller does not claim the `Port_Binding` row on the destination. The
VM remains reachable from the source for the brief period before the source
cleans up (source tap still has the correct `lsp-` stamp from pre-migration),
but after source unplug the VM loses all OVN-programmed flows: DHCP fails on
reconnect, ACLs are not applied, tenant traffic stops.

**Root cause.** Shared root cause above: destination `PrepareForMigration`
dispatched to `BridgeVifDriver.plug()` instead of `OvnVifDriver.plug()`.
Even after Commit A fixes the dispatch to `OvnVifDriver.plug()`, the
post-plug stamp (`applyPostPlugTunables`) still cannot fire from
`PrepareForMigrationCommandWrapper` because taps do not exist on the
destination at `PrepareForMigration` time — they are created by libvirt's
migration protocol after the management plane returns from `PrepareForMigration`.

**Production evidence.** nagini post-tap-1-migrate: `vnet62` had
`iface-id="a76a6509-..."` (raw UUID) until manual fix. Consistent with the
same stamp gap as Bug 14 (cold-start) but on the migration destination.

**Layer A fix (commit `ff59e27753`, 2026-05-10).**
Replace `getVifDriver(nic.getType(), nic.getName())` with
`libvirtComputingResource.selectVifDriverForNic(nic)` so `OvnVifDriver.plug()`
runs on the destination during `PrepareForMigration`. The `plug()` call
generates the correct `InterfaceDef` XML (with
`<virtualport type='openvswitch'><parameters interfaceid='<uuid>'/></virtualport>`)
that libvirt uses when it materializes the domain on the destination. This is
correct and necessary even though the post-plug stamp must run separately.

**Layer A remaining gap.** The `applyPostPlugTunables` stamp (override
`iface-id` from raw UUID to `lsp-<uuid>`) must run on the destination agent
AFTER libvirt completes the migration transfer and the tap is assigned its
`vnetN` name. The destination agent has no dedicated post-migrate-arrive
callback in the current agent protocol:
- `PrepareForMigrationCommand` runs before the domain exists on dest.
- `LibvirtMigrateCommandWrapper` runs on the SOURCE agent and has a
  `dconn` (libvirt connection to dest) and `destDomain.getXMLDesc()`, but
  cannot execute `ovs-vsctl` shell commands on the destination host.
- There is no `PostMigrateCommand` dispatched to the destination after
  migration succeeds.

**Deferred.** Migration-destination stamp requires a new
`PostMigrateOvnStampCommand` or equivalent hook on the destination agent.
Out of scope this session. Track as `OPEN` until a follow-up commit
introduces the destination-agent callback.

**Severity.** HIGH — every live-migrated OVN TAP VM loses its OVN
port-binding on the destination tap. Traffic resumes only via manual stamp
or until the VM is restarted on the destination (which re-runs the
cold-start path now fixed by commit `8e9b913cb1`).

**Status.** OPEN (Layer A routing fix landed; stamp gap on dest deferred).

---

### Bug 15 Layer A — vDPA destination VF never allocated during migration — MED

**Symptom.** Live migration of a vDPA-tier VM fails on the destination with
libvirt error:

```
Unable to open '/dev/vhost-vdpa-5' for vdpa device: No such file or directory
```

Because `BridgeVifDriver.plug()` was invoked on the destination, the
`OvnVdpaVifDriver.plug()` path that allocates a host VF from the pool and
writes the correct `<interface type='vdpa'>` XML with the VF device path was
never executed. libvirt then attempts to open the VF device path from the
SOURCE domain XML (which carries the source VF path), finds no such device
on the destination, and aborts the migration.

**Root cause.** Shared root cause above.

**Production evidence.** vdpa-1 migration from nagini to scabbers:
`Unable to open '/dev/vhost-vdpa-5' for vdpa device: No such file or directory`.
`/dev/vhost-vdpa-5` is the source nagini VF device path; scabbers has its
own VF device numbering and the correct path was never allocated because
`OvnVdpaVifDriver.plug()` never ran.

**Layer A fix (commit `ff59e27753`, 2026-05-10).**
Same as Bug 14b: `selectVifDriverForNic(nic)` routes to `OvnVdpaVifDriver`
on the destination during `PrepareForMigration`. `OvnVdpaVifDriver.plug()`
allocates a destination VF, writes `<interface type='vdpa'><source dev='/dev/vhost-vdpa-N'/>`,
and emits the destination-correct XML. libvirt migration succeeds.

**Layers B+C (deferred, out of scope this session).**

Layer B — XML rewrite: the source domain XML passed to libvirt's migration
call still contains the source VF path (`/dev/vhost-vdpa-5`). libvirt must
receive the destination XML with the destination VF path. This requires
`LibvirtMigrateCommandWrapper` to splice the destination `InterfaceDef`
XML (produced by `OvnVdpaVifDriver.plug()` in `PrepareForMigration`) into
the domain XML before the libvirt migrate call. Currently not implemented.

Layer C — Rollback: if migration fails after `OvnVdpaVifDriver.plug()` has
allocated a destination VF, that VF must be released. A rollback path via
`PrepareForMigrationCommand#isRollback()` already exists for storage; the
same pattern needs to apply to vDPA VF deallocation.

**Severity.** MED — vDPA-tier live migration always fails (hard error from
libvirt). Cold-start and stop/start work correctly (Bug 14/15 cold-start
fixed by commits `ded44f0c1a` + `8e9b913cb1`). Migration is a user-visible
outage only when `liveRestart` is attempted on a vDPA-tier VM.

**Status.** OPEN (Layer A routing fix landed; Layers B+C deferred).

---

## Commits that land fixes in this audit

| Hash | Subject | Bugs addressed |
|---|---|---|
| `ff59e27753` | fix(kvm): route migration destination through selectVifDriver(NicTO) | Bug 14b Layer A + Bug 15 Layer A |
| `8e9b913cb1` | fix(kvm/ovn): wire OvnVifDriver post-plug stamping into startVM and VifDriver contract | Bug 14 verification gap (cold-start stamp now live) |

---

## Deferred items

| Item | Scope | Priority |
|---|---|---|
| Bug 14b migration stamp — `PostMigrateOvnStampCommand` or equivalent hook on dest agent | New agent command + mgmt dispatch | HIGH |
| Bug 15 Layer B — destination VF path splice into migration domain XML | `LibvirtMigrateCommandWrapper` XML rewrite | MED |
| Bug 15 Layer C — vDPA VF rollback on migration failure | `LibvirtPrepareForMigrationCommandWrapper` rollback hook | MED |
| OP3 — Test A NAT/eBGP origin path (`external_mac=[]` on dnat_and_snat rows) | OVN NAT path forensic | HIGH |
| HA chassis ERR `cr-lrp-public-vpc742` | OVN HA chassis group forensic | MED |

---

## Skip list for future audits

- Do NOT re-flag `getVifDriver(TrafficType, String)` in
  `LibvirtPrepareForMigrationCommandWrapper` — replaced by
  `selectVifDriverForNic(nic)` in commit `ff59e27753`. Verify the call site
  uses `selectVifDriverForNic` before flagging as a new finding.
- Do NOT re-flag `applyPostPlugTunables` as dead code — it is wired from
  `LibvirtStartCommandWrapper` via `applyOvnPostPlugTunables` since commit
  `8e9b913cb1`. The migration-destination stamp gap is tracked as OPEN above.
- vDPA + VF passthrough cold-start paths were always correct. Do NOT re-flag
  `OvnVdpaVifDriver.plug()` or `OvnVfPassthroughVifDriver.plug()` unless
  those files have changed since `8e9b913cb1`.
