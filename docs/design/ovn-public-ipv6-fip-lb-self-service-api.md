# OVN Public IPv6 FIP + LB — self-service API (inventory, not ConfigKey)

> **Status:** DESIGN / PROPOSED. Living document — the source of truth for this
> feature (no speckit for this repo). Update the **Progress** table every phase.
> Date: **2026-07-10**. Deploy path: **jar-direct** only (no `.deb` promotion).
> Sibling design: [[ovn-complete-networking]]. Operator ConfigKey runbook today:
> CKS docs `cks-salazar` / `cks-snape` `docs/ovn-public-ipv6-lb.md` (cmk
> `ovn.lr.public.ipv6.lb`).

## 1. Goal

Promote **public IPv6 VIP / FIP + Load Balancer** from an operator-only global
ConfigKey string into a **cmk-first self-service inventory + API**, reusing the
already-live Phase-1 OVN dataplane (OVN `Load_Balancer` + BGP `/128`) without
rewriting guest IPv6 SLAAC or the IPv4 `user_ip_address` stack.

| # | Outcome | Who | Surface |
|---|---|---|---|
| 1 | Allocate / release a **public IPv6 address** from a Free pool in the PARSEL-V6 prefix | account (self-service) | new inventory table + list/allocate/release APIs (cmk) |
| 2 | Create **LoadBalancer rules** bound to that public IPv6 VIP | account | `createLoadBalancerRule` + explicit `publicipv6id` (not `publicipid`) |
| 3 | Program OVN LB + announce BGP `/128` | system | Phase-1 reconciler path (sole NB mutator) |
| 4 | Migrate live CKS VIPs (`::100` / `::101`) off ConfigKey | operator once | dual-read → cutover; grandfather until migration |
| 5 | Optional StaticNAT / PortForward on public IPv6 | later sprint | same inventory; dataplane TBD |

**In scope:** LAX ("slytherin") only; OVN provider; public prefix
`2a13:8740:0:7::/64` (from `ovn.public.ipv6.prefix`); cmk as primary UX.

**Non-goals (hard):**

| Non-goal | Why |
|---|---|
| `.deb` promotion | jar-direct deploy is standing default until explicitly asked |
| Replace private ECMP ConfigKey (`ovn.lr.ecmp.static.routes`) | orthogonal private VIP path; CKS ops keep it |
| Full upstream dual-stack rewrite of CloudStack public IP | out of scope; Option B isolates public v6 inventory |
| NYC ("gryffindor") | standing workspace scope — LAX only |
| Overload guest `user_ipv6_address` | SLAAC / guest-only; different lifecycle and consumers |
| Extend `user_ip_address` / `Ip` (long) in Phase 2 | IPv4-only type system; see §2.3 |

## 2. Current state (what exists)

### 2.1 Phase-1 public IPv6 LB (live, ConfigKey-driven)

| Piece | Location | Role today |
|---|---|---|
| ConfigKey `ovn.lr.public.ipv6.lb` | `plugins/network-elements/ovn/.../config/OvnNetworkConfig.java` → `LrPublicIpv6Lb` | global multi-stanza map of all public v6 LBs |
| Parser | `.../config/OvnPublicIpv6Lb.java` | pure parser: `[vip]:port->[be]:p\|...` per network UUID |
| Reconciler | `.../manager/OvnReconcilerService.java` (`ensurePublicIpv6Lb*`) | sole NB mutator for owned LBs |
| Ownership tag | `external_ids:cs-pub6-lb=<network-uuid>\|<vip>\|<port>` (`OvnConstants.EXT_ID_PUBLIC_IPV6_LB`) | reconciler touches **only** tagged rows |
| BGP `/128` | `.../manager/OvnBgpRedistributeManager.announceHost6` / `withdrawHost6` | FRR host route on gateway-chassis; bookkeeping `Kind.BGP_HOST_ANNOUNCE_V6` |
| Transport prefix | `OvnNetworkConfig.PublicIpv6Prefix` / `PublicIpv6Gateway` | public LRP GUA + fabric GW (`::1` model); blank disables v6 public path |
| CKS ops docs | `cks-salazar` / `cks-snape` `docs/ovn-public-ipv6-lb.md` | operator refresh of backends after CKS recreate |

