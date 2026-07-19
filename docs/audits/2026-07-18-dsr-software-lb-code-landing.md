# Change record — 2026-07-18 DSR software LB / OVN / BGP code landing

**Date:** 2026-07-18  
**Scope:** CloudStack OVN fork — `lb_kind=DSR_SOFTWARE` landing, DSR VIP ECMP, BGP cutover, CT plane isolation, `lb_force_snat_ip` topology gate  
**Branch:** `main`  
**Head (this audit):** `3ca99e3ab0c97dc8272b518c5aa24f64190541c3`  
**Binding ADR (cross-repo):** `~/dev/infra-base/docs/architecture/0001-dual-stack-active-active-dsr-software-lb.md`  
**Parent change record (fleet/ops status):** `~/dev/infra-base/docs/audits/2026-07-18-lax-dsr-ovn-bgp-dx6-status.md`  
**Companion audit (constraints, OPEN):** `docs/audits/2026-07-17-dsr-software-lb-parity-and-ct-separation.md` (DSR-1..DSR-9)  
**Implementation contract:** `docs/design/dsr-software-lb.md`

**Method:** read-only source review against the ADR + 2026-07-17 audit constraints; commit-anchored facts only (no chat-history reliance). No live-system mutations in this pass.

---

## 0. Headline

This audit records the **code side** of the 2026-07-18 DSR/OVN/BGP workstream — every
commit, what it changed, which audit finding or ADR lock it addresses, and the
verification evidence produced. It is the durable, reproducible companion to the
fleet/ops status record at `infra-base/docs/audits/2026-07-18-lax-dsr-ovn-bgp-dx6-status.md`.

> ✅ **Rollout status: COMPLETE 3/3.** The corrective fat JAR
> (`b2bf1d1e786ad40566862a0721037dd7`) is live on all three LAX control nodes
> (Bellatrix, Barty, Voldemort) with new PIDs and verified backups (§5.1). Scoped CMK
> reconciler runs **complete**: VPCs 924 + 927 legacy `router_ip` stripped; 18 OVS_POLICY
> drift/fixed across the six data hosts; final global dry-run `totalorphans=0`,
> `totalstalemappings=0`, `Hairpin_Drift=0`; 19 active PF rules preserved; 2 system VMs
> running; zone `Enabled`; traffic unchanged (§5.3–§5.5).
>
> The storm-control incident (parent record §6) remains calibrated: switch proved
> intermittent host-facing broadcast bursts above 1% per 100G member; baseline OVS/host
> clean; source MAC/VLAN still unknown. **Do not imply the hairpin drift caused the
> broadcast storm** — no evidence supports that causal link.

---

## 1. Commit log (this workstream, 2026-07-17 → 2026-07-18)

Reverse-chronological, as emitted by `git log`. All hashes are full SHAs; short forms
used inline map unambiguously.

| # | Commit | Date (UTC-3) | Type | Summary |
|---|---|---|---|---|
| 1 | `3ca99e3ab0c97dc8272b518c5aa24f64190541c3` | 2026-07-18 17:43 | fix(ovn) | Gate force SNAT by router topology |
| 2 | `b2cd36c4722989f24446fbf82054859b26e623b1` | 2026-07-18 07:03 | fix(ovn) | Refuse `ovn.lb.auto.cks` rewrite for Istio public/accounting edges |
| 3 | `7b39879b24e65d7c70175969a2040d8b9f504a22` | 2026-07-18 06:01 | fix(ovn) | CT BGP withdraw must preserve kernel transport host routes |
| 4 | `8004a69d586aa5eabf2f2a6189d9c4abe174a442` | 2026-07-18 04:16 | fix(lb) | Route DSR public IPv6 LB apply through `DsrSoftwareLbService` |
| 5 | `75b347d9c10db315d7232921b656b55b3da06485` | 2026-07-18 03:42 | test(ovn) | Cover reverse-order and BGP withdraw retry for DSR siblings |
| 6 | `234d4786b2fc2323aae86737caa2d2a9e5d0ab4a` | 2026-07-18 03:36 | fix(ovn) | DSR BGP withdraw via proven `ctWithdrawn`, `GlobalLock`, NAT residual |
| 7 | `9deba4fedad88d32761c16a699cf125e1f5240ee` | 2026-07-18 03:19 | fix(ovn) | DSR VIP-scoped ECMP, sibling BGP refcount, CT precheck |
| 8 | `2b241edb8ddb111dddbb25c9664f1e42037c5d96` | 2026-07-18 03:02 | feat(ovn) | Program DSR VIP ECMP static routes on VPC LR |
| 9 | `9813057e14cfa7358ac4f9fa82f3af5cfd6bbbb9` | 2026-07-17 22:43 | fix(ovn) | Add scoped `LOAD_BALANCER` orphan reconciliation |
| 10 | `1126804657da9b019f4b7ace89f2bdcc3d57a230` | 2026-07-17 21:07 | fix(lb) | Stop wrapping public IPv6 VIP in IPv4-only `Ip` |
| 11 | `d678a00e74d3e92e1a34d734cab1d44fa68ad7f6` | 2026-07-17 20:59 | fix(lb) | Bind `DsrLbDesiredState.updated` with `@Temporal TIMESTAMP` |
| 12 | `4b7a119a3b193ca4e32ab2d7653c4f2e5e0764b7` | 2026-07-17 16:15 | feat(lb) | Add DSR software load balancer kind and OVN path |
| 13 | `a8a37bca0da9bf7283df80812adea2f62c90b768` | 2026-07-18 21:03 | fix(ovn) | Scoped VPC force-SNAT + `OVS_POLICY` reconcile, PF DAO fix, ACK/orphan split |
| 14 | `9fea034bec2aae5e2f24d13872834e8b8f53a341` | 2026-07-18 21:45 | fix(ovn) | Reconciler safety follow-up — NAT sweep, fail-closed LR read, ACK contract |
| 15 | `21ce09bee84ec863d7d75ff913ee4b40d7b8ce1b` | 2026-07-18 22:00 | test(ovn) | Coverage follow-up — `invokePrivate` fix, `readLogicalRouterOptionsPublic` transport tests, dup doc removal |
| 16 | `94163fdd61d66759e0a67528c363a1cd4c623d1e` | 2026-07-18 22:22 | test(ovn) | Fix Aragog reconciler test failures |

