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

# Bug 24 — VR non-HW-offload tier NIC plugged to `br-bond` instead of `br-int`; iface-id stamped as raw UUID instead of `lsp-<uuid>` (Bug 14 relapse, VR-side only)

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** HIGH — every multi-tier VPC VR with at least one non-HW-offload (TAP) tier loses connectivity to that tier's gateway and VMs after boot. Bug 23 fix is required-but-not-sufficient.
**Fix commit:** _none yet_

---

## Symptom

After Bug 23 fix (`71dcc1a633`) is deployed, the previously-dropped tier-tap NIC now appears in libvirt domain XML, but it is plugged to `<source bridge='br-bond'/>` instead of `<source bridge='br-int'/>`. Additionally, OVS Interface `external_ids:iface-id` is the raw NIC UUID, not `lsp-<uuid>` — the exact pattern that Bug 14 (`2026-05-10-bug-14-iface-id-prefix.md`, FIXED commit `d85d27f126` + production stamp via manual remediation) was supposed to eliminate.

Concrete evidence on aragog for r-1165-VM (VPC test-20vm-vpc):

```
$ virsh dumpxml r-1165-VM | grep -E '<interface|<source bridge'
    <interface type='bridge'>     <source bridge='cloud0'/>      (Control, OK)
    <interface type='vdpa'>       <source dev='/dev/vhost-vdpa-8'/>   (tap-vdpa, OK)
    <interface type='hostdev' managed='yes'>                     (tap-vf, OK)
    <interface type='bridge'>     <source bridge='br-bond'/>      <-- WRONG, should be br-int

$ ovs-vsctl --columns=name,external_ids list Interface vnet100
name         : vnet100
external_ids : {attached-mac="02:04:02:55:00:14",
                iface-id="e75b9867-0691-4776-86ad-f7847973ef7f",  <-- raw UUID, no lsp- prefix
                iface-status=active,
                vm-id="97cebb55-a68f-4b2e-8545-8f0cdf5f9087"}

$ ovs-vsctl port-to-br vnet100
br-bond  <-- WRONG, should be br-int
```

Connectivity check from VR:
- tier-vdpa gw 10.97.1.1 — ping OK
- tier-vf   gw 10.97.2.1 — ping OK
- tier-tap  gw 10.97.3.1 — **ping 100% loss**
- tier-tap VMs 10.97.3.* — **ping 100% loss**

OVN br-int flow table HAS programmed forward rules for MAC `02:04:02:55:00:14` (table 8 / 30 / 34 / 40 entries match `metadata=0x5 reg15=0xd dl_src=02:04:02:55:00:14`), but vnet100 lives on br-bond — packets never enter the OVN pipeline.

## Root cause

When a VR is booted/restarted, `VpcVirtualNetworkApplianceManagerImpl.finalizeCommandsOnStart` enqueues `PlugNicCommand`s for the non-HW-offload tier(s). The KVM agent's `PlugNicCommandWrapper` resolves the bridge to plug to via the network's broadcast URI and the host's network-traffic-label mapping. For VR-side tier NICs, that resolution returns `br-bond` (the public uplink bridge) instead of `br-int` (the OVN bridge), because the resolver does not detect that the tier is an OVN-managed Guest network.

The mismatch is two-layered:
1. **Bridge resolution**: the wrong physical bridge name is selected for the VR's PlugNic.
2. **Iface-id stamp**: when the NIC lands on br-bond, the post-plug stamp path used for user VMs (`applyPostPlugTunables` from Bug 14 fix commit `8e9b913cb1`) does not apply to VR tier NICs, leaving `iface-id` at the raw UUID. Even if a follow-up reconcile moves vnet100 to br-int, the iface-id stays raw.

## Files involved

| File | Role |
|---|---|
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtPlugNicCommandWrapper.java` | Bridge resolution path for PlugNicCommand on the agent side; does not detect OVN-managed Guest tier when plugging on behalf of a VR. |
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtComputingResource.java` (`applyPostPlugTunables` family) | Post-plug stamp logic — currently fires for user VMs but skips system VMs / VR. Need to extend the dispatch to include VR Guest NICs (NOT Control NIC, which is correctly on cloud0/br-bond). |
| `server/src/main/java/com/cloud/network/router/VpcVirtualNetworkApplianceManagerImpl.java` | Optional: emit a stronger traffic-type hint (e.g. `networkType=OVN_GUEST`) in `PlugNicCommand.network` so the agent disambiguates without re-querying DB. |

## Fix surface (not implemented)

Option A — **agent-side bridge resolution patch**. In `LibvirtPlugNicCommandWrapper.execute(...)`, when the NIC's network has an OVN broadcast URI (e.g. `geneve://`), force bridge=`br-int` and route through `OvnVifDriver.plug(...)` regardless of whether the requesting VM is a user VM or a VR. Mirror the existing user-VM path; the VR-vs-user-VM dispatch should be irrelevant for non-HW-offload tier plug.

Option B — **mgmt-side hint** in `PlugNicCommand.network`. Add a `vnetType` field or repurpose `broadcastUri` to make the agent's job trivial. Less code change in the agent but touches the wire protocol.

Option C — **post-plug stamp extension**. Even after bridge is fixed, ensure `applyPostPlugTunables` (or a new `applyVrTierPostPlugTunables`) iterates VR Guest NICs on `br-int` and rewrites `external_ids:iface-id` to `lsp-<uuid>` matching the OVN LSP UUID. Mirror the proven Bug 14 TAP-tier pattern documented in `2026-05-10-bug-14-iface-id-prefix.md`.

Preferred direction: Options A + C combined. Option B is more invasive and not strictly needed.

## Manual remediation (operator-side, until source fix lands)

Per `2026-05-10-bug-14-iface-id-prefix.md` verification gap remediation:
```bash
# Identify the offending VR NIC on the host where the VR lives
ovs-vsctl --columns=name,external_ids find Interface external_ids:iface-id!=[] | grep <raw-UUID>

# Move from br-bond to br-int
ovs-vsctl del-port br-bond <vnet>
ovs-vsctl add-port br-int <vnet>

# Rewrite iface-id with lsp- prefix
ovs-vsctl set Interface <vnet> external_ids:iface-id=lsp-<uuid> external_ids:iface-status=active

# Verify ARP/ping works to tier gateway from VR
```

NOTE: this is operator-facing remediation only. Do NOT scribe this as an automated fix in production runbooks — the proper path is the source fix.

## Verification (post-fix, not yet executed)

1. `cmk restartVPC id=<vpc-id> cleanup=true` on a multi-tier VPC with at least one non-HW-offload tier.
2. After VR reaches Running, `virsh dumpxml <vr> | grep -E '<source bridge'` should show all Guest tier interfaces on `br-int` (Control stays on `cloud0`).
3. `ovs-vsctl --columns=name,external_ids find Interface ...` for each VR Guest vnet should show `iface-id=lsp-<uuid>`.
4. From VR shell: `ping 10.97.3.1` (or whichever tier-tap gw) must complete with 0% loss.

## References

- `2026-05-10-bug-14-iface-id-prefix.md` — original FIXED `lsp-` prefix bug; this audit captures the relapse on VR-tier path only.
- `2026-05-11-bug-22-vr-tier-nic-dropped.md` — downstream batch-atomicity symptom; ortho­gonal but related.
- `2026-05-11-bug-23-vdpa-iface-lookup.md` — upstream parser fix; required-but-not-sufficient.
