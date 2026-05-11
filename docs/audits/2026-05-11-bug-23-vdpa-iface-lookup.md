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

# Bug 23 — `LibvirtDomainXMLParser` drops `<interface type='vdpa'>` into a phantom mac=null entry; deterministic multi-tier VPC VR boot failure

**Date:** 2026-05-11
**Status:** FIXED
**Fix commit:** `71dcc1a633`
**Severity:** HIGH — every multi-tier VPC VR with at least one vDPA tier and at least one non-HW-offload TAP tier deterministically fails the StartCommand SetupGuestNetwork. The batch then aborts via `OnError.Stop`, leaving the non-HW-offload tier `PlugNicCommand` unprocessed (downstream symptom = Bug 22).

---

## Symptom (verbatim user evidence)

VR `r-1165-VM` (UUID `97cebb55-a68f-4b2e-8545-8f0cdf5f9087`), VPC `test-20vm-vpc`
(UUID `a1992656-2a76-43a5-82cc-3c9b7f5402ca`), zone Slytherin, host aragog.

Agent log on aragog at `2026-05-11 05:06:21,244` (verbatim):

```
SetupGuestNetwork: looking for mac=02:04:02:53:00:17 in VM r-1165-VM (3 interfaces found)
SetupGuestNetwork: found interface type=hostdev mac=02:04:02:54:00:17 dev=null pciAddr=0000:01:02.5
ERROR [resource.virtualnetwork.VirtualRoutingResource] Failed to prepare VR command due to
Can not find nic with mac 02:04:02:53:00:17 for VM r-1165-VM
LibvirtStopCommandWrapper: HwOffload: removed intent state for stopped VR r-1165-VM
```

`virsh dumpxml r-1165-VM` produced three `<interface>` blocks (bridge + vdpa +
hostdev) including the live `<interface type='vdpa'>` with the requested MAC.
The vDPA NIC `02:04:02:53:00:17` had been successfully plugged 53 seconds
earlier:

```
OvnVdpaVifDriver.plug: name=vdpa-020402530017 pci=0000:01:01.5 pf=dx6p0
  mac=02:04:02:53:00:17 rep=dx6p0vf11 lsp=lsp-9760d068-... vhost=/dev/vhost-vdpa-8
```

The parser saw three NIC elements, but only emitted one `found interface`
line for `type=hostdev`. The bridge entry was iterated (and matched against a
different MAC), the hostdev entry was logged, and the **third entry (vdpa)
was silently skipped by the null-mac guard** inside `prepareNetworkElementCommand(SetupGuestNetworkCommand)`.

---

## Root cause

`plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtDomainXMLParser.java`
lines 223–288 walked every `<interface>` element and matched only on five
type attributes:

```java
NodeList nics = devices.getElementsByTagName("interface");
for (int i = 0; i < nics.getLength(); i++) {
    Element nic = (Element)nics.item(i);
    String type = nic.getAttribute("type");
    String mac = getAttrValue("mac", "address", nic);
    ...
    InterfaceDef def = new InterfaceDef();
    ...
    if (type.equalsIgnoreCase("network")) {
        def.defPrivateNet(...);
    } else if (type.equalsIgnoreCase("bridge")) {
        def.defBridgeNet(...);
    } else if (type.equalsIgnoreCase("ethernet")) {
        def.defEthernet(...);
    } else if (type.equals("vhostuser")) {
        def.setDpdkSourcePort(...);
    } else if (type.equalsIgnoreCase("hostdev")) {
        def.defHostdevNet(pciAddr, mac, 0);
    }
    // FALL-THROUGH for type='vdpa': def stays empty (mac=null, netType=null)
    ...
    interfaces.add(def);   // phantom row added with all fields null
}
```

When the parser encountered `<interface type='vdpa'>`, none of the five
branches matched. `def` stayed in its zero-initialized state — `_netType=null`,
`_macAddr=null`, `_sourceName=null`. The row was still appended at line 287.

Downstream code at
`plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtComputingResource.java`
line 2856–2876 (`prepareNetworkElementCommand(SetupGuestNetworkCommand)`)
iterates the parsed list and matches by MAC:

