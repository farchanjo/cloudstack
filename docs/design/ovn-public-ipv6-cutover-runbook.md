# OVN Public IPv6 LB — Sprint 3 cutover runbook

> ## ✅ COMPLETED — live cutover **2026-07-10**
>
> | Result | Value |
> |---|---|
> | ConfigKey `ovn.lr.public.ipv6.lb` | **empty** |
> | VIP ownership | inventory (`user_public_ipv6_address`) — salazar `::100`, snape `::101` |
> | Smoke HTTP | **301** both VIPs (Host `x.salazar…` / `x.snape…`) |
> | OVN NB | **4** `cs-pub6-lb` rows (80+443 × both clusters) |
>
> Day-2: inventory / `publicipv6id` only. ConfigKey = break-glass restore from
> off-box backup (§8). List live ids: `cmk list publicipv6addresses`.
>
> ---
>
> **Purpose (historical procedure):** Migrate live CKS public IPv6 VIPs
> (`::100` / `::101`) from ConfigKey `ovn.lr.public.ipv6.lb` to inventory +
> `createLoadBalancerRule` (`publicipv6id`), then clear the ConfigKey.
>
> **Design:** [[ovn-public-ipv6-fip-lb-self-service-api]] §5 Sprint 3 / §9.
> **Scope:** LAX ("slytherin") only. **Deploy path:** jar-direct (no `.deb`).
> **Date:** 2026-07-10.

Historical procedure below. Do **not** re-run import/clear unless rolling back
or re-onboarding. Cutover was **operator once**; backends remain
operator-refreshed after CKS recreate (Q7).

---

## 0. Inventory (salazar / snape)

| Field | salazar | snape |
|---|---|---|
| Public VIP v6 | `2a13:8740:0:7::100` | `2a13:8740:0:7::101` |
| Public prefix | `2a13:8740:0:7::/64` | same |
| Public FIP v4 (unchanged) | `217.179.89.37` | `217.179.89.38` |
| Tier network UUID | `a4226ad6-604a-4cd6-883e-777958562fe1` | `d46c5f93-4f6f-47fc-89ad-b4b10fb30f90` |
| Tier name | `salazar-tier` | `snape-tier` |
| Tier v6 (backends) | `2a13:8740:0:a::/64` | `2a13:8740:0:9::/64` |
| LB public ports | `80`, `443` | `80`, `443` |
| Backend ports | hostNetwork `80` / `443` | hostNetwork `80` / `443` |
| Smoke Host header | `x.salazar.slytherin.eonf.ltd` | `x.snape.slytherin.eonf.ltd` |
| Inventory `publicipv6id` (post-cutover) | live: `cmk list publicipv6addresses ip6address=2a13:8740:0:7::100` | live: `…::101` |

Worker guest IPv6 addresses **change on every CKS recreate** — always refresh
from live `kubectl` / `listVirtualMachines` before assigning backends.

Phase-1 ownership tag (unchanged):
`external_ids:cs-pub6-lb=<network-uuid>|<vip>|<port>`.

---

## 1. Prerequisites — jar-direct Sprint 1–3

Cutover requires management code that already has IPAM + dual-read + LB
`publicipv6id`. Deploy **jar-direct on all three** control nodes
(`voldemort` → `bellatrix` → `barty`, one at a time). No `.deb`.

| Gate | Check |
|---|---|
| S1 IPAM | Table `user_public_ipv6_address` present; `listPublicIpv6Addresses` / associate / disassociate respond |
| S2 LB API | `createLoadBalancerRule` accepts `publicipv6id` (mutually exclusive with `publicipid`) |
| S2 dual-read | Reconciler desired = ConfigKey ∪ inventory; ConfigKey-only salazar/snape still live |
| S3 import path | Grandfather import available for transport-band VIPs (`importAllocated` / `importPublicIpv6Address` — **not** Free-pool associate) |
| All 3 mgmt | Same jar revision; reconciler still fires `ensurePublicIpv6LbForZone` |

Pre-flight:

```bash
# On a control node (cmk already configured for Slytherin)
cmk list configurations name=ovn.lr.public.ipv6.lb
cmk list publicipv6addresses   # or: listPublicIpv6Addresses — expect empty or Free-only before import
```

Confirm Phase-1 ConfigKey still carries both clusters (stanzas for
`a4226ad6-…` `::100` and `d46c5f93-…` `::101`, ports 80 and 443).

---

## 2. Backup ConfigKey `ovn.lr.public.ipv6.lb`

**Required before any inventory create or ConfigKey clear.** Rollback depends
on this string.

```bash
# Capture full value (multi-stanza; may be long)
cmk list configurations name=ovn.lr.public.ipv6.lb

# Store off-box, e.g.:
#   docs/design/.cutover-backup-ovn.lr.public.ipv6.lb-$(date -u +%Y%m%dT%H%M%SZ).txt
# Keep the exact value string; do not reformat brackets / separators.
export CK_BACKUP='…paste exact value…'
```

Verify backup length and both VIP literals:

```bash
printf '%s' "$CK_BACKUP" | grep -F '2a13:8740:0:7::100'
printf '%s' "$CK_BACKUP" | grep -F '2a13:8740:0:7::101'
```

---

## 3. Import grandfather public IPv6 addresses

`::100` / `::101` sit in the **transport band** (`::0`–`::255`). Free-pool
`associatePublicIpv6Address` **rejects** them. Use the grandfather import path
(`PublicIpv6AddressManager.importAllocated` — operator API name
**`importPublicIpv6Address`** when registered; same semantics).

Import as **Allocated**, system/admin owner (design Q2 default), associated to
the CKS **tier network UUID** so LB rules can bind.

```bash
# salazar ::100 → salazar-tier
cmk import publicipv6address \
  ip6address=2a13:8740:0:7::100 \
  networkid=a4226ad6-604a-4cd6-883e-777958562fe1
# record publicipv6id → SALAZAR_PUB6_ID

# snape ::101 → snape-tier
cmk import publicipv6address \
  ip6address=2a13:8740:0:7::101 \
  networkid=d46c5f93-4f6f-47fc-89ad-b4b10fb30f90
# record publicipv6id → SNAPE_PUB6_ID
```

> **cmk surface note:** exact verb spelling follows the live API
> (`importPublicIpv6Address`). If the thin CLI wrapper is not registered yet,
> call the API command by name with the same parameters, or invoke the
> manager import on a control node under change control — never insert rows
> with raw SQL.

Verify:

```bash
cmk list publicipv6addresses ip6address=2a13:8740:0:7::100
cmk list publicipv6addresses ip6address=2a13:8740:0:7::101
# state=Allocated; networkid matches table above; NOT Free
```

---

## 4. Create LoadBalancer rules (`publicipv6id`, ports 80 / 443)

For **each** cluster, create **two** rules (HTTP + HTTPS). Use
**`publicipv6id`** only — never `publicipid` for this path. Protocol/algorithm
match the Phase-1 intent (TCP, round-robin / existing v4 LB style on the same
Istio hostNetwork path).

```bash
# --- salazar :80 ---
cmk create loadbalancerrule \
  name=cks-salazar-pub6-80 \
  publicipv6id=$SALAZAR_PUB6_ID \
  publicport=80 privateport=80 \
  algorithm=roundrobin protocol=tcp \
  networkid=a4226ad6-604a-4cd6-883e-777958562fe1
# → SALAZAR_LBR_80

# --- salazar :443 ---
cmk create loadbalancerrule \
  name=cks-salazar-pub6-443 \
  publicipv6id=$SALAZAR_PUB6_ID \
  publicport=443 privateport=443 \
  algorithm=roundrobin protocol=tcp \
  networkid=a4226ad6-604a-4cd6-883e-777958562fe1
# → SALAZAR_LBR_443

# --- snape :80 ---
cmk create loadbalancerrule \
  name=cks-snape-pub6-80 \
  publicipv6id=$SNAPE_PUB6_ID \
  publicport=80 privateport=80 \
  algorithm=roundrobin protocol=tcp \
  networkid=d46c5f93-4f6f-47fc-89ad-b4b10fb30f90
# → SNAPE_LBR_80

# --- snape :443 ---
cmk create loadbalancerrule \
  name=cks-snape-pub6-443 \
  publicipv6id=$SNAPE_PUB6_ID \
  publicport=443 privateport=443 \
  algorithm=roundrobin protocol=tcp \
  networkid=d46c5f93-4f6f-47fc-89ad-b4b10fb30f90
# → SNAPE_LBR_443
```

Do **not** clear the ConfigKey yet. Dual-read prefers inventory on VIP:port
conflict (design Q5); identical backends → reconciler plan size ≈ 0.

---

## 5. Assign workers to LoadBalancer rules

Resolve **live** worker instance IDs (and guest IPv6 if `vmidipmap` is
required). Prefer current worker set only — not control-plane nodes.

```bash
# Example — fill VM UUIDs from listVirtualMachines / kubectl mapping
# salazar workers → both rules
cmk assign to loadbalancerrule id=$SALAZAR_LBR_80  virtualmachineids=$W1,$W2,$W3
cmk assign to loadbalancerrule id=$SALAZAR_LBR_443 virtualmachineids=$W1,$W2,$W3

# snape workers → both rules
cmk assign to loadbalancerrule id=$SNAPE_LBR_80  virtualmachineids=$W1,$W2,$W3
cmk assign to loadbalancerrule id=$SNAPE_LBR_443 virtualmachineids=$W1,$W2,$W3
```

Backend set **must match** the live ConfigKey stanzas (same worker v6
addresses and ports). Diff backends before cutover:

1. Parse ConfigKey backends for each VIP:port.
2. Compare to LB rule destinations after assign.
3. Fix assign until equal — dual-read conflict prefer-inventory would otherwise
   change dataplane before ConfigKey clear.

---

## 6. Verify dual-read, then clear ConfigKey

### 6.1 Dual-read window (ConfigKey still set)

