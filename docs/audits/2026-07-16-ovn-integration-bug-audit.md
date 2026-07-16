# OVN ↔ CloudStack integration — bug audit & fix plan

**Date:** 2026-07-16
**Scope:** OVN network plugin (`plugins/network-elements/ovn/`) — k8s/CKS, DNS, DHCP,
load balancing, firewall/ACL integration.
**Method:** 4 per-domain reviewers (DHCP/DNS, LB, firewall/ACL, CKS/k8s) +
line-level verification against source. Findings below are confirmed by reading the
exact code path unless marked **PLAUSIBLE**.
**Status:** fixes implemented locally for findings 1,2,3,4,6,7,8a/8b/8c,9,11,12,13,14; not deployed. Deploy path is the standing cycle
(code local → `git push aragog main` → build → jar-direct 3 controls one-at-a-time
→ restart → verify). Management restart is disruptive → confirm before jar-direct.

---

## Severity summary

| # | Sev | Area | File | One-liner |
|---|-----|------|------|-----------|
| 1 | 🔴 CRITICAL | ACL | OvnFirewallService.java:276,296 | VPC ACL shared across tiers applied on only ONE tier → default-allow on the rest |
| 2 | 🔴 CRITICAL | CKS/reconciler | OvnReconcilerService.java:2214 | `runOvnReconciler` deletes live imported worker LSPs (ORPHAN_NIC treated as NIC) |
| 3 | 🔴 CRITICAL | DHCP | OvnDhcpService.java:270,315 | DHCPv6 `server_id` set to an IPv6 addr instead of a MAC → northd warning, invalid row |
| 4 | 🟠 MAJOR | DNS | OvnDnsService.java:71,92,123 | Per-JVM DNS snapshot on a 3-node mgmt cluster wipes other VMs' records |
| 5 | 🟠 FALSE POSITIVE* | Firewall | OvnFirewallService.java:791 | Baseline matches ICMPv6 ND/RA, but built-in priority-34000 ACLs should prevail; live trace required |
| 6 | 🟠 MAJOR | Firewall | OvnFirewallService.java:458 | CIDR predicate hardcodes `ip4.src/dst` → IPv6 CIDR makes northd reject the ACL |
| 7 | 🟠 MAJOR | ACL | OvnFirewallService.java:223 | `clearAclsForTier` deletes ALL tiers' ACL mappings while clearing one switch |
| 8 | 🟠 MAJOR | LB | OvnLoadBalancerService.java:446 + OvnPendingDeletionProcessor.java:405 | LB delete retry skips detach; stale backends kept when vips set empties |
| 9 | 🟠 MAJOR | CKS | OvnCksWorkerDiscovery.java:90 | `SQLException` swallowed as "zero workers" → wipes LB backends / prunes ECMP routes |
| 10 | 🟡 PLAUSIBLE | LB/SNAT | OvnNbClient.java:1388 | `lb_force_snat_ip=router_ip` on distributed LR → "bad ip router_ip" log, east-west VIP SNAT not applied (live verification required) |
| 11 | 🟡 MINOR | reconciler | OvnReconcilerService.java:2229-2243 | `cloudstackEntityExists` fail-open (return true) for NETWORK_ACL/LB/PF/FIREWALL → deleted rule stays enforced |
| 12 | 🟡 MINOR | DHCP | OvnDhcpService.java:183-218 | DHCP_Options never resync content on reuse; `updateDhcpOptions` is dead code |
| 13 | 🟡 MINOR | Firewall | OvnFirewallService.java:440,504,685 | ICMP maps only `icmp4`; default-egress allow is v4-only |
| 14 | 🟡 MINOR | CKS | OvnNetworkElement.java:448 | LSP `addresses` + `port_security` written in 2 non-atomic NB txns |

---

## Detailed findings + proposed fixes

