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

# Bug 27 — VPC `test-20vm-vpc` (vpc742) L3 east-west asymmetry post Bug 25/26 fix; ARP succeeds, ICMP/TCP fails; reachability binary per remote chassis

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** HIGH — every tenant VM in VPC `test-20vm-vpc` loses east-west connectivity through its VR; affects ~20 `test20-*` VMs across all 6 data nodes. Net-new tenant traffic in this VPC silently drops at L3.
**Fix commit:** _none yet_
**Discovered during:** Bug 25 + Bug 26 smoke verification (this same date). Bug 25 reconcile re-stamped 93 OVS Interface rows with `ovn-installed=true`, dropping the missing-flag count from 56 → 5. Bug 26 live-migration smoke passed cleanly (`perf-vdpa-2` norbert → fluffy with 0% loss). Despite both fixes landing, `test20-*` VMs in `test-20vm-vpc` remained 100% unreachable from VR `r-1166-VM`.

---

## Symptom

VPC `test-20vm-vpc` (UUID `a1992656-2a76-43a5-82cc-3c9b7f5402ca`) is a 3-tier VPC with HW-offload-mixed tier configuration:

- tier-vdpa (10.97.1.0/24) — vDPA representors via mlx5 eSwitch
- tier-vf   (10.97.2.0/24) — SR-IOV passthrough hostdev
- tier-tap  (10.97.3.0/24) — non-HW-offload bridge taps on `br-int`

VR `r-1166-VM` (UUID `175bdbed-d141-4fdb-a81e-572207a6c577`) currently runs on chassis **aragog** (chassis-id `dfe030bb-3732-4472-8440-31154a6b9b12`). Its 4 `<interface>` blocks in libvirt XML are correctly configured post Bug 24 fix (tier-tap on `<source bridge='br-int'/>`, `iface-id=lsp-a2bb2e72-...`).

