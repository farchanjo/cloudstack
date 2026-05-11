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

# Bug 22 — Non-HW-offload tier NIC missing from VPC VR libvirt domain after start; root cause is `_agentMgr.send` batch atomicity failure during multi-tier VR boot

> **2026-05-11 update — Bug 22 is the downstream symptom; Bug 23
> (`2026-05-11-bug-23-vdpa-iface-lookup.md`) is the upstream root cause.**
> The original Bug 22 trace assumed `_agentMgr.send` timed out because the
> agent was SIGKILLed mid-batch. Subsequent investigation of a fresh
> reproduction on VR `r-1165-VM` showed the agent was alive and
> the StartCommand SetupGuestNetwork was the first command to return
> `ExecutionResult(false, "Can not find nic with mac ...")` — which
> triggered `OnError.Stop`, aborted the rest of the batch, and produced
> the same downstream "tier-tap NIC missing in libvirt" symptom captured
> here. The proximate cause was `LibvirtDomainXMLParser.parseDomainXML`
> silently dropping `<interface type='vdpa'>` elements (Bug 23). With
> Bug 23 fixed (commit `71dcc1a633`, deployed 2026-05-11), the
> StartCommand batch no longer aborts on this configuration.
>
> **Bug 22's batch-atomicity defect remains a separate systemic risk**
> and is preserved here as an OPEN audit. Any future scenario where a
> post-start command legitimately fails (slow PlugNic on contended
> OVSDB, agent restart mid-batch, network partition) will still drop
> the tail of the batch silently. The patch surface enumerated under
> "Files that hold the patch surface" remains valid; it is just no
> longer the **only** path to a missing-NIC VR boot.

**Date:** 2026-05-11
**Status:** OPEN
**Severity:** HIGH — every multi-tier VPC VR with at least one HW-offload tier AND one non-HW-offload tier (today: `tap-tap`) is at risk of booting with the non-HW-offload tier silently missing from libvirt, breaking DHCP / routing / ACLs / SNAT for that tier's guest VMs even though the OVN NB state is correct.
**Fix commit:** _none yet (Bug 23 root cause fixed; Bug 22 batch-atomicity still OPEN)_

---

## Symptom (verbatim user evidence)

VR `r-1164-VM` (UUID `265666f1-7e4e-4de3-960c-072a68f1ddea`), VPC `test-20vm-vpc`
(UUID `a1992656-2a76-43a5-82cc-3c9b7f5402ca`), zone Slytherin, host norbert
(host id 10).

CloudStack API view (4 NICs):

```
cmk list routers name=r-1164-VM
  nic[0]  mac=0e:00:a9:fe:70:44  ip=169.254.112.68  Control      gateway=169.254.0.1
  nic[1]  mac=02:04:02:53:00:16  ip=10.97.1.13      Guest tap-vdpa  gateway=10.97.1.1
  nic[2]  mac=02:04:02:54:00:16  ip=10.97.2.23      Guest tap-vf    gateway=10.97.2.1
  nic[3]  mac=02:04:02:55:00:13  ip=10.97.3.96      Guest tap-tap   gateway=10.97.3.1  <- missing in libvirt
```

Libvirt domain XML on norbert (3 `<interface>` blocks only):

| Slot | Device kind | MAC | Notes |
|---|---|---|---|
| 0 | `<interface type='bridge'>` cloud0 vnet5 | `0e:00:a9:fe:70:44` | Control |
| 1 | `<interface type='vdpa'>` `/dev/vhost-vdpa-2` | `02:04:02:53:00:16` | tap-vdpa (HW-offload) |
| 2 | `<interface type='hostdev'>` PCI `0000:01:07.2` | `02:04:02:54:00:16` | tap-vf (HW-offload, VFPT) |

No `<interface>` element for MAC `02:04:02:55:00:13` (tier `tap-tap`).

Agent log on norbert at 2026-05-11 04:35:16,848 (verbatim):

