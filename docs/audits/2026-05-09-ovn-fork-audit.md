# 2026-05-09 — OVN Fork DB/API/UI Audit

**Scope.** Cross-layer consistency audit of the custom OVN integration in this
CloudStack fork (4.24.1.26-SNAPSHOT). Hunted gaps between DB schema, API
response classes, command-layer validation, runtime dispatch, and management
UI.

**Trigger.** User reported `cmk listNetworkOfferings` was returning
`hwoffloadenabled` but never `vdpaenabled`, despite `vdpa_enabled=1` being
correctly persisted in `network_offerings`. Surfaced 8 issues end-to-end.

**Production cluster.** Slytherin (Los Angeles), 3 controls
(voldemort/bellatrix/barty), 6 data nodes.

## Build evidence

- Pre-audit JAR md5 (Onda 1+2+3 only): `aa45ecc04d942cc5a33ade38930a4123`.
- Post-fix JAR md5 (Onda 1+2+3 + 8 bug fixes): `fc3ddfed9114a35d88a134f687103c12`.
- JAR path on aragog: `/root/cloudstack-feature/client/target/cloud-client-ui-4.24.1.26-SNAPSHOT.jar`.
- Deployed to all 3 controls at: `/usr/share/cloudstack-management/lib/cloudstack-4.24.1.26-SNAPSHOT.jar`.
- Schema migration baked into JAR: `META-INF/db/schema-42426to42427.sql`.
- Bytecode check: `NetworkOfferingJoinVO.class` strings has 1 `vdpa_enabled` hit.

## Smoke-test evidence (cmk against live cluster, 2026-05-09 22:08 UTC)

```
$ cmk -o json list networkofferings id=150087b3-f827-4569-b0da-0e45ee7e5bf8
name: tier-vdpa-ovn
hwoffloadenabled: False
vdpaenabled: True

$ cmk -o json list networkofferings id=123830d3-eec0-4aa1-8fa5-4f2adacfab7f
name: tier-vf-ovn
hwoffloadenabled: True
vdpaenabled: False
```

Both flags now exposed via the API. Mutex (vdpa XOR hwoffload) holds.

## Bug catalog

### Bug 1+2+3 (atomic) — vdpa_enabled API response gap — `FIXED`

**Symptom.** `listNetworkOfferings` JSON response never carried the
`vdpaenabled` field, even when the underlying DB column was set.

**Root cause chain.**

1. `network_offering_view` was created in `schema-42400to42410.sql` BEFORE
   `vdpa_enabled` existed on `network_offerings`.
2. `schema-42410to42411.sql` dropped `vdpa_enabled` (feature retired upstream).
3. `schema-42422to42423.sql` re-added the column to the base table but did NOT
   recreate the view. The view stayed without the column.
4. `NetworkOfferingJoinVO` mapped `@Table(name = "network_offering_view")` and
   had no `@Column(name = "vdpa_enabled")`. The interface default
   `isVdpaEnabled() { return false; }` masked the real value.
5. `NetworkOfferingJoinDaoImpl.newNetworkOfferingResponse()` set
   `setHwOffloadEnabled` but never called `setVdpaEnabled`.

**Fix commit.** `54475e57b9` — `fix(api): expose vdpa_enabled in NetworkOfferingResponse`.

- New SQL migration `engine/schema/src/main/resources/META-INF/db/schema-42426to42427.sql`
  that drops + recreates `network_offering_view` projecting `vdpa_enabled`.
- New upgrade Java class `engine/schema/src/main/java/com/cloud/upgrade/dao/Upgrade42426to42427.java`
  registered in `DatabaseUpgradeChecker`.
- `NetworkOfferingJoinVO` gains `@Column(name = "vdpa_enabled")` plus `@Override
  isVdpaEnabled()` and a setter.
- `NetworkOfferingJoinDaoImpl.newNetworkOfferingResponse()` now calls
  `setVdpaEnabled(offering.isVdpaEnabled())` immediately after
  `setHwOffloadEnabled`.
- Canonical view definition under `engine/schema/src/main/resources/META-INF/db/views/cloud.network_offering_view.sql`
  kept in sync.

**Verification.** Post-deploy `cmk -o json list networkofferings ...` returns
`vdpaenabled: True` for `tier-vdpa-ovn` (DB row vdpa_enabled=1). Schema
migration runs cleanly; no duplicate-view errors in management.log.

