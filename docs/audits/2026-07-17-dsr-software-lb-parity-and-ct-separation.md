# Audit: DSR software LB vs OVN ct_lb/NAT — kind split, CT boundary, IPv4/IPv6 parity

**Date:** 2026-07-17  
**Status:** OPEN findings (DSR-1..DSR-9) — **product constraints ACCEPTED** by parent ADR  
**Binding ADR (cross-repo):**  
`~/dev/infra-base/docs/architecture/0001-dual-stack-active-active-dsr-software-lb.md`  
**CS implementation contract:** `docs/design/dsr-software-lb.md`  
**Scope:** CloudStack OVN LB path (`OvnLoadBalancerService`, `OvnNbClient` LB APIs,
`LoadBalancingRulesManagerImpl`, public IPv4 + public IPv6 VIP inventory) and how a
future **DSR software LB** must be modeled and enforced against the existing
**OVN `ct_lb` / force-SNAT** path.  
**Related:** `2026-07-16-ovn-integration-bug-audit.md` (findings 8, 10 on LB delete /
`lb_force_snat_ip`); `docs/design/ovn-public-ipv6-fip-lb-self-service-api.md`
(public IPv6 VIP inventory + `publicipv6id`); OVN ref
`virtualization-stack/references/ovn.md` (northd compiles `Load_Balancer` → `ct_lb()`).

---

## 0. Binding decisions (this audit)

These are **product constraints**, not optional optimizations:

| # | Constraint | Implication |
|---|------------|-------------|
| C1 | **Full IPv4 / IPv6 parity** for any LB kind | Same kind, same validation, same backend-selection model, same BGP announce shape (`/32` vs `/128`) for both families. No v4-only shortcuts for health probes, NIC lookup, or validate path. |
| C2 | **DSR LB is a software kind** | Not hardware/offloaded (`ct_lb` TC flower / ASAP² / DOCA). DSR programming must stay on the software datapath (or host software LB), never claim HW offload. |
| C3 | **No conntrack for LB backend selection** on DSR | Backend pick is pure L4 (hash/rr group) without `ct_lb` / CT zone. Reply path is **direct server return** (backend → client with VIP as source). |
| C4 | **Conntrack remains only for separate DNAT/SNAT/NAT** | `snat`, `dnat_and_snat`, and any pure NAT PF stay on the OVN `NAT` table (`ct_dnat` / `ct_snat`). They must not be fused into the DSR selection path. |
| C5 | **CloudStack must distinguish DSR software LB from OVN ct_lb/NAT** | Explicit first-class kind on the CS rule (and OVN ownership tag). Kind is not inferred from algorithm or scheme alone. |
| C6 | **Incompatible combinations are hard-rejected** | Fail at API / offering / apply time with a clear error; never silently program a hybrid that mixes CT-LB SNAT with DSR return. |

---

## 1. Two LB datapath models (must not be conflated)

### 1.1 Kind A — `CT_LB` (current OVN path; stateful)

```
Client → VIP
   │
   ▼
OVN Load_Balancer row  ──northd──►  ct_lb(backends=...)
   │                                  │
   │                                  ├─ CT zone: select backend + rewrite dst
   │                                  └─ reverse path expects CT to un-DNAT reply
   │
   + options:hairpin_snat_ip=<VIP>     (backend→self-VIP)
   + LR options:lb_force_snat_ip=router_ip  (east-west force SNAT)
   + attach LR (N-S) + tier LS (E-W)
```

**Code today:**

| Piece | Location | Behavior |
|-------|----------|----------|
| Apply | `OvnLoadBalancerService.applyOne` | Creates `Load_Balancer`, attaches LR + LS |
| Options | `buildLbOptions` | Always `hairpin_snat_ip=<VIP>`; optional `affinity_timeout` |
| Force SNAT | `OvnNbClient.attachLoadBalancerToLogicalRouter` → `ensureLbForceSnat` | Writes `lb_force_snat_ip=router_ip` on LR |
| Algorithm | `selectionFieldsFor` | empty = rr; source → `ip_src,ip_dst,tp_src,tp_dst` (CT affinity surface) |
| PF reuse | `OvnPortForwardingService` | Same `Load_Balancer` + `ct_lb` model (not NAT table) |
| NAT (separate) | `OvnStaticNatService` / `OvnSourceNatService` | `dnat_and_snat` / `snat` on `NAT` table — **correct CT use** |

**Properties:**

