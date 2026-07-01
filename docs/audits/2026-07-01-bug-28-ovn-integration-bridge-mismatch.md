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

# Bug 28 — OVN integration bridge mismatch silently breaks all vDPA/OVN VIF wiring on LAX

**Date:** 2026-07-01
**Status:** OPEN — root cause confirmed, no fix applied, no config change made
**Severity:** CRITICAL
**Scope:** all 6 LAX data nodes (aragog, norbert, fluffy, nagini, scabbers, trevor)

---

## Summary

Deployed 2 fresh vDPA-tier VMs (`test-vdpa-norbert`/`test-vdpa-norbert2` on
norbert, `test-vdpa-fluffy`/`test-vdpa-fluffy2` on fluffy) to close the
cross-host vDPA verification gap left open by
`2026-05-10-bug-16-17-vdpa-tc-race.md` (norbert's kernel `mlx5_vdpa` deadlock,
confirmed resolved by a since-happened reboot — norbert `uptime -s` =
2026-05-21). Both VMs booted with a genuine `<interface type='vdpa'>` in
libvirt (correct hardware path selected, not TAP fallback), but agent-log
inspection revealed the representor was **never actually attached to any OVS
bridge on either host**:

```
WARN  Execution of process [...] for command [/bin/bash -c ovs-vsctl --may-exist add-port br-int dx6p1vf18 ] failed.
WARN  ... encountered the error: [ovs-vsctl: no bridge named br-int].
WARN  ... ovs-vsctl set Interface dx6p1vf18 external_ids:iface-id=... ] failed.
WARN  ... encountered the error: [ovs-vsctl: no row "dx6p1vf18" in table Interface].
INFO  OvnVdpaVifDriver.attachRepresentorToBrInt: rep=dx6p1vf18 lsp=... stamped inactive (deferred-active: ...)
INFO  OvnVdpaVifDriver.plug: ... bridge=br-int
```

Every `ovs-vsctl` call targeting `br-int` fails with `no bridge named br-int`
— **all 6 LAX data nodes have `br-cluster` + `br-overlay` + `cloud0`, never a
bridge literally named `br-int`.** The driver logs the WARN and proceeds to
log an unrelated INFO "success" line regardless — `attachRepresentorToBrInt`
and the later `applyVdpaPostPlugTunables` (`iface-status=active
ovn-installed=true`) both report success even though the representor was
never added to any bridge. Confirmed independently on both norbert
(`dx6p1vf18`, mac `02:04:02:60:00:03`) and fluffy (`dx6p0vf4`, mac
`02:04:02:60:00:04`) — `ip link show <rep>` proves the kernel netdev exists,
`ovs-vsctl show` / `ovs-vsctl list-ports <bridge>` on all 3 real bridges
proves the rep is in none of them.

**Practical impact: right now, on this cluster, a vDPA (or plain OVN TAP —
see below) guest NIC has zero connectivity.** No DHCP, no ARP, no ICMP —
nothing reaches the OVN pipeline, because the host-side OVS port was never
created. CloudStack's own `ipaddress` field on the VM (e.g. `10.98.1.90`) is
just the IPAM allocation record; it is not evidence the guest ever completed
DHCP, and in this case it did not.

---

## Root cause

`OvnVdpaVifDriver` (and `OvnVifDriver`, same pattern) resolve the OVS
integration bridge name from an agent property, falling back to the
OVN-upstream default:

```java
// OvnVifDriver.java / OvnVdpaVifDriver.java
public static final String DEFAULT_INTEGRATION_BRIDGE = "br-int";
public static final String PROP_INTEGRATION_BRIDGE = "ovn.integration.bridge";
private String integrationBridge = DEFAULT_INTEGRATION_BRIDGE;
// ... constructor: honours params.get(PROP_INTEGRATION_BRIDGE) if present
```

This is a real, working override mechanism — not a hardcoded literal. The gap
is purely **operational**: `ovn.integration.bridge` is not set in
`/etc/cloudstack/agent/agent.properties` on any of the 6 LAX data nodes, so
every VIF driver instance silently falls back to `br-int`, which does not
exist in this cluster's actual OVS topology (`br-cluster` + `br-overlay`
split bridge, connected via `to-overlay`/`to-cluster` patch ports — a
deliberate design used by this fork, evidenced by the dedicated patch-port
naming and `hwoffload.uplink.netdev=c-bond` / `hwoffload.uplink.kind=pf`
properties that ARE correctly set fleet-wide).

Verified fleet-wide (all 6 LAX data nodes):

| Host | `ovn.integration.bridge` in agent.properties | OVS bridges present |
|---|---|---|
| aragog | NOT SET | br-cluster, br-overlay, cloud0 |
| norbert | NOT SET | br-cluster, br-overlay, cloud0 |
| fluffy | NOT SET | br-cluster, br-overlay, cloud0 |
| nagini | NOT SET | br-cluster, br-overlay, cloud0 |
| scabbers | NOT SET | br-cluster, br-overlay, cloud0 |
| trevor | NOT SET | br-cluster, br-overlay, cloud0 |

