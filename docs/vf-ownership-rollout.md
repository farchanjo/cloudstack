# VF ownership safety rollout

VF ownership repair is intentionally disabled by default. An agent-first
rollout alone is unsafe: an old management server can free database ownership
before a new agent treats its broad purge command as an empty-target no-op.

Use these phases:

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
5. Enable only `vf.ownership.repair.plan.enabled`. This is a one-time internal
   repair for incident plan ID `vf-ownership-incident-2026-07-16-v1`, not generic
   operator authorization. Record the exact candidate
   IDs, count, SHA-256 plan hash, approval token, transitions, and predicted
   before/after counts from the leader log.
6. Reject the plan if it contains any candidate beyond the reviewed set. Enter
   the exact count, sorted IDs, hash, and token in the corresponding approval
   settings.
7. Set the exact incident ID, then enable `vf.ownership.repair.apply.enabled`. The singleton task re-creates
   the plan under `GlobalLock("vf.pool.reconcile")`; any difference blocks all
   apply work.
8. Disable apply and planning after the approved plan converges. Keep legacy
   broad operations disabled.

The reviewed incident plan consists of eight noncanonical stale-row cleanups
and three wrong-host canonical promotions followed by old-row cleanup. Its
expected aggregate transition is `ALLOCATED 27 -> 19` and `FREE 237 -> 245`.
These counts are validation expectations, not authorization: the exact IDs,
count, hash, and token must all match.

Owner tokens are integrity bindings carried over the authenticated management-agent
channel; a token is not a standalone secret and must never be accepted without
matching BDF, expected MAC, operation identity, purpose, and evidence.
