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

# Bug 22 — OBSOLETE: multi-tier VPC VR boots with full NIC complement after Bug 23 + Bug 24 fixes; batch-atomicity defect preserved as future risk surface

**Date:** 2026-05-11
**Status:** OBSOLETE
**Severity:** HIGH (historical) — no longer reproducing in production after upstream root-cause fixes landed.
**Original audit:** `2026-05-11-bug-22-vr-tier-nic-dropped.md`
**Resolved by commits:**
- `71dcc1a633` — `fix(plugins/hypervisors/kvm): recognize <interface type='vdpa'> in MAC lookup` (Bug 23 fix)
- `19fb6cfde4346a8b98e2463b72d2c0310938c815` — `fix(plugins/hypervisors/kvm): plug NIC via OVN-aware vif driver and post-plug stamp` (Bug 24 fix)

---

## Why OBSOLETE

Per the audit log convention documented in `~/dev/cloudstack/docs/audits/README.md`, status `OBSOLETE` applies to bugs whose codepath was later removed or whose symptom is no longer reachable due to upstream/downstream fixes. Bug 22 falls into the second sub-category: the observable symptom (multi-tier VPC VR booting with N-1 NICs) cannot be triggered any more by the conditions captured in the original audit, because the upstream defect that caused `_agentMgr.send` to abort the batch was eliminated by Bug 23.

The original Bug 22 audit explicitly added a `2026-05-11 update` block at the top recognizing that Bug 23 was the upstream root cause. With Bug 23 fixed in `71dcc1a633` and Bug 24 fixed in `19fb6cfde4`, the documented Bug 22 scenario is no longer reachable through the production code path.

### Bug 23 eliminated the trigger

The original Bug 22 trace assumed the agent was SIGKILLed mid-batch. The subsequent investigation captured in the Bug 22 update block and the Bug 23 audit (`2026-05-11-bug-23-vdpa-iface-lookup.md`) re-traced the failure as: the agent was alive; the StartCommand's embedded `SetupGuestNetworkCommand` returned `ExecutionResult(false, "Can not find nic with mac …")` because `LibvirtDomainXMLParser.parseDomainXML` silently produced a phantom `InterfaceDef (mac=null)` for every `<interface type='vdpa'>` element. With `OnError.Stop` semantics, that single failure aborted the remainder of the batch, dropping the non-HW-offload tier `PlugNicCommand`. Bug 23 fix `71bcc1a633` extended the parser to handle `vdpa` (and added a defensive `else` for future types), so `SetupGuestNetwork` now succeeds and the batch no longer aborts.

### Bug 24 closed the per-tier landing-pad gap

Bug 24 fix `19fb6cfde4` made the `LibvirtPlugNicCommandWrapper` route through `selectVifDriverForNic(nic)` and call `applyOvnPostPlugTunables` after `vm.attachDevice(...)`, so the hot-plug path for the non-HW-offload tier lands on `br-int` with `lsp-<uuid>` iface-id and the `ovn-installed=true` stamp. Without this, even if the batch succeeded, the tier-tap NIC would not converge to a working OVN logical port.

Together, Bug 23 + Bug 24 closed both halves of the Bug 22 chain:
- Bug 23: upstream root cause of batch-abort (parser phantom row).
- Bug 24: downstream landing-pad for the hot-plug NIC.

---

## Production verification

Date: 2026-05-11 ~06:10 UTC.

### Cluster state baseline

```
cmk list hosts type=Routing filter=name,state,resourcestate
aragog   Up Enabled
norbert  Up Enabled
fluffy   Up Enabled
nagini   Up Enabled
scabbers Up Enabled
trevor   Up Enabled

md5sum /usr/share/cloudstack-agent/lib/cloud-plugin-hypervisor-kvm-*.jar (aragog)
556b03b837aa251255218387e22c7662  cloud-plugin-hypervisor-kvm-4.24.1.26-SNAPSHOT.jar
```

Six data nodes Up+Enabled with Bug 24 fix JAR (md5 `556b03b837aa251255218387e22c7662`).

### Bug 22 symptom no-longer-reproducing — VR `r-1166-VM` evidence

VR `r-1166-VM` (UUID `175bdbed-d141-4fdb-a81e-572207a6c577`) belongs to the same VPC `test-20vm-vpc` (UUID `a1992656-2a76-43a5-82cc-3c9b7f5402ca`) that produced the original Bug 22 reproduction on `r-1164-VM`. After the `restartVPC cleanup=true makeredundant=false` issued during Bug 24 deploy, `r-1166-VM` came up with the FULL NIC complement.

`cmk -o json list routers name=r-1166-VM` returned 4 NICs:

| Slot | MAC | IP | TrafficType | Network | Gateway |
|---|---|---|---|---|---|
| 0 | `0e:00:a9:fe:62:f4` | 169.254.98.244 | Control | (link-local) | 169.254.0.1 |
| 1 | `02:04:02:53:00:18` | 10.97.1.13 | Guest | tap-vdpa | 10.97.1.1 |
| 2 | `02:04:02:54:00:18` | 10.97.2.23 | Guest | tap-vf | 10.97.2.1 |
| 3 | `02:04:02:55:00:15` | 10.97.3.96 | Guest | tap-tap | 10.97.3.1 |

