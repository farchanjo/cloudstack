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

# Bug 26 — Live-migration destination OVS Interface stamped with `ovn-installed-ts` but NOT `ovn-installed=true` flag → migrated VM unreachable (Bug 14b partial regression)

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** HIGH — every live-migration of a VM with a vDPA NIC (and likely VF and TAP NICs too) lands on the destination host with the OVS Interface row partially stamped: `iface-id=lsp-<uuid>`, `iface-status=active`, and `ovn-installed-ts=<epoch>` are present, but the critical `ovn-installed=true` flag is absent. OVN never directs traffic into the logical port. The migrated VM is silently disconnected.
**Fix commit:** _none yet_

---

## Symptom

`cmk migrate virtualmachine id=<perf-vdpa-2-uuid> hostid=<norbert-id>` succeeded (DB state Running on norbert). Libvirt domain on norbert came up healthy. Mgmt API showed `state=Running hostname=norbert lastupdated=<now>`. But:

```
$ ssh -p 3922 VR "ping -c5 10.97.1.62"   # perf-vdpa-2 IP, post-migration
PING 10.97.1.62 (10.97.1.62): 56 data bytes
--- 10.97.1.62 ping statistics ---
5 packets transmitted, 0 packets received, 100% packet loss
```

OVS Interface inspection on the destination host (norbert) for the migrated VM's vDPA representor:

```
$ ovs-vsctl --columns=name,external_ids find Interface external_ids:attached-mac='"02:04:02:53:00:14"'
name         : dx6p1vf5
external_ids : {attached-mac="02:04:02:53:00:14",
                iface-id=lsp-92a30b54-576e-4b4f-9a66-77890f405224,    <-- lsp- prefix ✓
                iface-status=active,                                   <-- active ✓
                ovn-installed-ts="1778478045957"}                      <-- ts only
```

Compare with a working pre-existing VM (perf-vdpa-dst on fluffy, never migrated):

```
external_ids : {attached-mac="02:04:02:53:00:16",
                iface-id=lsp-50e6cade-...,
                iface-status=active,
                ovn-installed="true",                                  <-- THIS FLAG
                ovn-installed-ts="..."}
```

**Missing `ovn-installed="true"`** in the migrated case. ovn-controller on the destination host installed the timestamp but did not flip the boolean flag.

## Root cause

The Bug 14b migration audit (`2026-05-10-bug-14b-and-15-migration.md`, FIXED commits `ff59e27753` Layer A + `0d91ba8a3f` Layer B agent + `d34f9fe190` Layer B mgmt `dispatchPostMigrateOvnStamp`) added a post-migrate stamp dispatch from `VirtualMachineManagerImpl` after live-migration completes. The dispatched stamp issues a write to `external_ids:iface-id` + `iface-status=active` on the destination OVS Interface row. It does NOT set `ovn-installed=true`.

The `ovn-installed` flag is normally written by `ovn-controller` itself when it claims a logical port for a chassis after the Interface is bound. For freshly-plugged interfaces, the timing works: plug → ovn-controller observes → claim → set `ovn-installed=true`. For migrated interfaces, the stamp arrives AFTER ovn-controller has already observed the Interface (because libvirt brings the dest interface up before mgmt issues the stamp). ovn-controller observes a row that already has `iface-id` set, so it does NOT re-trigger the bind sequence — it only updates the timestamp.

Result: the row has `iface-id` + `ts` but no `ovn-installed=true`. Forwarding broken.

## Files involved

| File | Role |
|---|---|
| `engine/orchestration/src/main/java/com/cloud/vm/VirtualMachineManagerImpl.java` (`dispatchPostMigrateOvnStamp` introduced by `d34f9fe190`) | Currently writes iface-id + iface-status but NOT ovn-installed flag. |
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtPostMigrateOvnStampCommandWrapper.java` (or similar — Bug 14b Layer B agent commit `0d91ba8a3f`) | Agent-side stamp executor that ultimately writes the OVS columns. |
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtComputingResource.java` | Helper that builds the `ovs-vsctl set Interface` invocation; needs to add `ovn-installed=true` to the set list. |

## Fix surface (not implemented)

Option A — **stamp `ovn-installed=true` directly from agent** during the post-migrate hook. Single-line change in the OVS set command:
```bash
ovs-vsctl set Interface <rep> \
    external_ids:iface-id=lsp-<uuid> \
    external_ids:iface-status=active \
    external_ids:ovn-installed=true \              <-- add
    external_ids:ovn-installed-ts=$(date +%s%3N)
```

Option B — **trigger ovn-controller re-bind** via `ovn-appctl bind-port <port-uuid>` (or equivalent) instead of stamping flags directly. More involved, depends on ovn-controller version.

Option C — **wait + retry** in the agent stamp logic — observe the Interface row, if `ovn-installed != true` after 2 s, force-set it. Pragmatic but masks the underlying ordering issue.

Preferred direction: Option A. Single-line patch in the post-migrate stamp helper, mirroring how freshly-plugged interfaces get the flag from ovn-controller (we shortcut the controller and set it ourselves; controller will not unset it). Add JUnit coverage that asserts the `ovn-installed=true` key-value is present in the captured `ovs-vsctl set` argv.

## Manual remediation (operator-side, until source fix lands)

```bash
# Identify destination representor + flip flag
ovs-vsctl set Interface <rep> external_ids:ovn-installed=true
# Bounce ovn-controller if remains silent (rare)
systemctl restart ovn-controller
```

Verify reachability returns.

## Verification (post-fix, not yet executed)

1. Pick a vDPA-tier VM, e.g. `perf-vdpa-2`. Note current host.
2. Start a background `ping -i 0.2 -c 600 <vm-ip>` from the VR.
3. `cmk migrate virtualmachine id=<vm-id> hostid=<dest-host-id>`.
4. After migrate completes, inspect ping log. Acceptable: <500 ms gap during the actual switchover. Unacceptable: >5 s loss or 100% loss tail.
5. On destination host, `ovs-vsctl --columns=external_ids find Interface external_ids:attached-mac=\"<vdpa-mac>\"` must show `ovn-installed=true`.

## Impact summary

While OPEN, live-migration breaks east-west connectivity for the migrated VM. Workaround possible (operator stamp flip), but cuts against the value of live-migrate. Bug 26 must be fixed before live-migration can be relied on in production maintenance flows. Bug 14b verification on 2026-05-10 was likely insufficient — the `ovn-installed=true` flag presence was not asserted in the test acceptance.

## References

- `2026-05-10-bug-14b-and-15-migration.md` (FIXED Layer A/B agent/B mgmt) — original live-migration audit; this is its partial regression with one specific missing field.
- `2026-05-11-bug-25-old-vm-unreachable-after-agent-restart.md` — sister bug for the same `ovn-installed=true` missing pattern after agent restart (not migrate).
- Project policy `~/dev/dc/CLAUDE.md` — `cmk` API for all mutations; en-US; append-only audits.
