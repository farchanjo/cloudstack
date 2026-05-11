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

# Bug 25 — Pre-existing VMs lose tier connectivity after rolling `cloudstack-agent` restart; only newly-created VMs retain OVN forwarding state

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** MEDIUM — production impact gated on operator action (rolling agent restart triggers); user VMs running before the restart silently lose east-west connectivity. Bug 14 verification gap relapses on every cluster-wide agent restart.
**Fix commit:** _none yet_

---

## Symptom

A rolling restart of `cloudstack-agent` across the 6 data nodes (executed on 2026-05-11 to deploy Bug 23 JAR fix) caused all 20 `test20-*` VMs in VPC `test-20vm-vpc` to become unreachable from the VR `r-1165`. New VMs created in the same session (the 9 `perf-*` VMs) remained reachable.

Concrete reachability matrix from r-1165 (after the restart):
| VM | Tier | Age | Reachable? |
|---|---|---|---|
| perf-vdpa-dst (fluffy) | tap-vdpa | new (today) | ✓ |
| perf-vdpa-2 (fluffy) | tap-vdpa | new (today) | ✓ |
| perf-vf-dst (fluffy) | tap-vf | new (today) | ✓ (initial), later ✗ |
| test20-vdpa-1 (norbert) | tap-vdpa | pre-restart | ✗ |
| test20-vdpa-3 (nagini) | tap-vdpa | pre-restart | ✗ |
| test20-vdpa-5 (nagini) | tap-vdpa | pre-restart | ✗ |
| test20-vf-1 (scabbers) | tap-vf | pre-restart | ✗ |
| test20-vf-2 (scabbers) | tap-vf | pre-restart | ✗ |
| test20-vf-4 (fluffy) | tap-vf | pre-restart | ✗ |
| test20-vf-5 (fluffy) | tap-vf | pre-restart | ✗ |

OVS Interface inspection on the affected hosts shows the OVN representor exists with `attached-mac=<correct>`, `iface-id=lsp-<uuid>`, `iface-status=active`, but is missing the `ovn-installed="true"` flag in `external_ids`. OVN treats those representors as "claimed but not bound" — flows are programmed in the abstract logical pipeline, but the physical OpenFlow ingress action does not direct traffic into the logical port until `ovn-installed=true`.

## Root cause

The `applyPostPlugTunables` post-plug stamp (Bug 14 fix path) writes `iface-id=lsp-<uuid>` + `iface-status=active` + `ovn-installed-ts=<epoch-ms>` on the OVS Interface row when a VM is **freshly plugged**. The stamp logic never re-runs on agent restart for already-running VMs. When the agent process recycles:

1. libvirt domains keep running (VMs unaffected).
2. OVS Interface rows persist with their pre-restart `external_ids`.
3. ovn-controller restarts (driven by ovn-host dependency) and re-syncs OVS Bridge → OVN SB.
4. During the re-sync, ovn-controller is supposed to set `ovn-installed=true` on every Interface whose `iface-id` matches an OVN SB Port_Binding it claims. For some subset of pre-existing interfaces, this set-flag operation is skipped — probably because the Port_Binding's `chassis` field still references the old chassis UUID (or because `iface-status=active` was already set, so the controller decides no work needs to happen).
5. Network forwarding for those interfaces silently breaks until the stamp is reapplied manually or the VM is rebooted (forcing a fresh plug).

The Bug 14 audit `2026-05-10-bug-14-iface-id-prefix.md` (Verification gap section) documents this exact class of failure: `applyPostPlugTunables` had zero callers until commit `8e9b913cb1`. Cold-start production fix only worked because operator manually applied the stamp BEFORE the JAR was built. The current bug is the same gap re-manifesting on agent-restart.

## Files involved

| File | Role |
|---|---|
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtComputingResource.java` (`applyPostPlugTunables` family) | Stamp logic — needs an agent-startup hook that walks every running VM and reapplies the stamp idempotently. |
| `plugins/hypervisors/kvm/src/main/java/com/cloud/agent/Agent.java` (or the startup hook chain) | Currently invokes only `setupServerEnvironment` / `setupResource` paths; missing a reconcile pass for already-running VMs. |
| OVN side: `Port_Binding.chassis` column should be re-claimed on chassis reconnect. May also need a workaround on the ovn-controller side. |

## Fix surface (not implemented)

Option A — **agent-startup reconcile pass**. On `cloudstack-agent` startup, iterate `virsh list --name`, dump each running VM's interface MACs, and reapply `applyPostPlugTunables` (or `applyVdpaPostPlugTunables`) idempotently for each. The stamp logic is already idempotent on the OVS Interface column writes; the cost is one walk per startup, bounded by VM count.

Option B — **persist a stamp watermark**. Write the last successful stamp epoch on each Interface row. Compare against agent build version on startup; if older, reapply. Avoids unnecessary work on every restart.

Option C — **rely on operator-driven `applyPostPlugTunables`** invocation through an admin API endpoint. Last-resort if agent-side reconcile is judged risky. Adds operator runbook overhead.

Preferred direction: Option A. Implementation pattern mirrors `applyVdpaPostPlugTunables` (Bug 16/17 fix `bc76f2a8fc`) but driven from a startup hook instead of from the plug success path.

## Manual remediation (operator-side, until source fix lands)

After every rolling agent restart, run on each data node:

```bash
for vm in $(virsh list --name | grep -E '^i-'); do
  for mac in $(virsh dumpxml $vm | grep -oE '02:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}'); do
    iface=$(ovs-vsctl --columns=name find Interface external_ids:attached-mac=\"$mac\" 2>/dev/null | awk -F': ' '/^name/ {print $2; exit}')
    [ -n "$iface" ] && ovs-vsctl set Interface $iface external_ids:ovn-installed=true
  done
done
# Then bounce ovn-controller to re-claim
systemctl restart ovn-controller
```

This is a tactical remediation; do NOT use it as the long-term solution.

## Verification (post-fix, not yet executed)

1. Create 5 test VMs in tier-vdpa, tier-vf, tier-tap.
2. Stop and start `cloudstack-agent` on each of the 6 data nodes (sequentially).
3. From VR shell, ping each VM. Expected: 0% loss for all 15 VMs.
4. Inspect OVS: every Interface row for the 15 VMs should have `ovn-installed=true`.

## Impact summary

While OPEN, every cluster-wide agent restart drops pre-existing VM east-west connectivity. Production maintenance windows that include `cloudstack-agent` upgrade currently require operator-driven stamp reapplication on each host or full VM reboot to restore connectivity. The 9 perf-* VMs in the current session retained connectivity because they were created AFTER the agent restart that invalidated the older test20-* VMs' stamps.

## References

- `2026-05-10-bug-14-iface-id-prefix.md` (Verification gap section, FIXED) — original instance of this class.
- `2026-05-10-bug-16-17-vdpa-tc-race.md` (commit `bc76f2a8fc`) — the post-plug stamp pattern for vDPA that should be mirrored on startup.
- Project policy `~/dev/dc/CLAUDE.md` — append-only audit log.
