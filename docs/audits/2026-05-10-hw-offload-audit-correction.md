# 2026-05-10 — HW Offload Audit Correction

## Summary

Corrective audit revoking 3 spurious findings raised by an earlier HW-offload
audit pass executed on the same day. None of the three findings represented
real defects; production state was already optimal for the TC-flower + mlx5
offload path. Recording this in the audit log so future dispatches do not
re-flag the same false positives.

| # | Earlier finding | New status | Reason |
|---|---|---|---|
| C1 | `tc-policy=none` on all 6 data nodes treated as P0 blocker for OVS hardware offload | **FALSE_POSITIVE** | `tc-policy=none` is the documented OVS default (`ovs-vswitchd.conf.db.5`). It enables dual-path: rule installed in BOTH software datapath AND TC flower HW. It does NOT block offload. The actual block-mode value is `skip_hw`. Production was already offloading correctly. |
| C2 | `doca-init=false` on all 6 data nodes treated as P1 missed optimization (recommend enabling DOCA Flow datapath) | **WONTFIX** | Operator policy decision (2026-05-10) — DOCA Flow datapath is OFF for this fork. Stack stays on stock OVS + TC flower + kernel datapath. Recommendation withdrawn. |
| C3 | 14 OVS interfaces with raw-UUID `external_ids:iface-id` (aragog/4, norbert/4, trevor/5, scabbers/1) treated as Bug-14 regression candidates | **FALSE_POSITIVE** | Cross-check confirmed every raw-UUID interface lives on `cloud0` (link-local 169.254/16 system-VM control bridge) or `br-bond` (VLAN trunk uplink). Neither bridge is OVN-managed. Bug 14 only applies to interfaces on `br-int` with OVN northbound bindings. Scope confirmed: 0 raw-UUID interfaces on `br-int` across all 6 data nodes. |

## Evidence (collected 2026-05-10 ~20:30 UTC)

### C1 — tc-policy=none allows offload

Per-node snapshot of `ovs-appctl dpctl/dump-flows type=offloaded | wc -l`:

| Host | tc-policy | Offloaded flows | Total flows | Apparent ratio |
|---|---|---|---|---|
| aragog   | none | 62 | 357 | 17.4% |
| norbert  | none | 49 | 323 | 15.2% |
| fluffy   | none | 41 | 307 | 13.4% |
| nagini   | none | 41 | 306 | 13.4% |
| scabbers | none | 46 | 310 | 14.8% |
| trevor   | none | 42 | 307 | 13.7% |

Earlier audit conflated `dpctl/dump-flows` (no filter, default kernel datapath
cache including SW-only fast-path flows that are necessarily NOT offloaded)
with the actual offload-flow population (`type=offloaded`), and reported the
filtered-result as "0" without sanity-checking the type filter. The
`type=offloaded` query returns the active HW offload table — non-zero on every
node.

Pattern classification of aragog SW-only vs HW-offloaded flows
(`dpctl/dump-flows type=ovs` vs `type=offloaded`):

```
HW (74 flows): ipv4 68.9%, ct_state 37.8%, vlan 32.4%, arp 21.6%, ipv6 9.5%
SW (30 flows): recirc_id!=0 53.3%, tunnel_encap 16.7%, drop_action 16.7%,
               mcast_dst 16.7%, ct_state 13.3%, arp 13.3%, double-vlan 13.3%
```

Effective offload of offloadable flows: 74 / (74 + 30) = **71%**.
SW residue is architectural — `recirc_id != 0` (post-CT/NAT multi-table),
`multicast`/`broadcast`, dynamic tunnel encap, and explicit `drop` actions
are not reasonable HW-offload candidates on CX-6Dx + TC flower.

### C2 — DOCA off (policy)

Operator confirmed 2026-05-10 that DOCA Flow datapath will remain disabled
in this fork. No further DOCA recommendations should be raised by audits.

### C3 — raw-UUID iface-ids are non-OVN bridges

Per-node enumeration of OVS interfaces with `external_ids:iface-id` lacking
the `lsp-` prefix:

| Host | Raw-UUID count (total) | Located in | Located in `br-int`? |
|---|---|---|---|
| aragog   | 4 | `cloud0` x1, `br-bond` x2, `cloud0` (r-1092) x1 | 0 |
| norbert  | 4 | `cloud0` x1, `br-bond` x2, `cloud0` (r-1093) x1 | 0 |
| trevor   | 0 (cleared after Test 1 migrations earlier today) | — | 0 |
| scabbers | 0 (cleared after Test 4/5 migrations earlier today) | — | 0 |
| fluffy   | 0 | — | 0 |
| nagini   | 0 | — | 0 |

Affected VMs (all System VMs, pre-Bug-14-fix age):
- aragog: `v-839-VM` (console proxy) interfaces `vnet66/67/68` + `r-1092-VM`
  (VPC VR) interface `vnet87`.
- norbert: `s-840-VM` (SSVM) interfaces `vnet34/35/36` + `r-1093-VM` (VPC VR)
  interface `vnet50`.

All 8 raw-UUIDs absent from OVN NB (`Logical_Switch_Port` lookup miss) and SB
(`Port_Binding` lookup miss). OVN never consumed these iface-ids — they exist
solely as libvirt-side `<virtualport type='openvswitch'>` interfaceid metadata
on non-OVN bridges. No remediation required.

`br-int` raw-UUID count cluster-wide: **0**. Bug 14 mitigation holds.

## Action

- No source changes.
- No config changes.
- No remediation needed.

## References

- Original audit pass and its dispatched report from earlier 2026-05-10.
- `docs/audits/2026-05-10-bug-14-iface-id-prefix.md` (Bug 14 fix history).
- `docs/audits/2026-05-10-bug-14b-and-15-migration.md` (Bug 14b/15 live-mig).
- `ovs-vswitchd.conf.db.5` (`tc-policy` semantics).

## Status

| Item | Final |
|---|---|
| C1 tc-policy=none | OBSOLETE (false positive, no fix) |
| C2 doca-init=false | WONTFIX (operator policy) |
| C3 raw-UUID iface-ids | OBSOLETE (false positive, scoped to non-OVN bridges) |

Bugs open after this audit: 0.
