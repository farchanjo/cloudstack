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

# Bug 28 fix attempt (norbert + fluffy pilot) — INCONCLUSIVE, config-only fix does not work

**Date:** 2026-07-01
**Status:** OPEN — deeper than `2026-07-01-bug-28-ovn-integration-bridge-mismatch.md` originally scoped
**Severity:** CRITICAL (unchanged)
**Scope:** norbert + fluffy only (user-authorized pilot before fleet-wide rollout)

---

## What was attempted

Per the original Bug 28 audit's proposed fix, added
`ovn.integration.bridge=<value>` to `/etc/cloudstack/agent/agent.properties`
on norbert and fluffy, restarted `cloudstack-agent` on each, then redeployed
fresh vDPA VMs on each host to confirm the representor now attaches to the
correct OVS bridge.

**First attempt: `ovn.integration.bridge=br-cluster`.** Backed up
`agent.properties`, appended the line, verified it landed (single line,
`grep` confirmed), restarted the agent (`ActiveEnterTimestamp` confirmed
post-edit), deployed a fresh VM on each host. Agent log still showed
`bridge=br-int` and the identical `ovs-vsctl: no bridge named br-int` /
`no row "<rep>" in table Interface` failures as before the "fix".

**Correction discovered mid-investigation:** `br-cluster` was also the wrong
target bridge. The agent's own `VxlanTunnelManager` and `DvrManager` (both
initialized at agent startup, logged at `2026-07-01 02:16:07`) correctly
resolve to `bridge=br-overlay` — reading it from the pre-existing, already
correctly configured `guest.bridge.name=br-overlay` /
`guest.network.device=br-overlay` agent properties (a DIFFERENT key than
`ovn.integration.bridge`, via `VxlanTunnelManager.resolveBridge()`'s
`network.bridge.name` → `guest.bridge.name` → `guest.network.device`
fallback chain). `br-cluster` is the uplink/public-facing bridge (patch-port
peer of `br-overlay`, not itself the OVN integration point).

**Second attempt: `ovn.integration.bridge=br-overlay`** (the value every
other OVS-aware subsystem on this host already resolves to). Corrected the
property in place, restarted the agent again (confirmed fresh PID +
`ActiveEnterTimestamp`), destroyed and redeployed the norbert test VM.
**Identical result** — agent log line still reads `bridge=br-int`, identical
`ovs-vsctl: no bridge named br-int` failures.

## Conclusion — the override mechanism itself does not work at runtime

The correct property KEY (`ovn.integration.bridge`, verified byte-exact in
the deployed class's constant pool via `strings` on the extracted
`.class` file — see below) and two different plausible VALUES were both
tried; neither had any observable effect on `OvnVdpaVifDriver`'s resolved
`integrationBridge` field. This rules out "wrong value" as the explanation
and points to a genuine runtime defect in how (or whether) the property
reaches `OvnVdpaVifDriver.configure(Map<String,Object> params)`.

**Verified, not the cause:**
- Property key name matches the deployed bytecode exactly:
  `unzip -p <jar> .../OvnVdpaVifDriver.class | strings` shows both
  `ovn.integration.bridge` and `br-int` as string-pool constants — the
  override-reading code is present in the class that's actually running.
- Deployed JAR is current: `cloud-plugin-hypervisor-kvm-4.24.1.26-SNAPSHOT.jar`
  md5 `deb0b5724b83099e279ace65cdf53afd` on both hosts, matches the fleet-wide
  Bug 26 deploy (`2026-05-11-bug-26-FIX.md`), and zero commits have touched
  `OvnVdpaVifDriver.java` / `OvnVifDriver.java` since that build.
- Agent genuinely restarted and re-read the file: fresh PID each time,
  `agent.properties found at /etc/cloudstack/agent/agent.properties` logged
  at boot, the edited line visible via `grep` on the exact file the process
  has open.