**HEAD:** `94163fdd61d66759e0a67528c363a1cd4c623d1e` (verified ancestor of `main`).
Schema upgrade carried by commit #12: **4.24.1.31 → 4.24.1.32** (`engine/schema/.../Upgrade42431to42432.java` + `schema-42431to42432.sql`).

---

## 2. Commit-by-commit detail

### 2.1 `4b7a119a3b` — feat(lb): add DSR software load balancer kind and OVN path

**Addresses:** ADR L1, L7, L8; audit DSR-1, DSR-3, DSR-8 (first-class kind + mutex surface).

Introduces the `lb_kind` contract end-to-end:

| Surface | Added |
|---|---|
| API | `lbkind` on `CreateLoadBalancerRuleCmd`; `LoadBalancerResponse` |
| Domain | `LoadBalancerContainer.LbKind { CT_LB, DSR_SOFTWARE }` |
| Schema | `LoadBalancerVO.lb_kind` (default `CT_LB`); `DsrLbDesiredStateVO` (+ DAO + `@Temporal`) |
| Upgrade | `Upgrade42431to42432` + `schema-42431to42432.sql` (4.24.1.31→4.24.1.32) |
| Validation | `DsrSoftwareLbValidator` + `DsrSoftwareLbConfig` (offering capability, mutex gate) |
| OVN dispatch | `DsrSoftwareLbService` (separate programmer; never `createLoadBalancer` for DSR); `OvnLoadBalancerService` asserts `CT_LB` before any NB LB write (`OvnLoadBalancerServiceDsrGuardTest`) |
| Reconciler | `OvnReconcilerService` filters by kind (`OvnReconcilerLbKindFilterTest`); orphan prune within kind |
| BGP | `OvnBgpRedistributeManager` kind-aware |
| Tests | `LbKindTest`, `DsrSoftwareLbServiceTest`, `DsrDualStackBgpCutoverTest`, `OvnLoadBalancerServiceDsrGuardTest`, `OvnReconcilerLbKindFilterTest`, `DsrSoftwareLbValidatorTest`, `LoadBalancerVOLbKindTest`, `OvnSpringContextWiringTest` |
| Docs | `docs/design/dsr-software-lb.md` (239 lines), `docs/audits/2026-07-17-dsr-software-lb-parity-and-ct-separation.md` (+ README index row) |

`pom.xml` version bump to `4.24.1.32-SNAPSHOT` across all modules.

### 2.2 `d678a00e74` — fix(lb): bind `DsrLbDesiredState.updated` with `@Temporal TIMESTAMP`

`GenericDaoBase.prepareAttribute` only sets `Date` params when `Flag.Date/TimeStamp/Time`
is set. Without `@Temporal`, a non-null `updated` Date fell through unbound and MySQL
rejected `INSERT` with "No value specified for parameter N" — blocking
`createLoadBalancerRule lbkind=dsr_software`. One-file, 6-line defensive fix.

### 2.3 `1126804657` — fix(lb): stop wrapping public IPv6 VIP in IPv4-only `Ip`

`createPublicIpv6LoadBalancerRule` used `new Ip(pub6.getAddress())`, which routes through
`NetUtils.ip2Long` and cannot represent IPv6. That broke Snape `::101` CT_LB create
during DSR canary recovery. Fix: validate with `NetUtils.isValidIp6` and leave
`LoadBalancingRule.sourceIp` null; OVN pub6 reconciler programs the VIP from inventory.
Adds regression tests `PublicIpv6LoadBalancerCreateSafetyTest` + `DsrLbDesiredStateVOTest`.

Addresses audit gap **G3/G4** (IPv6 public LB parity) on the CT side.

### 2.4 `9813057e14` — fix(ovn): add scoped `LOAD_BALANCER` orphan reconciliation

`runOvnReconciler` can now target a single deleted LB rule by internal `cs_id` so
residual `cs-lb-*` NB rows are detached without zone-wide NAT/hairpin side effects.
Refuses cleanup while the CS rule still exists (fail-closed). Adds
`OvnReconcilerServiceTest` coverage.

### 2.5 `2b241edb8d` — feat(ovn): program DSR VIP ECMP static routes on VPC LR

**Addresses:** ADR L3, L6, L11; audit DSR-2 (no CT for DSR selection) — the core dataplane fix.

DSR_SOFTWARE previously only wrote `dsr_lb_desired_state` and withdrew CT host BGP,
leaving the OVN LR without VIP→guest routes — traffic arriving via pub-anchor
(recursive NH via `.32`/`::32`) blackholed without `ct_lb`.