| Check | Expect |
|---|---|
| `cmk list configurations name=ovn.lr.public.ipv6.lb` | Unchanged backup value |
| OVN NB `cs-pub6-lb` rows for `::100` / `::101` :80/:443 | Present; VIP/backends match |
| Reconciler plan / logs for pub6 | Idempotent — no thrash; plan size ≈ 0 |
| BGP `/128` for both VIPs | Still announced on gateway-chassis |
| HTTP smoke (below) | 301 on both VIPs |

Hold here if plan is non-zero or smoke fails — **do not clear ConfigKey**.

### 6.2 Clear ConfigKey

```bash
# Empty value disables Phase-1 ConfigKey desired-set contribution
cmk update configuration name=ovn.lr.public.ipv6.lb value=
```

### 6.3 Post-clear verify

| Check | Expect |
|---|---|
| ConfigKey value | empty |
| OVN LB rows for both VIP:ports | **Still present** (inventory path only) |
| Reconciler plan | still ≈ 0 (no withdraw/recreate storm) |
| BGP `/128` | still present for `::100` and `::101` |
| Smoke curl | still 301 |

If rows disappear or smoke fails → **§8 Rollback** immediately.

---

## 7. Smoke — `curl` with Host headers

From a fabric host with public IPv6 egress (e.g. aragog):

```bash
# salazar public v6
curl -g -sk -o /dev/null -w '%{http_code}\n' \
  -H 'Host: x.salazar.slytherin.eonf.ltd' \
  --connect-timeout 3 'http://[2a13:8740:0:7::100]/'
# expect 301 (Istio httpsRedirect)

curl -g -sk -o /dev/null -w '%{http_code}\n' \
  -H 'Host: x.salazar.slytherin.eonf.ltd' \
  --connect-timeout 3 'https://[2a13:8740:0:7::100]/'

# snape public v6
curl -g -sk -o /dev/null -w '%{http_code}\n' \
  -H 'Host: x.snape.slytherin.eonf.ltd' \
  --connect-timeout 3 'http://[2a13:8740:0:7::101]/'
# expect 301

curl -g -sk -o /dev/null -w '%{http_code}\n' \
  -H 'Host: x.snape.slytherin.eonf.ltd' \
  --connect-timeout 3 'https://[2a13:8740:0:7::101]/'
```

Optional regression: public **IPv4** FIPs still answer (inventory cutover is
v6-only):

```bash
curl -sk -o /dev/null -w '%{http_code}\n' -H 'Host: x.salazar.slytherin.eonf.ltd' http://217.179.89.37/
curl -sk -o /dev/null -w '%{http_code}\n' -H 'Host: x.snape.slytherin.eonf.ltd'   http://217.179.89.38/
```

---

## 8. Rollback — restore ConfigKey from backup

Use when inventory LB path is wrong, OVN rows drop after clear, or smoke fails.

```bash
# Restore exact backup string (prefer leave inventory rows in place —
# dual-read unions ConfigKey ∪ API until inventory is deliberately removed)
cmk update configuration name=ovn.lr.public.ipv6.lb value="$CK_BACKUP"
```

Post-rollback:

1. Re-check ConfigKey value matches backup.
2. Re-run OVN + BGP + smoke (§6.1 / §7).
3. Leave inventory + LB rules unless they are the failure cause; only delete
   inventory LB / public v6 rows after ConfigKey is healthy and dual-read is
   stable again.
4. Do **not** empty inventory and ConfigKey at the same time.

---

## 9. Post-cutover ops notes

| Topic | After cutover |
|---|---|
| VIP identity | Inventory (`user_public_ipv6_address` + LB rules) |
| ConfigKey | Empty default; **break-glass only** (one release window — design Q9) |
| Backend refresh after CKS recreate | Still operator: re-assign workers / update destinations — **not** ConfigKey stanza edits for VIP identity |
| CKS docs | Preferred path = inventory API; ConfigKey = break-glass (`cks-salazar` / `cks-snape` `docs/ovn-public-ipv6-lb.md`) |
| Private ECMP | Unchanged (`ovn.lr.ecmp.static.routes`) — out of scope |

---

## 10. Exit criteria (Sprint 3)

- [x] ConfigKey `ovn.lr.public.ipv6.lb` empty (or unused). — **done 2026-07-10**
- [x] salazar `::100` and snape `::101` still live (OVN LB + BGP `/128`).
- [x] Reconciler plan size ≈ 0 before **and** after clear.
- [x] Smoke 301 (or expected TLS) on both VIPs with correct Host headers.
- [x] Rollback path verified on paper (backup file retained).
- [x] CKS ops docs updated: inventory preferred; ConfigKey break-glass.

---

## Related

- Design: `docs/design/ovn-public-ipv6-fip-lb-self-service-api.md`
- CKS ops: `infra/cks-salazar` / `infra/cks-snape` → `docs/ovn-public-ipv6-lb.md`
- Sibling OVN deploy discipline: `docs/design/ovn-complete-networking.md`

*End of cutover runbook.*
