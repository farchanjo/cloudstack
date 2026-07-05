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

> ⚠️ **The mgmt is a 3-node active cluster** (`voldemort`/`bellatrix`/`barty`). Async jobs
> (VM deploy, network prepare, `ensureVpcPublicAttached`, the announce) run on whichever node
> picks them off the queue — random per op. **Patch the fat jar + restart on ALL 3 control
> nodes** (one at a time, keep 2/3 up), else the feature runs only ~1/3 of the time and
> single-node validation is a false positive. This exact gap caused the mode-4 flakiness
> (bellatrix/barty had the stale jar). Verify each: `javap -p -classpath <fatjar> …OvnNetworkElement | grep -c <newMethod>` = 1. Details: [[cloudstack-mgmt-cluster-deploy-all-3-nodes]].

## 8. Progress

| Phase | Status | Notes |
|---|---|---|
| Design | ✅ done | this doc |
| RR bgppeers via cmk | ✅ done | `.34/.35/.36` AS 4200000002 created |
| P1 Gateway capability | ✅ done | fix in `VpcManagerImpl.createVpcOffering` (Gateway follows Connectivity provider for ROUTED SDN) + `OvnNetworkElement` declares Service.Gateway. Verified: OVN Routed VPC (`vpc-ovn-routed`, routingmode=Dynamic) + tier `10.90.6.0/24` create via cmk with no "Gateway/Ovn not supported"; VM `gap-ovnr-p1` boots (10.90.6.30 on aragog). Deployed to mgmt fat jar. |
| P2 skip-SNAT routed | ✅ done | `OvnNetworkElement`: inject `VpcOfferingDao`, add `isRoutedVpc(Vpc)` (offering.getNetworkMode()==ROUTED, fails safe to NATTED), early-return in `ensureVpcSourceNat` for ROUTED (skips both the VPC-wide `snat` row and the source-nat /32 announce; covers both call sites). Verified: a routed VPC created post-fix has **0 snat** rules on its OVN LR (vs 1 on the pre-fix P1 VPC); VM boots + egresses with its real tier IP. Deployed to mgmt fat jar. Open (deferred to P5): whether a ROUTED VPC should allocate a source-nat public IP at all (server-side createVpc path). |
| P3 routed-private announce | ✅ done | `OvnBgpAnnounceCommand` gains optional `prefixLength` (defaults /32, FIP path unchanged); KVM wrapper `resolvePrefixLen` emits `network <cidr>/<plen>` + kernel route; `OvnLogicalIdMapVO.Kind.BGP_SUBNET_ANNOUNCE`; `OvnBgpRedistributeManager.announceSubnet/withdrawSubnet`; `OvnNetworkElement.ensureRoutedTierAnnounce` wired in `prepare()` (withdraw in `destroy()`), gated on `isRoutedVpc`. **jdb-on-server found the bug:** `BgpRedistributeRoutedTiers.value()` returned the String `"true"` (hot-added ConfigKey, ConfigDepot unparsed) so `Boolean.TRUE.equals(value())` was always false → fixed to `Boolean.parseBoolean(String.valueOf(value()))`. **Validated:** tier `10.90.10.0/24` announced → self-originated on aragog (best, `network` clause + kernel route via pub LRP .89.39) → RRs `.34/.35` learned via iBGP (Originator .88.5, localpref 100) → **upstream .225 advertised = 0** (no-leak filter holds). Deployed: mgmt fat jar (api+ovn) + aragog agent jars (cloud-api+kvm). |
| P4 routed-public | ✅ mechanism ready (live announce deferred) | Finding (P4 spec): routed-public is **DATA-identical to P3** — no per-tier public/private branching in the plugin; the ONLY gates are (a) the dynamic global `allow.non.rfc1918.compliant.ips` (guards the 3 RFC1918 CIDR checks: VpcManagerImpl.createVpc L1840, NetworkOrchestrator.setupNetwork L2988, NetworkServiceImpl.updateNetwork L3348) and (b) the fabric FRR `EBGP-LAT-OUT-V4` whitelist. **Done:** flipped `allow.non.rfc1918.compliant.ips` false→true (dynamic, no restart); confirmed the filter ADVERTISES the whitelisted public blocks upstream to .225 (`217.179.88.0/24`, `217.179.88.64/26`, `217.179.89.0/24`) while `10.90.x` private = 0 — so a routed tier in a whitelisted public block leaks upstream (permitted) exactly as a private tier does NOT. **Deferred (outward-facing):** actually standing up a routed-public VPC (public super-CIDR e.g. `.88.64/26`) + VM announces real public IPs to the internet (AS24452) — a coordinated public-block assignment, not an autonomous test. Code + config path is ready; the P3 announce handles the /24 identically. Caveat (P5 spec): a public-CIDR tier can't share an RFC1918 VPC (tier-cidr-within-vpc-cidr check) — mode 4 uses its own public-super-CIDR VPC. **Live non-destructive validation (operator chose the reuse-89.0/24 path):** confirmed (a) CloudStack creates a public-CIDR VPC + routed-public tier with `allow.non.rfc1918.compliant.ips=true` (no vlaniprange carve-out needed just to create); (b) a VM gets a REAL public IP (`217.179.89.209/27`) via ORDINARY guest IPAM (`acquireGuestIpAddress` over the public tier CIDR) — zero new IPAM code, no FIP/user_ip_address row; (c) NO snat for the routed tier on the OVN LR. **BLOCKER found:** the public LRP (the egress default-route anchor) does NOT stand up for a NATTED VPC hosting ONLY a routed tier — the source-nat IP is never marked `issourcenat=true` (nothing triggers VPC-level SourceNat; even passing an explicit `sourcenatipaddress` at create leaves it `false`), so `ensureVpcPublicAttached` defers and the routed-public VM has no egress default route. The `/24`-public-LRP vs `/27`-tier northd overlap therefore stayed UNVERIFIED (no public LRP to exercise it). **Mode-4 follow-up DONE + datapath-validated (commit ebce95d):** `ensureVpcPublicAttached` now anchors the public LRP on the first operator-associated public IP when there is no source-NAT IP but the VPC has a routed tier (gated on `vpcHasRoutedTier`, so pure-NAT/FIP VPCs are byte-identical). Validated live via ovn-trace on a routed-public VPC (tier `217.179.89.192/27`, VM `.196`, anchor `.41`): public LRP comes up `217.179.89.41/24` + `0.0.0.0/0` default route via `.89.1`; **egress** from the VM matches the default route out `lrp-public` keeping `.196` as source (no snat); **inbound** to `.196` matches the tier `.192/27` route at **priority 222** and is delivered to the tier LRP — OVN northd longest-prefix-matches the overlapping `/24` public LRP and `/27` tier connected subnets CLEANLY, so the design's "/32-host" edit #1 is UNNEEDED. Operator flow: create NATTED-base VPC (public super-CIDR) → `associate ipaddress` (the anchor) → routed tier (no-SourceNat offering) → VM (hugepages). **FIP-pool carve-out DONE + verified:** the memory's "delete+recreate" was infeasible (delete is blocked by allocated IPs .32-.40) — the real mechanism is `updateVlanIpRange id=<range> startip=217.179.89.32 endip=217.179.89.191` (server validates all allocated IPs stay within the new bounds via `checkAllocatedIpsAreWithinVlanRange`, so no in-place delete). Shrunk the FIP pool `.32-.254 → .32-.191`; verified pool now has 160 IPs, max=.191, **0 IPs in .192-.254** (the FIP allocator can no longer collide with a routed-public tier), all allocated FIPs .32-.40 intact. **`.192-.254` (/26) is now the reserved routed-public tier block.** **Cluster-deploy fix:** the mode-4 fix (+ all of P1-P5) was deployed to ALL 3 mgmt nodes (was voldemort-only — the root cause of the intermittency); re-validated on a post-fix VPC (public LRP `.41/24` + `0.0.0.0/0` default route + 0 snat) regardless of which node runs the deploy. Test VPCs torn down clean; fabric verified clean. Mode-4 (routed-public) is now END-TO-END WORKING. |
| Isolated-B standalone L3 | ✅ done + live-validated end-to-end (binding + ingress + egress incl. real internet HTTPS round-trip) | Phase B (§9, commit `faed26a60c`): per-network LR + gateway + source-NAT + public egress + ingress firewall for a non-VPC Isolated OVN network. Strictly additive, `VPC_REGRESSION=NONE` (adversarially verified). Validated live on `ovn-iso-pb2` (VM `10.94.1.31`): VM **binds** (iface-id=`lsp-<nicuuid>`, ovn-installed=true), resolves its gateway, gets L2/L3 + default route; **ingress round-trips** (Mac→`.89.42` ping 3/3 + SSH open, ingress firewall tcp/22+icmp enforced); **egress datapath confirmed** (VM traffic SNAT'd to `.89.42` and leaves the physical uplink `dx6p0` to the internet — tcpdump at every hop). Destroy path clean (0 orphans, processor drains the 5 new Kinds). **The binding "blocker" seen on the first test was a MISSING `useOvn` OFFERING TAG** (required by `HypervisorGuruBase` to set `NicTO.useOvn` → agent uses `OvnVifDriver` → stamps `iface-id=lsp-<nicuuid>`); a test-setup omission, not a code defect — all working OVN offerings carry `tags=useOvn`. **Internet egress WORKS** (real HTTPS round-trip from `.89.x` to `cloudflare.com` confirmed, 90 ms) — the earlier "not internet-return-routable" note was a false alarm from testing only `8.8.8.8`. A curated set of famous public-DNS resolver IPs (8.8.8.8/8.8.4.4/1.0.0.1/4.2.2.2/9.9.9.10/199.85.126.10) is blackholed for `.89.0/24`-sourced traffic by an **upstream DDoS-scrubbing/reputation policy** on the recently-RIPE-reallocated block (ICMP-pass-but-TCP-fail on 1.1.1.1/9.9.9.9 rules out a routing gap; control `.88.5→8.8.8.8` passes) — an upstream provider matter, not the plugin. Follow-up: NOC ticket to AS396356, or hand VMs a non-blackholed resolver (e.g. OpenDNS). Deployed to all 3 mgmt nodes; test artifacts torn down clean. |
| P5 mixed + cmk surface | ✅ done | Per-tier dispatch in `OvnNetworkElement`: tier mode = does its OWN offering provide `Service.SourceNat` (present→NAT tier, absent→routed tier). `isRoutedTier` (fail-safe to NATTED: requires Connectivity present before trusting the SourceNat-absent signal), `vpcHasRoutedTier`, `ensureTierEgressSourceNat` (uniform VPCs keep the P2 legacy VPC-wide path; a MIXED VPC drops the VPC-wide snat and re-asserts a per-tier snat for EVERY NAT tier scoped to its own cidr — fixes a live-mutation NAT-egress gap), `ensureRoutedTierAnnounce` widened to fire on `isRoutedVpc(vpc) OR isRoutedTier(network)`; `ovn.tier.advertise` network-detail override. **Core-validation blockers found via live cmk test (the P5 spec's premise was wrong):** (1) `Gateway` is NOT a valid network-offering service (createNetworkOffering rejects it, err 4350) — a routed tier offering carries Connectivity/Dhcp/Dns/NetworkACL only, no Gateway; (2) `VpcManagerImpl.validateNtwkOffForVpc` required every VPC tier offering to have SourceNat-or-Gateway → **relaxed** to also accept an Isolated tier whose Connectivity is a non-VirtualRouter SDN (OVN) — the SDN LR is the gateway, egress is BGP-routed. **Validated:** one NATTED OVN VPC (`10.91.0.0/16`) with a NAT tier (`10.91.1.0/24`, VM on nagini) AND a routed tier (`10.91.6.0/24`, VM on scabbers) — the OVN LR has `snat 217.179.89.40 10.91.1.0/24` (NAT tier, scoped to its own cidr; NO VPC-wide /16 snat) and NO snat for the routed tier, whose /24 self-originates on the gateway chassis. Modes 1+3 coexist in one VPC, all via cmk. Deployed: mgmt fat jar (OVN plugin + `server`/VpcManagerImpl). Mode 4 (routed-public) uses its own public-super-CIDR VPC (containment caveat). |

## 9. Standalone (non-VPC) Isolated OVN networks + Firewall (separate feature)

Orthogonal to the VPC routed work above. Goal: a plain **Isolated (non-VPC)** guest network on an OVN backend carrying the CloudStack **Firewall** service, with `cmk` firewall rules programming real OVN ACLs.

**Phase A — Firewall service on an isolated OVN L2 segment — ✅ DONE + live-validated** (commits `95adb7de` + `4370454b`):
- `OvnNetworkElement` implements `FirewallServiceProvider`; `applyFWRules` special-cases the System-Egress default rule (allow-related vs drop per `getNetworkEgressDefaultPolicy`) and delegates user rules to `OvnFirewallService.applyFirewallRules` (FirewallRule → OVN ACL, Ingress→to-lport / Egress→from-lport, allow-related, shared `composeMatch` with the NetworkACL path). Mappings under a new `Kind.FIREWALL`.
- `Service.Firewall` declared in `buildCapabilities` with `firewallCaps()` — `SupportedTrafficDirection` + `SupportedProtocols` + `SupportedEgressProtocols` (incl. `all`); `TrafficStatistics`/`MultipleIps` OMITTED (the old "enforces TrafficStatistics" comment was **wrong** — the only offering-create check, `NetworkModelImpl.canProviderSupportServices`, just verifies the Service KEY exists in the cap map).
- **Secure-by-default:** `installDefaultDenyBaseline` programs a low-priority (10) DROP scoped to `ip4 || ip6` in both directions (so ARP/ND still pass); DHCP (udp 67/68) + DNS (53) allow-related at priority 150 above it re-permit those. **Critical (found live):** the OVN `ls_in_acl` stage precedes the native DHCP/DNS responders, so a blanket drop would starve a VM of its lease — the infra-allows fix that. Synthetic ACL rows use a **POSITIVE** cs_id (base 9e18 + networkId*16 + slot) — the `ovn_logical_id_map.cs_id` column is `bigint unsigned`, so an earlier negative scheme aborted the network implement with "Out of range value" (VM never started; **found via live test, not the code review**).
- **Validated live:** isolated network `10.93.1.0/24` (`egressdefaultpolicy=false`), VM boots (`.61`), `ovn-nbctl acl-list` shows the baseline drop + DHCP/DNS infra-allow, and `cmk create egressfirewallrule` (tcp/443 and proto=`all`) become `from-lport allow-related` ACLs at priority ~1373-1376. Deployed to **all 3 mgmt nodes**. Offering shape: `guesttype=Isolated forvpc=false egressdefaultpolicy=... supportedservices=Connectivity,Dhcp,Dns,Firewall` (all `Ovn`); an isolated network with no SourceNat requires an explicit `gateway`+`netmask`.
- **Phase A scope = EGRESS firewall + intra-segment ACLs.** Ingress firewall rules attach to a source-NAT public IP, which an L2-only isolated network lacks.

**Phase B — standalone L3 (per-network LR + gateway + source-NAT + public egress) — ✅ DONE + live-validated end-to-end (binding + ingress + egress datapath); internet round-trip gated by an environmental public-block routing matter (NOT a Phase B defect)** (commit `faed26a60c`):
- **Implementation (strictly additive; VPC datapath byte-for-byte unchanged — adversarially verified `VPC_REGRESSION=NONE`).** New `Kind`s `NETWORK_LR` / `NETWORK_GW_LRP` / `ISOLATED_PUBLIC_LRP` / `ISOLATED_PUBLIC_RSP` / `ISOLATED_STATIC_ROUTE` (all keyed by `NetworkVO.id`, POSITIVE — bigint-unsigned safe; `SOURCE_NAT` reused by network id). `OvnVpcElement` gains `createLogicalRouterForNetwork` / `bindNetworkGateway` / `deleteLogicalRouterForNetwork` (the VPC LR methods untouched). `OvnPublicNetworkManager.bindVpcToPublic`/`persistVpcPublicBind`/`ensureVpcBoundToPublic`/`unbindVpcFromPublic` generalized to an owner-key form; the VPC entrypoints are thin delegators feeding the exact historical Kinds/names/nexthop(null). `OvnNetworkElement` `implement()`/`prepare()`/`applyIps()`/`applyStaticNats()`/`destroy()`/`shutdown()` gain isolated (`vpcId==null`) branches gated on `isStandaloneL3Isolated` (vpcId==null AND offering provides `SourceNat`). `OvnPendingDeletionProcessor` dispatches the 5 new Kinds. The isolated default-route nexthop uses the real `vlan.getVlanGateway()`.
- **Control-plane validated live** (isolated network `ovn-iso-pb` `10.94.1.0/24`, offering `guesttype=Isolated forvpc=false egressdefaultpolicy=true supportedservices=Connectivity,SourceNat,Firewall,Dhcp,Dns,StaticNat`, VM `pb-vm` on a hugepages offering): the OVN NB is programmed **completely and correctly, byte-for-byte identical in structure to a working VPC tier** — per-network LR `lr-net-<uuid>` + gateway LRP `10.94.1.1/24` + `snat 10.94.1.0/24 → 217.179.89.41` + `dnat_and_snat 217.179.89.42 ↔ 10.94.1.240` + `0.0.0.0/0 → 217.179.89.1 (real vlan gw) out lrp-public-net637` + DHCP `router=10.94.1.1` + firewall ACLs (default-deny baseline + DHCP/DNS infra-allow + user tcp/22 & icmp allow). northd computed the LR datapath (153 lflows) + the LS↔LR patch (peer options + datapaths correct). **`ovn-trace` PROVES the logical datapath**: the gateway ARP responder (`ls_in_arp_rsp` for `10.94.1.1`) fires and replies with the gateway MAC; the source-NAT IP auto-acquired (`.41`), the static-NAT + firewall rules realized. ovn-controller installed the gateway flows on the hypervisor.
- **Destroy path validated:** `delete network` tore down the LR + gateway LRP + SNAT + public LRP + default route + firewall with **0 orphan `ovn_logical_id_map` rows**, LR gone from NB, public IPs freed, and the async `OvnPendingDeletionProcessor` correctly processed + soft-removed all 5 new Kinds (0 active pending rows).
- **Live end-to-end validated (2nd test, `ovn-iso-pb2`, VM `10.94.1.31`):** with the offering carrying `tags=useOvn`, the VM **binds** (OVS `external_ids:iface-id=lsp-<nicuuid>` + `ovn-installed=true`), stops ARP-storming, **resolves its gateway** and installs `default via 10.94.1.1`. **Ingress round-trips:** `ping 217.179.89.42` from the public internet = 3/3, `tcp/22` open + SSH login succeeds (ingress firewall tcp/22 + icmp enforced). **Egress datapath confirmed** on the gateway chassis (aragog) via tcpdump: the VM's packet arrives over Geneve (`10.94.1.31 > 8.8.8.8`), is **SNAT'd** (`217.179.89.42 > 8.8.8.8`), and **egresses the physical uplink** (`public`→`c-bond`→`dx6p0 Out`) toward the internet.
- **The first test's "port never binds" symptom was a MISSING `useOvn` OFFERING TAG — a test-setup omission, NOT a code/environment defect.** `HypervisorGuruBase.toNicTO` sets `NicTO.useOvn=true` + `ovnLspName=lsp-<nicuuid>` **only when the NetworkOffering carries `tags=useOvn`** (line ~336). Without it the agent never routes the NIC through `OvnVifDriver`, so the OVS `iface-id` is never re-stamped to the LSP name and `ovn-controller` never claims the Port_Binding. All shipped OVN offerings (`tier-vf-ovn`, `tier-vdpa-ovn`, …) carry `tags=useOvn`; the isolated-L3 offering just needs it too. **This is the real contract: an OVN network needs BOTH the `Connectivity=Ovn` service (control plane) AND the `useOvn` offering tag (data-plane VIF binding).**
- **Internet egress WORKS to the general internet (corrected — the earlier "not internet-return-routable" conclusion was WRONG, a false alarm from testing ONLY 8.8.8.8).** Confirmed from the gateway chassis sourced from the CloudStack public block: `curl --interface 217.179.89.2 https://cloudflare.com` → HTTP 301, remote `104.16.132.229`, 90 ms round-trip; `ping -I 217.179.89.2` to `1.1.1.1` / `9.9.9.9` / `208.67.222.222` = 0% loss. **Our side is clean:** `.89.0/24` is BGP-advertised to upstream AS396356 (prefix-list `EBGP-LAT-OUT-V4` permits it), RPKI-valid, globally visible (325/325 RIS peers); the provider sends only a default route; `rp_filter` is loose. **A curated set of famous public-DNS anycast IPs is blackholed for `.89.0/24`-sourced traffic** (8.8.8.8, 8.8.4.4, 1.0.0.1, 4.2.2.2, 9.9.9.10, 199.85.126.10 — and `1.1.1.1`/`9.9.9.9` pass ICMP but time out on TCP/443), while the established block `217.179.88.0/24` reaches all of them (control `.88.5 → 8.8.8.8` = 0% loss). ICMP-pass-but-TCP-fail on the same IP rules out a routing gap and points to an **upstream DDoS-scrubbing / reputation appliance** applied to the recently-RIPE-reallocated `217.179.89.0/24` (it was AS834's until ~2026-03) that drops traffic to common reflection-attack-target resolver IPs. This is an **upstream provider policy, not the OVN plugin / Phase B / our routing.** The originally-observed VM symptom (`ping 8.8.8.8` + `curl ifconfig.me` DNS both failed) was exactly this: the VM's DNS/test happened to hit the blackholed resolver set. **Follow-ups (out of Phase B scope): (a) NOC ticket to AS396356 to clear the scrubbing/reputation policy on `217.179.89.0/24`; (b) immediate — hand CloudStack VMs a non-blackholed resolver (OpenDNS `208.67.222.222` passes, or the site's own) via the OVN `Dns` service / DHCP, instead of `8.8.8.8`.**
