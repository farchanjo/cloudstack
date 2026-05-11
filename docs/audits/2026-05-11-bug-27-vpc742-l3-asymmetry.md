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

## Investigation findings (2026-05-11 07:33 UTC)

Read-only diagnostic loop executed end-to-end on the existing live state (no `systemctl restart`, no `flush-conntrack`, no source patches, no SQL mutations). All hypotheses listed above are REJECTED by the evidence collected; a different root cause emerged. Operator-side restart is NOT warranted.

### Victim set selected for tracing

| name | host | IP | mac | LSP UUID | tunnel_key |
|---|---|---|---|---|---|
| test20-tap-2 | trevor | 10.97.3.206 | 02:04:02:55:00:0a | lsp-1dc850a3-7f5b-4b9d-ac85-c5971a07c05e | 4 (0x4) |
| perf-tap-src (control reference, same LS, same VPC) | norbert | 10.97.3.8 | 02:04:02:55:00:10 | lsp-46e57c8d-6499-4a9f-9949-36c5a95d1e0e | 12 (0xc) |
| VR `r-1166-VM` tier-tap | aragog | 10.97.3.96 | 02:04:02:55:00:15 | lsp-a2bb2e72-f604-432d-bac9-7903cd395ea4 | 14 (0xe) |

VPC topology correction: the audit's original framing of "VR per-tier IP at 10.97.3.96 as the L3 gateway" is wrong. The VR's tier-tap IP **is `10.97.3.96`** (a regular tenant address inside the tap-tap subnet, NOT the gateway). The L3 gateway is the **OVN distributed LR LRP `lrp-787ae4fb-177c-41c1-b0cc-090a824b17bc`** at `10.97.3.1/24`, MAC `02:01:01:61:03:01`. The VR is just another VM on `ls-787ae4fb` from OVN's perspective. The CR-LRP `cr-lrp-public-vpc742` anchored to aragog (chassis UUID `7ac32f8d-a682-486b-98b2-622ed57110eb`) serves only the **public-side** chassis-redirect for SNAT/DNAT (`217.179.89.34`) — it is NOT the tier-tap gateway. This invalidates Hypothesis 1's framing.

Second correction: `perf-*` VMs are in the **same VPC `test-20vm-vpc` (vpc742) and same per-tier networks** as `test20-*` VMs (`networkid=787ae4fb-...` for tap-tap, `b4e54207-...` for tap-vf, `fa50740c-...` for tap-vdpa). The audit's "perf-* are in a different VPC" claim is wrong. Reachability difference is NOT a VPC-membership discriminator.

### ovn-trace excerpts (logical pipeline)

VR (10.97.3.96, mac 02:04:02:55:00:15) → test20-tap-2 (10.97.3.206, mac 02:04:02:55:00:0a) ICMP echo on `ls-787ae4fb`:

```
0. ls_in_check_port_sec : 1, priority 50 (n_packets=466, metadata=0x5)
32. ls_in_l2_lkup : eth.dst == 02:04:02:55:00:0a, priority 50 (n_packets=7, dl_dst flow installed)
   outport = "lsp-1dc850"
14. ls_out_check_port_sec : 1, priority 0
15. ls_out_apply_port_sec : 1, priority 0
   output; / type "" /
```

**No `drop;` action anywhere in the trace.** Logical pipeline is clean. Same-VPC control trace (VR → perf-tap-src) follows the identical structure with `n_packets=2` matched at table 40. Both paths are programmed equivalently.

### Datapath flow counts on aragog (source chassis)

`ovs-ofctl dump-flows br-int` — tier-tap LS = metadata=0x5. Output port 129 = `ovn-d06b29-0` Geneve tunnel to trevor (BFD up + forwarding).

