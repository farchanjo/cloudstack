# OVN Complete Networking — NAT + BGP-Routed (private & public) in one VPC

> **Status:** DESIGN / IN PROGRESS. Living document — the source of truth for this
> feature (no speckit for this repo). Update the **Progress** table every phase.
> Owner session: OVN datapath + ConfigDrive + BGP work (2026-07).

## 1. Goal

A single OVN VPC (one Logical Router) must support **four network modes coexisting**,
all driven **via the CloudStack API (cmk)** and BGP-advertised to the fabric **route
reflectors** (`.34/.35/.36`, AS 4200000002) — with the existing FRR egress filter as the
**public-vs-private gate** (no leak of RFC1918 upstream).

| # | Mode | Egress / routing | BGP announce | IPs |
|---|---|---|---|---|
| 1 | Private NAT | OVN LR SNAT → public IP | none | RFC1918 |
| 2 | Public via FIP | 1:1 static NAT | FIP `/32` → RRs+upstream (**works today**) | RFC1918 + public FIP |
| 3 | Routed private | no NAT, direct route | tier `/24` → RRs **only** (filter denies external) | RFC1918 |
| 4 | Routed public | no NAT, direct route | tier `/24` → RRs **and upstream** (filter permits) | **public block** on the tier |

**Public/private gate is already in FRR** (`config-mgmt` is NOT the owner — this is the
Ansible-REX fabric / FRR on the gateway chassis): `ip prefix-list EBGP-LAT-OUT-V4` =
whitelist `217.179.88.0/24` + `217.179.89.0/24` + `217.179.88.64/26`, then `seq 100 deny
any`. Announce all routed tiers to the RRs identically; the filter decides who reaches the
internet. A **routed-public** tier must use a block inside that whitelist (or the block is
added to it). No leak risk — `deny any` protects.

## 2. Current state (what exists)

- Mode 1 (NAT) + Mode 2 (FIP `/32` announce) **work today** (`gaptest` VPC, `vpc-default-ovn`).
- The OVN plugin has **no ROUTED-mode handling at all** (`rg ROUTED|routingmode` in the
  plugin = empty) and **does not declare `Service.Gateway`** — proven: `Gateway=Ovn` is
  silently dropped from a VPC offering, and a Routed OVN tier fails
  `Service/provider combination Gateway/Ovn is not supported by VPC`.
- BGP announce is `/32`-per-public-IP only (`OvnBgpRedistributeManager.announce(publicIp,
  ipAddrId, vpcId, zoneId)`), gated by `ovn.bgp.redistribute.public_ips` + per-VPC detail.
- **Done via cmk already:** 3 `bgppeer` objects for the RRs (`.34/.35/.36`, AS 4200000002).
  Kept. See [[lax-ovn-bgp-internal-tier-routing]].

## 3. Architecture

```
        ┌─────────────────── 1 OVN Logical Router per VPC ──────────────────┐
  tier1 │ Private NAT     RFC1918   → SNAT rule (lr-nat-add snat)            │→ egress via public IP
  tier2 │ FIP/NAT         RFC1918   → dnat_and_snat (FIP)  + announce /32    │
  tier3 │ Routed private  RFC1918   → NO snat, connected route + announce /24│→ gateway-chassis FRR
  tier4 │ Routed public   PUBLIC    → NO snat, connected route + announce /24│   → RRs (iBGP)
        └───────────────────────────────────────────────────────────────────┘        │
                                                                            EBGP-LAT-OUT filter
                                                                          ├ public block → upstream (internet)
                                                                          └ RFC1918      → deny (internal only)
```

Per-tier decision, NOT per-VPC networkmode (avoids CloudStack's Routed-VPC coupling that
needs the Gateway service on the whole VPC). The VPC stays a normal OVN VPC; a **network
detail / offering flag** marks a tier as routed (skip SNAT + announce its subnet).

## 4. Component → code map (`plugins/network-elements/ovn/.../element|manager`)

