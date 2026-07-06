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

## Hard rules

- **Never edit code in `/root/cloudstack` on aragog.** That path is build+test deploy target only. Every change originates in `~/dev/cloudstack`.
- **Deploy = `git push aragog main`.** The remote repo at `/root/cloudstack` has `receive.denyCurrentBranch=updateInstead` configured — push updates the working tree directly, no manual pull/checkout needed on the remote side.
- Build on aragog uses the Maven cache at `/root/.m2` (preserved, do not delete).
- `git push origin main` is separate — pushes to the GitHub fork. `git push aragog main` is the deploy. Two distinct pushes, different purposes.
- Commits follow Angular Conventional Commits (`<type>(<scope>): <subject>`), small contextual commits — inherited from global instructions.

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
