# Design: CKS auto ECMP + auto LB backends

**Status:** implemented (ConfigKey opt-in)  
**Train:** schema 4.24.1.30 → 4.24.1.31 (ConfigKey INSERT only; no DDL)  
**Product:** `4.24.1.31-SNAPSHOT`  
**Deploy:** jar-direct all 3 management nodes (no `.deb`) — fat jar  
`client/target/cloud-client-ui-4.24.1.31-SNAPSHOT.jar` →  
`/usr/share/cloudstack-management/lib/cloudstack-4.24.1.31-SNAPSHOT.jar`

## Non-goals

- No Kubernetes API / pod watch  
- No auto placement of Istio hostNetwork pods  
- No force-rewrite of **explicit** Ready-only public edge membership  
- Default empty ConfigKeys → zero behavior change

## ConfigKeys

| Key | Syntax | Effect |
|-----|--------|--------|
| `ovn.lr.ecmp.auto.clusters` | `<network-uuid>=<cks-uuid>\|<v4-prefix>\|<v6-prefix>;…` | Worker guest IPs → ECMP next-hops for VIP prefixes |
| `ovn.lr.ecmp.static.routes` | existing | Manual overlay; merge after auto (same-prefix union) |
| `ovn.lb.auto.cks` | `<lb-rule-id>=<cks-uuid>:<dest-port>;…` | Rewrite `load_balancer_vm_map` + re-apply OVN LB (**full worker set only**) |

### Hard ban — do not list these in `ovn.lb.auto.cks`

| Rule name pattern | Why |
|---|---|
| `istio-public-*` | hostNetwork Ready subset (not all workers) |
| `istio-accounting-*` / `istio-accounting-*6` | same |
| `pub6-istio-*` | public IPv6 inventory mirrors Ready subset |
| any `DSR_SOFTWARE` rule | owned by `DsrSoftwareLbService` |

Mis-listing Istio public/accounting rules re-adds every CKS worker each
`OvnBgpReconcileTask` tick (TLS reset / blackhole on nodes without Envoy).

**Code guard (2026-07-18):** `OvnReconcilerService.isExplicitMembershipOnlyLbRule`
+ non-CT_LB skip — even if a banned name is listed, auto-CKS **refuses** the
rewrite and logs WARN. Operators still must keep the ConfigKey empty for those
ids. Membership for public edges = `assignToLoadBalancerRule` /
`removeFromLoadBalancerRule` only (Ready EndpointSlices).

## Source of workers

`KubernetesClusterVmMapDao.listByClusterIdAndVmType(WORKER)` + `NicDao` on tier + `VMInstance.State.Running`.

## Runtime path

1. `OvnBgpReconcileTask` / `OvnReconcilerService.reconcileZone`  
2. `buildDesiredEcmpRoutes()` = auto expand ∪ static parse  
3. existing `ensureEcmpStaticRoutes` / `filterRunningNextHops`  
4. `ensureLbAutoCksForZone` → skip explicit/DSR → inventory diff → `OvnLoadBalancerService.applyLBRules`

## Ops phases

0. Deploy code; keys empty  
1. Enable ECMP auto; keep static  
2. Verify `ovn-nbctl` hops; clear static hop list  
3. Enable LB auto **only** for full-worker rules (e.g. API LB) — never Istio public/accounting  
4. Rollback: empty auto keys  

## Incident note (2026-07-18 LAX salazar)

`ovn.lb.auto.cks` listed `1464/1467/1542/1545` (istio-public + accounting v4).
Reconciler rewrote maps to 10 workers every tick. Fix: clear ConfigKey + explicit
Ready 3/6 membership + name guard above. Snape DSR `1650/1653/1656/1659` untouched.

## Closes

Design Q7 in `ovn-public-ipv6-fip-lb-self-service-api.md` (optional automation).
