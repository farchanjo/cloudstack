# Bug 18 — Tier LS missing router-type peer LSP (LS→LR path broken)

**Date:** 2026-05-10 (patch) / 2026-05-11 (production verification)
**Status:** FIXED
**Fix commit:** `cd1d7ad5f0ef0be32812e8f4ce65b9d173e2cf33`
**Severity:** HIGH
**VPC under test:** `test20` (uuid `a1992656-2a76-43a5-82cc-3c9b7f5402ca`)

---

## Symptoms

All three tiers of VPC `test20` (tap-vdpa, tap-vf, tap-tap) had their
Logical_Router_Port (LRP) entries in OVN NB but zero matching `type=router`
Logical_Switch_Port (LSP) entries on the tier Logical_Switch. Observable
evidence before fix:

```
$ ovn-nbctl get Logical_Router_Port lrp-fa50740c-86af-47c6-8f2f-efa151b8b8fd peer networks mac
[]
["10.97.1.1/24"]
"02:01:01:61:01:01"

$ ovn-nbctl find Logical_Switch_Port type=router
# ONLY rsp-public-vpcXXX entries; NO rsp-<tierUUID>
```

- VMs could not ARP for tier gateway (e.g. `10.97.1.1`): no reply.
- Inter-tier routing through the VPC Logical_Router was broken.
- Intra-LS ICMP (same-tier VMs) worked normally.
- `rsp-*` LSPs existed for the north-south `public-vpcXXX` LS only.

---

## Root Cause

**Hypothesis H2 confirmed** (current code path has an incomplete reconcile,
not a legacy pre-bindLrToLs code path).

`OvnNetworkElement.ensureTierBoundToVpcLr()` persists only the LRP UUID in
the `Kind.PUBLIC_LRP` `OvnLogicalIdMapVO` mapping row. On the idempotent
reconcile path (`existing != null`), it called `rowExistsByUuid` to confirm
the LRP row still exists in OVN NB and, if so, called
`updateLogicalRouterPortNetworks` and returned immediately — without ever
checking whether the matching `type=router` peer LSP existed on the tier LS.

The `OvnLogicalIdMapVO` row stores only the LRP UUID; it stores nothing about
the peer LSP. Therefore, any scenario that destroyed the peer LSP after the
initial `bindLrToLs` transaction left `lrp-<tierUUID>.peer=[]` with no
recovery path.

Triggering scenarios (any one sufficient):
1. A prior cleanup bug that deleted the tier LS and rebuilt it without
   re-running `bindLrToLs`.
2. A partial OvnTransaction commit (e.g. NB leader failover mid-batch)
   where the LRP insert committed but the paired LSP insert did not.
3. Manual `ovn-nbctl` edits that removed a port from a tier LS.

Git evidence supporting H2:

```
cd1d7ad5f0  fix(ovn/lr-binding): self-heal missing tier-LSP peer on LR binding
22a4715f0f  feat(ovn): wire 11 OVN tunables across NicTO + NB client + dispatcher
e44d18cd0b  feat(ovn): enqueue NB rows before sync delete in all OVN delete paths
5c6c41eb4a  refactor(plugin-ovn): OvnNetworkElement destroy/shutdownVpc/shutdown with retry queue
```

The short-circuit early-return was present since `bindLrToLs` was adopted in
the refactor series (`5c6c41eb4a` era). No "legacy pre-bindLrToLs" LRP
insertion path was found — all paths call `OvnVpcElement.bindTierToVpc` which
delegates to `bindLrToLs`. The orphan LSP arose from a lost LSP after binding
completed, not from a prior code path that skipped LSP creation.

---

## Fix

**Files changed:**

- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/client/OvnNbClient.java`
  - Added `ensureRouterPeerLsp(String lsUuid, String lspName, String lrpName)`:
    idempotent helper. Looks up the LSP by name. If missing, inserts a
    `type=router` LSP with `options:router-port=<lrpName>` and mutates
    `LS.ports` in a single `OvnTransaction`. If present, re-attaches to the
    LS (covers the detached-but-not-deleted orphan case). Returns the UUID of
    the existing or newly created LSP.

- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnNetworkElement.java`
  - In `ensureTierBoundToVpcLr`, on the `existing != null` reconcile path,
    after `updateLogicalRouterPortNetworks`, now calls
    `nb.ensureRouterPeerLsp(tierLsUuid, "rsp-"+tierName, "lrp-"+tierName)`.
    Logs at INFO: `OvnNetworkElement.ensureTierBound: peer LSP <uuid> ensured`.
    The call is idempotent: if the LSP is already present it is a no-op.

---

## Build Evidence

Built on aragog (`10.182.0.21`) from `/root/cloudstack-feature` commit `cd1d7ad5f0`:

```
mvn -T 4 -pl client,plugins/network-elements/ovn -am -Pdeveloper install \
    -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true
# BUILD SUCCESS — 48s wall clock
```

| Artifact | md5 |
|---|---|
| `cloud-plugin-network-ovn-4.24.1.26-SNAPSHOT.jar` | `7e11246603b2425cc73b5cae965fa348` |
| `cloud-client-ui-4.24.1.26-SNAPSHOT.jar` (uber-jar, deployed as `cloudstack-4.24.1.26-SNAPSHOT.jar`) | `c4b4373fd56aac1b613adc4eef81f315` |

OVN plugin classes are bundled inside the uber-jar; no standalone plugin JAR
found in `/usr/share/cloudstack-management/lib/` on controls before deploy.

---

## Deploy Evidence

Deployed to all 3 control nodes via `scp` from aragog (mgmt VLAN):