### Bug 4 — VPC offering response missed `internetProtocol` — `FIXED` (pre-existing)

**Symptom.** `listVpcOfferings` never returned `internetprotocol`, despite the
column existing on `VpcOfferingJoinVO` and the setter existing on
`VpcOfferingResponse`.

**Root cause.** The response builder in `VpcOfferingJoinDaoImpl.newVpcOfferingResponse`
populated `domainId`, `zoneId`, `forNsx`, `networkMode`, but skipped
`internetProtocol`.

**Fix.** Already present in working tree at audit time
(`server/src/main/java/com/cloud/api/query/dao/VpcOfferingJoinDaoImpl.java`
lines 91-95):

```java
String protocol = offeringJoinVO.getInternetProtocol();
if (StringUtils.isEmpty(protocol)) {
    protocol = NetUtils.InternetProtocol.IPv4.toString();
}
offeringResponse.setInternetProtocol(protocol);
```

Was committed in an earlier session but never rebuilt + redeployed. Picked up
by the post-fix build.

**Verification.** Post-deploy bytecode includes the call site; production
`cmk listVpcOfferings` now returns `internetprotocol`.

### Bug 5 — vdpa/hwoffload mutex not enforced at create time — `FIXED`

**Symptom.** `createNetworkOffering` accepted both `hwoffloadenabled=true` AND
`vdpaenabled=true` without complaint. Runtime priority silently picked vDPA
over HW passthrough (HypervisorGuruBase:734-738), masking operator intent.

**Fix commit.** `b159261abb` — `fix(configuration): enforce vdpaenabled/hwoffloadenabled mutex`.

`ConfigurationManagerImpl.createNetworkOffering` lines 7113-7118:

```java
boolean hwOffloadEnabled = cmd.isHwOffloadEnabled();
boolean vdpaEnabled = cmd.isVdpaEnabled();
if (hwOffloadEnabled && vdpaEnabled) {
    throw new InvalidParameterValueException(
            "Parameters 'hwoffloadenabled' and 'vdpaenabled' are mutually exclusive; choose one.");
}
```

**Verification.** Code path is live in deployed bytecode. Manual contradiction
attempt would now fail-fast with the exception above.

### Bug 6 — UI missing vdpa/hwoffload toggles — `FIXED`

**Symptom.** Neither flag was exposed in the management UI form for creating a
network offering. API-only.

**Fix commit.** `31f1df9c4f` — `feat(ui): add vdpa/hwoffload toggles to network offering form`.

- `ui/src/views/offering/AddNetworkOffering.vue`: two `<a-switch>` toggles
  side-by-side (`hwoffloadenabled` + `vdpaenabled`) with `@change` handlers
  that auto-clear the sibling flag on toggle (client-side mutex matching the
  server-side guard from Bug 5).
- `ui/public/locales/en.json` + `ui/public/locales/pt_BR.json`: 4 new i18n
  keys (`label.hwoffloadenabled`, `label.vdpaenabled`,
  `message.hwoffloadenabled`, `message.vdpaenabled`).

**Verification.** UI build picks up the new toggles; mutex behaviour verified
in static review of the change handlers.

### Bug 7 — LB `affinity_timeout` not resolved via 4-scope chain — `FIXED`

**Symptom.** `OvnLoadBalancerService.buildLbOptions` read
`OvnNicConfig.LbAffinityTimeout.value()` directly. The 4-scope override
(vm_details > network_details > network_offering_details > global) was
completely bypassed for this tunable, despite docs claiming the chain applied.

**Fix commit.** `fb05f195b4` — `fix(ovn): resolve LB affinity_timeout via 4-scope chain`.

- `buildLbOptions` is now an instance method
  `public Map<String, String> buildLbOptions(LoadBalancingRule rule, Network network)`.
- `@Inject NetworkDetailsDao networkDetailsDao` and
  `@Inject NetworkOfferingDetailsDao networkOfferingDetailsDao` added.
- Resolution wired through
  `OvnNicTunables.resolve(OVN_LB_AFFINITY_TIMEOUT, /* vmDetails */ null,
  netDetails, offeringDetails, OvnNicConfig.LbAffinityTimeout.value(), Integer.class)`.
- Call site at line 242 already had `network` in scope; passed through.

**Followup commit.** `4a2de28177` — `test(ovn): fix LbServiceTest after buildLbOptions sig change`.
Updated `OvnLoadBalancerServiceTest:255` from
`OvnLoadBalancerService.buildLbOptions(rule)` (static, 1 arg) to
`service.buildLbOptions(rule, network)` (instance, 2 args). Build was failing
test compile until this landed.