```
createVifs: VM=r-1164-VM total NICs=3
createVifs: NIC devId=0 mac=0e:00:a9:fe:70:44 type=Control useHwOffload=null vfPci=null ip=169.254.112.68
createVifs: NIC devId=1 mac=02:04:02:53:00:16 type=Guest   useHwOffload=null vfPci=0000:01:04.4 ip=10.97.1.13
createVifs: NIC devId=2 mac=02:04:02:54:00:16 type=Guest   useHwOffload=true vfPci=0000:01:07.2 ip=10.97.2.23
```

No log line for `deviceId=3 mac=02:04:02:55:00:13`. The `StartCommand` payload
on the wire (`VirtualMachineTO.nics[]`) carried only 3 entries, by design (see
"Why 3 NICs in StartCommand is correct" below).

---

## Trace summary (top-down)

The bug is **NOT** a per-NIC silent skip in `NetworkOrchestrator.prepare` or
`OvnNetworkElement.prepare`. The OVN side did fire for tier-tap. The DB rows
were created. The defect is in the agent-side **batch-execution atomicity** of
`_agentMgr.send(destHostId, cmds)`: the batch carries StartCommand plus the
per-tier post-start commands (one of which is the `PlugNicCommand` that would
materialize the tier-tap NIC in libvirt), the agent processes the StartCommand,
the management server then times out waiting for the rest, throws
`AgentUnavailableException`, and **never runs `vmGuru.finalizeStart` to retry
or to surface the failure** — the VR is left half-configured.

| Step | File / Method | Result |
|------|---------------|--------|
| 1. Top of VR start | `VirtualMachineManagerImpl.orchestrateStart` line 1344-1610 in `engine/orchestration/src/main/java/com/cloud/vm/VirtualMachineManagerImpl.java` | OK; same path as user VMs |
| 2. NIC prepare | `NetworkOrchestrator.prepare` line 2142-2175 in `engine/orchestration/src/main/java/org/apache/cloudstack/engine/orchestration/NetworkOrchestrator.java` | OK; all 4 NICs added to `vmProfile.getNics()` (Control + tap-vdpa + tap-vf + tap-tap) |
| 3. OVN element prepare | `OvnNetworkElement.prepare` (called via `NetworkOrchestrator.prepareNic` line 2239) | OK; mgmt log lines at 04:35:16,168 / ,283 / ,398 confirm `prepare()` ran for all 3 tier LSPs including tier-tap (`name=lsp-fe9cd7c2-2f01-43f8-b85f-dbbeeecec383`, `addrs=[02:04:02:55:00:13 10.97.3.96]`) |
| 4. VR-specific profile finalize | `VpcVirtualNetworkApplianceManagerImpl.finalizeVirtualMachineProfile` line 318-447 in `server/src/main/java/com/cloud/network/router/VpcVirtualNetworkApplianceManagerImpl.java` | OK by design; **tap-tap NIC is REMOVED** from `profile.getNics()` at line 419 (`it.remove()`) because `isHwOffloadNetwork(597) == false`. tier-vdpa + tier-vf are KEPT in boot profile (line 411, 416) because `isHwOffloadNetwork` returns true for `vdpa_enabled` offerings (line 1310). This is correct: non-HW-offload guest NICs are hot-plugged via `PlugNicCommand` AFTER boot. |
| 5. Hypervisor TO | `HypervisorGuruBase.toVirtualMachineTO` line 978-991 in `server/src/main/java/com/cloud/hypervisor/HypervisorGuruBase.java` | OK; `vmProfile.getNics()` already filtered, so `VirtualMachineTO.nics[]` = 3 entries (Control + tap-vdpa + tap-vf). |
| 6. StartCommand + finalize | `VirtualMachineManagerImpl.orchestrateStart` line 1543-1552: `Commands cmds = new Commands(OnError.Stop)` + `cmds.addCommand(StartCommand)` + `vmGuru.finalizeDeployment(cmds, ...)` | OK; tier-tap `PlugNicCommand` IS appended by `VpcVirtualNetworkApplianceManagerImpl.finalizeCommandsOnStart` line 703-706 (because tier-tap `isHwOffloadNetwork=false`). Other commands also appended: 3x `SetupGuestNetworkCommand`, 3x `AggregationControl{Start,Finish}`, public-IP-assoc, SNAT, ACL, monitor, LB, etc. Batch size ~12-15 commands. |
| 7. Send batch | `VirtualMachineManagerImpl.orchestrateStart` line 1563: `_agentMgr.send(destHostId, cmds)` (single batched Request) | **FAILS — see "Root cause" below**. Mgmt log Seq 269-991917817928351783 timed out after ~7 minutes at 04:42:39,612 with `Timed out on null` → `AgentUnavailableException Host 10: Unable to start r-1164-VM`. |
| 8. finalizeStart | `vmGuru.finalizeStart` line 1580 in `VirtualMachineManagerImpl.orchestrateStart` | **NEVER reached** — exception bubbled out of `_agentMgr.send`. |
| 9. Power-report reconciliation | `ClusteredVirtualMachineManagerImpl` async power-report handler | At 04:43:28,275 mgmt receives a power-on report for `r-1164-VM` from the (newly reconnected) agent and **flips the DB state Starting -> Running**. The VM is alive on norbert with only 3 NICs; mgmt now believes the start succeeded. |