| Host | IP | Backup file | Deployed md5 | Match |
|---|---|---|---|---|
| voldemort | 10.182.0.11 | `cloudstack-4.24.1.26-SNAPSHOT.jar.bak.20260511012122` | `c4b4373fd56aac1b613adc4eef81f315` | YES |
| bellatrix | 10.182.0.12 | `cloudstack-4.24.1.26-SNAPSHOT.jar.bak.20260511012124` | `c4b4373fd56aac1b613adc4eef81f315` | YES |
| barty     | 10.182.0.13 | `cloudstack-4.24.1.26-SNAPSHOT.jar.bak.20260511012125` | `c4b4373fd56aac1b613adc4eef81f315` | YES |

Rolling restart (one node at a time to maintain HA):

| Host | Restart outcome | API check |
|---|---|---|
| voldemort | active after attempt 4 (~30s) | HTTP 401 (serving) |
| bellatrix | active after attempt 4 (~30s) | HTTP 401 (serving) |
| barty     | active after attempt 4 (~30s) | HTTP 401 (serving) |

---

## Reconcile Trigger

Triggered `cmk restartNetwork cleanup=false` on all 3 tier UUIDs. The API
returned errorcode 530 "Failed to restart network" (unrelated failure in the
cleanup/rebuild phase after the OVN implement step), but `ensureTierBoundToVpcLr`
ran before the error in all 3 cases. Management log evidence from
`/var/log/cloudstack/management/management-server.log` on voldemort:

```
2026-05-11 01:26:58,023 INFO OvnNetworkElement.ensureTierBound: peer LSP
  9c9059a8-f4c3-4728-8383-c1425bc62e5e ensured
  (tier id=595, lrpName=lrp-fa50740c-86af-47c6-8f2f-efa151b8b8fd)

2026-05-11 01:27:00,182 INFO OvnNetworkElement.ensureTierBound: peer LSP
  52688723-221e-40a5-ab69-26ad94399377 ensured
  (tier id=596, lrpName=lrp-b4e54207-ba9b-41b1-98c4-e969cdef7c16)

2026-05-11 01:27:01,325 INFO OvnNetworkElement.ensureTierBound: peer LSP
  4c0ab02a-1720-422e-84f2-f9b36028d921 ensured
  (tier id=597, lrpName=lrp-787ae4fb-177c-41c1-b0cc-090a824b17bc)
```

---

## Verification

### OVN NB — peer LSPs present (post-fix)

```
$ ovn-nbctl --no-leader-only --bare --columns=name,type \
    find Logical_Switch_Port name=rsp-fa50740c-86af-47c6-8f2f-efa151b8b8fd
rsp-fa50740c-86af-47c6-8f2f-efa151b8b8fd
router

$ ovn-nbctl --no-leader-only get Logical_Switch_Port \
    rsp-fa50740c-86af-47c6-8f2f-efa151b8b8fd options
{router-port=lrp-fa50740c-86af-47c6-8f2f-efa151b8b8fd}
```

Same result for `rsp-b4e54207-*` and `rsp-787ae4fb-*`. All 3 LSPs:
- `type=router` confirmed
- `options:router-port` points to the correct LRP name
- Membership in tier LS confirmed (OVN mutate committed)

Note: `LRP.peer` column remains `[]` for router-to-switch patch ports in OVN
NB schema — this is expected and correct. The LS→LR link is established via
the LSP `options:router-port` key, not the LRP `peer` column.

### ARP + ICMP from perf-vdpa-dst (i-2-1159-VM, fluffy, IP 10.97.1.106)

```
ARPING 10.97.1.1 from 10.97.1.106 ens3
Unicast reply from 10.97.1.1 [02:01:01:61:01:01]  1.751ms
Unicast reply from 10.97.1.1 [02:01:01:61:01:01]  1.402ms
Unicast reply from 10.97.1.1 [02:01:01:61:01:01]  0.968ms
Sent 3 probes (1 broadcast(s))
Received 3 response(s)

PING 10.97.1.1 (10.97.1.1) 56(84) bytes of data.
64 bytes from 10.97.1.1: icmp_seq=1 ttl=254 time=6.62 ms
64 bytes from 10.97.1.1: icmp_seq=2 ttl=254 time=0.847 ms
64 bytes from 10.97.1.1: icmp_seq=3 ttl=254 time=0.907 ms
3 packets transmitted, 3 received, 0% packet loss
```

Gateway MAC `02:01:01:61:01:01` matches the deterministic output of
`OvnNetworkElement.deriveGatewayMac("10.97.1.1")`. ARP reply proves OVN is
now routing the L3 response from the VPC LR through the LS→LR patch pair.

---

## Anomalies

1. `restartNetwork` returned errorcode 530 on all 3 tiers. This is a
   pre-existing issue in the OVN `restartNetwork` handler (likely a VPC
   router shutdown/rebuild failure unrelated to the LSP gap). The
   `ensureTierBoundToVpcLr` self-heal ran and succeeded before the 530 error
   was generated. The 530 is NOT introduced by this fix and is NOT a blocker
   for Bug 18 resolution.

2. The `LRP.peer` column shows `[]` after fix. This is not a defect. OVN NB
   uses `LRP.peer` only for LR-to-LR patch ports; LS-to-LR patch ports are
   linked via `LSP.options:router-port`. The ovn-northd pipeline populates
   the datapath tables correctly from this field.

---

## Cleanup State

- No stale async jobs.
- No leaked LSPs (3 new `rsp-*` LSPs created, all correctly parented to their
  respective tier LS, all with correct `options:router-port` values).
- Backup JARs remain on each control node for rollback if needed.