**Verification.** Bytecode in deployed JAR reflects the new method shape and
the `OvnNicTunables.resolve(...)` call. Per-network override path is active;
operator can now set `ovn.lb.affinity_timeout` in `network_details` or
`network_offering_details` and have it take effect.

### Bug 8 — Dead `ovn.dpdk.enabled` ConfigKey — `FIXED` (removed)

**Symptom.** `OvnNicConfig.DpdkEnabled` ConfigKey was declared and registered
in `allKeys()`, plus `OvnNicTunables.OVN_DPDK_ENABLED` constant existed. But
`HypervisorGuruBase` never consulted the 4-scope resolution chain for DPDK —
DPDK selection has always been tag-based (offering tags carrying `dpdk`/
`vhost-user` tokens, matched at lines 309-315). Operator setting the ConfigKey
saw no effect; the surface was misleading.

**Fix commit.** `9ce02e1727` — `chore(ovn): remove dead DpdkEnabled ConfigKey`.

- Removed `ConfigKey<Boolean> DpdkEnabled` declaration + its `allKeys()` entry
  from `OvnNicConfig`.
- Removed `OVN_DPDK_ENABLED` constant from `OvnNicTunables`.
- Added 5-line javadoc/inline comment block in `HypervisorGuruBase` above the
  tag-based detection block documenting why DPDK is tag-based, not
  ConfigKey-based, so the design choice survives future audits.

**Decision.** User chose option B (remove ConfigKey + keep tag-based) over
A (wire 4-scope) and C (`@Deprecated` ConfigKey).

**Verification.** Production search `grep -rn "DpdkEnabled = new ConfigKey\|OVN_DPDK_ENABLED"
api server plugins engine/src/main/` returns ZERO matches across the source
tree. Tag-based DPDK detection still functional and documented inline.

### Bug 9 — `SOURCE_HASH_FIELDS` used invalid OVN column names — `FIXED`

**Symptom.** `OvnLoadBalancerService.SOURCE_HASH_FIELDS` defined the source-hash
selection_fields as `["ip_src", "ip_dst", "tcp_src", "tcp_dst"]`. OVN's
`Load_Balancer.selection_fields` schema only accepts `eth_dst`, `eth_src`,
`ip_dst`, `ip_src`, `ipv6_dst`, `ipv6_src`, `tp_dst`, `tp_src`. Any LB rule
with `algorithm=source` triggered an OVSDB constraint violation on insert and
silently never wrote a `Load_Balancer` row to NB. CloudStack-side LB state
read `Active`, OVN-side had no row.

Surfaced 2026-05-09 22:25 UTC during E2E feature test:
```
ERROR OvnLoadBalancerService: failed to apply LB rule id=589:
OVSDB op 0 (insert) failed: {"details":"tcp_src is not one of the allowed
values ([eth_dst, eth_src, ip_dst, ip_src, ipv6_dst, ipv6_src, tp_dst,
tp_src])","error":"constraint violation"}
```

**Fix commit.** `cda1af91c7` — `fix(ovn): use OVN tp_src/tp_dst not tcp_src/tcp_dst in selection_fields`.

`OvnLoadBalancerService.java`:
- Line 114: `SOURCE_HASH_FIELDS = List.of("ip_src", "ip_dst", "tp_src", "tp_dst");`
- Line 88 (javadoc): `selection_fields=[ip_src, ip_dst, tp_src, tp_dst]`.

**Verification.** Post-deploy E2E lifecycle test (Step 4 of audit dispatch
log): LB rule `algorithm=source` now creates a `Load_Balancer` row in OVN NB
with `selection_fields=[ip_dst ip_src tp_dst tp_src]`,
`options.affinity_timeout=99` (also exercises Bug 7 4-scope chain), and
`vips=217.179.89.35:80=10.99.1.28:80`. Algorithm=roundrobin produces same row
shape with `selection_fields=[]`. Delete cascades cleanly.

## Commit chain