- Conntrack is **required** for backend selection **and** symmetric reply rewrite.
- Force SNAT / hairpin SNAT exist precisely because the reply would otherwise
  bypass the VIP identity.
- Compatible with (and often offloaded as) HW `ct` flower rules — **not DSR**.

### 1.2 Kind B — `DSR_SOFTWARE` (required new model)

```
Client → VIP
   │
   ▼
Software LB (no CT for selection)
   │  rewrite L3/L4 dst (or L2 DSR) → backend
   │  do NOT SNAT client source
   │  do NOT commit CT for LB
   ▼
Backend accepts VIP on lo / VIP ARP
   │
   └── reply: src=VIP, dst=client  ──► Client   (direct return; no LB reverse path)
```

**Properties (normative):**

| Property | DSR_SOFTWARE | CT_LB (current) |
|----------|--------------|-----------------|
| Backend selection | SW hash/rr **without CT** | `ct_lb` / CT zone |
| Reply path | Direct from backend | Through CT un-DNAT (+ often SNAT) |
| `hairpin_snat_ip` | **Forbidden** | Used today |
| `lb_force_snat_ip` | **Forbidden** for DSR flows | Written on every LR attach today |
| HW offload of LB | **Forbidden** (software kind) | May offload CT |
| Conntrack | **Only** coexisting NAT rules | Integral to LB |
| IPv4 + IPv6 | **Mandatory parity** | Partial (see §4) |

DSR is **not** “OVN `Load_Balancer` + `skip_snat`”. OVN’s native LB still
compiles to `ct_lb()` (see skill `ovn.md`: *northd compiles LB into `ct_lb()`*).
Even with `options:skip_snat`, selection and reverse affinity remain CT-based.
That is still Kind A, not Kind B.

---

## 2. How CloudStack must distinguish the kinds

### 2.1 First-class kind (required)

Introduce an explicit **LB kind** owned by CloudStack inventory — not a free-form
detail and not algorithm reuse.

| Layer | Proposal | Notes |
|-------|----------|-------|
| API | `lbkind` on create/update/list (`ct_lb` default, `dsr_software`) | Mutually exclusive with any future HW-LB kinds |
| Schema | `load_balancing_rules.lb_kind` (VARCHAR/ENUM, default `CT_LB`) | Upgrade SQL + VO |
| Domain | `LoadBalancer` / `LoadBalancerContainer` enum `LbKind { CT_LB, DSR_SOFTWARE }` | Wire name stable |
| Offering capability | e.g. `SupportedLbKinds=ct_lb,dsr_software` (or extend isolation/schemes carefully) | Offering without `dsr_software` rejects DSR create |
| OVN ownership | `external_ids:cs_lb_kind=CT_LB\|DSR_SOFTWARE` on any NB object the kind owns | Reconciler / orphan prune must filter by kind |
| Provider dispatch | `OvnLoadBalancerService` only accepts `CT_LB`; DSR has a **separate** programmer (or hard no-op + error if mis-dispatched) | Prevents accidental `createLoadBalancer` for DSR |

**Do not** encode DSR as:

- algorithm `source` / `roundrobin` — already used by CT_LB  
- scheme `Public` / `Internal` — orthogonal  
- protocol `tcp`/`udp` — orthogonal  
- network detail alone — too easy to drift; not on list/API responses

### 2.2 Ownership map after the split

```
CloudStack LoadBalancer rule
        │
        ├─ lb_kind = CT_LB ────────► OvnLoadBalancerService
        │                              OVN Load_Balancer + ct_lb
        │                              hairpin_snat_ip / lb_force_snat (as today)
        │                              Kind.LOAD_BALANCER map
        │
        └─ lb_kind = DSR_SOFTWARE ─► DsrSoftwareLbService (new)
                                       SW programming only (no OVN Load_Balancer)
                                       no hairpin / force SNAT
                                       no CT for selection
                                       Kind.DSR_LB (new map kind) or host-side state
```

NAT services stay independent:

```
StaticNat  → OvnStaticNatService  → NAT dnat_and_snat  (CT OK)
SourceNat  → OvnSourceNatService  → NAT snat           (CT OK)
PF (today) → OvnPortForwardingService → Load_Balancer/ct_lb  (Kind A; not DSR)
```

Port-forward remains Kind A until explicitly redesigned; it must **not** share
DSR VIP ownership (see §3).

---

## 3. Incompatible combinations (hard reject)

### 3.1 Matrix