**Live VIPs (grandfathered Phase-1):**

| Cluster | VIP | Notes |
|---|---|---|
| salazar | `2a13:8740:0:7::100` | hostNetwork Istio :80/:443 backends on tier `2a13:8740:0:a::/64` |
| snape | `2a13:8740:0:7::101` | tier `2a13:8740:0:9::/64` |

These sit in the **transport-adjacent** host-id band (`::0`–`::255`). New
allocations prefer the Free pool (`::1000`+); Phase-1 VIPs stay until migration.

### 2.2 Public IPv4 LB path (contrast)

IPv4 LB uses inventory + API end-to-end:

- Pool: `user_ip_address` / `IPAddressVO` (`Ip` typed column).
- Rule: `createLoadBalancerRule` with **`publicipid`** → `firewall_rules.ip_address_id` FK.
- Provider: `OvnLoadBalancerService` programs OVN LB for v4 VIPs.

IPv6 public LB **bypasses** all of that today — intentional Phase-1 shortcut so
CKS public v6 could ship without an IPAM redesign.

### 2.3 Why `Ip` / `user_ip_address` is IPv4-only (do not overload)

| Layer | Fact |
|---|---|
| Value type | `com.cloud.utils.net.Ip` stores an address as a **Java `long`** (32-bit IPv4 space). |
| Conversion | `NetUtils.ip2Long` / `long2Ip` split on `.` and assert **exactly 4 octets** — cannot represent IPv6. |
| Persistence | `IPAddressVO.address` is `@Enumerated` `Ip` on table `user_ip_address`. |
| Rule FK | `FirewallRuleVO` / `firewall_rules.ip_address_id` points at `user_ip_address.id` — every LB / PF / StaticNAT / Firewall purpose assumes that FK. |

Extending `user_ip_address` to hold IPv6 in Sprint 1–2 would force a cross-cutting
rewrite of IPAM, quarantine, rules, responses, and agents. **Decision: Option B —
new table** (see §5 Sprint 1 and Decision summary). Guest
`user_ipv6_address` / `UserIpv6AddressVO` remains **SLAAC / guest NIC** only;
public VIP lifecycle (allocate to account, bind LB, BGP announce) is different
and must not share that table.

### 2.4 Gaps this design closes

1. **No inventory** — VIP host ids are free-form strings in a global ConfigKey.
2. **No multi-tenant safety** — any admin with configuration update can clobber
   every cluster's stanza; no account ownership.
3. **No `createLoadBalancerRule` for v6 public** — only ConfigKey → reconciler.
4. **Backend refresh is manual ops** (CKS recreate checklist) — still true after
   this design until optional automation; the design moves **VIP identity** to
   inventory/API first.
5. **Collision risk** with transport GUAs (`::1` GW, `::2` anchor, per-VPC LRP =
   last octet of public LRP v4) if Free pool is not carved out.

## 3. Architecture

```
  Account / operator (cmk)
        │
        │  allocatePublicIpv6 / listPublicIpv6Addresses
        │  createLoadBalancerRule publicipv6id=<id>  (NOT publicipid)
        ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  CloudStack inventory                                       │
  │  NEW table user_public_ipv6_address  (Option B)             │
  │    Free pool: 2a13:8740:0:7::1000 .. ::ffff  (or /112 doc)  │
  │    Reserve:   ::0 .. ::255  transport (GW ::1, anchor ::2,  │
  │               per-VPC LRP GUA = last octet of public LRP v4)│
  │    Grandfather: ::100 / ::101 until migration               │
  │  LB rule rows reference public IPv6 id (parallel to v4 FK)  │
  └───────────────────────────┬─────────────────────────────────┘
                              │
                              │  desired set =
                              │    ConfigKey ovn.lr.public.ipv6.lb
                              │    ∪ API inventory / LB rules
                              │    (dual-read until cutover)
                              ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  OvnReconcilerService  (SOLE OVN NB mutator for pub6 LB)     │
  │    tag: external_ids:cs-pub6-lb=<net-uuid>|<vip>|<port>      │
  │    program Load_Balancer on VPC LR (+ tier LS as today)     │
  └───────────────┬─────────────────────────────┬───────────────┘
                  │                             │
                  ▼                             ▼
           OVN Northbound                 OvnBgpRedistributeManager
           Load_Balancer                  announceHost6(vip)/128
                  │                             │
                  └──────────┬──────────────────┘
                             ▼
                    gateway-chassis FRR  →  RRs / fabric IPv6
                    (PARSEL-V6 public prefix permitted upstream)
```