The user's "tier-tap missing" is the durable artifact of step 7-9: the agent
never received / processed the `PlugNicCommand` (and never ran the matching
`SetupGuestNetworkCommand`), so the libvirt domain has no `<interface>` for
`02:04:02:55:00:13` and the VR has no DHCP / SNAT / ACL for tier `tap-tap`.

---

## Why "3 NICs in StartCommand" is correct (not the bug)

`VpcVirtualNetworkApplianceManagerImpl.finalizeVirtualMachineProfile` lines
396-421 explicitly remove non-HW-offload `Guest` (and non-HW-offload `Public`)
NICs from `profile.getNics()`:

```java
final Iterator<NicProfile> it = profile.getNics().iterator();
while (it.hasNext()) {
    final NicProfile nic = it.next();
    if (nic.getTrafficType() == TrafficType.Public || nic.getTrafficType() == TrafficType.Guest) {
        ...
        if (nic.getTrafficType() == TrafficType.Guest && isHwOffloadNetwork(nic.getNetworkId())) {
            logger.info("Keeping HW offload guest NIC network={} in boot profile as eth{} ...", ...);
            continue; // kept, DB device_id flows through
        }
        ...
        logger.debug("Removing NIC " + nic + " of type " + nic.getTrafficType()
                     + " from the NICs passed on Instance start. The NIC will be plugged later");
        it.remove();
    }
}
```

`isHwOffloadNetwork(networkId)` is at line 1298-1321 and returns
`offering.isHwOffloadEnabled() || offering.isVdpaEnabled()`. For:

| Offering | `hw_offload_enabled` | `vdpa_enabled` | `isHwOffloadNetwork` returns | Kept in boot XML? |
|---|---|---|---|---|
| 217 `tier-vdpa-ovn` | false | true | true | **yes (eth1)** |
| 226 `tier-vf-ovn-nolb` | true | false | true | **yes (eth2)** |
| 229 `tier-tap-ovn-nolb` | false | false | false | **no — hot-plug post-boot** |

The mgmt log confirms each branch:

```
04:35:16,526 isHwOffloadNetwork: network=595 ... hwOffload=false vdpa=true
04:35:16,526 Keeping HW offload guest NIC network=595 in boot profile as eth1 ...
04:35:16,527 isHwOffloadNetwork: network=596 ... hwOffload=true  vdpa=false
04:35:16,527 Keeping HW offload guest NIC network=596 in boot profile as eth2 ...
04:35:16,528 isHwOffloadNetwork: network=597 ... hwOffload=false vdpa=false
            (no "Keeping" line -> falls into it.remove() branch at line 419)
```

So by design `VirtualMachineTO.nics[]` carries exactly 3 entries (Control +
tap-vdpa + tap-vf) when the VR boots — the agent log `createVifs total NICs=3`
is the **correct** post-filter shape. The defect is downstream: the hot-plug
that should fill in the 4th NIC never runs.