Now programs `Logical_Router_Static_Route` ECMP rows (VIP `/32` + `/128` to Ready guest
backends) tagged `cs-dsr-route`, with dual-stack atomic converge/rollback, **no OVN
`Load_Balancer`/NAT**, reconciler re-apply, and unit coverage for ownership, member
churn, and fail-closed paths. Adds `OvnConstants` DSR tags.

### 2.6 `9deba4feda` — fix(ovn): DSR VIP-scoped ECMP, sibling BGP refcount, CT precheck

Review-blocker fixes on `2b241edb`:

- **B2/B3** — `cs-dsr-route=<vpcId>|<prefix>`; 80+443 share one ECMP set; BGP
  withdraw/restore only on first/last sibling.
- **B4** — fail-closed residual CT/pub6 LB precondition (no silent delete).
- **H1** — `listEcmpStaticRoutes` propagates `OvnException`; post-condition compensates
  adds + restores removes.
- **H3** — BGP withdraw failure tears down DSR routes (no dual path).
- **H5** — inventory-eligible backends only (not Envoy Ready).
- **H6** — add-before-remove member churn.
- Multi-MS lock per VPC+VIP; legacy `ruleId` owner migration.

Large diff (`DsrSoftwareLbService` 1229-line rework) + 319-line test refresh.

### 2.7 `234d4786b2` — fix(ovn): DSR BGP withdraw via proven `ctWithdrawn`, `GlobalLock`, NAT residual

- Never skip CT BGP withdraw based on sibling Add count; only inherit when a peer
  desired-state is `PROGRAMMED` with `ctWithdrawn=true`.
- Multi-MS `GlobalLock` on `dsr.vip.<vpcId>|<vip>` with timeout.
- Residual `dnat` / `dnat_and_snat` on same VIP blocks `PROGRAMMED`.
- IPv6 owner-key canonicalize; destination requires Running VM/NIC.
- Last-sibling revoke always clears VIP ECMP; BGP restore only if withdraw proven.

Adds `OvnNbClient` DSR helpers (+84 lines) and 175 lines of `DsrSoftwareLbServiceTest`.

### 2.8 `75b347d9c1` — test(ovn): cover reverse-order and BGP withdraw retry for DSR siblings

Asserts: single CT BGP withdraw when 443 then 80 apply with both in Add; and a failed
first withdraw leaves `ctWithdrawn=false` so retry performs a real withdraw before
`PROGRAMMED`. 109-line pure-test commit.

### 2.9 `8004a69d58` — fix(lb): route DSR public IPv6 LB apply through `DsrSoftwareLbService`

CT_LB pub6 remains reconciler-owned; DSR_SOFTWARE pub6 must hit the provider path for VIP
ECMP and BGP cutover. Also resolves Running NIC by IPv6 address for destination
eligibility (audit gap **G1** on the DSR side).

### 2.10 `7b39879b24` — fix(ovn): CT BGP withdraw must preserve kernel transport host routes

`DSR withdrawCtLbBgpDualStack` ends BGP network origination only. Agent
`applyDatapathRoute` no longer `ip route del /32` or `/128` on withdraw, so
config-mgmt `public_vip_host_routes` keep OVN LR DSR transport. RCA: `::101/128`
flapped on worker HVs after `ctWithdrawn=1`. Adds `LibvirtOvnBgpAnnounceCommandWrapperTest`
coverage (+45 lines).

### 2.11 `b2cd36c472` — fix(ovn): refuse `ovn.lb.auto.cks` rewrite for Istio public/accounting edges

HostNetwork Ready-only public edges (`istio-public-*`, `istio-accounting-*`,
`pub6-istio-*`) must never be force-rewritten to the full CKS worker set. Mis-listing
them in `ovn.lb.auto.cks` re-added ineligible backends every `OvnBgpReconcileTask` tick.
Guard + tests + design/ConfigKey docs. Adds `OvnReconcilerLbAutoCksGuardTest` (91 lines).

### 2.12 `3ca99e3ab0` — fix(ovn): gate force SNAT by router topology

**Addresses:** audit finding 10 (`lb_force_snat_ip=router_ip` inert on distributed VPC LR).

#### Root cause

OVN northd's `en-lr-nat.c` only resolves the `lb_force_snat_ip=router_ip` magic value
on **centralized** routers (those carrying `options:chassis`). On a **distributed** VPC
LR (CloudStack default), the magic value is fed verbatim to
`extract_ip_address("router_ip")`, logs `bad ip router_ip` in `ovn-northd.log`, and
leaves forced-SNAT unset — the exact east-west VIP break the option was meant to prevent.

#### Fix

`ensureLbForceSnat` now reads `options:chassis`:

| LR topology | Behaviour |
|---|---|
| Centralized (has `options:chassis`) | Assert `router_ip` (unchanged, idempotent) |
| Distributed (no `options:chassis`) | Strip any legacy `router_ip` token and never rewrite it; **preserve** an explicit IPv4/IPv6 `lb_force_snat_ip` that an operator/future feature may have set |

Reconcile safety net `ensureLbForceSnatOnRoutersWithLb` walks every LR with an LB and
applies the same rule, so the two live Slytherin VPC LRs are cleaned on the next
CloudStack-owned `ovnreconciler` pass — **no manual OVN ops**.

#### Tests

`OvnNbClientLbTest` gains six topology-aware cases (centralized write/idempotent,
distributed strip/no-op/explicit-IP-preserving) plus five reconcile-path cases.
**28/28 PASS locally.** Checkstyle clean.