- Source-level trace of `params` from `configure(String, Map<String,Object>)`
  → `configureVifDrivers(params)` (line 1507) →
  `getVifDriverClass(OvnVdpaVifDriver.class.getName(), params)` (line 2044)
  → `vifDriver.configure(params)` shows the SAME `params` reference
  threaded through with no reassignment or filtering in between, as far as
  static reading of the source goes.

**Not yet identified:** the actual point where the property value is lost.
Candidates not yet checked: whether `params` at the top of
`LibvirtComputingResource.configure()` is built from a *different* pass over
`agent.properties` than what `VxlanTunnelManager`'s constructor receives
(the latter takes a raw `Properties` object read separately — worth
confirming both code paths actually parse the same file the same way);
whether some earlier `params.remove(...)` / key-normalization step strips
unrecognized (non-`AgentProperties`-enum) keys before reaching line 2044;
or a class-loading / driver-caching path that instantiates
`OvnVdpaVifDriver` a second time without calling `configure()` (unlikely per
the log evidence — only one `configure`-driven `getVifDriverClass` call site
exists in `configureVifDrivers`).

**This needs a runtime debugging session** (per this environment's
`fapp-debug`/`fapp-jdebug` convention — JDI attach to the live agent JVM,
breakpoint at `OvnVdpaVifDriver.configure()`, dump the actual `params` Map
content and the `override` value at that exact point) to find the real
gap, rather than further static/log-based guessing. Not done in this pass —
flagging as the concrete next step.

## State left behind

- `ovn.integration.bridge=br-overlay` is now present in
  `/etc/cloudstack/agent/agent.properties` on norbert and fluffy (backups:
  `agent.properties.bak.<timestamp>` alongside the pre-existing rotation of
  older backups on each host). **Currently a no-op** given the finding
  above — safe to leave in place; it will start working for free once the
  underlying override-plumbing bug is fixed, with no further config change
  needed.
- `cloudstack-agent` was restarted twice on each of norbert and fluffy
  during this investigation. Both hosts confirmed `active` and healthy after
  each restart; no other running VMs on either host were observed to be
  disrupted (a KVM agent restart does not tear down already-running guest
  domains, only the agent's own control-plane connection).
- Test VPC `test-vdpa-fix` (10.99.0.0/16), tier `tier-vdpa-fix`
  (10.99.1.0/24), and all VMs deployed against it, created and destroyed via
  `cmk` only. Confirmed fully gone (`list vpcs` / `list virtualmachines` /
  `list networks` all return "not found" for these IDs) at end of session.
- The 4 VMs, 2 VPCs, 2 tier networks, 1 extra keypair from the earlier
  same-day verification pass (`2026-07-01-bug-28-ovn-integration-bridge-mismatch.md`)
  were already cleaned up before this fix attempt began.

## Revised fix path

The config-only fix proposed in the original Bug 28 audit is **not
sufficient**. Closing this bug now requires:

1. A JDI-attached runtime session against a live KVM agent (norbert or
   fluffy, both already have the property set and are otherwise idle test
   targets) to find exactly why `params.get("ovn.integration.bridge")`
   doesn't reach `OvnVdpaVifDriver.integrationBridge`.
2. Once the real mechanism is understood: either a source fix (if the gap is
   a genuine code defect — e.g. driver instantiated before `params` is fully
   populated, or a key-name mismatch introduced by some
   normalization/allowlist step not found by static reading) + rebuild +
   redeploy, or a corrected operational procedure (if the gap turns out to
   be "this key needs to be set some other way — DB `host_details`, a
   `resource.properties` sibling file, etc." rather than `agent.properties`
   at all).
3. Only after (1)+(2) land should the fix be rolled out past the norbert/
   fluffy pilot to the remaining 4 LAX data nodes.

Do not re-attempt "just set the config differently" without first doing
step 1 — this pass already tried two different plausible values with
identical (non-)results, which is strong evidence the config layer itself
is not the remaining variable.