OVN SB chassis residency for `cr-lrp-public-vpc742` (the gateway-router chassis-resident LRP for this VPC's distributed Logical Router) is also on **aragog** (`cr-lrp-public-vpc742` listed under `Chassis "dfe030bb..."`).

### Observed reachability matrix from VR `r-1166-VM` (on aragog)

| Destination | Chassis residency | L2 (ARP) | L3 (ICMP/TCP) |
|---|---|:---:|:---:|
| `perf-vdpa-2` (10.97.1.62, different VPC) | fluffy | ✅ | ✅ |
| `perf-vdpa-dst` (different VPC) | fluffy | ✅ | ✅ |
| `test20-*` on aragog (same chassis as VR) | aragog | ✅ (MAC reply received) | ❌ (100% loss) |
| `test20-*` on norbert | norbert | ✅ | ❌ |
| `test20-*` on fluffy | fluffy | ✅ | ❌ |
| `test20-*` on nagini | nagini | ✅ | ❌ |
| `test20-*` on scabbers | scabbers | ✅ | ❌ |
| `test20-*` on trevor | trevor | ✅ | ❌ |

The failure is **uniform across remote chassis** for `test20-*` and **succeeds across remote chassis** for `perf-*`. The pattern correlates with VPC membership, NOT with chassis-pair tunnel state. ARP returning MAC replies confirms L2 multicast / unicast through OVN works end-to-end (br-int → Geneve tunnel → remote br-int → tenant tap). The L3 path collapses somewhere between ARP-resolved next-hop and packet egress to the remote tap.

## Pre-fix vs post-fix continuity

`test20-*` VMs were created BEFORE the Bug 23/24 deploy (existed during the original Bug 14b/15/16/17/18 era — same VPC as the Bug 22 reproduction VR `r-1164-VM`). They survived the Bug 23 + Bug 24 + Bug 25 + Bug 26 deploys but lost east-west during one of the rolling restarts. Per Bug 25 audit, rolling `cloudstack-agent` restart breaks pre-existing east-west until reconcile re-stamps `ovn-installed=true`. The Bug 25 fix DID re-stamp the OVN reps (51 / 56), yet `test20-*` reachability did NOT recover.

This implies the missing piece is NOT the `ovn-installed=true` flag itself but a flow-table / forwarding state that ovn-controller fails to rebuild even when the flag is restored.

## Evidence collected (2026-05-11 ~07:11 UTC)

### Chassis inventory (`ovn-sbctl show` from voldemort)

```
Chassis "e1a12e99-ac9f-471e-b5f5-e8bf34362b11"  hostname: nagini   ip: 10.182.0.24
Chassis "c17bf268-2560-4d6a-89f8-67eba84973af"  hostname: norbert  ip: 10.182.0.22
Chassis "d06b29b7-ef78-4361-9bea-22b86745e16c"  hostname: trevor   ip: 10.182.0.26
Chassis "dfe030bb-3732-4472-8440-31154a6b9b12"  hostname: aragog   ip: 10.182.0.21
   Port_Binding cr-lrp-public-vpc742           <-- chassis-resident gateway LRP for the affected VPC
Chassis "39a20fc5-46bf-4a05-9be0-8aa61bb3e02c"  hostname: scabbers ip: 10.182.0.25
Chassis "ce92dade-db69-4f22-b38c-bdf5f33fef0c"  hostname: fluffy   ip: 10.182.0.23
```

All 6 chassis report `Encap geneve` with `csum=true`. No chassis-id stale entries. The CR-LRP for `public-vpc742` is anchored to **aragog** — the same chassis hosting the VR `r-1166-VM`.

### BFD on aragog (selected — head -40 truncated remaining sessions)

```
---- ovn-ce92da-0 ----  (→ fluffy)
  Forwarding: true   Local state: up   Remote state: up
  Local Diag: No Diagnostic

---- ovn-39a20f-0 ----  (→ scabbers)
  Forwarding: true   Local state: up   Remote state: up
  Local Diag: Neighbor Signaled Session Down   <-- historical, not current
```

### BFD on norbert

```
---- ovn-e1a12e-0 ----  (→ nagini)
  Forwarding: true   Local state: up   Remote state: up
  Remote Diag: Control Detection Time Expired   <-- historical, peer recovered

---- ovn-d06b29-0 ----  (→ trevor)
  Forwarding: true   Local state: up   Remote state: up
```

### BFD on fluffy

```
---- ovn-e1a12e-0 ----  (→ nagini)   up / up   No Diagnostic
---- ovn-39a20f-0 ----  (→ scabbers) up / up   No Diagnostic
```

### Aragog ovn-controller log (last 30 lines filtered)

```
2026-05-11T07:11:16.769Z pinctrl|WARN|Dropped 2675 log messages in last 60 seconds
2026-05-11T07:11:16.769Z pinctrl|WARN|IGMP Querier enabled without a valid IPv4 or IPv6 address
```

The `Dropped 2675 log messages` line is suspicious — `pinctrl` is the OVN controller subsystem that handles packet-in/packet-out messages for ARP, DHCP, ND, port-security, and ICMP responder logic. 2675 dropped messages / 60 s = ~44 msg/s sustained — indicates a packet-in storm. Could be ARP probes that have nowhere to go (consistent with the L3 failure) OR could be unrelated to the symptom. Worth correlating with the affected MACs.

The `IGMP Querier enabled without a valid IPv4 or IPv6 address` line is a chronic warning — likely unrelated; mentions IGMP not IP routing.

## Hypotheses (ranked)

1. **CR-LRP flow programming gap on aragog** (most likely). The chassis-resident gateway LRP for `public-vpc742` is on aragog. After the rolling restart of `cloudstack-agent`, ovn-controller on aragog was restarted as part of the agent lifecycle. The Bug 25 reconcile re-stamped OVS Interface rows but did NOT trigger ovn-controller to fully rebuild the Logical_Router → distributed flow tables for the VPC742 LR. The L3 forwarding flows for the LR (tables 8–24 in OVN's logical pipeline) may be partially programmed: ARP/ND in tables 0–2 work (hence MAC replies received), but the LR's L3 forwarding stage (table 12-ish — `lr_in_ip_routing`) is missing or stale for the `test20-*` destinations.
2. **conntrack invalid state on aragog**. Pre-fix packets that traversed the broken plug path may have created stale conntrack entries in INVALID state. OVN's stateful ACLs match `ct_state` — if conntrack rejects the connection setup, no SYN-ACK or ICMP-reply egresses. Would manifest as ARP-pass / TCP-fail / ICMP-fail consistent with the observed symptom. Migrate of conntrack state across chassis is not standard OVN — each chassis has its own ct view.
3. **Distributed ACL or port-security misfire**. The CR-LRP could be enforcing a stale port-security mac/ip whitelist that admits ARP but drops L3. Less likely because port-security is typically per-LSP not per-LR.
4. **Geneve VNI mismatch for VPC742 specifically**. Cross-chassis tunneling for OTHER VPCs (perf-* in fluffy) works, so the encap is fine globally. But VPC742's datapath could be using a different (perhaps stale) tunnel key after some ovn-controller restart races. Unlikely but cheap to verify.
5. **OVN ECMP / multi-path with one path black-holed**. If the distributed LR has redundant nexthop routes and one is broken, ECMP would hash some flows into the broken bucket. Doesn't match the 100% loss pattern — would be partial.

## Investigation surface (not implemented)

Diagnostic loop priority order:

1. **OVN logical pipeline trace for a failing flow**:
   ```
   ovn-trace --ovs --inport=<vr-tier-tap-lsp> 'eth.src=<vr-mac> && ip4.dst=<test20-vm-ip> && eth.dst=<gateway-mac>'
   ```
   on each chassis. Compare the trace on aragog vs other chassis. If the trace stops at the LR's ip_routing table on aragog, hypothesis 1 is confirmed.
2. **`ovs-ofctl dump-flows br-int | grep -c <test20-mac>`** on aragog — count datapath flow entries referencing a failing test20 MAC. Then same on fluffy. If aragog has 0 entries and fluffy has > 0, ovn-controller on aragog has not programmed the flows.
3. **`ovs-appctl dpctl/dump-conntrack | grep <test20-ip>`** on aragog — look for INVALID-state ct rows for the failing pairs.
4. **`ovs-vsctl get Open_vSwitch . external_ids:ovn-monitor-all`** — confirm not in selective mode that would skip irrelevant LSPs. Should be `true` on each chassis or unset.
5. **`ovn-appctl -t ovn-controller debug/status`** on aragog — capture `n-flows` counters, last-recompute time, lflow processing rate. A stalled engine would show n-flows much lower than peer chassis.
6. **`ovn-appctl -t ovn-controller engine/dump-engine-stats`** — identifies which OVS-IDL transaction node is stuck.
7. **Crude isolation test**: `cmk migrateSystemVm vmid=<r-1166> hostid=<some-other-chassis>` to move the VR. If reachability recovers on the new chassis, hypothesis 1 is confirmed (aragog-specific state). If reachability stays broken, hypothesis is wrong and the issue is in the VPC742 logical topology itself (NB-side).

Once root cause is identified, append a follow-up fix audit citing source commit + production verification per project convention.

## Manual remediation (operator-side, until investigation lands)

Untested but likely-effective:

```bash
# On aragog (or whichever chassis hosts cr-lrp-public-vpc742):
systemctl restart ovn-controller   # full restart, NOT just reload — forces flow table rebuild
```

If that doesn't recover east-west within ~30 s, escalate to the deeper investigation surface above.

Alternative cleanup (more invasive):

```bash
# On aragog:
ovs-appctl dpctl/flush-conntrack       # purge stale ct state
ovs-ofctl del-flows br-int 'priority=0' # forces full reprogramming (CAUTION: temporarily blackholes ALL traffic on br-int until ovn-controller reseeds — do NOT run during traffic peak)
```

Last-resort: VR re-creation (`cmk restartVPC ... cleanup=true`) but this loses the test20 state without recovering it because the issue is chassis-side, not VR-side.

## Why this is NOT a Bug 25 / Bug 26 regression

- Bug 25 fix (`reconcileOvnInstalledOnStartup`) does exactly what its audit promised: re-stamp `iface-status=active` + `ovn-installed=true` on Interface rows whose `iface-id` matches `lsp-<uuid>`. Production evidence: 56 → 5 missing-flag count drop, 93 successful re-stamps across 6 hosts logged at INFO. The fix surface enumerated in `2026-05-11-bug-25-old-vm-unreachable-after-agent-restart.md` is fully delivered.
- Bug 26 fix extends `applyPostPlugTunables` in all 3 OVN VIF drivers to emit `ovn-installed=true` alongside `iface-id` and `iface-status`. Verified end-to-end on `perf-vdpa-2` live-migration norbert → fluffy with 0% packet loss and the stamp present on destination OVS Interface.

The unreachable-test20 symptom that Bug 25's audit attributed loosely to Bug 25 was, on closer inspection, a **separate, co-occurring** failure mode whose fix surface lives elsewhere — not in the agent's stamp-reconcile path. Bug 27 captures it for separate investigation.

## References

- `2026-05-11-bug-25-old-vm-unreachable-after-agent-restart.md` — the original symptom attribution scope-clarified in `2026-05-11-bug-25-FIX.md`.
- `2026-05-11-bug-25-FIX.md` — describes the scope clarification text that points at this Bug 27 audit.
- `2026-05-11-bug-26-FIX.md` — Bug 26 fix confirms the `ovn-installed=true` stamp is present at agent-side; Bug 27 demonstrates the stamp is necessary but not sufficient for L3 forwarding in this VPC.
- OVN logical pipeline reference: tables 0–24 sequence in `Northbound.dsl` ↔ `Logical_Flow.actions` chain. Tables 8 (`ls_in_pre_acl`), 11 (`ls_in_pre_stateful`), 12 (`lr_in_ip_routing`), 14 (`lr_in_arp_resolve`), 22 (`lr_out_delivery`) are the prime suspects.
- VPC742 NB topology: distributed LR with 3 tier-LS attachments + 1 public-VPC LR with chassis-resident gateway. CR-LRP `cr-lrp-public-vpc742` anchored to aragog.
