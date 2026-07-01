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

# Bug 28 FIX — verified on norbert + fluffy (pilot)

**Date:** 2026-07-01
**Status:** FIXED (norbert + fluffy pilot) — 4 remaining data nodes NOT yet patched
**Fix commit:** `88196343a4` (code), `f22375a6ac` (docs)
**Severity:** was CRITICAL

---

## Fix

`LibvirtComputingResource.configureVifDrivers()` now reads `agent.properties`
directly (same `PropertiesUtil.loadFromFile` pattern already used for the HW
offload uplink config a few lines below) and resolves the OVN integration
bridge with the same precedence `VxlanTunnelManager.resolveBridge()` uses —
`network.bridge.name` → `guest.bridge.name` → `guest.network.device` — then
`params.put(OvnVifDriver.PROP_INTEGRATION_BRIDGE, ...)` before any OVN-aware
VIF driver (`OvnVifDriver`, `OvnVfPassthroughVifDriver`, `OvnVdpaVifDriver`)
is constructed. Root cause and full derivation:
`2026-07-01-bug-28-root-cause-confirmed.md`.

## Build + deploy (jar-direct, per updated `infra-base/CLAUDE.md` standing rule)

- Built on aragog from `/root/cloudstack` @ `f22375a6ac`:
  `mvn -pl plugins/hypervisors/kvm -am -Pdeveloper -DskipTests -Dcheckstyle.skip=true clean install`
  → BUILD SUCCESS, then `mvn -pl plugins/hypervisors/kvm -DskipTests package`
  → new jar md5 `7bf1a9da3ef04ca275946d2533c6394d`.
- Test run (`mvn -pl plugins/hypervisors/kvm test`): 725 tests, 7 errors — confirmed
  **pre-existing and unrelated** by checking out the pre-fix
  `LibvirtComputingResource.java` (`1ecb4ac481`) and re-running the same scoped
  test classes: identical 7 failures (6 `LibvirtComputingResourceTest` NPEs
  unrelated to VIF driver config, 1 `OvnVfPassthroughVifDriverOrphanRepTest`
  Mockito matcher issue). The patch introduces zero new failures.
- Deployed via jar-swap (not a `.deb` — see `infra-base/CLAUDE.md` "jar-direct
  deploy until explicitly promoted to `.deb`" standing rule, added this session):
  backed up the old jar with a timestamp suffix, copied the new one to
  `/usr/share/cloudstack-agent/lib/cloud-plugin-hypervisor-kvm-4.24.1.26-SNAPSHOT.jar`
  on norbert and fluffy, md5-verified the copy, `systemctl restart cloudstack-agent`
  on each, confirmed `active`.

## Verification — representor now genuinely attaches

Deployed 2 fresh vDPA-tier VMs (`bug28-norbert` on norbert, `bug28-fluffy` on
fluffy, same `tier-vdpa-ovn-nolb` pattern as prior passes) via `cmk` only.

Agent log — **zero WARN lines this time** (compare to every prior pass in
this bug's history, which always logged `ovs-vsctl ... failed` /
`no bridge named br-int`):

```
applyVdpaPostPlugTunables: rep=dx6p1vf25 mac=02:04:02:62:00:01 vm=i-2-1198-VM
  iface-status=active ovn-installed=true (TC chain-0+1 race window closed; Bug-26 flag stamped)
```

`ovs-vsctl` on both hosts confirms the representor is genuinely in
`br-overlay` with correct state (previously: not in ANY bridge at all,
despite the identical-looking log line):

```
# norbert
$ ovs-vsctl list-ports br-overlay | grep dx6p1vf25
dx6p1vf25
$ ovs-vsctl get interface dx6p1vf25 external_ids
{attached-mac="02:04:02:62:00:01", iface-id=lsp-9df82f0e-..., iface-status=active,
 ovn-installed="true", ovn-installed-ts="1782875597956"}

# fluffy
$ ovs-vsctl list-ports br-overlay | grep dx6p0vf18
dx6p0vf18
$ ovs-vsctl get interface dx6p0vf18 external_ids
{attached-mac="02:04:02:62:00:02", iface-id=lsp-0bbbf810-..., iface-status=active,
 ovn-installed="true", ovn-installed-ts="1782875606151"}
```

This closes the original cross-host vDPA verification gap all the way back
to `2026-05-10-bug-16-17-vdpa-tc-race.md` — the representor attach step that
every prior "PASS" implicitly assumed was working is now actually verified
working, on real hardware, with the actual fix in place.

## State left behind

- norbert + fluffy: patched jar live, `cloudstack-agent` active on both,
  `openjdk-17-jdk-headless` still installed on norbert (harmless, from the
  root-cause debug session), `JAVA_DEBUG` confirmed empty.
- Test VPC `bug28-verify`, tier `bug28-tier`, both test VMs: destroyed and
  deleted via `cmk`. One `destroy virtualmachine` on the fluffy VM showed as
  stuck in `Stopping` for ~90s in `cmk list virtualmachines` before a
  follow-up `destroy ... forced=true` returned `unable to find a virtual
  machine` — i.e. it had actually already completed, the list output was
  momentarily stale. Not investigated further; noted here in case it
  recurs and turns into its own bug.
- **aragog, nagini, scabbers, trevor are NOT patched.** This was an explicit
  norbert+fluffy-only pilot. Roll out to the remaining 4 data nodes only on
  explicit request — same jar-swap procedure, same verification pattern.