`virsh dumpxml r-1166-VM` on aragog returned `4` `<interface>` blocks with matching MACs and bridges:

```
<interface type='bridge'>
  <mac address='0e:00:a9:fe:62:f4'/>
  <source bridge='cloud0'/>
<interface type='vdpa'>
  <mac address='02:04:02:53:00:18'/>
<interface type='hostdev' managed='yes'>
  <mac address='02:04:02:54:00:18'/>
<interface type='bridge'>
  <mac address='02:04:02:55:00:15'/>
  <source bridge='br-int'/>          <-- tier-tap on br-int (Bug 24 fix verified)
```

All four NICs are present in libvirt. The non-HW-offload tier (`tap-tap`, MAC `02:04:02:55:00:15`) is the one that was missing in the original Bug 22 trace on `r-1164-VM`. With Bug 23 + Bug 24 deployed, this NIC is now correctly plugged, on `br-int`, and (per Bug 24 audit) carries `external_ids:iface-id=lsp-a2bb2e72-...` plus `external_ids:ovn-installed="true"`.

### Batch processing now reaches `vmGuru.finalizeStart`

Indirect evidence: with `SetupGuestNetwork` no longer failing on vDPA NICs, `_agentMgr.send` in `VirtualMachineManagerImpl.orchestrateStart` no longer throws `AgentUnavailableException` for this configuration; `vmGuru.finalizeStart` at line 1580 runs to completion; the post-start `PlugNicCommand` for the tier-tap NIC executes and lands on `br-int`.

---

## Why the batch-atomicity defect is preserved as an OPEN class

Bug 22's underlying batch-atomicity hole — `_agentMgr.send` sending a large `Commands` object with `OnError.Stop`, allowing a single mid-batch failure to drop the tail without rollback — **remains in the code**. It is no longer **trigger-reachable through the documented Bug 22 scenario**, but it WILL re-surface if:

- a future post-start command legitimately fails (slow `ovs-vsctl add-port` on a contended OVSDB, agent restart mid-batch, kernel deadlock, network partition);
- new tier types are added that introduce new failure modes inside the batch;
- a future parser regression (e.g. for a libvirt interface type beyond `vdpa`) reintroduces the same phantom-row class.

The patch surface enumerated in the original Bug 22 audit (under "Files that hold the patch surface") remains valid as future-work for any of the above. This audit does NOT remove that recommendation; it only marks the **specific Bug 22 incident** as OBSOLETE because the documented trigger is no longer reachable.

### Watch-list for future audits

Re-open Bug 22 with a NEW audit file (do NOT edit this one — append-only) if any of the following are observed:

1. A multi-tier VPC VR booting with N-1 NICs in `virsh dumpxml` while `cmk list routers ... | jq .nic[]` returns N. The diagnostic check is: `cmk list routers name=<vr> -o json | jq '.router[0].nic | length'` vs `virsh dumpxml <vr> | grep -c '<interface'`. Drift = bug class re-surfaced.
2. Mgmt log `Seq <N>: Timed out on null` followed by `ClusteredVirtualMachineManagerImpl: VM <vr> is sync-ed to at Running state according to power-on report` for a VR start (the reconcile-by-power-report path that hides partial-batch failure).
3. Mgmt log shows `Answer[StartAnswer{success:true}, null, null, …]` for a VR start — partial-answer pattern.

Any of these would indicate the same atomicity hole firing under a new trigger, and would warrant a new audit file scoped to the new failure path.

---

## Cross-references

- `2026-05-11-bug-22-vr-tier-nic-dropped.md` — original Bug 22 audit (status preserved as OPEN per append-only convention; this file supersedes the OPEN status by declaring the trigger no longer reachable).
- `2026-05-11-bug-23-vdpa-iface-lookup.md` — Bug 23 audit, FIXED commit `71cc1a633`, root cause of Bug 22 batch abort.
- `2026-05-11-bug-24-FIX.md` — Bug 24 FIX, commit `19fb6cfde4`, downstream landing-pad for non-HW-offload tier hot-plug.
- `2026-05-10-bug-14-iface-id-prefix.md` — Bug 14 verification gap is the methodological precedent for "fix the trigger, hold the class open for future regressions".
- `~/dev/cloudstack/docs/audits/README.md` — audit index; this file's index row uses the OBSOLETE state.

---

## Lessons

- **OBSOLETE is not WONTFIX**. The defect class is still real; only the documented incident is closed. Re-running the original reproduction (multi-tier VPC VR boot with mixed HW-offload + non-HW-offload tiers) will succeed because the trigger is gone, NOT because the batch handler became robust.
- **Bug-chain audits compound**. Bug 22 needed two upstream fixes (23 + 24) to render its symptom unreachable. Future bug investigations should look for the FIRST observable failure in the batch trace rather than the LAST observable artifact — they are not always the same defect.
- **Verification of OBSOLETE status requires positive evidence in production**. Reading source diffs alone is insufficient; a fresh reproduction attempt that fails to reproduce is required (here: `r-1166-VM` boot with all 4 NICs after `restartVPC cleanup=true`).