### 1. 🔴 VPC ACL shared across tiers applied on only ONE tier
**File:** `element/OvnFirewallService.java:276,296`
**Cause:** the ACL→OVN mapping is keyed by `(Kind.NETWORK_ACL, rule.getId(), controllerId)`
with **no tier/network discriminator**. Core `NetworkACLManagerImpl.applyNetworkACL`
applies the same `NetworkACLItem.id`s to every tier bound to the shared ACL list. On the
second tier, `findByCsId` finds the first tier's mapping, `rowExistsByUuid("ACL", …)` is
true → **idempotent skip** → that tier's LS gets no ACL for the rule.
**Impact:** default-deny list → other tiers fall to OVN default-allow (traffic that should
be denied is wide open). Allow-in-deny list → other tiers blackholed.
**Fix:** include the network id in the mapping key. Either add a network-scoped
`findByCsId` variant, or encode `(networkId, ruleId)` into the cs_id / ovn_name so each
tier gets its own mapping row. Audit `OvnLogicalIdMapDao` for a network-scoped lookup;
add one if absent. Touch the reorder + clear paths (finding 7) together — same key.

### 2. 🔴 `runOvnReconciler` deletes live imported worker LSPs
**File:** `manager/OvnReconcilerService.java:2214` (`cloudstackEntityExists`)
**Cause:** `case NIC: case ORPHAN_NIC: … return nicDao.findById(csId) != null;` — but
`ORPHAN_NIC` rows store a **synthetic hash** as csId (`OvnVpcImporter.synthesise()`,
range 1e9–2e9), never a real nic id → `findById` returns null → the reconciler
classifies every imported/orphan CKS worker LSP as "CS entity gone" and deletes the live
OVN port. The `adoptOvnNic` command referenced in doc comments does not exist.
**Impact:** running the documented-safe `cmk runOvnReconciler` (dryrun defaults false)
blackholes imported worker traffic, no recovery path.
**Fix:** give `ORPHAN_NIC` its own `case` returning `true` (never auto-reap synthetic
rows via the NIC probe) — or resolve orphan rows through the correct table. Same
synthetic-csId bug also affects `applyExtraPortSecurity()` at `:463` (finding covered
here): the extra-CIDR resync silently skips ORPHAN_NIC LSPs. Fix both together.

### 3. 🔴 DHCPv6 `server_id` is an IPv6 address, not a MAC
**File:** `element/OvnDhcpService.java:270` (`buildDhcpv6Options`), `:315`
(`deriveLinkLocalFromGateway`)
**Cause:** `opts.put("server_id", deriveLinkLocalFromGateway(network.getIp6Gateway()))`
puts the v6 gateway (or literal `fe80::1`) into `server_id`. OVN's DHCPv6 `server_id`
must be a **MAC** (used to build the DUID-LL) — exactly analogous to the `server_mac`
this file already derives for DHCPv4 (`deriveServerMac`, :297).
**Impact:** root cause of the live rate-limited `ovn-northd.log` warning
`server_id not present in the DHCPv6 options for lport lsp-a9ff1dc4-…` (854 dropped).
Pinned onto every dual-stack (CKS) NIC even though those tiers are SLAAC-only.
**Fix (minimal, low-risk):** add `deriveServerMacV6(gw6)` that returns a stable MAC
(reuse the v4 octet-mix approach on the v6 address bytes; fallback `02:00:00:00:00:02`),
and set `server_id` to it. **Do NOT** change the SLAAC pinning behaviour in the same
commit (separate decision — some tiers may legitimately want stateful DHCPv6).
**Secondary (finding 4-of-DHCP review):** DHCPv4 `server_id` at :228 is set to
`defaultString(gateway)` unconditionally → `server_id=""` when gateway blank; guard it.