```java
for (final InterfaceDef pluggedNic : pluggedNics) {
    LOGGER.info("SetupGuestNetwork: found interface ...", ...);
    if (pluggedNic.getMacAddress() == null) {
        continue;                       // phantom vdpa row dropped here
    }
    if (pluggedNic.getMacAddress().equalsIgnoreCase(nic.getMac())) {
        routerNic = pluggedNic;
        break;
    }
}
if (routerNic == null) {
    return new ExecutionResult(false, "Can not find nic with mac " + nic.getMac() + " for VM " + routerName);
}
```

The phantom row (mac=null) was silently skipped by the `getMacAddress() == null`
guard, the bridge row (mac=`0e:00:a9:fe:e6:f3`) did not match the requested
vDPA MAC, and the hostdev row (mac=`02:04:02:54:00:17`) was iterated but did
not match either. The lookup returned `null`, the wrapper returned a failed
`ExecutionResult`, the batch `Commands cmds` saw `OnError.Stop`, and every
subsequent post-start command (`SetupGuestNetwork` for the other tiers,
`PlugNicCommand` for the non-HW-offload tier, `AggregationControl`,
`IpAssoc`, `ACL`) was aborted.

`LibvirtStopCommandWrapper` then stopped the VR, the StartCommand was
retried by the orchestrator, and the loop repeated indefinitely.

The defect is parser-side, not lookup-side. The lookup loop is correct and
defensive (the null-mac guard prevents NPE on the phantom row); the parser
must not produce a phantom row in the first place.

---

## Why this surfaced now (timeline context)

- Pre-vDPA fork: only bridge / hostdev / network NICs were used; the parser
  surface was complete.
- vDPA introduced via `OvnVdpaVifDriver` (commit history shows
  `bc76f2a8fc` deferring iface-status to post-VM-running). The emission side
  (`<interface type='vdpa'>` in `LibvirtVMDef.InterfaceDef.toString`) was
  fully wired; the parse-back side was never updated.
- Single-tier vDPA VPCs would have hit a different failure mode (no
  `SetupGuestNetwork` because no Control/Guest split). The defect surfaces
  only when SetupGuestNetwork is called against a VR that has a vDPA NIC,
  i.e. multi-tier VPC VRs with mixed tier types.
- The matrix of multi-tier VPC offerings with vDPA + non-HW-offload TAP
  arrived via the OVN fork's tiered networking work; the parser gap was
  exercised the moment Bug 22's mixed-tier configuration was used.

---

## Fix

Add a `vdpa` branch that calls `InterfaceDef.defVdpaNet(vhostDevPath, mac,
null)` mirroring `OvnVdpaVifDriver.plug` emission. Read the
`<source dev='/dev/vhost-vdpa-N'/>` attribute as the source path. Pass
`null` for queues — the parser cannot recover the original queue count
from libvirt (queues are an emit-time decision, not a domain-state field
libvirt round-trips).

Add a defensive `else` branch that logs the unrecognized type and skips
the `interfaces.add(def)` call. This protects future libvirt types
(vhost-user-blk, mdev, ...) from re-introducing the same phantom-row
class of bug: an unrecognized type now produces a WARN log entry, not a
silent runtime failure deep in the call graph.

File changed:
`plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtDomainXMLParser.java`

Diff (3-line core fix + 7-line defensive else + comments, 20 insertions total):

```diff
@@ -269,6 +269,26 @@ public class LibvirtDomainXMLParser {
                             hFunc != null ? hFunc.replace("0x", "") : "0");
                     def.defHostdevNet(pciAddr, mac, 0);
+                } else if (type.equalsIgnoreCase("vdpa")) {
+                    // <interface type='vdpa'>
+                    //   <source dev='/dev/vhost-vdpa-N'/>
+                    //   <mac address='...'/>
+                    //   <model type='virtio'/>
+                    // </interface>
+                    // Without this branch the parser produces a phantom
+                    // InterfaceDef with mac=null, causing MAC-based lookups
+                    // (SetupGuestNetworkCommand, hot-plug correlation, OVN
+                    // VR tier resolution) to silently skip the NIC and fail
+                    // with "Can not find nic with mac ...".
+                    String vhostDevPath = getAttrValue("source", "dev", nic);
+                    def.defVdpaNet(vhostDevPath, mac, null);
+                } else {
+                    // Unknown <interface type='...'>. Skip the add so a
+                    // phantom InterfaceDef (mac=null, type=null) does not
+                    // leak into the parsed list and hide a real entry from
+                    // MAC-based lookups downstream.
+                    logger.warn("LibvirtDomainXMLParser: skipping unrecognized <interface type='{}'> (mac={}) - downstream lookups would miss this NIC", type, mac);
+                    continue;
                 }
                 String multiQueueNumber = getAttrValue("driver", "queues", nic);
```