| # | Combination | Severity | Enforcement site | Reason |
|---|-------------|----------|------------------|--------|
| X1 | `DSR_SOFTWARE` + OVN `Load_Balancer`/`ct_lb` on **same VIP:port** | CRITICAL | create + apply | Two selectors; CT reverse path fights direct return |
| X2 | `DSR_SOFTWARE` + `hairpin_snat_ip` / `lb_force_snat_ip` | CRITICAL | DSR programmer must never write; CT path must not attach force-SNAT for DSR VIP | SNAT destroys DSR return identity |
| X3 | `DSR_SOFTWARE` + HW-offload network offering (`hwoffloadenabled` / offload-expecting tier) **for the LB datapath** | HIGH | offering + create rule | C2: DSR is software kind; offload path is CT-centric |
| X4 | `DSR_SOFTWARE` + StaticNat / `dnat_and_snat` on **same public VIP** | CRITICAL | create StaticNat / create LB | VIP cannot be both 1:1 FIP and multi-backend DSR VIP |
| X5 | `DSR_SOFTWARE` + PortForwarding (ct_lb) on **same VIP:port** | CRITICAL | create PF / create LB | Same as X1 |
| X6 | `DSR_SOFTWARE` + CT stickiness (`affinity_timeout`, CT-backed stickiness methods) | HIGH | create stickiness / validate | Stickiness via CT contradicts C3 |
| X7 | `DSR_SOFTWARE` + algorithm `leastconn` | HIGH | validate (already rejected for OVN CT path; keep for DSR unless a non-CT implementation exists) | leastconn implies active connection accounting |
| X8 | Mixed kinds on one dual-stack rule (v4 DSR + v6 CT_LB or reverse) | CRITICAL | create dual-stack / parity gate | Violates C1 |
| X9 | `DSR_SOFTWARE` backend without VIP acceptance (no lo VIP / no ARP/ND for VIP) | HIGH | backend assign + guest prep | Dataplane prerequisite; fail closed or document + probe |
| X10 | `CT_LB` rule later flipped to `DSR_SOFTWARE` (or reverse) in place | HIGH | update API | Require delete+recreate or explicit migrate that tears down the other kind’s NB objects first |
| X11 | Two rules different kinds, same VIP, overlapping ports | CRITICAL | port-conflict check (extend v4 + v6) | Same as X1 across rules |
| X12 | DSR VIP also used as VPC source-NAT IP | HIGH | create | SourceNat SNAT CT path collides with VIP identity |

### 3.2 Enforcement layers (ordered)

1. **API / manager (authoritative)** — `LoadBalancingRulesManagerImpl.createPublicLoadBalancer*` /
   `createPublicIpv6LoadBalancerRule` / assign / update:
   - resolve `lb_kind`
   - VIP ownership scan: any other rule / StaticNat / PF on same address+port → reject
   - offering capability must list the kind
   - dual-stack parity: if rule binds both families, kind must match on both
2. **Provider validate** — `LoadBalancingServiceProvider.validateLBRule` /
   `OvnLoadBalancerService.validateLBRule`:
   - OVN element returns **false** for `DSR_SOFTWARE` (OVN must not “half-apply” CT_LB)
   - DSR element validates SW prerequisites
3. **Apply** — `applyLBRules`:
   - dispatch by kind; never call `nb.createLoadBalancer` for DSR
   - CT_LB path continues to set hairpin / force SNAT as today
4. **Reconciler** — refuse to heal a DSR rule into a `Load_Balancer` row; prune
   orphans only within kind tag
5. **Offering create** — mutex: offering that advertises only `ct_lb` cannot be
   used to create DSR rules; optional offering-level “LB kind default”

### 3.3 Error message contract (en-US)

Stable, actionable strings (examples):

- `Load balancer kind DSR_SOFTWARE is incompatible with OVN conntrack LB (ct_lb) on VIP <addr> port <p>`
- `Load balancer kind DSR_SOFTWARE cannot share VIP <addr> with StaticNat or PortForwarding`
- `Load balancer kind DSR_SOFTWARE requires software datapath; network offering enables hardware offload LB`
- `IPv4 and IPv6 load balancer kinds must match for dual-stack rule id=<id>`

---

## 4. IPv4 / IPv6 parity (C1) — current gaps

Public IPv6 LB inventory + `publicipv6id` exist (Sprint 1–3 LIVE). Parity for a
**new kind** still fails several code paths that are v4-shaped.