---

## Root cause — `_agentMgr.send` batch atomicity hole

Two facts combine to produce the bug:

**Fact 1.** `VpcVirtualNetworkApplianceManagerImpl.finalizeCommandsOnStart`
(lines 503-784) appends a long sequence of post-start commands to the same
`Commands cmds` object that already carries `StartCommand`. For a 3-tier
VPC VR (1 vdpa, 1 vf, 1 tap) the batch contains, roughly:

```
[0] StartCommand                                 (boot the domain)
[1] CheckSSHCommand                              (control-IP SSH probe)
[2] PlugNicCommand   (only for tap-tap; HW-offload skipped — line 702-709)
[3] SetupGuestNetworkCommand (tap-vdpa)
[4] SetupGuestNetworkCommand (tap-vf)
[5] SetupGuestNetworkCommand (tap-tap)
[6] AggregationControl.Start (per tier x 3)
[7..] finalizeIpAssocForNetwork, finalizeNetworkRulesForNetwork,
      finalizeMonitorService, LB, StaticRoute, VPN (per tier)
[N] AggregationControl.Finish (per tier x 3)
```

Empirically observed: batch reached 12-15 commands for this VR.

**Fact 2.** `_agentMgr.send(destHostId, cmds)` is one synchronous Request with
`OnError.Stop` semantics. The agent processes commands sequentially on a single
`AgentRequest-Handler-N` thread. If any command after `StartCommand`
hangs / takes too long, or if the agent JVM is restarted mid-batch (which is
what happened in the captured trace), the mgmt side waits ~7 minutes
(`Request` default `wait`), then throws `AgentUnavailableException`. Because
the exception originates inside `_agentMgr.send`, control flow in
`VirtualMachineManagerImpl.orchestrateStart` jumps to the surrounding catch and
**`vmGuru.finalizeStart` at line 1580 is never executed**. The catch in
`orchestrateStart` does not re-issue the remaining commands and does not roll
back the VM — it just propagates the `AgentUnavailableException` to the
caller, which in turn lets `ClusteredVirtualMachineManagerImpl` later reconcile
the VM as `Running` based on the libvirt-power-state-on report (mgmt log
04:43:28,275). No partial-batch failure is exposed anywhere in the API
response; the VR appears to start "successfully" from the API caller's POV.

Captured timeline for `r-1164-VM` (logid `25ad3920`, mgmt host bellatrix
`10.182.0.12`, target host norbert `10.182.0.22` aka host id 10):

| Time | Where | Event |
|---|---|---|
| 04:35:16,603 | bellatrix mgmt | `finalizeCommandsOnStart: guestNics.size=3 for VR r-1164-VM` |
| 04:35:16,648 | bellatrix mgmt | `isHwOffloadNetwork: network=597 ... hwOffload=false vdpa=false` (last loop iter for tap-tap; PlugNicCommand was appended on this iter via line 703-706, no DEBUG log emitted by the addCommand call) |
| 04:35:16,787 | norbert agent | `LibvirtComputingResource` starts handling `StartCommand` for `r-1164-VM` |
| 04:35:16,848 | norbert agent | `createVifs: VM=r-1164-VM total NICs=3` |
| 04:35:17,715 | norbert agent | StartCommand `LibvirtKvmAgentHook` step complete (hooks unavailable warnings) |
| 04:35:30,653 | norbert agent | `applyVdpaPostPlugTunables` cycled tap-vdpa `iface-status` to active. **StartCommand wrapper finished here.** No subsequent commands processed on `AgentRequest-Handler-4`. |
| 04:35:30 - 04:42:39 | norbert agent | Other unrelated commands (storage pool refresh, GetVmStats for other VMs) handled by Handlers 1/2/3/5; **no command processed for r-1164-VM on Handler-4.** |
| 04:42:39,611 | norbert agent | `AgentShutdownThread: Stopping the agent: Reason = sig.kill` (the entire agent JVM is killed; the in-flight Request is dropped). |
| 04:42:39,612 | bellatrix mgmt | `ClusteredAgentAttache: Seq 269-991917817928351783: Timed out on null` |
| 04:42:39,616 | bellatrix mgmt | `AgentUnavailableException: Resource [Host:10] is unreachable: Host 10: Unable to start r-1164-VM` (thrown out of `_agentMgr.send`; `finalizeStart` never called) |
| 04:42:41,248 | norbert agent | `cloud.agent.AgentShell: Agent started` (reconnects to bellatrix) |
| 04:43:28,275 | bellatrix mgmt | `ClusteredVirtualMachineManagerImpl: VM r-1164-VM is sync-ed to at Running state according to power-on report from hypervisor.` (the libvirt domain reports running with 3 NICs; mgmt flips DB Starting -> Running. The tier-tap PlugNicCommand is lost.) |