**Invariants:**

1. **Reconciler is the only NB writer** for `cs-pub6-lb` rows — API/IPAM never
   call `ovn-nbctl` / `OvnNbClient` LB create directly from the request path
   except by enqueueing desired state the reconciler already understands.
2. **Dataplane reuse:** same `OvnPublicIpv6Lb` entry shape (or an internal DTO
   equivalent), same tag, same `announceHost6` — Phase-1 code stays the sink.
3. **Dual-read until cutover:** desired = `LrPublicIpv6Lb` ConfigKey **union**
   API inventory/LB. After cutover, ConfigKey may be emptied; grandfather rows
   must exist in inventory first.

## 4. Component → code map (`plugins/network-elements/ovn/...` + server/api)

| Concern | File / area | Change |
|---|---|---|
| **NEW public v6 inventory table** | schema migration + `UserPublicIpv6AddressVO` / Dao (name TBD, table **`user_public_ipv6_address`**) | Option B — Free/Allocated/Released; zone, account, domain, address string, vpc/network association optional |
| Do **not** use | `engine/schema/.../UserIpv6AddressVO` (`user_ipv6_address`) | guest SLAAC only |
| Do **not** extend (Sprint 1–2) | `IPAddressVO` / `com.cloud.utils.net.Ip` / `user_ip_address` | IPv4 long + `ip2Long` |
| Pool bounds | config or zone-detail (document default) | Free: `::1000`–`::ffff`; reserve `::0`–`::255` |
| Allocate / list / release API | new API commands + cmk verbs | cmk-first; account-scoped |
| LB create | `api/.../CreateLoadBalancerRuleCmd` (+ service impl) | add **`publicipv6id`** (mutually exclusive with `publicipid`) |
| Rule persistence | `firewall_rules` extension **or** parallel LB binding table | must not force IPv6 into `ip_address_id` → `user_ip_address` without a design decision in Sprint 2 (see Q3) |
| Desired-state union | `OvnReconcilerService.ensurePublicIpv6LbForZone` | dual-read: parse ConfigKey **∪** load API inventory LBs for zone |
| Parser reuse | `OvnPublicIpv6Lb` | keep for ConfigKey; inventory path may build `Entry` objects without string round-trip |
| OVN LB apply | existing `ensurePublicIpv6Lb` / `applyPublicIpv6LbPlan` | unchanged contract; richer desired set |
| BGP | `OvnBgpRedistributeManager.announceHost6` | unchanged |
| Transport GUA | `OvnPublicNetworkManager` + `PublicIpv6Prefix` | unchanged; reserve band documents collision avoidance |
| CKS ops docs | `cks-*/docs/ovn-public-ipv6-lb.md` | post-cutover: point at inventory/API; ConfigKey legacy note |
| Private ECMP | `LrEcmpStaticRoutes` / CKS `ovn-ecmp-workers.md` | **out of scope** — leave ConfigKey |

## 5. Phased plan (sprints)

Each sprint is independently deployable (jar-direct all 3 mgmt nodes) and has
explicit exit criteria.

### Sprint 1 — IPAM (public IPv6 inventory)

**Build:**

- Schema: **`user_public_ipv6_address`** (Option B — **new table**, not guest
  `user_ipv6_address`, not `user_ip_address`).
