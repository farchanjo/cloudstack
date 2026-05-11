# OVN Fork Audit Log

Running log of audits performed on the custom OVN integration in this CloudStack
fork. Each audit file lists every bug surfaced, its severity, the commit that
fixed it (or a `WONTFIX` / `DEFERRED` marker), and the production verification
proving the fix landed.

## Purpose

Without this log, every fresh audit dispatch keeps re-flagging bugs that were
already fixed in an earlier session. The conductor (or a subagent) must read
the relevant audit file BEFORE running an open-ended "find all the bugs" pass
and scope its analysis to either:

- gaps the prior audit explicitly marked as `OPEN`/`DEFERRED`, OR
- surface area that did not exist at the time of the prior audit
  (newer commits, newer modules).

## Conventions

- One file per audit pass: `YYYY-MM-DD-<topic>.md`.
- Status states: `FIXED`, `OPEN`, `DEFERRED`, `WONTFIX`, `OBSOLETE`.
- Every `FIXED` entry MUST cite the fix commit hash + production verification
  evidence (build md5, smoke-test output, API field returned, etc.).
- Audits are append-only. Never edit historical files; create a new file when
  re-auditing the same surface.
- en-US for all content (project policy).

## Index

| Date | File | Scope | Bugs found | Bugs fixed | Bugs open |
|---|---|---|---|---|---|
| 2026-05-09 | `2026-05-09-ovn-fork-audit.md` | OVN fork DB + API + UI inconsistencies + LB selection_fields + LB update state-machine + vDPA user VM guard + VF pool concurrent allocation race | 12 | 12 | 0 |
| 2026-05-10 | `2026-05-10-bug-13-configkey-leak.md` | Bug 13 — `ovn.requested_chassis` + `ovn.ha_chassis_priority` ZONE-scope ConfigKeys left set from Phase B verification audit + never reverted; every new LSP carried `options:requested-chassis="norbert=42"`; HIGH severity; config-only revert (no source change, no rebuild); verified via Stage 2 rebuild (20 VMs, 0 contaminated LSPs) | 1 | 1 | 0 |
| 2026-05-10 | `2026-05-10-bug-14-iface-id-prefix.md` | Bug 14 — `OvnVifDriver` emits raw NIC UUID as `external_ids:iface-id` instead of `lsp-<uuid>`; TAP-tier-only regression from `d85d27f126`; promised `OvnNicTunableApplier` post-plug stamp never implemented; 6 TAP LSPs stuck `up=false` (Port_Binding never claimed); HIGH severity; manual remediation via `ovs-vsctl set Interface … external_ids:iface-id=lsp-<uuid>` on fluffy + trevor (6/6 LSPs `up=true`); source patch in `applyPostPlugTunables` mirrors vDPA + VF passthrough drivers. **Verification gap appended 2026-05-10**: `applyPostPlugTunables` had zero callers until commit `8e9b913cb1`; cold-start production fix worked only via manual stamp applied before JAR was built — see audit file "Verification gap" section. | 1 | 1 | 0 |
| 2026-05-10 | `2026-05-10-bug-14b-and-15-migration.md` | Bug 14b — live-migration destination OVN tap never runs `OvnVifDriver.plug()` → post-plug stamp never fires → dest tap has raw UUID iface-id → Port_Binding not claimed after migration; HIGH; Layer A fix `ff59e27753`; Layer B agent fix `0d91ba8a3f`; Layer B mgmt fix `d34f9fe190` (`dispatchPostMigrateOvnStamp` in `VirtualMachineManagerImpl`). Bug 15 Layer A — vDPA destination VF never allocated during migration → `No such file or directory`; MED; Layer A fix `ff59e27753`; Layer B agent fix `f03d7cfbfa`; Layer B mgmt fix `b540cbd183` (`buildMigrateCommand` vdpa mapping plumb); Layer C fix `3bfbf9e596` (rollback VF release). Shared root cause: legacy `getVifDriver(TrafficType)` call in `LibvirtPrepareForMigrationCommandWrapper:80`. | 2 | 6 (Layers A+B+C all layers including mgmt wiring) | 0 |
| 2026-05-10 | `2026-05-10-hw-offload-audit-correction.md` | Corrective audit revoking 3 spurious findings from an earlier HW-offload pass — C1 `tc-policy=none` flagged as P0 blocker (FALSE_POSITIVE: `none` is OVS default, dual-path SW+HW, NOT a block-mode); C2 `doca-init=false` flagged as P1 missed optimization (WONTFIX: operator policy keeps DOCA OFF); C3 14 raw-UUID `iface-id` interfaces flagged as Bug-14 regression candidates (FALSE_POSITIVE: every raw-UUID interface lives on `cloud0`/`br-bond` non-OVN bridges, 0 on `br-int` cluster-wide). 71% effective offload of offloadable flows; SW residue is architectural (recirc multi-table, mcast, dynamic tunnel encap, drop actions). No source/config changes. | 3 | 0 (all FALSE_POSITIVE/WONTFIX) | 0 |
| 2026-05-10 | `2026-05-10-bug-16-17-vdpa-tc-race.md` | Bug 16 — vDPA-tier VMs never receive DHCP; OVN table 73 DHCP exception rule matches 0 packets; cloud-init breaks. Bug 17 — TCP from vDPA VMs never establishes; SYN-ACK storm + guest RST; only ICMP works. Shared root cause: `OvnVdpaVifDriver.attachRepresentorToBrInt()` stamped `iface-status=active` at plug() time before vhost-vdpa queue negotiation completed → mlx5 TC offload partial install (chain 0 has `ct zone N nat pipe + goto chain 1`, chain 1 empty) → all TCP/UDP HW-dropped. ICMP escapes because chain 0 only matches TCP+UDP. Both HIGH. Fix `bc76f2a8fc` defers active stamp to `LibvirtComputingResource.applyOvnPostPlugTunables()` post-VM-running hook via new `applyVdpaPostPlugTunables()`, mirroring proven Bug-14 TAP pattern; rep name cached on `NicTO.setVfRepName()` during plug. Companion `e10dd14676` drops 4 pre-existing unused imports blocking `package` build. JAR md5 `312819d405eefebe673bb9a89f3df13f` deployed norbert + fluffy. Verified same-host on fluffy via 2 fresh vDPA VMs (`perf-vdpa-dst` + `perf-vdpa-2`): DHCP lease 86400s on both, ICMP 5/5 0% loss, iperf3 TCP 3.28 Gbps sustained 15s 4 streams. Cross-host verification DEFERRED — norbert kernel `mlx5_vdpa` deadlock (concurrent `vdpa dev add`/`del` on same VF, D-state, unreapable; separate kernel issue, NOT introduced by patch). Bug 18 (tier-vdpa LS has no router-type LSP, `lrp-fa50740c` `peer=[]`) OPEN, separate audit pending. | 2 | 2 (Bug 16 + 17 FIXED) | 1 (Bug 18 routing) |