> Commit message explicitly ends with **"Not deployed."** — see §4.3 for the subsequent
> scoped-reconciler commits that made this safe to roll out cluster-wide.

### 2.13 `a8a37bca0d` — fix(ovn): scoped VPC force-SNAT + `OVS_POLICY` reconcile, PF DAO fix, ACK/orphan split

**Why:** `3ca99e3ab0` only fixed the *write* path (`ensureLbForceSnat`). The reconciler
had no safe way to **converge** existing distributed VPC LRs without a zone-wide sweep
that touched NAT/PF/BGP/DSR. Six fixes in one commit:

| # | Fix |
|---|---|
| 1 | `cloudstackEntityExists(PORT_FORWARDING)` now uses `PortForwardingRulesDao` (`port_forwarding_rules`) **not** `LoadBalancerDao` (`load_balancing_rules`). The DAO mismatch had caused **every active PF rule to be misclassified as stale** (`csGone=true`) — risking deletion of live OVN `Load_Balancer` + mapping rows on a global reconcile. |
| 2 | Corrected inverted comments/migration-table semantics. Current PF representation is **OVN `Load_Balancer`** (single-backend VIP); **NAT** is the legacy pre-plugin shape. `sweepLegacyPortForwardingLb` now correctly targets **legacy NAT-tagged** PF rows, not `Load_Balancer` ones. |
| 3 | Synthetic `Open_vSwitch_Hairpin` and `Open_vSwitch_TcPolicy` ACK counters moved from the `orphans` map to a **separate `acks` map**. A clean zone now reports `totalorphans=0` instead of 2. Response gains an `acksbytable` field for operator visibility without inflating orphan counts. |
| 4 | Added scoped `resourcekind=VPC` path under `runOvnReconciler`. Fail-closed: validates zone/VPC Enabled/mapping `Kind.VPC`/LR existence. Dry-run reports `would_strip_legacy_router_ip` / `would_assert_router_ip` / `no_change` without mutating. Apply calls `OvnNbClient.ensureLbForceSnat` on that one LR only. **Never** calls `reconcileZone` / `TABLE_KINDS` / OVS sweep / NAT/PF sweep / DSR/ECMP / port-security / BGP. Preserves explicit IPv4/IPv6 `lb_force_snat_ip` values and centralized `router_ip` assertions. |
| 5 | Added scoped `resourcekind=OVS_POLICY` path keyed by internal host ID. Calls only `sweepOneChassis` for that host. Validates host belongs to zone, is `Up`, and is registered as an OVN chassis. Aggregates `scanned`/`drifted`/`fixed`/`tc-policy`. No zone-wide reconcile. ACK/status rows do not inflate orphans. |
| 6 | Hairpin lifecycle regression tests (idempotent re-plug, VF migration, virtio-tap fallback, race safety). |

**API contract:** `VPC` and `OVS_POLICY` are scoped `resourcekind` values under the
existing `runOvnReconciler` API. API catalog exposes accepted values. Fail-closed on
unknown/mismatched parameters. Response JSON includes machine-checkable `acksbytable` +
`totalorphans` for gates. Tests: 66/66 PASS.

### 2.14 `9fea034bec` — fix(ovn): reconciler safety follow-up — NAT sweep, fail-closed LR read, ACK contract

Safety review blockers on `a8a37bca0d`:

| # | Blocker | Severity |
|---|---|---|
| 1 | `sweepLegacyPortForwardingLb` → `sweepLegacyPortForwardingNat`. Now queries the **NAT table** (not `Load_Balancer`) for legacy `cs_kind=PORT_FORWARDING` rows. Fail-closed ownership: deletes only when mapping has migrated to a different (LB) UUID; skips when no mapping (cannot prove PF entity is gone without `cs_id`). Never touches current `Load_Balancer` rows — the prior code queried the current PF representation table and would delete live LB-backed PF rows when their mapping was transiently absent. | CRITICAL |
| 2 | `readScopedLrOptions` removed — `OvnException` from `readLogicalRouterOptionsPublic` now **propagates** instead of being swallowed. Null options after `rowExistsByUuid=true` = race; throws before topology classification or mutation. Both transport failure and missing-row race fail closed with no `ensureLbForceSnat` call. | HIGH |
| 3 | Response contract: dry-run uses `would_*` action prefix; apply uses `asserted_*`/`stripped_*`. `Logical_Router_ForceSnat :applied=1` recorded **only AFTER** successful write. Failed write propagates exception — no result with `applied=1` is ever returned. | MED |
| 4 | Zero-valued ACK entries are no longer inserted. Absent keys mean false/zero/distributed: absent topology = distributed, absent applied = no-write. Documented in `OvnReconcileResultResponse`. | MED |
| 5 | `OVS_POLICY` positive-path test now uses non-null defaults; `easySend` returns valid `OvnOvsPolicySweepAnswer` with drift counts; `easySend` verified exactly once with `OvnOvsPolicySweepCommand` for the requested host; no NB fallthrough. | MED |
| 6 | Fail-closed tests for non-Routing host, null chassis, mismatched controller. API parsing: lowercase/whitespace accepted for `VPC`/`OVS_POLICY`/`LOAD_BALANCER`; raw NIC and blank explicitly rejected. zone-wide + `purgeUntagged` dispatches `reconcileZone`. | MED |
| 7 | `Kind.NIC` alias for `OVS_POLICY` kept (no new persisted `Kind` value). Explicit branch guard + comment in `reconcileResource`. `parseScopedKind` rejects raw NIC/blank so `Kind.NIC` cannot be invoked through the API as a NIC scope. | MED |