| Gap | Evidence | Impact on DSR + parity |
|-----|----------|------------------------|
| G1 | `OvnLoadBalancerService.buildIpPortMappings` uses `nicDao.findByIp4AddressAndNetworkId` only | IPv6 backends never enter health / mapping; DSR must resolve v6 NICs |
| G2 | `healthCheckSourceIp` walks IPv4 CIDR only (`getIpRangeEndIpFromCidr` / `long2Ip`) | No v6 probe source; CT_LB HC already asymmetric |
| G3 | `validateLbRule` short-circuits public IPv6 (`isPublicIpv6LoadBalancer` → `return true`) | Provider never validates v6 algorithms/kind — **must not** skip for DSR |
| G4 | `OvnLoadBalancerService.applyLBRules` is the v4 provider path; public v6 often via reconciler / inventory dual-read | Two mutators risk kind skew; DSR needs **one** apply path per family or a unified dual-stack apply |
| G5 | BGP: v4 `announce` `/32` vs v6 `announceHost6` `/128` | OK if both wired for DSR VIP; reject if only one family announced |
| G6 | `formatVipKey` already dual-stack | Keep; required for any VIP map representation |
| G7 | Capabilities `lbCaps()` list algorithms including `leastconn` while `validateLBRule` rejects leastconn for OVN | Pre-existing; DSR must not inherit false “supported” claims |

**Parity acceptance tests (both kinds):**

1. Create VIP + rule + backends for v4-only, v6-only, and dual-stack — same kind.  
2. Reject mixed-kind dual-stack (X8).  
3. Delete/revoke tears down both families.  
4. BGP withdraw on revoke for both `/32` and `/128`.  
5. Incompatibility matrix exercised on both address families.

---

## 5. Conntrack boundary (C3 / C4)

### 5.1 Allowed CT use (unchanged)

| Feature | OVN object | CT action | Owner |
|---------|------------|-----------|--------|
| Source NAT | `NAT` type `snat` | `ct_snat` | `OvnSourceNatService` |
| Static NAT / FIP | `NAT` type `dnat_and_snat` | `ct_dnat` + `ct_snat` | `OvnStaticNatService` |
| Firewall/ACL related | ACL `allow-related` | conntrack for state | `OvnFirewallService` |

These remain valid **alongside** DSR **only if** they do not claim the DSR VIP
(see X4/X12). Tier SNAT for guest egress is orthogonal to public VIP DSR.

### 5.2 Forbidden CT use for DSR LB

| Mechanism | Why forbidden for DSR |
|-----------|----------------------|
| OVN `Load_Balancer` → `ct_lb` / `ct_lb_mark` | CT selects backend + owns reverse path |
| `options:hairpin_snat_ip` | SNAT on hairpin |
| `options:lb_force_snat_ip` | SNAT all LB flows to router IP |
| `options:affinity_timeout` (CT affinity) | CT-backed stickiness |
| `selection_fields` on OVN LB row | CT_LB affinity surface only |
| HW offload of the above | C2 software kind |

### 5.3 Side note on finding 10 (2026-07-16)

`lb_force_snat_ip=router_ip` on distributed LR is already **PLAUSIBLE broken**
for east-west CT_LB. That bug stays on Kind A. DSR must not depend on fixing
or using that option — DSR forbids force SNAT entirely.

---

## 6. Software kind vs hardware offload (C2)

| Surface | Rule |
|---------|------|
| Network offering `hwoffloadenabled` / VF / vDPA | Guest NIC offload may still exist; **LB kind DSR must not program offloaded ct_lb flows** |
| OVS `hw-offload=true` / TC flower | CT_LB may offload; DSR selection stays SW (group/hash or host IPVS-DSR etc.) |
| Operator expectation | Listing/API must show `lbkind=dsr_software` so operators do not expect ASAP² LB PPS |
| Mutex | Prefer reject DSR create when offering capability set implies “HW LB only”; allow DSR on mixed SW guest tiers |

This matches the standing HW-offload audit correction: SW residue is
architectural for multi-table/CT paths; DSR is **intentionally** SW-only.

---

## 7. Implementation sketch (not in this pass)

Ordered work packages when implementation is authorized:

1. **Schema + API** — `lb_kind` column, `CreateLoadBalancerRule` / IPv6 twin, list response, upgrade SQL.  
2. **Manager gates** — VIP ownership + kind mutex + dual-stack parity in `LoadBalancingRulesManagerImpl`.  
3. **Provider split** — OVN element rejects DSR; CT_LB path unchanged by default.  
4. **DSR programmer** — software-only backend (design of host IPVS / OVS SW groups / other is a follow-up design doc; this audit only freezes the **kind boundary**).  
5. **Parity fixes G1–G5** — even for CT_LB health checks; required before claiming dual-stack DSR.  
6. **Tests** — unit matrix for X1–X12; Marvin dual-stack create/reject; no `Load_Balancer` row when kind=DSR.  
7. **Docs** — update `ovn-complete-networking` / public IPv6 LB design with kind table.

---

## 8. Findings summary (audit register)

| ID | Sev | Status | Title |
|----|-----|--------|-------|
| DSR-1 | CRITICAL | OPEN | No first-class LB kind: CT_LB and DSR cannot be distinguished in inventory/API |
| DSR-2 | CRITICAL | OPEN | Current OVN path always programs `ct_lb` + hairpin SNAT + LR force SNAT — incompatible with DSR |
| DSR-3 | CRITICAL | OPEN | No VIP ownership mutex across LB / StaticNat / PF / kinds |
| DSR-4 | HIGH | OPEN | IPv6 public LB skips `validateLbRule` provider path (G3) |
| DSR-5 | HIGH | OPEN | Health-check / NIC resolution IPv4-only (G1/G2) — blocks parity |
| DSR-6 | HIGH | OPEN | No software-kind enforcement vs HW-offload offering expectations |
| DSR-7 | MEDIUM | OPEN | PortForwarding shares CT_LB machinery; must stay out of DSR VIP space |
| DSR-8 | MEDIUM | OPEN | dual-stack kind parity not defined in schema or apply paths |
| DSR-9 | INFO | OPEN | Finding 10 (`lb_force_snat_ip=router_ip`) remains Kind A only; DSR must not depend on it |

---

## 9. Explicit non-goals (this audit)

- Implementing DSR dataplane (IPVS, OVS groups, eBPF, etc.).  
- Changing default of existing rules (default remains `CT_LB`).  
- Removing CT from NAT/FIP.  
- Claiming OVN `skip_snat` equals DSR.  
- NYC / gryffindor scope.

---

## 10. Decision checklist (for implementers)

- [ ] `LbKind` enum + DB column + API parameter default `CT_LB`  
- [ ] Offering capability advertises allowed kinds  
- [ ] Create/update reject matrix X1–X12 (v4 and v6)  
- [ ] `OvnLoadBalancerService` asserts kind==CT_LB before any NB LB write  
- [ ] DSR path never calls `createLoadBalancer` / `ensureLbForceSnat` / `hairpin_snat_ip`  
- [ ] NAT services unchanged; VIP collision checks only  
- [ ] IPv6 validate + backend NIC resolution parity  
- [ ] external_ids kind tag + reconciler filters  
- [ ] Tests for reject paths and dual-stack parity  
- [ ] Audit README index row + fix commits when closed  

---

## 11. Code anchors (read-only map)

```
plugins/network-elements/ovn/.../element/OvnLoadBalancerService.java
  applyOne, buildLbOptions, selectionFieldsFor, buildIpPortMappings, validateLBRule
plugins/network-elements/ovn/.../client/OvnNbClient.java
  createLoadBalancer, ensureLbForceSnat, attachLoadBalancerToLogicalRouter/Switch
  updateLoadBalancerProperties  (selection_fields flushes CT — Kind A only)
plugins/network-elements/ovn/.../element/OvnPortForwardingService.java
  Load_Balancer / ct_lb for PF
plugins/network-elements/ovn/.../element/OvnStaticNatService.java
  NAT dnat_and_snat  (allowed CT)
plugins/network-elements/ovn/.../element/OvnSourceNatService.java
  NAT snat           (allowed CT)
plugins/network-elements/ovn/.../element/OvnNetworkElement.java
  lbCaps() algorithms / schemes
server/.../lb/LoadBalancingRulesManagerImpl.java
  validateLbRule, createPublicLoadBalancer*, createPublicIpv6LoadBalancerRule,
  isPublicIpv6LoadBalancer
engine/schema/.../dao/LoadBalancerVO.java
  algorithm, scheme, lb_protocol — no lb_kind today
```

---

**End of audit.** Implementation is blocked on an explicit go-ahead; this file freezes
the kind split, CT boundary, software-only DSR modeling, full dual-stack parity, and
the incompatible-combination enforcement contract.