- VO/Dao/manager: allocate next free host id in Free pool; release; list by
  account/zone; optional associate-to-VPC/network.
- Default Free pool host ids: **`2a13:8740:0:7::1000`–`::ffff`** (or a
  documented `/112` carve-out inside the `/64`). Document reserve **`::0`–`::255`**
  for transport (GW `::1`, anchor `::2`, per-VPC LRP GUA = last octet of public
  LRP v4). New allocations **prefer `::1000+`**.
- Seed / import path for grandfather `::100` / `::101` (Allocated, system or
  owning account) without putting them back into Free.
- cmk: list + allocate + release (admin + account where appropriate).

**Exit criteria:**

- [ ] Table present on all 3 mgmt DBs; Dao CRUD green in unit tests.
- [ ] Allocate returns addresses only in Free pool; never `::0`–`::255` for new
      Free draws.
- [ ] `::100` / `::101` importable as Allocated without Free collision.
- [ ] cmk list/allocate/release works against LAX zone; no OVN NB change yet.

### Sprint 2 — LB API + OVN desired-state from inventory

**Build:**

- `createLoadBalancerRule` (and list/delete as needed) accepts **`publicipv6id`**
  explicitly — **do not overload `publicipid`**.
- Wire rule → VIP address string + backends (same model as v4 where possible:
  stickiness, protocol, ports).
- Reconciler **dual-read**:
  `desired = OvnPublicIpv6Lb.parse(LrPublicIpv6Lb) ∪ inventoryLBs(zone)`.
- Still sole NB mutator; tag + `announceHost6` unchanged.
- Conflict policy: same VIP:port from both sources must resolve deterministically
  (prefer inventory if both present — default; see Q5).

**Exit criteria:**

- [ ] cmk create LB with `publicipv6id` programs OVN LB + BGP `/128` (no ConfigKey
      edit).
- [ ] ConfigKey-only entries (salazar/snape) still reconcile (dual-read).
- [ ] Tagged rows only; no untagged LB churn.
- [ ] Unit tests for union + conflict; live smoke `curl` to new VIP in Free pool.

### Sprint 3 — Migrate / cutover

**Build:**

- Import live Phase-1 VIPs + LB rules into inventory (if not done in Sprint 1).
- Operator runbook: create inventory LB rows matching ConfigKey stanzas; verify
  dual-read is a no-op (same desired set).
- Empty `ovn.lr.public.ipv6.lb` (or mark deprecated); reconciler inventory-only
  mode.
- Update CKS ops docs: backends still refreshed, but VIP identity is inventory;
  ConfigKey section → historical.

**Exit criteria:**

- [ ] ConfigKey empty (or unused); salazar `::100` and snape `::101` still live.
- [ ] Reconciler dry-run delta = 0 before and after emptying ConfigKey.
- [ ] CKS recreate checklist no longer requires editing ConfigKey for VIP
      identity (backend IP refresh may still be manual — Q7).
- [ ] Rollback documented: re-fill ConfigKey; dual-read still available for one
      release window if needed.

### Sprint 4 — Optional StaticNAT / PortForward (public IPv6)

**Build (only if prioritized):**

- StaticNAT / PF rules referencing `user_public_ipv6_address` ids.
- Dataplane: OVN NAT / LB patterns for 1:1 or port map on public v6 (design
  detail in sprint kickoff — not committed here beyond inventory reuse).

**Exit criteria:**

- [ ] cmk StaticNAT or PF on a public IPv6 VIP reaches a guest tier address.
- [ ] No regression on Sprint 2–3 LB path.

## 6. Test / verify