### 2.15 `21ce09bee8` — test(ovn): coverage follow-up

1. `OvnReconcilerServiceTest.invokePrivate` now maps `Boolean`/`Integer`/`Long` wrapper
   types to their primitive equivalents before `getDeclaredMethod` lookup. Without this,
   the 7 `sweepLegacyPortForwardingNat` tests passed `Boolean.class` for the `boolean`
   parameter and failed to resolve the method. Tests prove NAT-only behavior, no
   `Load_Balancer` query/delete, mapping-migrated deletion, mapping-absent fail-closed,
   dry-run no-write.
2. Seven direct tests of the **real** `OvnNbClient.readLogicalRouterOptionsPublic`
   transport/decode contract in `OvnNbClientLbTest` (not mocked at the service layer):
   options row present decodes map (centralized + distributed); empty options map decodes
   to non-null empty map; missing/empty rows return null; null/empty-array reply returns
   null; OVSDB transport `OvnException` propagates (does not swallow to null). Exercises
   the real JSON decode path so a wire-format regression is caught here.
3. Removed duplicate `:applied` javadoc bullet in `OvnReconcileResultResponse`.
4. Static sanity-check — no production behavior changes. Follow-up to `9fea034bec`.

### 2.16 `94163fdd61` — test(ovn): fix Aragog reconciler test failures

1. Replace mixed raw/matcher `findUuidsByExternalIds("NAT", any(), any())` stubs with
   all-matchers `eq("NAT")` in the 6 `legacySweep` tests. Mockito rejects mixing raw
   values with argument matchers in the same call; the raw `"NAT"` alongside `any()`
   caused `InvalidUseOfMatchersException` on Aragog. All 6 `when(...)` stubs now use
   `eq("NAT")` consistently. The `verify(...)` at line 139 uses all-raw values (no
   matchers) so it was already valid.
2. `readLogicalRouterOptionsReturnsNullWhenReplyNull` renamed to
   `readLogicalRouterOptionsPropagatesOvnExceptionWhenReplyNull` and changed to expect
   `OvnException`. `OvnTransaction.commit()` rejects a null reply before the reader can
   return null — the fail-closed transport semantics the scoped VPC reconciler relies
   on. A null `pool.call` result throws `OvnException` at `commit()` line 72-73, **NOT**
   a silent null return. The empty-array and empty-rows tests still expect null (valid
   `ArrayNode` replies pass the `commit()` check). Production code unchanged — the dead
   raw==null branch in `readLogicalRouterOptionsPublic` is harmless defensive code left
   in place per minimal-change preference.

Test-only; no production behavior changes.

---

## 3. ADR / audit finding coverage matrix

| ADR lock / audit finding | Addressed by | Status |
|---|---|---|
| L1 two planes, kind split | `4b7a119a3b` | CODE LANDED |
| L3 no CT for DSR selection | `2b241edb8d` (ECMP routes), `9deba4feda`, `234d4786b2` | CODE LANDED |
| L7 DSR opt-in, disabled-by-default | `4b7a119a3b` (gate + offering capability) | CODE LANDED |
| L8 `lb_kind` contract + mutex | `4b7a119a3b` (API + validate + apply + reconciler) | CODE LANDED |
| L11 chassis-redirect bypass | `2b241edb8d` (LR static routes, no CR-LRP pin) | CODE LANDED |
| X1 DSR + ct_lb same VIP:port | `4b7a119a3b` (mutex + provider dispatch) | CODE LANDED |
| X2 DSR + force/hairpin SNAT | `4b7a119a3b` (DSR path never writes), `3ca99e3ab0` (topology gate) | CODE LANDED |
| X4/X5 VIP ownership vs NAT/PF | `4b7a119a3b` (validate), `234d4786b2` (residual NAT blocks PROGRAMMED) | CODE LANDED |
| DSR-1 first-class kind | `4b7a119a3b` | CODE LANDED |
| DSR-2 no ct_lb for DSR | `2b241edb8d` + `9deba4feda` | CODE LANDED |
| DSR-3 VIP mutex | `4b7a119a3b` + `234d4786b2` | CODE LANDED |
| DSR-8 dual-stack kind parity | `4b7a119a3b` + `1126804657` + `8004a69d58` | CODE LANDED |
| Finding 10 (`lb_force_snat_ip` inert) | `3ca99e3ab0` | CODE LANDED |
| G1 IPv6 NIC resolution | `8004a69d58` (DSR side), G1 CT side remains OPEN | PARTIAL |
| DSR-4 IPv6 validate skip (G3) | `1126804657` addresses CT create path | PARTIAL — full v6 validate parity still OPEN |
| DSR-5 health-check v4-only (G1/G2) | Not addressed this workstream | OPEN |
| DSR-6 SW-kind vs HW-offload offering | Not addressed this workstream | OPEN |
| DSR-7 PF shares CT machinery | Not addressed (intentional; PF stays CT) | OPEN (by design) |
| DSR-9 finding 10 dependency | Closed by `3ca99e3ab0` + `a8a37bca0d` (DSR never depends on force SNAT; scoped VPC reconcile strips inert `router_ip` on distributed LRs) | **CLOSED** (live: VPC 924+927 stripped, post `no_change`) |

