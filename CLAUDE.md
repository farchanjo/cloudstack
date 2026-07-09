# cloudstack

Dev fork of Apache CloudStack. Workflow: **code only here** (`~/dev/cloudstack`), deploy and test on aragog.

## Topology

```
~/dev/cloudstack/      <- CODE HERE. Only place where source is edited.
        |
        | git push aragog main   (push-to-deploy)
        v
aragog:/root/cloudstack/   <- build + test. NEVER edit code directly here.
```

## Remotes

| Remote | URL | Role |
|---|---|---|
| `origin` | `https://github.com/farchanjo/cloudstack.git` | GitHub fork, source of truth for history |
| `aragog` | `root@aragog.slytherin.eonf.ltd:/root/cloudstack` | deploy target — push directly updates the working tree |

`aragog.slytherin.eonf.ltd` is directly reachable from the Mac (no jump host / MCP needed) — verified, plain `ssh root@aragog.slytherin.eonf.ltd` works.

## Standing operational contract (NON-NEGOTIABLE)

### API / `cmk` only — maximum CloudStack compatibility

Every **state change** in the live Slytherin (LAX) CloudStack cluster goes through the
**CloudStack API**, operated with **`cmk`** on a control node (`voldemort` / `bellatrix` /
`barty`). That is the only supported apply path for networking, VPC, VM lifecycle, offerings,
rules, and plugin-facing config.

- ✅ `cmk list|create|deploy|update|delete|start|stop|restart|…` for all writes.
- ✅ Read-only forensics with `ovn-nbctl` / `ovn-sbctl` / `ovs-vsctl` / SQL `SELECT` on the
  `cloud` DB are fine for verification — they must not be used to “fix” state.
- ❌ Never hand-edit OVN NB/SB to create/fix logical objects that the OVN plugin owns.
- ❌ Never `UPDATE`/`INSERT`/`DELETE` the `cloud` MySQL DB for orchestration state.
- ❌ Never bypass the plugin with ad-hoc NB scripts and treat that as a delivered feature —
  if it cannot be done via API/`cmk`, the **code in this repo** must expose it first.

Reason: API validation, locking, async jobs, `ovn_logical_id_map`, agent commands, and BGP
redistribute only run on the CMS path. Direct plane edits break compatibility and leave
orphans the plugin cannot reconcile.

### Code, deploy, and remotes

- **Code only in** `~/dev/cloudstack` on the Mac. **Never edit** `/root/cloudstack` on aragog.
- **Default deploy to the fleet build host:** `git push aragog main`
  (`receive.denyCurrentBranch=updateInstead` — push updates the working tree).
- **GitHub (`origin`) only when the user explicitly asks** (e.g. “push to my github / origin”).
  Do not `git push origin` on your own initiative.
- After `push aragog`: build on aragog (`mvn …`), then **jar-direct** into
  `/usr/share/cloudstack-{management,agent}/lib/` + service restart (backup + `md5sum`),
  per `infra-base` standing rule — no `.deb` unless the user asks.
- Management jar → all 3 control nodes (one at a time). Agent jar → data nodes that need it.
- Build on aragog uses `/root/.m2` (do not delete).
- Commits: Angular Conventional Commits (`<type>(<scope>): <subject>`), small contextual commits.

### Fleet ops from aragog (NON-NEGOTIABLE — survives compaction)

**aragog is the ops hub.** The Mac is only for editing git; it is **not** a transfer middleman.

- ✅ `ssh_connect` / MCP SSH **to aragog** (public), then from that session: `scp`/`ssh` to
  controls/data for jar-direct, qemu-ga, and forensics.
- ✅ Build jars **on aragog** (`/root/cloudstack/.../target/*.jar`) and copy **aragog → target
  host** (`scp jar root@voldemort:/tmp/` then install). Same for agent hosts.