| Concern | File / method | Change |
|---|---|---|
| Declare Gateway/Routed capability | `element/OvnNetworkElement.getCapabilities()` | add `Service.Gateway` (+ capability) — hygiene; the element's LR IS the gateway |
| **P1 real fix — Gateway provider in ROUTED** | `server .../vpc/VpcManagerImpl.createVpcOffering` (~L750) | it HARDCODED `Gateway=VPCVirtualRouter` for ROUTED VPCs, so an OVN tier (`Gateway/Ovn`) never matched → "not supported by VPC". Fixed: for ROUTED, Gateway **follows the Connectivity provider** (OVN LR is the gateway) when Connectivity is a non-VR SDN, else VPCVirtualRouter. |
| Network design / mode | `element/OvnGuestNetworkGuru.canHandle/design` | accept routed tiers; today hardcodes `GuestType.Isolated` + `Connectivity` |
| SNAT (make per-tier conditional) | `element/OvnSourceNatService.addSnat` + its caller (`OvnVpcElement`/`OvnNetworkElement`) | skip SNAT for routed tiers |
| Routed gateway (no NAT) | new: `element/OvnGatewayService` or in `OvnVpcElement` | LR connected route for the tier, no SNAT |
| Tier-subnet BGP announce | `manager/OvnBgpRedistributeManager` | add `announceSubnet(cidr, vpcId, zoneId)` beside `announce(/32)`; gate by a per-network "advertise" flag |
| Routed-public IP mgmt | public IP range → tier binding (api + `OvnPublicNetworkManager`) | tier draws from a public range, attached directly (no FIP) |
| cmk / API surface | api command classes + network/vpc offerings | per-tier mode flag + public-range binding; offerings NAT-capable + routed-capable |
| Fabric filter (reference only) | FRR `EBGP-LAT-OUT-V4` on gateway chassis | already correct; public blocks must be whitelisted |

## 5. Phased plan (incremental, each independently testable)

- **P1 — Gateway/Routed capability.** Declare `Service.Gateway` in the OVN element; guru
  accepts a routed tier. **Test:** create an OVN VPC + a routed tier via cmk without the
  `Gateway/Ovn not supported` error; VM boots on the tier.
- **P2 — Skip SNAT for routed tiers.** Routed tier gets a connected route on the LR, no
  SNAT. **Test:** routed-tier VM has its real tier IP as source (no SNAT) on the datapath.
- **P3 — Routed-private BGP announce.** `announceSubnet(tier /24)` → RRs; gated per-network.
  **Test:** `show bgp ipv4 unicast <tier>/24` present on `.34/.35/.36`; **absent** on the
  external eBGP (upstream) — filter holds.
- **P4 — Routed-public.** Tier from a public block; announced to RRs **and** upstream (block
  in whitelist). **Test:** public-tier VM reachable from the internet directly (no FIP), and
  the `/24` in the upstream eBGP.
- **P5 — Mixed + cmk surface.** NAT tier + routed tiers in the same VPC; per-tier flag +
  offerings exposed via cmk. **Test:** one VPC with modes 1+3(+4) coexisting; all via cmk.

## 6. Test / verify patterns

- cmk create (offering/vpc/tier/vm) — no hardcoding.
- BGP: `vtysh -c "show bgp ipv4 unicast <cidr>"` on a RR (present) + on aragog's EBGP-LAT
  view (absent for private / present for public).
- Datapath: conntrack on the gateway chassis (aragog) + in-guest reachability.
- No-leak regression: the private `/24` must NEVER appear in `EBGP-LAT-OUT` advertised routes.

## 7. Deploy

Per [[cloudstack-deploy-fatjar-vs-agent-jars]]: mgmt = patch the fat jar (`jar uf`); agent =
replace the separate jars; restart both; activate/verify via cmk. JVM debug via
[[prod-jvm-debug-jdebug-ssh-tunnel]] (direct `.88.34:8000`, not the ssh_forward tunnel).

## 8. Progress