| Stage | reg15 value | n_packets | idle_age (s) | Status |
|---|---|---|---|---|
| `table=44 reg15=0x4 metadata=0x5` (tunnel-output to trevor for test20-tap-2) | 0x4 | 7 | 1834 | FLOW INSTALLED |
| `table=44 reg15=0xc metadata=0x5` (tunnel-output for perf-tap-src) | 0xc | **MISSING from current dump** | n/a | (perf-tap-src is on norbert, would be output:130; current dump did not list this row for metadata=0x5) |
| `table=32 ls_in_l2_lkup dl_dst=02:04:02:55:00:0a` | reg15→0x4 | 7 | 1764 | FLOW INSTALLED |
| `table=34 ls_in_arp_rsp arp_tpa=10.97.3.206 op=1` | n/a | 1 | 3284 | ARP RESPONDER PRESENT (answers locally — explains audit's "ARP works") |

Tunnel port (port 129 → trevor) carries 68,129 rx / 68,220 tx packets ever; no errors. BFD up. Geneve tunnel is healthy.

**Megaflow stats:** 16 kernel flows, 60.18% mask-cache hit-rate, 71.06% offloaded packets. Kernel datapath healthy. `ovs-appctl dpctl/dump-conntrack | wc -l` = 3260, **0 INVALID entries.** Hypothesis 2 (conntrack INVALID storm) REJECTED.

### Kernel datapath flow evidence on aragog (in_port=41 = vnet102 = VR's tier-tap)

`ovs-appctl dpctl/dump-flows` filtered to `src=02:04:02:55:00:15` (VR mac):

```
in_port(41), eth(src=02:04:02:55:00:15, dst=02:01:01:61:03:01),
  ipv4(src=10.97.3.96, dst=10.97.3.1, proto=1, ttl=64),
  icmp(type=8, code=0),
  packets:364, used:37.4s,
  actions:userspace(pid=4294967295,slow_path(action))

in_port(41), eth_type=0x0806 (ARP),
  arp(sip=10.97.3.96, tip=10.97.3.1, op=1),
  packets:2, actions:userspace(slow_path)
```

There are **only two active kernel datapath flows from the VR's tier-tap port**, and **both terminate at `dst=10.97.3.1` (the OVN distributed LR's gateway)**, not at any `test20-*` tenant address.

`ovs-appctl dpctl/dump-flows` filtered to `dst_mac=02:04:02:55:00:0a` (test20-tap-2 mac): **NO MATCHES.** The VR has emitted **zero** unicast frames addressed to test20-tap-2 (or any test20-* MAC) in the lifetime of the current kernel datapath flow set.

### SB MAC_Binding table

```
ovn-sbctl find MAC_Binding ip='10.97.3.206' → (empty result)
```

The OVN southbound `MAC_Binding` table has **no entry for `10.97.3.206`** (test20-tap-2). The VR's LRP `lrp-787ae4fb-...` has never resolved this neighbor. Combined with the missing kernel datapath flows above, this confirms the VR is not initiating any L3 traffic toward `10.97.3.206` — it is only pinging its default gateway.

### Destination-side evidence on trevor (test20-tap-2 chassis)

```
ovs-vsctl find Interface external_ids:attached-mac='02:04:02:55:00:0a':
  name=vnet32, ofport=134, iface-id=lsp-1dc850a3-..., iface-status=active, ovn-installed=true,
  ovn-installed-ts=1778430123277, admin_state=up, link_state=up

ovs-ofctl dump-ports br-int 134:
  rx pkts=0, bytes=0, drop=0, errs=0     (VM → OVS direction)
  tx pkts=2, bytes=220, drop=0           (OVS → VM direction)

ip -s link show vnet32:
  RX: 0 packets   (VM has received 0 packets in current uptime)
  TX: 2 packets / 220 bytes  (VM has sent 2 packets — likely DHCP/RA solicit and one retry)
```

trevor's br-int has the full logical pipeline programmed for test20-tap-2:
- `table=0 in_port=134` flow loads metadata=0x5, reg14=0x4
- `table=65 reg15=0x4 metadata=0x5` outputs to port 134 (vnet32), with `n_packets=23` over 11473s
- `table=75 dl_dst=02:04:02:55:00:0a nw_dst=10.97.3.206` n_packets=4 (4 unicast IP packets to test20-tap-2 mac have been matched)
- `table=44 reg15=0xe metadata=0x5 → output:135` (the tunnel back to aragog for VR responses) — `n_packets=0` — trevor's br-int has installed but never used the reverse-direction tunnel flow back to aragog from test20-tap-2