- ❌ **Never** download large jars (fat client / agent) to the Mac just to re-upload to the
  fleet — wasted bandwidth and time. No `/tmp/cloudstack-*.jar` on the laptop for deploy.
- ❌ Do not treat “no nested SSH” as “pull 140MB to Mac”. Nested hop **from aragog** to
  other LAX hosts is the intended path (data nodes are not always directly reachable from
  the Mac). Prefer **one** MCP session on aragog + lateral ssh/scp from there.
- Guest heal / dual-stack checks: **qemu-guest-agent via `virsh` on the KVM host** (from
  aragog or via aragog→host). Do not require SSH into CKS guests.

### CKS dual-stack IPv6 (standing facts)

- Image ships `/etc/sysctl.conf` with a full “Disable IPv6” block. Drop-in must be
  **`/etc/sysctl.d/zz-cks-dualstack.conf`** (sorts after `99-sysctl.conf`) **and** the
  image block must be rewritten (`disable_ipv6=0`, `forwarding=1`, **`accept_ra=2`**,
  `autoconf=1`) plus **per-iface** `eth0` sysctls.
- Code: `plugins/integrations/kubernetes-service/.../k8s-{node,control-node,control-node-add}.yml`
  (commit `7d6d2eea42`). New nodes get it via cloud-init; existing nodes need a one-shot
  heal (recipe already proven on all 6 salazar guests 2026-07-09).
- OVN tier RA is `address_mode=slaac`. With GUA up, Calico bird6 needs L3 to RR cplane
  `2a13:8740:0:3::{34,35,36}:179` for PARSEL-V6.
- **PARSEL-V6 path (snape 2026-07-09):** guest→OVN→`pub-anchor` works; host kernel routes
  tier `/64` via public LRP (`2a13:8740:0:7::{32,34}`) on `pub-anchor`. If ND to that LRP
  is **INCOMPLETE**, reverse-path / local delivery breaks → guest cannot reach RR (SYN-SENT).
  **Workaround on gateway chassis (aragog):** permanent neigh for public LRP v6 MACs:
  `ip -6 neigh replace 2a13:8740:0:7::32 lladdr 02:02:02:b3:59:20 dev pub-anchor nud permanent`
  (and `::34` / `02:02:02:b3:59:22` for salazar). After that, snape bird6 **ESTAB** and RR
  shows 6 dynamic PARSEL-V6 neighbors. **Durable fix:** agent BGP announce should install
  these neigh entries (or an onlink/OVN-safe route) when programming tier v6 `network` routes.

### Agent autonomy

The agent may: edit and commit in `~/dev/cloudstack`, `git push aragog main`, build **on
aragog**, jar-direct **from aragog to fleet**, and verify with `cmk` + read-only forensics.
The agent must **not** push to GitHub until the user says so in the current turn.

## Build + test on aragog (after push)

```bash
ssh root@aragog.slytherin.eonf.ltd
cd /root/cloudstack
git log -1 --oneline   # confirm the push landed
mvn -P developer,systemvm -pl <module> -am clean install -DskipTests   # scoped build
```

aragog runs an active `cloudstack-agent` 4.24.1 (systemd) + real KVM hypervisor + Ceph RBD backend
(`slytherin.cloudstack.primary`) — it's a production hypervisor in the LAX fleet, not an isolated
sandbox. Testing changes here can affect running VMs. See `/Users/farchanjo/dev/infra-base/CLAUDE.md`
for fleet context (NYC/LAX, SSH hard rules, hammer).

## Relevant history

- `a53021c1e8` `ca(openbao): add legacy-trust ConfigKey for Path B migration`
- `d1ce143113` `ca: add OpenBao/Vault CA provider plugin (openbao-ca)`
- `5bfc3c8705` `docs(audits): append Bug 27 investigation findings (INCONCLUSIVE — guest-side suspected)`

New plugin under development: `plugins/ca/openbao-ca/` — custom CA provider integrating with OpenBao/Vault.