The actual reason the agent went silent on `r-1164-VM` between 04:35:30 and
the SIGKILL is independent (the `sig.kill` came from the operator / a
watchdog, not from this batch). What this audit pins is the **management-side
defect**: a successful StartCommand combined with a failed remainder of the
batch produces an unrecoverable VR (no per-NIC retry, no rollback, no
visibility) that the API caller sees as "Running" via the async power-report
reconciliation.

The same defect would fire on any of these conditions, not just SIGKILL:

- The `_agentMgr.send` default Request wait expires while the agent is
  legitimately busy on a slow PlugNic (e.g. `ovs-vsctl add-port` retry-back-off
  on a contended OVSDB).
- The agent JVM is restarted by `systemctl restart cloudstack-agent` mid-batch.
- The agent dies on the host (OOM-kill, panic, libvirt deadlock).
- Network partition between mgmt and host long enough for `Request` to time
  out but short enough for the libvirt domain to keep running.

In all four cases the VR boots with `(N - non-HW-offload-tier-count)` NICs in
libvirt instead of `N`. For a single-tier VPC (only HW-offload) the bug is
invisible. For a multi-tier VPC with at least one non-HW-offload tier, the
defect surfaces.

---

## Files that hold the patch surface

(No fix proposed here — investigation-only audit. The following enumerates
where a fix would go.)

1. `engine/orchestration/src/main/java/com/cloud/vm/VirtualMachineManagerImpl.java`
   - Line 1543-1563: split `cmds` into (a) `StartCommand`-only sub-batch and
     (b) post-start sub-batch. Send (a) first; on success enter the
     `finalizeStart` path; **then** send (b) under a separate Request so a
     timeout on (b) is retryable without unwinding the started VM. The
     `OnError.Stop` of the current single-batch design conflates StartCommand
     atomicity (which is OK to all-or-nothing) with post-start
     atomicity (which should be per-tier retryable).
   - Alternative: keep the single batch but, after the `_agentMgr.send`
     timeout, run a `verifyAndReapplyPostStart` reconciliation that re-issues
     only the post-start commands that did not get a matching Answer in
     `cmds.getAnswers()`. Today after timeout, `cmds.getAnswers()` is
     `[StartAnswer{success:true}, null, null, ...]`; the mgmt code throws
     instead of using that signal.

2. `server/src/main/java/com/cloud/network/router/VpcVirtualNetworkApplianceManagerImpl.java`
   - Line 503-784 (`finalizeCommandsOnStart`): when appending
     `PlugNicCommand` for a non-HW-offload guest tier, also record the
     intended action in a persistent `op_vr_pending_plug` table so a later
     restart-or-reconcile path can replay it (mirrors the
     `op_nwgre_mapping` / `ovn_logical_id_map` pattern used by the OVN
     plugin and the existing `OvnNetworkElement: queued pending deletion` log
     line).
   - Line 1298-1321 (`isHwOffloadNetwork`): the predicate is correct; do not
     touch it. (The tier-tap is meant to be hot-plugged; that contract is
     fine.)