| Phase | Status | Notes |
|---|---|---|
| Design | ✅ done | this doc |
| RR bgppeers via cmk | ✅ done | `.34/.35/.36` AS 4200000002 created |
| P1 Gateway capability | ✅ done | fix in `VpcManagerImpl.createVpcOffering` (Gateway follows Connectivity provider for ROUTED SDN) + `OvnNetworkElement` declares Service.Gateway. Verified: OVN Routed VPC (`vpc-ovn-routed`, routingmode=Dynamic) + tier `10.90.6.0/24` create via cmk with no "Gateway/Ovn not supported"; VM `gap-ovnr-p1` boots (10.90.6.30 on aragog). Deployed to mgmt fat jar. |
| P2 skip-SNAT routed | ✅ done | `OvnNetworkElement`: inject `VpcOfferingDao`, add `isRoutedVpc(Vpc)` (offering.getNetworkMode()==ROUTED, fails safe to NATTED), early-return in `ensureVpcSourceNat` for ROUTED (skips both the VPC-wide `snat` row and the source-nat /32 announce; covers both call sites). Verified: a routed VPC created post-fix has **0 snat** rules on its OVN LR (vs 1 on the pre-fix P1 VPC); VM boots + egresses with its real tier IP. Deployed to mgmt fat jar. Open (deferred to P5): whether a ROUTED VPC should allocate a source-nat public IP at all (server-side createVpc path). |
| P3 routed-private announce | ✅ done | `OvnBgpAnnounceCommand` gains optional `prefixLength` (defaults /32, FIP path unchanged); KVM wrapper `resolvePrefixLen` emits `network <cidr>/<plen>` + kernel route; `OvnLogicalIdMapVO.Kind.BGP_SUBNET_ANNOUNCE`; `OvnBgpRedistributeManager.announceSubnet/withdrawSubnet`; `OvnNetworkElement.ensureRoutedTierAnnounce` wired in `prepare()` (withdraw in `destroy()`), gated on `isRoutedVpc`. **jdb-on-server found the bug:** `BgpRedistributeRoutedTiers.value()` returned the String `"true"` (hot-added ConfigKey, ConfigDepot unparsed) so `Boolean.TRUE.equals(value())` was always false → fixed to `Boolean.parseBoolean(String.valueOf(value()))`. **Validated:** tier `10.90.10.0/24` announced → self-originated on aragog (best, `network` clause + kernel route via pub LRP .89.39) → RRs `.34/.35` learned via iBGP (Originator .88.5, localpref 100) → **upstream .225 advertised = 0** (no-leak filter holds). Deployed: mgmt fat jar (api+ovn) + aragog agent jars (cloud-api+kvm). |
| P4 routed-public | ✅ mechanism ready (live announce deferred) | Finding (P4 spec): routed-public is **DATA-identical to P3** — no per-tier public/private branching in the plugin; the ONLY gates are (a) the dynamic global `allow.non.rfc1918.compliant.ips` (guards the 3 RFC1918 CIDR checks: VpcManagerImpl.createVpc L1840, NetworkOrchestrator.setupNetwork L2988, NetworkServiceImpl.updateNetwork L3348) and (b) the fabric FRR `EBGP-LAT-OUT-V4` whitelist. **Done:** flipped `allow.non.rfc1918.compliant.ips` false→true (dynamic, no restart); confirmed the filter ADVERTISES the whitelisted public blocks upstream to .225 (`217.179.88.0/24`, `217.179.88.64/26`, `217.179.89.0/24`) while `10.90.x` private = 0 — so a routed tier in a whitelisted public block leaks upstream (permitted) exactly as a private tier does NOT. **Deferred (outward-facing):** actually standing up a routed-public VPC (public super-CIDR e.g. `.88.64/26`) + VM announces real public IPs to the internet (AS24452) — a coordinated public-block assignment, not an autonomous test. Code + config path is ready; the P3 announce handles the /24 identically. Caveat (P5 spec): a public-CIDR tier can't share an RFC1918 VPC (tier-cidr-within-vpc-cidr check) — mode 4 uses its own public-super-CIDR VPC. |
| P5 mixed + cmk surface | ✅ done | Per-tier dispatch in `OvnNetworkElement`: tier mode = does its OWN offering provide `Service.SourceNat` (present→NAT tier, absent→routed tier). `isRoutedTier` (fail-safe to NATTED: requires Connectivity present before trusting the SourceNat-absent signal), `vpcHasRoutedTier`, `ensureTierEgressSourceNat` (uniform VPCs keep the P2 legacy VPC-wide path; a MIXED VPC drops the VPC-wide snat and re-asserts a per-tier snat for EVERY NAT tier scoped to its own cidr — fixes a live-mutation NAT-egress gap), `ensureRoutedTierAnnounce` widened to fire on `isRoutedVpc(vpc) OR isRoutedTier(network)`; `ovn.tier.advertise` network-detail override. **Core-validation blockers found via live cmk test (the P5 spec's premise was wrong):** (1) `Gateway` is NOT a valid network-offering service (createNetworkOffering rejects it, err 4350) — a routed tier offering carries Connectivity/Dhcp/Dns/NetworkACL only, no Gateway; (2) `VpcManagerImpl.validateNtwkOffForVpc` required every VPC tier offering to have SourceNat-or-Gateway → **relaxed** to also accept an Isolated tier whose Connectivity is a non-VirtualRouter SDN (OVN) — the SDN LR is the gateway, egress is BGP-routed. **Validated:** one NATTED OVN VPC (`10.91.0.0/16`) with a NAT tier (`10.91.1.0/24`, VM on nagini) AND a routed tier (`10.91.6.0/24`, VM on scabbers) — the OVN LR has `snat 217.179.89.40 10.91.1.0/24` (NAT tier, scoped to its own cidr; NO VPC-wide /16 snat) and NO snat for the routed tier, whose /24 self-originates on the gateway chassis. Modes 1+3 coexist in one VPC, all via cmk. Deployed: mgmt fat jar (OVN plugin + `server`/VpcManagerImpl). Mode 4 (routed-public) uses its own public-super-CIDR VPC (containment caveat). |