> **DSR-1..DSR-9 remain OPEN in the 2026-07-17 audit file** (append-only convention)
> until the full acceptance suite + Marvin dual-stack tests are green. This record
> captures code progress, not audit closure. Do not flip those rows without a FIX audit.

---

## 4. Build, tests, and deploy artifact

### 4.1 Build artifact (the only correct artifact)

> **Never deploy the plugin JAR separately.** The live **fat JAR** is the sole class
> owner on the management classpath; a separate plugin JAR is not loaded consistently
> and breaks class ownership.

| Property | Value |
|---|---|
| Build artifact (full fat JAR) | `/root/cloudstack/client/target/cloud-client-ui-4.24.1.32-SNAPSHOT.jar` |
| Installed filename (live) | `cloudstack-4.24.1.32-SNAPSHOT.jar` |
| New MD5 | `e83ec822c0702a3b9d8f923aa3c7ddc4` |
| New SHA256 | `d198df55dcfc4bc7bc98cad8bc767f13e7d80bdb5a9a49e1b5d6d3e8ee6311ba` |
| Old rollback MD5 | `deaa550ad657e7c2e26512b17f010b15` |

### 4.2 Test + review evidence

| Gate | Result |
|---|---|
| Unit tests for `3ca99e3ab0` (topology gate) | **28/28 PASS** locally |
| Full unit suite for the workstream | **66/66 PASS** (run **twice**) |
| Code review | **PASS** |
| Full build | **Success** |

### 4.3 Aragog validation (the only validation host)

Validation ran **exclusively on Aragog** (build host + KVM hypervisor). No local
Maven/tests/build on the workstation; no live CMK mutations during validation.

| Gate | Result |
|---|---|
| Checkstyle (OVN + KVM modules) | **0 violations** |
| OVN unit tests | **96/96 PASS** |
| KVM unit tests | **13/13 PASS** |
| Full reactor build | **BUILD SUCCESS** (Maven 3.8.7 transparently) |
| Standalone OVN plugin deployment | **None** (fat JAR is sole class owner) |

### 4.4 Final build artifact (the only correct artifact)

> **Never deploy the plugin JAR separately.** The live **fat JAR** is the sole class
> owner on the management classpath; a separate plugin JAR is not loaded consistently
> and breaks class ownership.

| Property | Value |
|---|---|
| Build artifact (full fat JAR) | `/root/cloudstack/client/target/cloud-client-ui-4.24.1.32-SNAPSHOT.jar` |
| Installed filename (live) | `cloudstack-4.24.1.32-SNAPSHOT.jar` |
| Size (bytes) | `144839459` |
| **New MD5** | `b2bf1d1e786ad40566862a0721037dd7` |
| **New SHA256** | `3121d7a9ac1fdee3408537d79af6b8dbfec510e898c7ec476f742bbe1279b29e` |
| Prior MD5 (intermediate, `3ca99e3ab0` build) | `e83ec822c0702a3b9d8f923aa3c7ddc4` |
| Prior SHA256 (intermediate) | `d198df55dcfc4bc7bc98cad8bc767f13e7d80bdb5a9a49e1b5d6d3e8ee6311ba` |
| Old rollback MD5 (pre-workstream) | `deaa550ad657e7c2e26512b17f010b15` |

> The intermediate `e83ec822…` artifact (from the `3ca99e3ab0` build) was **replaced**
> by the final `b2bf1d1e…` artifact after the scoped-reconciler commits landed. The
> older `deaa550a…` rollback JARs remain on each control node as the pre-workstream
> rollback path.

---

## 5. Rolling deployment 3/3 — COMPLETE

### 5.1 Per-node rollout (one at a time, backup → atomic replace → restart → gates)

| Control node | PID | Start (UTC) | Backup path |
|---|---|---|---|
| Bellatrix | `2359655` | `2026-07-19 01:41:40Z` | `/usr/share/cloudstack-management/rollback/cloudstack-4.24.1.32-SNAPSHOT.jar.e83ec822c0702a3b9d8f923aa3c7ddc4_20260719_014121` |
| Barty | `2367957` | `2026-07-19 01:43:37Z` | `/usr/share/cloudstack-management/rollback/cloudstack-4.24.1.32-SNAPSHOT.jar.e83ec822c0702a3b9d8f923aa3c7ddc4_20260719_014255` |
| Voldemort | `3178908` | `2026-07-19 01:45:03Z` | `/usr/share/cloudstack-management/rollback/cloudstack-4.24.1.32-SNAPSHOT.jar.e83ec822c0702a3b9d8f923aa3c7ddc4_20260719_014448` |

All three backups preserve the intermediate `e83ec822…` JAR (the `3ca99e3ab0` build).
The older pre-workstream `deaa550a…` rollback JARs remain in place on each node as the
deeper rollback path.

**Exactly one fat JAR per node; zero standalone OVN plugin JARs deployed.**

### 5.2 Dangerous old global dry-run finding (why global apply was blocked)

During the rollout a pre-flight **global** dry-run exposed a dangerous defect in the
pre-`a8a37bca0d` reconciler. This is the reason the scoped path was built and global
apply was never used.