**Bottom line on trevor:** the OVN-side infrastructure is fully programmed. The VM has sent only 2 packets out of its NIC in 11,473 seconds (3h 11min) of OVS uptime. The destination guest is effectively dormant or has a broken network stack inside the guest OS — it is NOT initiating traffic that would be subject to OVN/OVS forwarding decisions, and the 23 OpenFlow `output:134` hits have not materially reached the guest (only 2 confirmed at the tap level).

### Other observations

- **Bug 24 still active for the OTHER VR on aragog (`r-1165-VM`).** `vnet100` (mac `02:04:02:55:00:14`) is plugged into **`br-bond`** (NOT `br-int`) with `iface-id=e75b9867-...` (raw NIC UUID, NOT `lsp-`-prefixed). The OPEN Bug 24 audit row in `README.md` predates the `2026-05-11-bug-24-FIX.md` audit but `r-1165-VM` was created before the fix landed — the running VR was not rebuilt and still carries the pre-fix bridge mis-assignment. The Bug 24 FIX audit's verification VR was specifically `r-1166-VM` (our subject), which IS healed (vnet102 on `br-int`, iface-id `lsp-a2bb2e72-...`, `ovn-installed=true`). This is a side-finding, NOT the Bug 27 root cause.
- **`ovn-monitor-all` split across chassis.** aragog=true, fluffy=true, trevor=true, nagini=false, norbert=false, scabbers=false. The conductor noted this asymmetry — chassis with `ovn-monitor-all=false` use conditional monitoring. However, since `test20-tap-2` is on trevor (monitor-all=true) and the VR is on aragog (monitor-all=true), this asymmetry does NOT discriminate the failing case from working cases. Side-finding worth tracking but not causal to Bug 27.
- **`ovn-controller debug/status` on aragog returns `running`**, `consider_logical_flow` coverage 10.8/sec sustained — engine healthy, no stall.
- **`pinctrl WARN Dropped 2675 log messages in last 60 seconds`** on aragog (already noted in original audit) — investigated, appears to be DHCP-snooping packet-in volume from the 9 perf-* + 1 VR + i-2-* VMs combined; not a root-cause indicator. The `IGMP Querier enabled without a valid IPv4 or IPv6 address` warning is chronic, IGMP-only, not L3-routing-related.

### Symptom re-classification

The audit's "100% loss on ICMP/TCP from VR to test20-tap-*" claim is **not reproducible in the current data plane state**. Direct kernel datapath inspection on aragog (in_port=41 = vnet102 = VR's tier-tap) shows the VR is not emitting any L3 traffic addressed to `10.97.3.206` or any other `test20-*` IP. The VR is exclusively ICMP-probing its own default gateway `10.97.3.1` (364 echo requests, OVN distributed LR replies via `lr_in_ip_input` n_packets=360 + complete return path through `lr_out_delivery` n_packets=8635 to `lrp-787ae4`), and the VR's vnet102 RX:478 / TX:431 packet counts are consistent with the VR receiving the echo replies normally.

The original audit was likely conducted from inside the VR's console (via `ssh` from a control node to the VR's link-local control IP `169.254.98.244`, the systemvm management plane). That access path was attempted during this investigation and timed out (`ssh: connect to host 169.254.98.244 port 22: Connection timed out`) — i.e. the systemvm SSH listener is currently unreachable from voldemort/aragog, and from project policy "no nested SSH" the diagnostic loop cannot probe inside-the-VR state. The audit's narrative may reflect a transient condition from when the original verification was run; it does not match the current state.

### Destination guest state (the proximate cause of the observed-but-now-unreproducible symptom)

`test20-tap-2` (and almost certainly its 19 siblings) is essentially DEAD at the guest network level. `vnet32 RX=0 packets / TX=2 packets` over 11,473 s of OVS uptime means the guest's NIC has emitted 2 frames in 3h 11min and has accepted 0 frames in the same window from the OVS datapath. If the VR pings `10.97.3.206` (hypothetical, since we have no evidence it did), the kernel datapath would correctly tunnel the packet via aragog port 129 → trevor port 135 → br-int → output port 134 → vnet32 → guest. The guest would then need to respond with an ICMP echo reply, which it CANNOT because its network stack is non-functional (TX=2 across 3h means it can barely emit ARP/DHCP-probe).

