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
| 2026-05-09 | `2026-05-09-ovn-fork-audit.md` | OVN fork DB + API + UI inconsistencies + LB selection_fields + LB update state-machine + vDPA user VM guard + VF pool concurrent allocation race | 12 | 11 | 1 (Bug 10 — `updateLoadBalancerRule` algo no re-sync, LOW) |
| 2026-05-10 | `2026-05-10-bug-13-configkey-leak.md` | Bug 13 — `ovn.requested_chassis` + `ovn.ha_chassis_priority` ZONE-scope ConfigKeys left set from Phase B verification audit + never reverted; every new LSP carried `options:requested-chassis="norbert=42"`; HIGH severity; config-only revert (no source change, no rebuild); verified via Stage 2 rebuild (20 VMs, 0 contaminated LSPs) | 1 | 1 | 0 |
| 2026-05-10 | `2026-05-10-bug-14-iface-id-prefix.md` | Bug 14 — `OvnVifDriver` emits raw NIC UUID as `external_ids:iface-id` instead of `lsp-<uuid>`; TAP-tier-only regression from `d85d27f126`; promised `OvnNicTunableApplier` post-plug stamp never implemented; 6 TAP LSPs stuck `up=false` (Port_Binding never claimed); HIGH severity; manual remediation via `ovs-vsctl set Interface … external_ids:iface-id=lsp-<uuid>` on fluffy + trevor (6/6 LSPs `up=true`); source patch in `applyPostPlugTunables` mirrors vDPA + VF passthrough drivers. **Verification gap appended 2026-05-10**: `applyPostPlugTunables` had zero callers until commit `8e9b913cb1`; cold-start production fix worked only via manual stamp applied before JAR was built — see audit file "Verification gap" section. | 1 | 1 | 0 |
| 2026-05-10 | `2026-05-10-bug-14b-and-15-migration.md` | Bug 14b — live-migration destination OVN tap never runs `OvnVifDriver.plug()` → post-plug stamp never fires → dest tap has raw UUID iface-id → Port_Binding not claimed after migration; HIGH; Layer A fix `ff59e27753`; Layer B fix `0d91ba8a3f` (PostMigrateOvnStampCommand agent side; mgmt dispatch wiring deferred). Bug 15 Layer A — vDPA destination VF never allocated during migration → `No such file or directory`; MED; Layer A fix `ff59e27753`; Layer B fix `f03d7cfbfa` (XML rewrite; mgmt plumbing deferred); Layer C fix Commit-F-2026-05-10 (rollback VF release). Shared root cause: legacy `getVifDriver(TrafficType)` call in `LibvirtPrepareForMigrationCommandWrapper:80`. | 2 | 4 (Layers A+B+C; mgmt wiring deferred) | 2 (mgmt dispatch wiring for 14b-B and 15-B) |