| Finding | Detail |
|---|---|
| 19 active PF rules falsely stale | `cloudstackEntityExists(PORT_FORWARDING)` used `LoadBalancerDao` (`load_balancing_rules`) instead of `PortForwardingRulesDao` (`port_forwarding_rules`). Every active PF rule was misclassified as `csGone=true`, risking deletion of live OVN `Load_Balancer` + mapping rows on a global reconcile. |
| Legacy NAT-vs-current-LB inversion | Comments and the migration-table target were inverted. Current PF representation is **OVN `Load_Balancer`** (single-backend VIP); **NAT** is the legacy pre-plugin shape. The old `sweepLegacyPortForwardingLb` queried the **current** PF representation table and would have deleted live LB-backed PF rows when their mapping was transiently absent. |
| Synthetic ACK counts inflating orphans | Synthetic `Open_vSwitch_Hairpin` and `Open_vSwitch_TcPolicy` ACK counters lived in the `orphans` map, so a clean zone reported `totalorphans=2` instead of 0. |
| Global apply blocked | A global `dryrun=false` would have **deleted live PF/LB rows**. Global apply was therefore **never run**. The scoped `resourcekind=VPC` + `resourcekind=OVS_POLICY` paths (commits `a8a37bca0d` → `94163fdd61`) were built specifically to converge state safely without a zone-wide sweep. |

> ⚠️ **Standing rule:** **Never** run `runOvnReconciler` with `dryrun=false` globally
> (no `resourcekind`, or `resourcekind=LOAD_BALANCER` zone-wide + `purgeUntagged`).
> Use the scoped `resourcekind=VPC` / `resourcekind=OVS_POLICY` paths (see runbook
> `infra-base/docs/runbooks/dsr-ct-gate-and-jar-rollout.md` §4).

### 5.3 Final live reconciler results (scoped CMK, post-rollout)

| Check | Result |
|---|---|
| VPC 924 legacy `router_ip` | **stripped**; post dry-run = `no_change` |
| VPC 927 legacy `router_ip` | **stripped**; post dry-run = `no_change` |
| Host IDs (aragog/norbert/nagini/scabbers/fluffy/trevor) | `1` / `10` / `13` / `16` / `22` / `269` |
| OVS_POLICY drift/fixed per host (same order) | `4/3` / `4/3` / `4/3` / `0/4` / `4/3` = **18 total** |
| Final global dry-run (read-only gate) | `totalorphans=0`, `totalstalemappings=0`, `Hairpin_Drift=0` |
| ACK counters | **separated** from orphans (`acksbytable` field) |
| 19 active PF rules | **preserved** (no false-stale deletion) |

### 5.4 Final LB rule inventory (precise current language)

| Plane | Rule count | Kind | State |
|---|---|---|---|
| DSR | **6** | `lbkind=dsr_software` | all 6 Active |
| CT | **2** (`api-lb` `.33:6443`, `.35:6443`) | `lbkind=ct_lb` | all 2 Active |
| **Total** | **8** | | all 8 Active |

> **Language note:** earlier drafts of the fleet record referred to "12 DSR rules".
> That count was **12 = 6 DSR rules × 2 address families** (each dual-stack rule counted
> once per family). The precise current statement is **6 DSR rules + 2 CT rules = 8
> Active rules**. Both phrasings describe the same inventory; use the 6+2/8 form to
> avoid ambiguity.

### 5.5 Cluster health (post-rollout)

| Check | Result |
|---|---|
| System VMs running | 2 |
| Zone state | `Enabled` |
| Traffic | unchanged (no customer-impactful shift) |

---

## 6. Rollback

| Step | Action |
|---|---|
| 1 | Stop `cloudstack-management` on the node being rolled back |
| 2 | Restore the intermediate JAR (MD5 `e83ec822c0702a3b9d8f923aa3c7ddc4`) from the timestamped `rollback/` path in §5.1, **or** the deeper pre-workstream JAR (MD5 `deaa550ad657e7c2e26512b17f010b15`) for full workstream rollback |
| 3 | `md5sum` verify the restored file matches the chosen rollback MD5 |
| 4 | Restart `cloudstack-management`; confirm `active` |
| 5 | Repeat per control node (reverse order: Voldemort → Barty → Bellatrix) |

Rollback does **not** require OVN NB manual writes — the reconciler is the only valid
path to converge OVN NB with CloudStack inventory (standing rule: CloudStack owns
reconcile; never manual OVN writes for product state).

---

## 6. Operational rules reinforced (standing)

1. **CloudStack owns reconcile.** Reconciler is the only valid path to converge OVN NB
   with CloudStack inventory. Never manual OVN NB writes for product state.
2. **One control at a time.** No concurrent JAR swap + reconcile + REX + cmk changes.
3. **Backup / atomic replace / restart / gates / rollback** for every JAR deploy.
4. **No sleep / watch / background loops.** Use one-shot timestamps and finite
   foreground checks.
5. **`cmk` for apply; DB is read-only.** No direct `UPDATE`/`INSERT`/`DELETE` against
   the `cloud` MySQL DB.
6. **Never deploy the plugin JAR separately.** Fat JAR is the sole class owner.
7. **LAX only.** Never CloudStack/REX/Puppet work against NYC / `gryffindor-*`.

---

## 7. Residual operational notes (not open implementation TODOs)

These are operational hardening items, **not** open code TODOs. No implementation work
is owed from this workstream.

| # | Note |
|---|---|
| 1 | **Voldemort JDWP `:8000`** — a debug port is reachable on Voldemort. Hardening risk; close the port or bind to loopback only outside an authorized debug window. |
| 2 | **Barty local CMK auth broken** — Barty's local `cmk` invocation does not authenticate correctly. Other control nodes' `cmk` is fine; use Bellatrix or Voldemort for CMK apply until Barty auth is repaired. |
| 3 | **Aragog `tools/marvin/setup.py`** — build-generated version stamp was modified during the Aragog validation build and is **intentionally not reverted** (build artifact, not source of truth). Do not chase it as a repo change. |
| 4 | **Storm source MAC/VLAN unknown** — switch proved intermittent host-facing broadcast bursts above 1% per 100G member, but the source MAC and VLAN remain unidentified. Pinning the exact storm source requires **event-time SPAN/sFlow** on the switch, which is out of host-only scope. |