3. `server/src/main/java/com/cloud/network/router/VpcNetworkHelperImpl.java`
   (companion of `NetworkHelperImpl`, drives post-start NIC plug retries on
   user-triggered `restartNetwork` / `addNetworkToVpc`)
   - Consider extracting a `replayPendingPlugForVr(domainRouterVO)` that the
     reconciler can call when a VR is detected as `Running` but the libvirt
     `<interface>` count < `nics where instance_id = vr.id` count. This is
     today an OPS-only escape hatch; making it programmatic closes the gap.

4. `engine/components-api/src/main/java/com/cloud/agent/AgentManager.java`
   + `server/src/main/java/com/cloud/agent/manager/AgentManagerImpl.java`
   (line 103 / 105 `Answer[] send(...)` signatures)
   - The semantic gap is in the `send` contract: today the caller sees only
     `AgentUnavailableException` on partial-batch failure; expose an
     overload `Pair<Answer[], Throwable> sendBestEffort(hostId, cmds)` so
     `orchestrateStart` can distinguish "all failed" vs "StartCommand
     succeeded, tail failed". Probably more invasive than option (1) above.

---

## Proposed verification (when a fix is built)

1. **Smoke test, single-host (positive).**
   On a fresh VR start in a 3-tier VPC, after `restartVPC cleanup=true`:
   - assert `cmk list routers name=<vr>` returns 4 NICs and matches the
     `virsh dumpxml <vr>` `<interface>` count;
   - assert `domiflist <vr>` lists 4 entries with the same MACs as
     `cmk list routers ... | jq '.nic[].mac_address'`;
   - assert OVN `ovn-nbctl get logical_switch_port lsp-<tap-tap-nic-uuid> up`
     returns `true`.

2. **Negative test, agent-restart mid-batch.**
   In a CI integration test (Marvin) or a simulator scenario:
   - inject a sleep into `LibvirtPlugNicCommandWrapper.execute` for the
     `tap-tap` MAC so it exceeds the `_agentMgr.send` Request wait;
   - assert the mgmt server retries / surfaces the failure rather than
     letting the VR boot with N-1 interfaces;
   - assert `op_vr_pending_plug` (or the equivalent reconcile state) is
     populated and replayed on next mgmt-side reconcile.

3. **Unit assertion in `VirtualMachineManagerImplTest`**:
   add a test case where `mockAgentMgr.send` returns
   `Answer[]{StartAnswer{success:true}, null, null}` (StartCommand
   answered, tail timed out) and assert that `orchestrateStart` does NOT
   mark the VM `Running` and either retries the tail or rolls back the
   start.

4. **Log assertion**:
   each multi-tier VPC VR start emits exactly one `Answer` per command in
   `Commands.cmds`. The current production log has
   `Seq 269-991917817928351783: Timed out on null` with no per-command
   diagnostic. Add a `Request.dumpUnansweredCommands(seq)` helper that
   logs the missing answer slots at ERROR before throwing
   `AgentUnavailableException` from `_agentMgr.send`.

---

## Cross-references (other audits that touch the same call sites)

- `2026-05-10-bug-14b-and-15-migration.md` — covers `getVifDriver(TrafficType)` vs
  `selectVifDriverForNic(NicTO)` in the migration-prepare wrapper, and the new
  `PostMigrateOvnStampCommand` dispatched from
  `VirtualMachineManagerImpl.dispatchPostMigrateOvnStamp`. Same surface
  (`VirtualMachineManagerImpl` post-VM-operation orchestration); the migration
  Layer-B mgmt commit (`d34f9fe190`) demonstrates the pattern this fix would
  follow (split into a separate post-step Command + Wrapper).
- `2026-05-10-bug-14-iface-id-prefix.md` — explicitly notes "`applyPostPlugTunables`
  must run on the destination agent AFTER libvirt completes the migration
  transfer" — the same separation-of-concerns required here for `PlugNicCommand`
  to run AFTER `StartCommand` answers, not bundled in the same batch.
