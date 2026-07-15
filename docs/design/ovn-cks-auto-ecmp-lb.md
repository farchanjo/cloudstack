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
- Default empty ConfigKeys → zero behavior change

## ConfigKeys

| Key | Syntax | Effect |
|-----|--------|--------|
| `ovn.lr.ecmp.auto.clusters` | `<network-uuid>=<cks-uuid>\|<v4-prefix>\|<v6-prefix>;…` | Worker guest IPs → ECMP next-hops for VIP prefixes |
| `ovn.lr.ecmp.static.routes` | existing | Manual overlay; merge after auto (same-prefix union) |
| `ovn.lb.auto.cks` | `<lb-rule-id>=<cks-uuid>:<dest-port>;…` | Rewrite `load_balancer_vm_map` + re-apply OVN LB |

## Source of workers

`KubernetesClusterVmMapDao.listByClusterIdAndVmType(WORKER)` + `NicDao` on tier + `VMInstance.State.Running`.

## Runtime path

1. `OvnBgpReconcileTask` / `OvnReconcilerService.reconcileZone`  
2. `buildDesiredEcmpRoutes()` = auto expand ∪ static parse  
3. existing `ensureEcmpStaticRoutes` / `filterRunningNextHops`  
4. `ensureLbAutoCksForZone` → inventory diff → `OvnLoadBalancerService.applyLBRules`

## Ops phases

0. Deploy code; keys empty  
1. Enable ECMP auto; keep static  
2. Verify `ovn-nbctl` hops; clear static hop list  
3. Enable LB auto per rule (backup `load_balancer_vm_map` first)  
4. Rollback: empty auto keys  

## Closes

Design Q7 in `ovn-public-ipv6-fip-lb-self-service-api.md` (optional automation).