| Layer | How |
|---|---|
| IPAM | allocate N addresses; assert range; concurrent allocate no duplicate; release returns to Free |
| API | cmk create/list/delete LB with `publicipv6id`; reject both `publicipid` + `publicipv6id`; reject guest ipv6 id |
| Dual-read | ConfigKey-only, inventory-only, both (identical and conflicting VIP:port) |
| OVN NB | `listOwnedLoadBalancers(cs-pub6-lb)`; VIP/backends match; orphan prune on delete |
| BGP | `announceHost6` → `/128` on gateway-chassis + learned on RRs; withdraw on delete |
| Live smoke | `curl -g` HTTP to `[vip]:80` (Istio 301 pattern as in CKS docs) |
| Regression | IPv4 LB / FIP unchanged; guest `user_ipv6_address` unchanged; private ECMP key untouched |
| Deploy | all 3 mgmt nodes jar-direct; class md5 audit for reconciler + new IPAM classes |

## 7. Deploy

Per standing CloudStack deploy rules (jar-direct, **all 3** control nodes
`voldemort` / `bellatrix` / `barty`, one at a time):

1. Schema migration applied (Sprint 1+) before code that reads the new table.
2. Patch fat jar / replace plugin jars as for other OVN work — **no `.deb`**.
3. Restart mgmt services; confirm reconciler interval still fires
   `ensurePublicIpv6LbForZone`.
4. Agent jars only if Sprint 4 or BGP command changes require them (Sprint 1–3
   expected **mgmt-only** if `announceHost6` path is unchanged).
5. Verify: `javap` / class presence for new types; cmk smoke; OVN + BGP checks.

> ⚠️ Management is a 3-node active cluster — patch **all three** or dual-read /
> allocate appears flaky by node. See [[ovn-complete-networking]] §7.

## 8. Progress

| Phase | Status | Notes |
|---|---|---|
| Design | ✅ done | this document (2026-07-10) |
| Sprint 1 — IPAM (`user_public_ipv6_address`) | ✅ foundation + API complete (code); live schema/cmk smoke still pending deploy | table `user_public_ipv6_address`; APIs list/associate/disassociatePublicIpv6Address; 13 unit tests; no OVN yet |
| Sprint 2 — LB API + OVN dual-read | ✅ code complete (schema `public_ipv6_address_id`, `publicipv6id` API, dual-read reconciler, unit tests); live cmk/OVN smoke pending jar-direct | `publicipv6id`; reconciler union |
| Sprint 3 — migrate / cutover | Proposed / not started | empty ConfigKey after inventory |
| Sprint 4 — StaticNAT / PF (optional) | Proposed / not started | deferred until LB path stable |
| CKS ops doc update | Proposed / not started | after Sprint 3 |
| Phase-1 ConfigKey dataplane | ✅ live | salazar `::100`, snape `::101` |

## 9. Migration (dual-read + cutover)

```
  Phase-1 only          Sprint 2+ dual-read              Sprint 3 cutover
  ─────────────         ───────────────────              ────────────────
  desired = CK          desired = CK ∪ API               desired = API
  (ConfigKey)           (prefer API on conflict)         (CK empty)
```

1. **Before Sprint 2:** production stays ConfigKey-only (today).
2. **Sprint 2 ship:** dual-read on; no forced ConfigKey change.
3. **Import:** create `user_public_ipv6_address` rows for `::100` / `::101` +
   equivalent LB rules; backends match live ConfigKey.
4. **Observe:** reconciler plan size ≈ 0 for pub6 (idempotent).
5. **Cutover:** clear `ovn.lr.public.ipv6.lb`; re-check plan size 0 and live
   HTTP on both VIPs.
6. **Rollback:** restore ConfigKey string from backup; dual-read still unions
   until inventory rows are removed (prefer leave inventory and restore CK only
   if inventory path is broken).

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| VIP collision with transport GUA | blackhole / steal LRP address | reserve `::0`–`::255`; Free pool `::1000+`; reject allocate outside Free |
| Dual-read conflict (CK vs API) | flap or wrong backends | deterministic prefer-inventory; log WARN; cutover runbook |
| Extending `firewall_rules.ip_address_id` wrongly | orphan FK / IPv4 code paths break | Sprint 2 design choice: nullable FK + side column **or** parallel binding (Q3) — never store IPv6 in `Ip` long |
| Overloading guest `user_ipv6_address` | SLAAC IPAM corruption | **forbidden** — Option B only |
| Reconciler not sole mutator | NB drift | no direct NB LB writes from API command path |
| Partial 3-node deploy | intermittent allocate / LB | always jar-direct all 3 mgmt nodes |
| CKS backend churn | VIP live, backends stale | ops checklist remains; optional later automation (Q7) |
| Scope creep to private ECMP / dual-stack rewrite | delay | non-goals table §1 |