### 4. 🟠 Per-JVM DNS snapshot wipes records on a 3-node mgmt cluster
**File:** `element/OvnDnsService.java:71` (field), `:92` (add), `:123` (remove)
**Cause:** `snapshots` is an in-memory `Map` per JVM; the javadoc claims a lazy rebuild
from the live NB row but **no read-back exists**. `updateDnsRecords` does a full column
replace. VM A registers via node 1, VM B via node 2 (empty map) → overwrites A's record.
**Impact:** with CKS worker NICs distributing NIC-prepare across 3 mgmt nodes, most
internal DNS names silently stop resolving.
**Fix:** make the update read-modify-write against the live NB `records` column
(`OvnNbClient` read of the DNS row) instead of trusting the local snapshot — or drop the
snapshot entirely and always merge into the live map. Also fix removal keying on a
possibly-renamed hostname (`:117-124`) — key removal by nic/IP, not recomputed hostname.

### 5. 🟠 Baseline `ip4 || ip6` drop black-holes IPv6 ND/RA
**File:** `element/OvnFirewallService.java:791` (`ensureBaselineDrop`)
**Disposition:** **FALSE POSITIVE (pending live validation).** The baseline does match
ND/RA because they are ICMPv6 and therefore satisfy `ip6`; the original claim that
`ip4 || ip6` does not match them is incorrect. OVN's built-in ACLs at priority 34000
should take precedence over this priority-10 baseline, but this worktree has no live
OVN reproduction. `ovn-trace`/live validation is required before treating this as
closed. No behavior change is made for finding 5.
**Cause:** the original audit confused the protocol classification with the ACL
priority ordering.
**Impact:** potentially dead IPv6 only if the built-in ND/RA ACLs are absent or do not
win in the deployed northd pipeline.
**Fix:** none locally; validate built-in priority-34000 ACLs with `ovn-trace`.

### 6. 🟠 CIDR predicate hardcodes `ip4.src/dst`
**File:** `element/OvnFirewallService.java:458` (`appendCidrPredicate`)
**Cause:** `final String column = ingress ? "ip4.src" : "ip4.dst";` — an IPv6 CIDR yields
`ip4.src == 2a13:8740::/64`, a type error; northd rejects the whole ACL.
**Impact:** silent per-rule: intended deny never applies (traffic allowed) or intended
allow never applies (dropped by baseline).
**Fix:** detect the CIDR family (`NetUtils.isValidIp6Cidr` / `:` presence) per entry and
emit `ip6.src/dst` for v6, grouping v4 and v6 predicates separately. Same family-split
needed for ICMP (finding 13).

### 7. 🟠 `clearAclsForTier` deletes ALL tiers' ACL mappings
**File:** `element/OvnFirewallService.java:223`
**Cause:** `clearAllAclsFromLogicalSwitch(tierLsUuid)` empties only one LS, but the loop
`listByKind(Kind.NETWORK_ACL, controllerId)` removes every tier's mapping row.
**Impact:** in multi-tier `reorderAclRules`, live ACLs on other tiers lose their mapping
→ later reconcile treats them as orphans and deletes live security ACLs.
**Fix:** scope the mapping removal to the tier being cleared (network-keyed listing — same
key work as finding 1), removing only rows whose ovn_uuid is attached to `tierLsUuid`.

### 8. 🟠 LB delete retry skips detach; stale backends kept
**Files:** `manager/OvnPendingDeletionProcessor.java:405`;
`element/OvnLoadBalancerService.java:446` (`updateExistingLbRow`), `:591` (`buildVipsMap`)
**Cause:** (a) retry path calls `nb.deleteLoadBalancer(uuid)` directly, violating its own
precondition (detach from LR/LS first) → same referential-integrity error every tick,
exhausts retries. (b) `updateExistingLbRow` skips writing `vips` when the live-destination
set is empty but still clears health-check `ip_port_mappings` → removed backends stay in
the forwarding table with no health check. (c) `buildVipsMap` ignores
`getSourcePortEnd()`/`getDestinationPortEnd()` → port-range rules truncated to first port.
**Fix:** (a) detach before delete in the retry path (mirror `revokeOne`); (b) always write
`vips` (empty map = no backends) consistently with `ip_port_mappings`; (c) expand port
ranges or reject with a clear error.