Test added:
`plugins/hypervisors/kvm/src/test/java/com/cloud/hypervisor/kvm/resource/LibvirtDomainXMLParserVdpaTest.java`
(four `@Test` cases against canonical VR libvirt XML: assert parse exposes
the vDPA interface, MAC lookup resolves it, no phantom null-mac entries leak,
bridge + hostdev still parse alongside vDPA).

---

## Verification

### Build

```
Host: aragog (10.182.0.21)
Source tree synced via ~/dev/dc/sync-aragog.sh --apply (commit 71dcc1a633)
Compile: mvn -T 4 -Pdeveloper -pl plugins/hypervisors/kvm -am compile -DskipTests
         -Dmaven.javadoc.skip=true -Dcheckstyle.skip
         BUILD SUCCESS, 19.164 s wall clock
Package: mvn -T 4 -Pdeveloper -pl plugins/hypervisors/kvm package -DskipTests
         -Dmaven.javadoc.skip=true -Dcheckstyle.skip
         BUILD SUCCESS, 8.095 s wall clock
```

Artifact:

| JAR | md5 |
|-----|-----|
| `cloud-plugin-hypervisor-kvm-4.24.1.26-SNAPSHOT.jar` | `36dc5a92595ecb2e001ad590666a35e2` |

Previous JAR (Bugs 16/17/18/19, no Bug 23 fix): `03bd999b4b072cf8790c806d6a8ffcc1`.

### Deploy

Direct `scp` aragog → 5 peers over mgmt VLAN (`10.182.0.0/24`, trusted),
then individual MCP-driven `systemctl restart cloudstack-agent` on each
host. md5 verified on every host:

| Host | IP | md5 | service |
|------|----|----|---------|
| nagini   | 10.182.0.24 | `36dc5a92595ecb2e001ad590666a35e2` | active |
| scabbers | 10.182.0.25 | `36dc5a92595ecb2e001ad590666a35e2` | active |
| trevor   | 10.182.0.26 | `36dc5a92595ecb2e001ad590666a35e2` | active |
| fluffy   | 10.182.0.23 | `36dc5a92595ecb2e001ad590666a35e2` | active |
| aragog   | 10.182.0.21 | `36dc5a92595ecb2e001ad590666a35e2` | active |
| norbert  | 10.182.0.22 | `36dc5a92595ecb2e001ad590666a35e2` | active |

Backups: `cloud-plugin-hypervisor-kvm-4.24.1.26-SNAPSHOT.jar.bak.<TS>` on each
host (md5 `03bd999b4b072cf8790c806d6a8ffcc1`).

### Smoke test — VR boot

VR `r-1165-VM` was in `Stopped` state with `removed intent state for stopped
VR` recorded in `agent.log`. Issued `cmk startRouter
id=97cebb55-a68f-4b2e-8545-8f0cdf5f9087` on voldemort. API returned the full
router payload (no errorcode 530). State `Running` on host `aragog`.

### Smoke test — libvirt domain XML

`virsh dumpxml r-1165-VM` on aragog (post-fix) shows **4 `<interface>` blocks**:

| Slot | Type | MAC | Source / PCI | Tier |
|------|------|-----|--------------|------|
| 0 | bridge | `0e:00:a9:fe:e6:f3` | bridge=`cloud0`, dev=`vnet99` | Control |
| 1 | vdpa | `02:04:02:53:00:17` | dev=`/dev/vhost-vdpa-8` | tier-vdpa |
| 2 | hostdev managed='yes' | `02:04:02:54:00:17` | PCI=`0000:01:03.2` | tier-vf |
| 3 | bridge | `02:04:02:55:00:14` | bridge=`br-bond`, dev=`vnet100` | tier-tap |

`virsh domiflist r-1165-VM`:

```
Interface   Type      Source              Model    MAC
-----------------------------------------------------------------------
vnet99      bridge    cloud0              virtio   0e:00:a9:fe:e6:f3
-           vdpa      /dev/vhost-vdpa-8   virtio   02:04:02:53:00:17
-           hostdev   -                   -        02:04:02:54:00:17
vnet100     bridge    br-bond             virtio   02:04:02:55:00:14
```

The previously dropped tier-tap NIC (`02:04:02:55:00:14`) is now present.
The grep `<interface type=` count = 4. Before the fix, `count = 3`.

### Smoke test — agent log

Post-fix agent log on aragog (logid `de345144`):

```
2026-05-11 05:25:02,908 OvnVdpaVifDriver.plug: name=vdpa-020402530017 pci=0000:01:04.5
  pf=dx6p1 mac=02:04:02:53:00:17 rep=dx6p1vf3 lsp=lsp-9760d068-... vhost=/dev/vhost-vdpa-8

2026-05-11 05:26:04,167 SetupGuestNetwork: looking for mac=02:04:02:53:00:17
  in VM r-1165-VM (3 interfaces found)
2026-05-11 05:26:04,167 SetupGuestNetwork: found interface type=bridge
  mac=0e:00:a9:fe:e6:f3 dev=vnet99 pciAddr=null
2026-05-11 05:26:04,167 SetupGuestNetwork: found interface type=vdpa
  mac=02:04:02:53:00:17 dev=null pciAddr=null     <-- FIX VISIBLE

2026-05-11 05:26:06,657 SetupGuestNetwork: looking for mac=02:04:02:54:00:17
  in VM r-1165-VM (3 interfaces found)
2026-05-11 05:26:06,657 SetupGuestNetwork: found interface type=bridge
  mac=0e:00:a9:fe:e6:f3 ...
2026-05-11 05:26:06,657 SetupGuestNetwork: found interface type=vdpa
  mac=02:04:02:53:00:17 ...
2026-05-11 05:26:06,657 SetupGuestNetwork: found interface type=hostdev
  mac=02:04:02:54:00:17 dev=null pciAddr=0000:01:03.2

2026-05-11 05:26:09,833 SetupGuestNetwork: looking for mac=02:04:02:55:00:14
  in VM r-1165-VM (4 interfaces found)   <-- 4 NOT 3
2026-05-11 05:26:09,833 SetupGuestNetwork: found interface type=bridge
  mac=0e:00:a9:fe:e6:f3 ...
2026-05-11 05:26:09,833 SetupGuestNetwork: found interface type=vdpa
  mac=02:04:02:53:00:17 ...
2026-05-11 05:26:09,833 SetupGuestNetwork: found interface type=hostdev
  mac=02:04:02:54:00:17 ...
2026-05-11 05:26:09,833 SetupGuestNetwork: found interface type=bridge
  mac=02:04:02:55:00:14 dev=vnet100 pciAddr=null  <-- tier-tap PRESENT
```

No `Can not find nic with mac` ERROR in the post-fix log. The PlugNicCommand
for tier-tap (`02:04:02:55:00:14`) was issued and matched libvirt's
`<interface type='bridge' source bridge='br-bond'>` entry, the SetupGuestNetwork
for that NIC succeeded, and the VR booted with all four NICs visible to
libvirt and to the API.

---

## Surface protected by the fix

The same `getInterfaces(conn, vmName)` call path is consumed by every
MAC-based libvirt lookup in `LibvirtComputingResource`:

- `prepareNetworkElementCommand(SetupGuestNetworkCommand)` line 2853 —
  fixed (positive trace above).
- `prepareNetworkElementCommand(SetSourceNatCommand)` line 2905 — same
  pattern, iterates all interfaces and matches by MAC. Bug 23 fix is the
  prerequisite for SNAT IP rewiring on VRs that own a vDPA NIC.
- `prepareNetworkElementCommand(IpAssocCommand)` line 3588 — same pattern.
- `applyOvnPostPlugTunables` / `applyVdpaPostPlugTunables` — already
  exercised (Bug 16/17 fix), but their input list comes from the same
  parser. Without this Bug 23 fix, post-migrate stamp on a vDPA NIC
  would silently no-op because the parser would drop the row.
- `getInterface(conn, vmName, macAddress)` line 5930 — single-NIC lookup
  helper used by hot-plug correlation.
