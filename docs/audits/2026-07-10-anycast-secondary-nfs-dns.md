# 2026-07-10 — LAX anycast cutover + secondary NFS via DNS

## Scope

LAX (Slytherin) only. Service anycast VIPs moved off underlay into loopback
planes; CloudStack secondary storage URL renamed from bare IP to DNS.

## Anycast VIP map (live)

| Name | IPv4 | IPv6 | Role |
|---|---|---|---|
| apparate | 217.179.88.20/32 | 2a13:8740::20/128 | DNS / internal LB |
| lumos | 217.179.88.21/32 | 2a13:8740::21/128 | NFS (Ganesha) VIP |
| pensieve | 217.179.88.22/32 | 2a13:8740::22/128 | Ceph RGW VIP |

Identity loopbacks and keepalived VIPs (accio / alohomora / cspod) unchanged.
OpenBao remains name-based via apparate (no dedicated anycast VIP).

## CloudStack secondary storage

| Field | Value |
|---|---|
| Name | Slytherin-nfs-storage |
| UUID | `c54f5853-37c2-4343-a6a7-0ba960178196` |
| URL (final) | `nfs://lumos.slytherin.eonf.ltd/cloudstack-secondary` |
| Prior URL | `nfs://217.179.88.21/cloudstack-secondary` (post-anycast IP) |
| Soft-deleted prior | `nfs://217.179.88.45/cloudstack-secondary` (id=1, readonly) |

### Why not `updateImageStore` / migrate

- `updateImageStore` only supports name / readonly / capacity — **not URL**.
- `migrateSecondaryStorageData` **Complete** must not be used when src and dest
  share one NFS export (2026-07-10 incident: Complete wiped blob inventory on
  the shared export path).
- URL change was a **metadata rename** of the same export (IP via DNS A/AAAA).

### Validation

- Host mount: `mount -t nfs -o vers=4.1 lumos.slytherin.eonf.ltd:/cloudstack-secondary`
- SSVM log: `Determined host lumos.slytherin.eonf.ltd corresponds to IP 217.179.88.21`
- Kernel mount table may show the resolved IP after mount; config SoT is the DNS URL.
- Templates on store id=3: **10 × DOWNLOADED** (systemvm 209/210/211 ready).

### Residual template recovery (same day)

After SSVM reboot against the DNS URL, empty `template.properties` (left by the
earlier migrate recovery) caused agent warnings *“Post download installation
was not completed”* and flipped download state. Properties were regenerated
from on-disk payloads; `template_store_ref` realigned to DOWNLOADED.

Orphan empty path `template/tmpl/2/203/` (template 203 removed since 2026-03-07)
was deleted from the export.

## Operational rules (standing)

1. Secondary storage URL must track **lumos** (name, not underlay IP).
2. Never `migrateSecondaryStorageData` **Complete** when src/dest are the same NFS export.
3. CloudStack state changes via `cmk` where the API exists; image-store URL is an
   exception (no API) — surgical metadata update only, never invent a second store
   on the same path to “rename”.
4. FRR/hiera `vip_nfs` stays the anycast **IP** on lo (routing plane); DNS is the
   client-facing name for the same VIP.

## Related

- Puppet CONTROL_VERSION at cutover close: 0.1.121
- k8s-services: regcred SealedSecret resealed (`d961f685`) after registry oauth2 fix
- Dual legacy underlay VIPs (.45 / .51 / .53) removed from lo / nginx / nft