### 9. 🟠 Worker discovery swallows `SQLException` as "zero workers"
**File:** `manager/OvnCksWorkerDiscovery.java:90`
**Cause:** a `SQLException` during worker-IP discovery folds into `WorkerIps.empty()`,
indistinguishable from a genuine scale-to-zero.
**Impact:** the 60s reconcile then reprograms the live OVN VIP with **zero backends**
and/or prunes ECMP static routes → blackholes a live k8s VIP/pod route on any transient
DB blip.
**Fix:** propagate the failure (throw / return a distinct "unknown, do-not-act" result);
the resync must **skip** rewriting on an errored discovery, never treat it as authoritative
empty. Log the DB error explicitly.

### 10. 🟡 PLAUSIBLE — `lb_force_snat_ip=router_ip` on distributed LR
**File:** `client/OvnNbClient.java:1388` (`ensureLbForceSnat`), :1426
**Cause:** only writer of a `router_ip`-valued option; runs on every LR with an LB. On a
CloudStack distributed VPC LR northd cannot resolve `router_ip` → logs
`bad ip router_ip in options of router e74045b8-…`.
**Impact:** the east-west VIP force-SNAT this method guarantees is not applied →
same-subnet client→VIP fails.
**Verify first:** confirm the router topology with a live `ovn-nbctl list logical_router`
and northd log correlation before changing. If confirmed, set an explicit SNAT IP (the
VPC public/gateway IP) instead of the `router_ip` magic value, or gate the option to
gateway routers only.

### 11. 🟡 `cloudstackEntityExists` fail-open for rule kinds
**File:** `manager/OvnReconcilerService.java:2229-2243`
**Cause:** NETWORK_ACL / LOAD_BALANCER / PORT_FORWARDING / FIREWALL fall to `return true`
(no cheap exists-probe) → the stale sweep never reaps a rule whose CS entity was deleted
but whose revoke failed → allow ACL stays enforced in OVN indefinitely. Same pattern class
as the already-fixed BGP row bug.
**Fix:** add real exists-probes (FirewallRulesDao / LoadBalancerDao / NetworkACLItemDao)
so revoke-failure leftovers are reaped. Lower priority — depends on DAO wiring.

### 12. 🟡 DHCP_Options never resync content on reuse
**File:** `element/OvnDhcpService.java:183-218`; `client/OvnNbClient.java:1825`
(`updateDhcpOptions` — only definition, zero callers)
**Cause:** `ensureDhcpOptionsRow(V6)` only checks row existence, never re-applies options.
Changing a tier's DNS/gateway via `updateNetwork` never reaches already-deployed VMs.
**Fix:** on reuse, compare the desired options map against the live row and call
`updateDhcpOptions` when drifted (wire the dead method into the ensure path).

### 13. 🟡 ICMP + default-egress are v4-only
**File:** `element/OvnFirewallService.java:440,504` (ICMP→`icmp4`), `:685`
(default-egress `"ip4"`)
**Fix:** family-split ICMP (`icmp6.type/code`) and open IPv6 egress in the default-allow
override. Bundle with findings 5/6 (dual-stack firewall commit).

### 14. 🟡 LSP addresses + port_security non-atomic
**File:** `element/OvnNetworkElement.java:448`
**Cause:** `addresses` and `port_security` written in two separate NB txns → brief
fully-permissive window; no reconcile path back if the second call fails.
**Fix:** combine into one transaction (single `Logical_Switch_Port` mutate) or ensure the
extra-port-security resync covers a half-written port.

---

## Root-cause clusters (fix by theme, not one-by-one)

1. **Missing/wrong mapping discriminator** — findings 1, 2, 7 (+ applyExtraPortSecurity):
   ACL mapping needs the tier key; ORPHAN_NIC needs its own reap rule. One design pass on
   `OvnLogicalIdMap` keying resolves three.
2. **v4-only assumptions in dual-stack paths** — findings 5, 6, 13: one "dual-stack
   firewall" commit (ND/RA allow + family-split CIDR/ICMP + v6 egress).
3. **Fail-open silence** — findings 9, 11 (+ the pattern behind 2): a DB/probe failure
   must never be read as authoritative-empty. Throw/skip, log, retry next tick.