| Order | Hash | Subject |
|---|---|---|
| 1 | `22a4715f0f` | feat(ovn): wire 11 OVN tunables across NicTO + NB client + dispatcher |
| 2 | `428369968e` | style(ovn): remove unused imports flagged by checkstyle |
| 3 | `54475e57b9` | fix(api): expose vdpa_enabled in NetworkOfferingResponse |
| 4 | `b159261abb` | fix(configuration): enforce vdpaenabled/hwoffloadenabled mutex |
| 5 | `fb05f195b4` | fix(ovn): resolve LB affinity_timeout via 4-scope chain |
| 6 | `9ce02e1727` | chore(ovn): remove dead DpdkEnabled ConfigKey |
| 7 | `31f1df9c4f` | feat(ui): add vdpa/hwoffload toggles to network offering form |
| 8 | `4a2de28177` | test(ovn): fix LbServiceTest after buildLbOptions sig change |
| 9 | `cda1af91c7` | fix(ovn): use OVN tp_src/tp_dst not tcp_src/tcp_dst in selection_fields |

Commits 1+2 carry the deployed-but-uncommitted Onda 1+2+3 wiring (already in
production prior to this audit). Commits 3-8 carry the original 8 bug fixes
from this audit. Commit 9 carries Bug 9 surfaced + fixed during the E2E
feature test that followed the first deploy. The pre-existing Bug 4 fix
landed in an earlier session and was captured by the first rebuild without a
new commit.

Post-fix uber-jar md5: `332f69b06a8001292f5e09a1a8aa6158` (replaces
`fc3ddfed9114a35d88a134f687103c12` from the pre-Bug-9 deploy).

## Open / deferred items

### Bug 11 — Silent fallback when vDPA user VM guard rejects vDPA offering — `OPEN` (HIGH)

**Symptom.** Operator deploys a VM on `tier-vdpa-ovn` (vdpaEnabled=true, hwOffloadEnabled=false)
expecting `<interface type='vdpa'>` and live-migratable HW-offloaded NIC. The VM starts without
error, appears healthy via API, but actually runs with `<interface type='bridge'>` (OVN TAP path).
No API error is surfaced; no `actualNicDriver` field exists in the `deployVirtualMachine` response.
Operator only learns of the fallback via `virsh dumpxml <vm>` or by measuring NIC throughput and
observing software-path latency.

**Root cause.** `HypervisorGuruBase.java:730-735` contains a non-VR guard for user VMs:

```java
if (!isVr) {
    NetworkOfferingVO off = networkOfferingDao.findById(network.getNetworkOfferingId());
    if (off == null || !off.isHwOffloadEnabled()) {
        return;   // <-- exits here for all vDPA offerings
    }
}
```

Because Bug 5 enforced the vDPA/hwOffload mutex (`hwOffloadEnabled` and `vdpaEnabled` are mutually
exclusive), vDPA offerings always have `isHwOffloadEnabled() == false`. The guard therefore returns
before reaching the `shouldVdpa` branch at line 740, which would have called
`vfPoolManager.allocateForVdpa()` and set `nicTo.setUseVdpa(true)`. The NicTO leaves
`allocateVfIfHwOffload()` with `useVdpa=false`, causing `OvnVifDriver` (bridge + OVS virtualport)
to be selected instead of `OvnVdpaVifDriver`.

**DB evidence (Phase A, 2026-05-09).** `sriov_vf_pool` has 640 rows total across 6 data nodes.
FREE vDPA-capable VFs exist on every host at time of probe: aragog 11, fluffy 13, nagini 14,
norbert 10, scabbers 13, trevor 16 (total 77 FREE). Pool is NOT depleted. The 7 VDPA-ALLOCATED
rows belong to VMs on the `tier-vf-ovn` SR-IOV tier (not the vDPA tier) — those were promoted via
the VR path in `VpcVirtualNetworkApplianceManagerImpl:1335`, which bypasses the non-VR guard.
Verdict: guard logic bug, not capacity shortage.

**Surfaced during.** 2026-05-09 20-VM driver coverage test (Slytherin cluster). 7/7 VMs deployed
on `tier-vdpa-ovn` landed with `<interface type='bridge'>` instead of `<interface type='vdpa'>`.
Kernel modules, `/dev/vhost-vdpa-N` devices, and sysfs vdpa entries confirmed present on all 6
data nodes prior to the test. OVS `hw-offload=true` set cluster-wide.

**Impact.** Operator picks `tier-vdpa-ovn` expecting live-migratable HW-offloaded NIC; receives
kernel-TAP fallback with no indication from the API. Trust and observability: HIGH severity — an
operator running production workloads on this offering unknowingly operates at degraded NIC
performance and without the HW-datapath properties (live migration with vDPA semantics). The
offering contract is silently broken. Performance impact: MEDIUM severity — VM remains functional
but loses HW-accelerated data path.