This pattern matches the audit's prior framing of Bug 25 ("rolling cloudstack-agent restart broke east-west for all 20 test20-* VMs") — the agent restart cycle MAY have collateral-damaged the GUESTS themselves (e.g. by toggling the OVS Interface admin state for >IP-stack-detection-window), not the OVN infrastructure. The Bug 25 reconcile fix re-stamped `ovn-installed=true` and that landed correctly (Bug 25 FIX audit cites 51 / 56 re-stamps), but the guest kernels may have already declared their NICs DOWN due to carrier loss during the agent recycle. Linux network manager (NetworkManager, systemd-networkd, or dhclient) typically requires explicit reactivation after carrier flap if `link.required-timeout` is short.

## Root cause + recommendation

ROOT_CAUSE: **INCONCLUSIVE on the original audit's framing, REJECTED on all five enumerated hypotheses.** Direct evidence in OVN/OVS data plane contradicts the audit's claim — the VR is not attempting to send traffic to `test20-*` destinations at all. The proximate symptom (test20-tap-* VMs unreachable) is more consistent with **GUEST-side network-stack failure** post Bug 25 rolling restart than with any OVN/OVS infrastructure defect.

EVIDENCE summary:
- victim_set: as tabulated above (test20-tap-2 on trevor / perf-tap-src on norbert as same-LS same-VPC control / VR `r-1166-VM` on aragog)
- ovn_trace_excerpts: VR → test20-tap-2 trace completes with no `drop;` actions; identical structure to working perf-* control
- aragog_flow_counts: `test20-tap-2 mac (02:04:02:55:00:0a)` = 0 kernel-datapath entries from VR's port (in_port=41); `VR mac (02:04:02:55:00:15)` = 2 entries, both to `dst_mac=02:01:01:61:03:01` (LRP gateway MAC); `table=12 lr_in_ip_routing` ip4.dst=10.97.3.0/24 priority 198 `n_packets=15141` (healthy)
- fluffy_flow_counts: not separately exercised since perf-* dispatch claim already invalidated (same VPC as test20-*)
- conntrack: total=3260, invalid_count=0, tier_tap_subnet_count=many (most are inbound public-NAT entries, not VR-internal)
- ovn_controller_engine: aragog `debug/status=running`, `consider_logical_flow=10.8/sec`, no stall
- nb_topology: tier_lsp_enabled_status=enabled, lr_static_routes=clean (default `0.0.0.0/0 via 217.179.89.1 lrp-public-vpc742`), acls_matching_test20=NONE (table empty for tier-tap LS and VPC742 LR)

RECOMMENDED_FIX:
- **operator_side**: NONE. Do not restart `ovn-controller` on aragog — OVS/OVN plane is fully programmed and functioning. Restarting would burn 30-60 s of east-west blackout for no benefit. Instead, the next step belongs at the **guest OS layer** of the 20 `test20-*` VMs.
- **source_side**: NONE in OVN/OVS plumbing. Bug 27 as originally framed is not a CloudStack/OVN defect.
- **audit_followup**: This file (append-only). No new audit number.

NEXT_STEP_FOR_USER: Boot one test20-* VM via VNC console (e.g. `virsh -c qemu+tcp://10.182.0.26/system console i-2-1150-VM`) and verify whether its guest network stack has a routable interface (IP assigned, default gateway via 10.97.3.1, ARP cache populated). If the guest NIC is DOWN or has no IP, this is a guest-side recovery problem, not an OVN/OVS problem, and Bug 27 should be re-labelled `OBSOLETE — guest-side recovery, not OVN/OVS plane`. If the guest NIC is UP and addressed but cannot egress, the next investigation surface shifts to **MTU path discovery** (jumbo 9000 inner vs underlay 9000 with Geneve 58-byte overhead may fragment-drop without ICMP-too-big replies) and to a **passive `tcpdump` on vnet32 (trevor) and vnet102 (aragog)** to capture the exact moment of egress failure.

Audit status remains OPEN until either: (a) a fresh user-driven reproduction returns dataplane-side evidence inconsistent with the read-only findings above, or (b) guest-side root cause is confirmed and this audit is reclassified `OBSOLETE`.