- `cleanupVMNetworks` line 5894 — iterates parsed interfaces for unplug.

All six surfaces previously had the same latent failure for any operation
that touched a vDPA NIC by MAC. The fix lands once at the parser and heals
all six.

---

## Cross-references

- `2026-05-11-bug-22-vr-tier-nic-dropped.md` — captures the **downstream**
  consequence of Bug 23. When the SetupGuestNetwork in the StartCommand batch
  fails (Bug 23), `Commands cmds` is configured with `OnError.Stop`, so the
  whole tail of the batch (including the `PlugNicCommand` for the
  non-HW-offload tier) is aborted; the management server later reconciles
  `Starting -> Running` based on libvirt power-state alone and the missing
  NIC stays missing. Bug 22 batch-atomicity defect remains a separate
  systemic risk but does not block Bug 23 fix verification — the
  StartCommand SetupGuestNetwork now succeeds, so `OnError.Stop` is never
  triggered for this configuration.
- `2026-05-10-bug-16-17-vdpa-tc-race.md` — first audit to surface
  vDPA-tier-specific defects in the OVN fork. Bug 16/17 fixed the
  `iface-status` deferred-stamp path, but did not touch the parser; the
  parser gap remained latent until Bug 22 evidence forced a deeper trace.
- `2026-05-10-bug-14b-and-15-migration.md` — `dispatchPostMigrateOvnStamp` in
  `VirtualMachineManagerImpl` triggers `applyPostPlugTunables` on the
  migration destination. With the parser dropping vDPA rows, post-migrate
  stamps on a vDPA NIC would silently no-op on the destination host. Bug 23
  is the prerequisite for that path to actually function on vDPA NICs.
- `2026-05-09-ovn-fork-audit.md` — original fork audit. The parser file was
  in scope but the gap was not surfaced because the audit corpus at the time
  was single-tier VPCs.

---

## Skip list for future audits

- Do NOT re-flag `prepareNetworkElementCommand(SetupGuestNetworkCommand)` line
  2856 null-mac guard (`if (pluggedNic.getMacAddress() == null) continue;`).
  The guard is correct and defensive; it remains in place to protect
  against any future parser regression.
- Do NOT re-flag `defVdpaNet(vhostDevPath, mac, null)` for passing `null`
  queues. Queues are emit-time-only; libvirt does not round-trip the original
  caller-requested value, so the parser cannot recover it. Setting `null`
  preserves the existing `setMultiQueueNumber(...)` post-call which reads
  `driver/queues` if present in the XML.
- DO re-flag any new `<interface type='X'>` libvirt type that lands in the
  emitter without a matching parser branch. The defensive `else` now emits a
  WARN line `LibvirtDomainXMLParser: skipping unrecognized <interface type='X'>`
  — that log line should fail CI / surface a build alarm.

---

## Lessons

- **Parsers must be symmetrical with emitters.** Every `defXxxNet(...)`
  helper in `InterfaceDef` must have a corresponding `else if
  (type.equalsIgnoreCase("xxx"))` branch in `LibvirtDomainXMLParser`. The
  emit-only paths (`defVhostUserNet`, `defDirectNet`) are also affected; the
  defensive `else` now catches them too.
- **A null-field guard hides a parser bug.** The lookup-loop's
  `if (mac == null) continue;` was the right thing to do for safety (no
  NPE), but it masked a parser-side defect for months. Pair every null
  guard with a log-then-skip and a counter so the masking is visible.
- **Phantom rows scale with NIC type diversity.** The OVN fork's tiered
  networking added two new libvirt types (`vdpa` from
  `OvnVdpaVifDriver`, plus an extant `hostdev` from `OvnVfPassthroughVifDriver`).
  Every new type that lands without a parser branch creates a phantom row
  that masks any real row sharing its position in the iteration. The
  defensive `else` now upgrades this from "silent runtime failure deep in
  the call graph" to "WARN at parse time with the unrecognized type name".
- **Subset of the OVN fork affected — but the parser is universal.** This
  parser is in the core KVM plugin, not the OVN plugin. Any caller of
  `LibvirtComputingResource.getInterfaces(...)` benefits. Future
  contributions to vDPA / vhost-user-blk / mdev hardware paths inherit
  this fix automatically.