4. **DHCP/DNS state correctness** — findings 3, 4, 12: v6 server_id MAC, cluster-safe DNS
   read-modify-write, DHCP options content resync.

---

## Proposed commit grouping (Angular Conventional Commits, one logical change each)

| Commit | Findings | Risk |
|--------|----------|------|
| `fix(ovn): key ACL mappings by tier + own reap rule for ORPHAN_NIC` | 1, 2, 7, +:463 | high — core ACL path; test multi-tier VPC |
| `fix(ovn): dual-stack firewall (IPv6 ND/RA allow, v6 CIDR/ICMP, v6 egress)` | 5, 6, 13 | med |
| `fix(ovn): DHCPv6 server_id MAC + guard v4 server_id` | 3 | low |
| `fix(ovn): cluster-safe DNS records read-modify-write` | 4 | med |
| `fix(ovn): fail-closed CKS worker discovery` | 9 | low |
| `fix(ovn): LB delete detach + consistent vips/health-check + port ranges` | 8 | med |
| `fix(ovn): resync DHCP_Options content on drift` | 12 | low |
| `fix(ovn): stale-reap probes for ACL/LB/PF/FIREWALL kinds` | 11 | low |
| `fix(ovn): atomic LSP addresses+port_security` | 14 | low |
| (investigate) `fix(ovn): explicit lb force-snat IP on distributed LR` | 10 | needs live verify |

**Deploy:** each commit → `git push aragog main` → build module on aragog → jar-uf into
`cloudstack-4.24.1.31-SNAPSHOT.jar` on the 3 controls (one at a time) → restart → verify
in `management-server.log` + `ovn-nbctl`/`ovn-northd.log`. Management restart is
disruptive — confirm the deploy window before jar-direct. Origin push only when asked.

## Verification hooks per fix (what to watch)
- **3:** `ovn-northd.log` — the `server_id not present` warning must stop.
- **10:** `ovn-northd.log` — `bad ip router_ip` must stop; east-west VIP curl succeeds.
- **1/7:** create a 2-tier VPC with a shared deny ACL; confirm `ovn-nbctl list acl` on
  BOTH tier LSes.
- **5/6/13:** dual-stack tier — VM gets SLAAC GUA + `ping6` gateway through the firewall.
- **2:** `cmk runOvnReconciler dryrun=true` must report ZERO ORPHAN_NIC deletions.
- **4:** register DNS from two mgmt nodes; both records survive in the NB `DNS` row.
- **9:** kill DB access mid-reconcile (test) — LB backends/ECMP routes unchanged.

## Local implementation evidence (2026-07-16)

- ACL mappings now include an explicit `network_id` discriminator; `ovn_name` is no
  longer part of global identity. Clear/revoke paths use the tier-scoped key.
  `ORPHAN_NIC` synthetic IDs are never probed as NIC IDs, and extra
  port-security resync skips them.
- Firewall matching is family-aware for CIDRs and ICMP, default egress covers both
  families, while finding 5 is explicitly retained as a false positive (ND/RA are
  non-IP and do not match `ip4 || ip6`).
- DHCPv6 `server_id` is derived as a stable MAC; v4 omits an empty server ID; reused
  DHCP rows are content-resynced. DNS updates use atomic NB map mutations (and live
  reads only to find renamed-IP removal keys), so no JVM snapshot or lost update is
  authoritative.
- LB retries detach from known logical-switch/router parents before delete; empty VIP
  maps are written and port ranges are expanded. CKS SQL errors now abort reconciliation
  rather than producing authoritative zero workers. LSP addresses and port security are
  inserted in one NB transaction.
- Stale-reap probes were wired for ACL, firewall, LB and PF entities. Finding 10 remains
  plausible and unchanged; its source reference is corrected to `OvnNbClient.java`.

Tests and build results must be appended here only after they run; no live deployment was
performed in this worktree.

### Test log

