# VF ownership safety rollout

VF ownership repair is intentionally disabled by default. An agent-first
rollout alone is unsafe: an old management server can free database ownership
before a new agent treats its broad purge command as an empty-target no-op.

## Dynamic operational gates (no management restart)

All VF operational `ConfigKey`s are **dynamic** (`isDynamic=true`):

| Key | Default | Role |
|-----|---------|------|
| `vf.legacy.broad.operations.enabled` | `false` | Emergency legacy broad release/recovery gate |
| `vf.ownership.repair.plan.enabled` | `false` | Build and log exact non-mutating plan |
| `vf.ownership.repair.apply.enabled` | `false` | Allow apply of exact separately approved plan |
| `vf.ownership.repair.approved.count` | `0` | Exact approved candidate count |
| `vf.ownership.repair.approved.ids` | empty | Sorted comma-separated approved candidate ids |
| `vf.ownership.repair.approved.hash` | empty | Exact SHA-256 of approved plan |
| `vf.ownership.repair.approval.token` | empty | Exact token emitted with approved plan |
| `vf.ownership.repair.incident.id` | empty | Exact one-time internal incident plan ID |

- **No management restart is required** when flipping plan / approval / apply
  inputs. The leader singleton re-reads each key via `ConfigKey.value()` on
  every sweep (`SweepOrphansTask`, period 15 minutes, under
  `GlobalLock("vf.pool.reconcile")`).
- Defaults remain fail-closed (`false` / empty / `0`).
- `VfPoolManagerImpl` must not cache approval or apply values outside
  `ConfigKey`; gate helpers always call `.value()`.
- After `updateConfiguration` / `cmk update configuration`, depot cache
  invalidation plus dynamic keys means the **next leader sweep** observes the
  new value (cluster-wide depot cache TTL is at most ~30s if a node missed the
  invalidation event).

### Observing and auditing transitions

On each planning sweep the leader logs (WARN):

```
VF ownership repair plan hash=… token=… candidates=… ids=…
ALLOCATED …->… FREE …->… applyEnabled=…
```

Use that line as the audit record for each phase:

1. Record the plan line when `plan.enabled` first becomes true (hash, token,
   candidate ids/count, predicted ALLOCATED/FREE deltas, `applyEnabled=false`).
2. After entering approval settings, confirm the next sweep still logs the same
   hash/ids/token before enabling apply.
3. When `apply.enabled=true` and approval matches, the same sweep either applies
   exact candidates or logs a fail-closed block reason (`plan is not the
   immutable approved … incident scope`, `count/ids/hash/token approval`,
   progress drift, etc.).
4. After convergence, set plan and apply back to `false` and clear approval
   inputs; the next sweep must show planning disabled (no plan WARN) without
   restarting management.

## Phases

1. Deploy the management-side safety/default-off subset first. Keep
   `vf.legacy.broad.operations.enabled=false`,
   `vf.ownership.repair.plan.enabled=false`, and
   `vf.ownership.repair.apply.enabled=false`.
2. Deploy the complete command API and KVM wrapper artifact together to every agent. Do not mix a
   new command model with an old wrapper in one agent package.
3. Deploy the final management ownership lifecycle changes. A new
   management server sends legacy broad flags as false; an old agent ignores
   unknown target fields and returns no per-target result, so management does
   not release a row.
4. Verify agent versions and targeted dry-run inventory on every relevant
   host.
5. Enable only `vf.ownership.repair.plan.enabled` (dynamic; no restart). This is
   a one-time internal repair for incident plan ID
   `vf-ownership-incident-2026-07-16-v1`, not generic operator authorization.
   On the next leader sweep, record the exact candidate IDs, count, SHA-256 plan
   hash, approval token, transitions, and predicted before/after counts from the
   leader log.
6. Reject the plan if it contains any candidate beyond the reviewed set. Enter
   the exact count, sorted IDs, hash, and token in the corresponding approval
   settings (dynamic; no restart). Confirm the next planning sweep still emits
   the same hash/ids/token before continuing.
7. Set the exact incident ID, then enable `vf.ownership.repair.apply.enabled`
   (dynamic; no restart). The singleton task re-creates the plan under
   `GlobalLock("vf.pool.reconcile")`; any difference blocks all apply work.
8. Disable apply and planning after the approved plan converges (dynamic; no
   restart). Keep legacy broad operations disabled.

The reviewed incident plan consists of eight noncanonical stale-row cleanups
and three wrong-host canonical promotions followed by old-row cleanup. Its
expected aggregate transition is `ALLOCATED 27 -> 19` and `FREE 237 -> 245`.
These counts are validation expectations, not authorization: the exact IDs,
count, hash, and token must all match.

Owner tokens are integrity bindings carried over the authenticated management-agent
channel; a token is not a standalone secret and must never be accepted without
matching BDF, expected MAC, operation identity, purpose, and evidence.