---

## 8. Code anchors (touched this workstream)

```
api/src/main/java/com/cloud/network/Network.java
api/src/main/java/com/cloud/network/lb/LoadBalancingRulesService.java
api/src/main/java/com/cloud/network/rules/LoadBalancerContainer.java
api/src/main/java/org/apache/cloudstack/api/ApiConstants.java
api/src/main/java/org/apache/cloudstack/api/command/user/loadbalancer/CreateLoadBalancerRuleCmd.java
api/src/main/java/org/apache/cloudstack/api/response/LoadBalancerResponse.java

engine/schema/src/main/java/com/cloud/network/dao/DsrLbDesiredStateDao.java
engine/schema/src/main/java/com/cloud/network/dao/DsrLbDesiredStateDaoImpl.java
engine/schema/src/main/java/com/cloud/network/dao/DsrLbDesiredStateVO.java
engine/schema/src/main/java/com/cloud/network/dao/LoadBalancerVO.java
engine/schema/src/main/java/com/cloud/upgrade/dao/Upgrade42431to42432.java
engine/schema/src/main/resources/META-INF/db/schema-42431to42432.sql

server/src/main/java/com/cloud/network/lb/DsrSoftwareLbConfig.java
server/src/main/java/com/cloud/network/lb/DsrSoftwareLbValidator.java
server/src/main/java/com/cloud/network/lb/LoadBalancingRulesManagerImpl.java

plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/DsrSoftwareLbService.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnLoadBalancerService.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnConstants.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/client/OvnNbClient.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/config/OvnNetworkConfig.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnReconcilerService.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnBgpRedistributeManager.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnAdminService.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnAdminServiceImpl.java
plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/api/command/admin/RunOvnReconcilerCmd.java

plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtOvnBgpAnnounceCommandWrapper.java
```

Test files (all under `*/src/test/java/...`): `LbKindTest`, `DsrSoftwareLbServiceTest`,
`DsrDualStackBgpCutoverTest`, `OvnLoadBalancerServiceDsrGuardTest`,
`OvnReconcilerLbKindFilterTest`, `OvnReconcilerLbAutoCksGuardTest`,
`OvnReconcilerServiceTest`, `OvnNbClientLbTest`, `DsrSoftwareLbValidatorTest`,
`LoadBalancerVOLbKindTest`, `DsrLbDesiredStateVOTest`,
`PublicIpv6LoadBalancerCreateSafetyTest`, `LibvirtOvnBgpAnnounceCommandWrapperTest`,
`OvnSpringContextWiringTest`.

---

## 9. Related

| Doc | Role |
|---|---|
| `docs/audits/2026-07-17-dsr-software-lb-parity-and-ct-separation.md` | Constraint audit (DSR-1..DSR-9 OPEN) |
| `docs/design/dsr-software-lb.md` | CS implementation contract |
| `docs/design/ovn-cks-auto-ecmp-lb.md` | CKS auto-ECMP/auto-LB design |
| `~/dev/infra-base/docs/architecture/0001-dual-stack-active-active-dsr-software-lb.md` | Cross-repo ADR |
| `~/dev/infra-base/docs/audits/2026-07-18-lax-dsr-ovn-bgp-dx6-status.md` | Fleet/ops status record (parent) |

---

## 10. Status

| Item | State |
|---|---|
| Code review | **Complete — PASS** |
| Aragog checkstyle (OVN + KVM) | 0 violations |
| Aragog OVN unit tests | 96/96 PASS |
| Aragog KVM unit tests | 13/13 PASS |
| Full reactor build (Maven 3.8.7) | **BUILD SUCCESS** |
| Fat JAR (final) | `cloud-client-ui-4.24.1.32-SNAPSHOT.jar`, size `144839459`, MD5 `b2bf1d1e786ad40566862a0721037dd7`, SHA256 `3121d7a9ac1fdee3408537d79af6b8dbfec510e898c7ec476f742bbe1279b29e` |
| Standalone OVN plugin deploy | **None** (fat JAR is sole class owner) |
| Live JAR rollout | **COMPLETE 3/3** (Bellatrix + Barty + Voldemort, 2026-07-19 01:41–01:45 UTC) |
| Scoped CMK reconciler | **Complete** — VPC 924+927 stripped; 18 OVS_POLICY drift/fixed; `totalorphans=0`, `totalstalemappings=0`, `Hairpin_Drift=0`; 19 PF preserved |
| Final LB inventory | 6 DSR + 2 CT = 8 Active rules |
| System VMs / zone / traffic | 2 running / `Enabled` / unchanged |
| Storm-control host-side | **Complete — no host defect; root cause UNDETERMINED (switch telemetry required)** (parent record §6) |
| Code/config change for storm? | **No** — hairpin drift ≠ broadcast storm cause (no evidence) |
| Residual operational notes | See §7 (Voldemort JDWP, Barty CMK auth, Aragog setup.py, storm SPAN) |
| Docs commit / push | **Pending** — stage CS docs only, commit, push `main` to `aragog` |