- `git diff --check`: passed.
- `mvn -pl plugins/network-elements/ovn -am -DskipTests compile`: blocked before the
  OVN module by the local Maven repository missing the reactor test artifact
  `org.apache.cloudstack:cloud-api:jar:tests:4.24.1.31-SNAPSHOT` (HTTP snapshot mirror
  is blocked). No test success is claimed.
- `mvn -pl plugins/network-elements/ovn -am -Dtest=OvnFirewallServiceTest,OvnLoadBalancerServiceTest,OvnNbClientAclTest -Dsurefire.failIfNoSpecifiedTests=false -Dnet.bytebuddy.experimental=true test`: PASS, 46 tests, 0 failures/errors. The plain command without `-Dnet.bytebuddy.experimental=true` is FAIL on this JDK 25 because the repository Byte Buddy version supports through JDK 24.
- `mvn -pl plugins/network-elements/ovn -am -Dnet.bytebuddy.experimental=true test`: FAIL before reaching OVN because the unrelated `cloud-utils` suite has 1 pre-existing environment failure (`NetUtilsTest.testAllIpsOfDefaultNic`, `UnsupportedOperationException`); 2,385 tests ran in that module, 0 failures and 1 error.
- A local install (`mvn -pl plugins/network-elements/ovn -am -DskipTests -Dmaven.test.skip=true install`) was used only to make all reactor artifacts available locally; it is not a deployment.
- Additional regression class added: `OvnDhcpServiceTest.dhcpOptionsHaveCorrectServerIdentity`; the final post-`network_id` full OVN test run was not completed because reactor artifact installation timed out before module test execution. No PASS is claimed for that new test yet.
- Worker SQL failure propagation is covered by the fail-closed implementation, but a
  deterministic SQLException unit test is not currently feasible without introducing
  a seam for `TransactionLegacy.currentTxn()`/the static DB connection factory; the
  existing class obtains its transaction statically. This is recorded as a test gap,
  not as passing evidence. DNS atomic mutations are covered at OVSDB-wire level by
  `OvnNbClientAclTest`; reconciler live probes and LSP transaction behavior still need
  a live NB/DB integration fixture.

### Independent-finding matrix

| Finding | Status | Evidence / remaining risk |
|---|---|---|
| 1 | PASS (local) | Explicit `network_id` key plus legacy migration test; live multi-tier OVN not run. |
| 2 | PASS (local) | `ORPHAN_NIC` no longer probes NIC DAO; live importer/reaper not run. |
| 3 | PASS (local) | DHCPv6 MAC server identity and v4 empty omission; no live northd validation. |
| 4 | PASS (local) | Atomic DNS map mutations and wire tests; DB cluster integration not run. |
| 5 | FALSE POSITIVE / LIVE REQUIRED | Baseline does match ICMPv6 ND/RA; built-in priority-34000 ACL precedence needs `ovn-trace`. |
| 6 | PASS (local) | CIDR and ICMP family split covered by firewall regression test. |
| 7 | PASS (local) | Tier-scoped clear and legacy UUID ownership handling; live multi-tier teardown not run. |
| 8a | PASS (local) | Empty VIP map is always written. |
| 8b | PASS (local) | LB detach precedes delete in synchronous, pending, and stale-reap paths. |
| 8c | PASS (local) | Equal-width ranges expand 1:1; incompatible ranges reject, with tests. |
| 9 | PASS (local) | SQL errors propagate as `OvnException`; callers do not interpret failure as zero. SQLException injection test remains blocked by static `TransactionLegacy` seam. |
| 10 | UNCHANGED / LIVE REQUIRED | Plausible `OvnNbClient.java` issue intentionally not changed. |
| 11 | PASS (local) | Reconciler probes ACL/LB/PF/firewall DAOs. |
| 12 | PASS (local) | Reused DHCP rows are content-resynced. |
| 13 | PASS (local) | ICMP4/ICMP6 and dual-stack egress behavior covered locally. |
| 14 | PASS (local) | LSP insert includes addresses and port security in one transaction; live NB verification not run. |