- `2026-05-10-bug-19-staticnat-idempotency.md` — `restartNetwork cleanup=false`
  re-runs `applyStaticNats` and the OVN element now handles idempotency via
  `handleExistingMapping`. The reconcile path proposed in "Files that hold the
  patch surface" item (3) is the same pattern: detect a partial state and
  replay only the missing operation, never duplicate.
- `2026-05-10-bug-13-configkey-leak.md` and
  `2026-05-09-ovn-fork-audit.md` — both confirm the `OvnNetworkElement.prepare`
  + `OvnSourceNatService` + `OvnDhcpService` + `OvnDnsService` flow runs and
  produces a correct NB state for tier-tap (mgmt log
  04:35:16,398-04:35:16,493 traces all of them firing for `lsp-fe9cd7c2-...`).
  This bug is **not** an OVN-side regression; the OVN state is correct, only
  the libvirt-side hot-plug is missing.

---

## Skip list for future audits

- Do NOT re-flag `_networkMgr.prepare` (line 2142) or
  `NetworkOrchestrator.prepareNic` (line 2178) as the cause of "missing NIC"
  symptoms on multi-tier VPC VRs — those produce all 4 NICs in `vmProfile`
  correctly. Verify the count via the
  `Asking Ovn to prepare for Nic { ... deviceId=3 ... }` log line at
  `o.a.c.e.o.NetworkOrchestrator` and the matching
  `OvnNetworkElement.prepare: LSP ... (name=lsp-..., addrs=[...])` line at
  `c.c.n.o.e.OvnNetworkElement`.

- Do NOT re-flag `VpcVirtualNetworkApplianceManagerImpl.finalizeVirtualMachineProfile`
  line 396-421 (`it.remove()`) as the cause of tier-tap being dropped. The
  removal is intentional. Drift indicator: the
  `Removing NIC ... will be plugged later` DEBUG log line (or its INFO
  equivalent) being absent from the mgmt log for a non-HW-offload guest NIC
  would mean someone changed the contract.

- Do NOT re-flag `isHwOffloadNetwork` (line 1298) — its `||
  offering.isVdpaEnabled()` shortcut is correct (vDPA tiers need a VF
  pre-allocated like HW-offload tiers do, and the live mgmt log confirms
  `Keeping HW offload guest NIC network=595` fires).

- DO re-flag any future code that adds large numbers of commands to
  `Commands cmds` between `cmds.addCommand(StartCommand)` and
  `_agentMgr.send(destHostId, cmds)` without splitting the batch — the
  atomicity hole described in this audit is upstream of any new tier types
  that get added to the OVN fork.

---

## Lessons

- **Batch atomicity is a separate contract from command atomicity.**
  `OnError.Stop` covers "stop on first failed answer"; it does NOT cover
  "treat StartCommand success as commit-point and continue retrying the tail".
  CloudStack's `_agentMgr.send` treats the whole batch as one Request with one
  failure mode. Multi-tier VPC VRs are the worst case for this design because
  the batch size grows with tier count.

- **API-visible vs library-visible state divergence is silent.**
  `cmk list routers` (the API surface) reads from `nics` and `domain_router`;
  `virsh dumpxml` reads from libvirt. When the hot-plug tail of the start
  batch is lost, the API view stays correct (DB has all 4 NICs) and the
  libvirt view loses one. No metric / log / event surfaces the divergence
  until a tenant VM in the dropped tier fails DHCP.

- **`finalizeStart` is the only post-batch reconciliation hook**, and it is
  bypassed by `AgentUnavailableException`. Any "partial success of a multi-
  command batch" recovery has to live above `_agentMgr.send` because the
  catch block in `orchestrateStart` does not differentiate "all failed" from
  "head succeeded, tail failed".

- **Reconcile-by-power-report is a footgun.** `ClusteredVirtualMachineManagerImpl`
  flipped `Starting -> Running` based purely on libvirt's `domState=Running`,
  with no cross-check against
  `_nicDao.listByVmId(vm.getId()) vs domain.getXMLDesc()` interface count.
  The mgmt server happily told the API caller the VR was healthy.