## 11. Open questions

| ID | Question | Default (until decided otherwise) |
|---|---|---|
| **Q1** | Exact Free pool notation: host range `::1000`–`::ffff` vs explicit `/112` (`2a13:8740:0:7:0:0:1000:0/112` style)? | Document **host ids `::1000`–`::ffff`** in the `/64`; implement as inclusive string/BigInteger walk |
| **Q2** | Who owns grandfather `::100`/`::101` account — system, root admin, or CKS project account? | **System / admin account**; transferable later |
| **Q3** | How does `firewall_rules` reference public IPv6 — new nullable `public_ipv6_address_id`, purpose-specific table, or reuse `ip_address_id` only for v4? | **New nullable column or parallel LB binding** — do not point `ip_address_id` at the new table without FK rename |
| **Q4** | Should allocate require a VPC association up front, or allocate Free then associate on LB create? | **Allocate Free unassociated**; bind on first LB / associate API |
| **Q5** | Dual-read conflict: ConfigKey vs inventory same VIP:port, different backends? | **Prefer inventory**; WARN log ConfigKey side |
| **Q6** | Multi-zone / multi-prefix later? | **Single prefix** from `PublicIpv6Prefix` for LAX; table has `data_center_id` for future |
| **Q7** | Auto-refresh CKS backends from instance NICs? | **Out of Sprint 1–3**; keep operator refresh (or separate design) |
| **Q8** | Quotas / resource limits for public IPv6 count per account? | **Yes, simple limit** (ConfigKey or resource count) in Sprint 1 if cheap; else Sprint 2 |
| **Q9** | After cutover, remove `LrPublicIpv6Lb` ConfigKey entirely or keep as break-glass? | **Keep key one release as break-glass empty default**; remove in a later cleanup |

## 12. Decision summary

| Decision | Choice | Rationale |
|---|---|---|
| Inventory model | **Option B: NEW table `user_public_ipv6_address`** | Do **not** overload guest `user_ipv6_address` (SLAAC). Do **not** extend `user_ip_address` / `Ip` long in Phase 2 |
| Why not `Ip` / `user_ip_address` | IPv4-only | `Ip` as `long` + `NetUtils.ip2Long` (4 octets) + `IPAddressVO` + `firewall_rules.ip_address_id` FK |
| Pool carve-out | Free **`::1000`–`::ffff`**; reserve **`::0`–`::255`** | Protect GW `::1`, anchor `::2`, per-VPC LRP GUA; new alloc prefer `::1000+` |
| Phase-1 VIPs | Grandfather `::100`/`::101` until migration | Live CKS; import as Allocated, not Free |
| Dataplane | **Reuse Phase-1** OVN LB + `cs-pub6-lb` + `announceHost6` | Reconciler sole NB mutator |
| Desired state | **Dual-read** ConfigKey ∪ API until cutover | Zero-downtime migration |
| Sprints | 1 IPAM · 2 LB+OVN · 3 migrate · 4 optional SNAT/PF | Incremental jar-direct |
| LB API parameter | Explicit **`publicipv6id`** | Not overload `publicipid` |
| UX | **cmk-first** | Matches fleet ops |
| Non-goals | no `.deb`; no private ECMP replace; no full dual-stack rewrite; no NYC | Standing scope |
| Sibling design | [[ovn-complete-networking]] | Shared OVN/BGP/deploy discipline |
| Ops today | CKS `docs/ovn-public-ipv6-lb.md` | Operator ConfigKey until Sprint 3 |

---

*End of design. Implementation starts only when Sprint 1 is explicitly scheduled;
this file is design-only (no code commit required for the design drop).*