## Secondary defect — silent failure swallowing

Independent of the missing config, the driver's error handling is itself a
defect: `Script.execute()` failures are logged at WARN and the calling code
(`attachRepresentorToBrInt`, `applyVdpaPostPlugTunables`) continues
unconditionally to an INFO "success" log line, with no exception raised and
no state reflecting the failure anywhere CloudStack or an operator would
normally look (`cmk` API responses, VM state, agent health). A cluster-wide
OVS wiring outage is invisible short of manually grepping WARN lines out of
`agent.log` and cross-referencing `ovs-vsctl show` — exactly how this was
found. This masked the bug through the entire Bug 14–26 fix cycle: every
"PASS" in `2026-05-10-bug-16-17-vdpa-tc-race.md` and
`2026-05-10-bug-18-ls-lr-peer-backfill.md` describes correct behavior of code
that runs *after* the bridge attach step, and none of those audits happened
to run `ovs-vsctl list-br` to confirm the attach itself actually landed.

## Open question — regression or pre-existing gap?

Not resolved in this pass. The historical audits' own log excerpts (e.g.
`2026-05-10-bug-16-17-vdpa-tc-race.md`, verified PASS on fluffy) show the
identical `bridge=br-int` value in the `OvnVdpaVifDriver.plug` log line back
in May — meaning either (a) fluffy's OVS bridge was genuinely named `br-int`
at that time and was renamed/restructured into the `br-cluster`/`br-overlay`
split some time between 2026-05-10 and now (a fabric-level change outside
this repo, possibly part of the LAX `networking_base`/`ovs` Ansible-REX
rollout referenced in `infra-base/CLAUDE.md`), or (b) the attach was already
silently failing back then too and the DHCP/ICMP/iperf3 "PASS" results in
those audits were produced through a different, unidentified path. Not
investigated further here — flagging for whoever picks this up next.

## Fix (not applied — needs explicit go-ahead, touches all 6 live data nodes)

1. Add `ovn.integration.bridge=br-cluster` to
   `/etc/cloudstack/agent/agent.properties` on all 6 LAX data nodes.
2. Restart `cloudstack-agent` on each host (VIF driver params are read at
   driver construction / agent startup — a running agent will not pick up
   the property live). Rolling, one host at a time, matches the pattern used
   for jar deploys in `2026-05-10-bug-18-ls-lr-peer-backfill.md`.
3. Re-run this session's cross-host vDPA test (2 VMs, norbert + fluffy, same
   tier) to confirm DHCP + ICMP + TCP now succeed end-to-end — this is the
   actual verification the original Bug 16/17 audit deferred, still not done.
4. Separately (code-level, this repo): make `attachRepresentorToBrInt` /
   `applyVdpaPostPlugTunables` (and the equivalent path in `OvnVifDriver`)
   fail loudly — throw or return a failure status — when the underlying
   `ovs-vsctl add-port` does not succeed, instead of logging WARN and
   continuing to an unconditional INFO success line. This is what let a
   cluster-wide dataplane outage hide for (at least) 6+ weeks.

## Test artifacts (cleaned up)

Created and destroyed during this investigation, via `cmk` only (no direct
DB writes, per the `infra-base/CLAUDE.md` standing rule): VPC
`test-vdpa-xhost`, tier network `tier-vdpa-xhost` (10.98.1.0/24,
`tier-vdpa-ovn-nolb` offering), 4 VMs across 2 keypair generations
(`test-vdpa-norbert`/`test-vdpa-fluffy` with the pre-existing `voldemort-root`
keypair whose private key could not be located; `test-vdpa-norbert2`/
`test-vdpa-fluffy2` with a fresh `xhost-test-key` keypair), 2 port-forwarding
rules on the VPC's SourceNat IP `217.179.89.34`. All destroyed/deleted via
`cmk` by end of session; nothing left running.

## Anomaly noted, not investigated

The VPC's auto-created virtual router (`r-1188-VM`) never left `Starting` →
reverted to `Stopped` with only its Control-traffic NIC ever plugged, despite
`cmk start router` returning success. This blocked the originally-planned
external SSH-based verification path (public IP `217.179.89.34` unreachable
even from voldemort, same DC) and is why this audit's evidence is agent-log
+ `ovs-vsctl`/`ip link` based rather than in-guest `ping`/`iperf3` like the
May audits. Possibly the same class of issue as
`2026-05-11-bug-22-vr-tier-nic-dropped.md` (multi-NIC VR start batch
stalling) — not confirmed, not chased further; the vDPA representor-attach
finding above stands on its own regardless of the VR issue.
