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

# Bug 28 root cause CONFIRMED via live JDWP debug — `params` never carries `agent.properties`

**Date:** 2026-07-01
**Status:** OPEN — root cause now certain, fix is source-only, not applied yet
**Severity:** CRITICAL (unchanged)
**Method:** `jdb` (JDK debugger, installed via `openjdk-17-jdk-headless`) attached to the live
`cloudstack-agent` JVM on norbert over a suspended JDWP session, localhost-only.

---

## Summary

`2026-07-01-bug-28-fix-attempt-inconclusive.md` established empirically (via log
comparison) that no value of `ovn.integration.bridge` in `agent.properties`
had any effect on `OvnVdpaVifDriver`'s resolved bridge name, and flagged a
live JDI/JDWP session as the next step rather than further guessing. That
session ran this pass and found the exact mechanism.

## Method

1. `openjdk-17-jdk-headless` was not installed on norbert (only the JRE was
   — sufficient to run `cloudstack-agent`, but no `jdb`). Installed via
   `apt-get install -y openjdk-17-jdk-headless`. This triggered an automatic
   `cloudstack-agent` restart via `needrestart` (harmless, already observed
   several times this session).
2. `/etc/systemd/system/cloudstack-agent.service` already had a `$JAVA_DEBUG`
   slot wired into `ExecStart` (`/usr/bin/java $JAVA_OPTS $JAVA_DEBUG -cp
   $CLASSPATH $JAVA_CLASS`), fed by an empty `JAVA_DEBUG=""` in
   `/etc/default/cloudstack-agent` — built for exactly this use case, no
   systemd override needed.
3. Backed up `/etc/default/cloudstack-agent`, set
   `JAVA_DEBUG="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005"`
   (loopback-only — never exposed off-host), restarted the agent. The JVM
   held at its very first bytecode instruction waiting for a debugger.
4. `jdb -attach 127.0.0.1:5005` run **locally on norbert** (via the existing
   nested-SSH-through-aragog session — no SSH port forwarding needed at all,
   sidestepping `AllowTcpForwarding no` on aragog's sshd entirely, since the
   debugger client and the target JVM are on the same host).
5. `stop at com.cloud.hypervisor.kvm.resource.OvnVdpaVifDriver:68` (the line
   fetching `params.get(OvnVifDriver.PROP_INTEGRATION_BRIDGE)`), `run` to let
   the agent boot to that point, then inspected `params` directly.
6. Cleared the breakpoint, `cont`/`exit`ed jdb to let the agent finish
   booting normally. Reverted `JAVA_DEBUG` to `""` and restarted the agent a
   final time — norbert left in its original, non-debug state (confirmed
   port 5005 closed, `cloudstack-agent` active).

## Finding

At the breakpoint (`OvnVdpaVifDriver.configure()`, right where the override
is read):

```
params.get("ovn.integration.bridge") = null
params.get("guest.bridge.name")      = null
params.size()                        = 9
params.keySet() = [guest.cpu.model, host.ip, host.mac.address,
                    libvirt.host.bridges, libvirt.host.pifs,
                    libvirt.computing.resource, libvirtVersion,
                    domr.scripts.dir, guest.cpu.mode]
```

**`params` is not, and was never, a passthrough of `agent.properties`.** It
is a small, hand-built `Map` — 9 entries, all either computed at runtime
(`guest.cpu.model`, `host.ip`, `host.mac.address`) or cross-references to
other in-memory objects (`libvirt.computing.resource`,
`libvirt.host.bridges`, `libvirt.host.pifs`). No amount of editing
`agent.properties` could ever make `ovn.integration.bridge` (or any other
raw property key, as proven by `guest.bridge.name` also being `null` here
despite genuinely being set on disk) visible to `OvnVdpaVifDriver.configure()`
— the override code at `OvnVdpaVifDriver.java:68-71` /
`OvnVifDriver.java` (same pattern) is unreachable dead code in practice,
present since whenever it was written, never functional.

This also retroactively explains why `VxlanTunnelManager`/`DvrManager`
correctly resolve `br-overlay`: they do NOT go through this `params` Map at
all — they take a raw `Properties` object read directly from
`agent.properties` via their own constructor path (confirmed in
`2026-07-01-bug-28-fix-attempt-inconclusive.md`'s `resolveBridge()` trace).
The two subsystems use fundamentally different, incompatible plumbing for
"how do I find my configured bridge name" — one works, the other (OVN VIF
drivers) doesn't and never did.

## Fix (source-only, not yet written)

Two viable approaches, either closes Bug 28 for good:

1. **Match the working pattern.** Have `OvnVdpaVifDriver`/`OvnVifDriver`
   read the bridge override the same way `VxlanTunnelManager` does — via a
   `Properties` object loaded directly from `agent.properties`
   (`PropertiesUtil.loadFromFile` or equivalent), not via the synthetic
   `params` Map. Most consistent with the rest of the codebase's existing
   working precedent.
2. **Thread it through `params`.** In
   `LibvirtComputingResource.configure()`, before calling
   `configureVifDrivers(params)` (around line 1500-1507), explicitly
   `params.put("ovn.integration.bridge", <resolved value>)` — resolving it
   the same way `VxlanTunnelManager.resolveBridge()` does (checking
   `network.bridge.name` → `guest.bridge.name` → `guest.network.device` in
   `agent.properties`, defaulting to `br-int` only if none are set). Smaller
   diff, keeps the existing `OvnVdpaVifDriver`/`OvnVifDriver` override code
   working as originally intended without touching those classes.

Recommend **option 2**: it reuses the bridge name every other OVS-aware
subsystem on the host already agrees on (`br-overlay` here), requires no new
config key at all (the fix works with zero `agent.properties` changes on any
host — `guest.bridge.name` is already correctly set fleet-wide), and is a
~3-line diff in one already-well-understood method.

Once patched: rebuild `plugins/hypervisors/kvm`, redeploy the jar to norbert
+ fluffy (pilot), re-run the cross-host vDPA VM test from
`2026-07-01-bug-28-ovn-integration-bridge-mismatch.md` /
`-fix-attempt-inconclusive.md` to confirm `ovs-vsctl add-port` finally
targets `br-overlay` and succeeds, THEN roll to the remaining 4 data nodes.

## State left behind

- `openjdk-17-jdk-headless` now installed on norbert (harmless — provides
  `jdb`/`javac`/etc. alongside the pre-existing JRE; not removed, low value
  in reverting a small package add).
- `/etc/default/cloudstack-agent.bak.<timestamp>` backup left on norbert
  (matches existing backup-before-edit convention on this host).
- `JAVA_DEBUG` confirmed reverted to `""`, agent restarted clean, port 5005
  confirmed closed. norbert is back to its pre-session operational state
  aside from the (harmless, Ansible-managed-file-unaffected) JDK package.
- fluffy was NOT touched in this pass — the JDWP session only ran against
  norbert, sufficient to prove the root cause fleet-wide (the bug is in
  shared code, not host-specific state).