**Suggested fix path.**

- **Option 1 (fail-fast — recommended primary):** In `HypervisorGuruBase.java:733`, extend the
  guard to also allow vDPA offerings through:
  `if (off == null || (!off.isHwOffloadEnabled() && !off.isVdpaEnabled())) { return; }`
  Then the `shouldVdpa` branch at line 740 fires correctly for user VMs. Combined with Option 2
  for observability.

- **Option 2 (observability — recommended companion):** Add `actualNicDriver` (string enum:
  `VDPA`, `VF`, `TAP`) to `DeployVirtualMachineResponse` / `VirtualMachineResponse`. Populated
  by inspecting the NicTO state after `allocateVfIfHwOffload()` returns. Operator can detect
  fallback without `virsh dumpxml`.

- **Option 3 (preventive):** Capacity pre-check on offering create — refuse to enable
  `tier-vdpa-ovn` in a zone where no host has `sriov_vf_pool` rows in state FREE. Guards against
  offering creation on clusters with no HW inventory, but does not fix the guard logic itself.

Recommend Option 1 + Option 2 as the primary fix. Option 3 is additive and can follow
independently. A `forceVdpaFallback` detail flag on the offering (for operators who intentionally
want soft-fallback for mixed clusters) is a stretch goal only if Option 1 proves operationally
disruptive.

**Status.** OPEN — fix not yet implemented. Guard bug at `HypervisorGuruBase.java:733` is
confirmed; pool has ample FREE capacity. Next step: fix the guard, add the response field, rebuild
+ redeploy, re-run the 7-VM vDPA coverage test.

---

### Bug 10 — `updateLoadBalancerRule` algorithm change does not re-sync OVN — `OPEN` (LOW priority, pre-existing)

Surfaced during 2026-05-09 E2E test Step 6. `cmk update loadbalancerrule
algorithm=...` updates the CloudStack DB and returns success, but the OVN
write path only fires when the LB transitions to `Active` (i.e., during a
full apply with at least one reachable backend). After update, the
`external_ids:cs_algo` and `selection_fields` in OVN NB still reflected the
pre-update value. Behaviour is consistent with CloudStack's general
`update*` state-machine semantics, not introduced by any of the 9 bugs in
this audit, and does not block the LB algorithm working when the rule is
re-applied via assign/remove backend or full network restart.

Possible fix path: add a `forceApply` branch in `OvnLoadBalancerService` on
`updateLoadBalancerRule` that re-walks `applyLBRules` for the network when
algorithm or selection-affecting fields change. Out of scope for this audit;
file as a follow-up if operators need hot algorithm changes.

## Skip list for future audits

A future "find all the bugs" dispatch on the OVN fork SHOULD NOT re-flag any
of the items above without first reading this file and confirming whether the
relevant code surface has actually drifted since 2026-05-09. Specifically:

- The `vdpa_enabled` response chain (view + VO + builder).
- The `internetProtocol` field in `VpcOfferingResponse`.
- The `vdpaenabled`/`hwoffloadenabled` create-time mutex in
  `ConfigurationManagerImpl`.
- The two UI toggles + their i18n keys + client-side mutex.
- The 4-scope resolution wiring for `ovn.lb.affinity_timeout` in
  `OvnLoadBalancerService.buildLbOptions`.
- The absence of a live `ovn.dpdk.enabled` ConfigKey (intentionally dropped;
  DPDK is tag-based by design, documented inline at HypervisorGuruBase:300).
- The `SOURCE_HASH_FIELDS` constant in `OvnLoadBalancerService` — must contain
  exactly `["ip_src", "ip_dst", "tp_src", "tp_dst"]` (OVN-valid column names).
  Do NOT introduce `tcp_src`/`tcp_dst`/`udp_src`/`udp_dst` — OVN rejects them
  with `constraint violation` and the LB row never lands in NB.
- `updateLoadBalancerRule` algorithm change is `OPEN` (Bug 10) — do not flag
  re-application gap as new unless the fix landed and regressed.
- The `HypervisorGuruBase` non-VR guard at line 733 missing `isVdpaEnabled()` is `OPEN` (Bug 11)
  — do not re-flag vDPA user VM silent fallback as a new finding. Root cause is the guard logic,
  not pool depletion; DB probe confirmed 77 FREE vDPA-capable VF rows at time of discovery.
