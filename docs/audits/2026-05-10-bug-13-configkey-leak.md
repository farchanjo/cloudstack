# 2026-05-10 — Bug 13 ConfigKey leak (`requested-chassis="norbert=42"`)

**Scope.** Forensic + remediation pass on a single OVN integration bug
discovered during Test A smoke prep on the Slytherin cluster: every newly
bound LSP carried `options:requested-chassis="norbert=42"` even though VMs
were scheduled to other hosts.

**Trigger.** During Test A smoke matrix prep (3 sentinel VMs allocated public
IPs `217.179.89.{36,37,38}`, ping should have flowed), all 3 sentinel LSPs in
OVN NB carried `options:{requested-chassis="norbert=42"}` despite ConfigKey
`ovn.requested_chassis` defaulting empty and `ovn.ha_chassis_priority`
defaulting `0`. VMs ran on scabbers/aragog/fluffy, not norbert. Result: OVN
port-binding never completed (`up=false`), no flows installed, all sentinels
unreachable via SSH on their public IPs.

**Production cluster.** Slytherin (Los Angeles), 3 controls
(voldemort/bellatrix/barty), 6 data nodes.

## Build evidence

- Production JAR md5 at time of forensic: `b878c25a5f356f9ed6d7f232a8a10035`
  (Bug 12 fix landed 2026-05-10, deployed to all 3 controls).
- **Bug 13 fix is config-only — NO source change, NO rebuild, NO new JAR.**
- Post-fix verification used the exact same JAR md5 — the fix was a `cmk
  update configuration` revert.

## Bug catalog

### Bug 13 — ConfigKey leak (`ovn.requested_chassis` left set + never reverted) — `FIXED`

**Symptom.** All 3 sentinel LSPs in OVN NB carried
`options:requested-chassis="norbert=42"`. VMs scheduled on other hosts. OVN
port-binding never completed. ICMP/SSH unreachable on public IPs.

**Hypotheses ranked at start of forensic.**

1. ConfigKey leak from prior verification audit (set + never reverted).
2. Stale row in `network_offering_details` / `network_details` /
   `user_vm_details` carrying `requested-chassis` override.
3. Code bug in `OvnNetworkElement.applyLspOptions` defaulting to first
   cluster member or stale field reference.
4. Hardcoded debug literal in a recent commit.

**Forensic outcome.** Hypothesis 1 confirmed with HIGH confidence.

**Root cause chain.**

1. Phase B verification audit (2026-05-06 23:17:41 UTC) set ConfigKeys
   `ovn.requested_chassis="norbert"` and `ovn.ha_chassis_priority="42"` at
   ZONE scope to test placement-pinning behaviour.
2. Phase B Step 11 narrative claimed "ConfigKey defaults restored" — this
   was prose, not a verified `cmk` revert. The revert never executed.
3. `cloud.configuration` rows survived the Bug 12 deploy (2026-05-10) because
   `dpkg -i` + service restart does not touch the configuration table.
4. `OvnNetworkElement.applyLspOptions` (lines 902–914) reads ConfigKey
   fallback when per-VM/network/offering details are null. Snippet:
   ```java
   final String chassis = OvnNicTunables.resolve(
       OvnNicTunables.OVN_REQUESTED_CHASSIS,
       vmDetails, null, null,
       OvnNicConfig.RequestedChassis.value(), String.class);
   final int haPriority = ...;
   opts.put("requested-chassis", chassis + "=" + haPriority);
   // → "norbert=42"
   ```
5. Every newly-bound LSP from 2026-05-06 23:17 onward got
   `options:requested-chassis="norbert=42"`. OVN refused to bind because the
   target chassis hostname did not match any registered HV chassis (CloudStack
   uses host UUIDs in OVN SB, not display names).

**Evidence.**

- `cloud.configuration` SELECT (read-only):
  ```
  scope_name  name                     value     updated
  ZONE        ovn.requested_chassis    norbert   2026-05-06 23:17:41
  ZONE        ovn.ha_chassis_priority  42        2026-05-06 23:17:41
  ```
- `cmk list configurations name=ovn.requested_chassis` API output mirrored
  the DB row (value `"norbert"`).
- `network_offering_details`, `network_details`, `user_vm_details`,
  `nic_details` — ALL returned 0 rows matching `%chassis%`, `%norbert%`, or
  `value=42`. No per-VM/per-network override existed.
- `grep -rn '"norbert"' plugins/ server/` returned 0 hits. No hardcoded
  literal.
- `git log -S "norbert" --since="60 days ago" -- plugins/ server/` returned
  0 commits. The token was never in source.
- `git log -S "requested-chassis" --since="60 days ago" -- plugins/ server/`
  showed only `22a4715f0f` (2026-05-09) which introduced
  `applyLspOptions()` — straightforward ConfigKey read with no hardcoded
  override.

**Side-finding.** Sentinel LSPs (`lsp-384a5dd8`, `lsp-28146bf8`,
`lsp-cda48396`) were absent from OVN NB at forensic time — NB had been
wiped. Subsequent inventory cross-check found 40 VMs Running (two duplicate
generations 1094–1113 and 1115–1134) with 0 LSPs in NB and 3 floating IPs
already cross-VPC bound to a different VPC `a1992656`. Drift required full
rebuild rather than targeted fix.

**Fix.** Two `cmk` calls (no source change):

```
cmk update configuration name=ovn.requested_chassis value=
cmk update configuration name=ovn.ha_chassis_priority value=0
```

Verified post-revert:

```
$ cmk list configurations name=ovn.requested_chassis | jq -r '.configuration[0].value'

$ cmk list configurations name=ovn.ha_chassis_priority | jq -r '.configuration[0].value'
0
```

(empty string + `"0"` — fallback inactive).

**Production verification (Stage 2 rebuild 2026-05-10).**

- Cleanup phase: 40 VMs expunged, both VPCs (`6f263df1` deleted; `a1992656`
  retained — empty, fresh from earlier session); 3 tiers torn down with VPC
  delete; ConfigKeys reverted as above.
- Rebuild phase: VPC `a1992656-2a76-43a5-82cc-3c9b7f5402ca` reused (same name,
  same offering, same CIDR — clean state, no residue); 3 tiers created
  (`fa50740c-...` `tap-vdpa`, `b4e54207-...` `tap-vf`, `787ae4fb-...`
  `tap-tap`); 20 VMs deployed (instance IDs `i-2-1135..1154`); all 20 land
  Running.
- OVN NB post-rebuild:
  - 4 logical switches (3 tiers + `ls-public-z4`).
  - 1 logical router (`lr-a1992656`).
  - 66 LSPs total (20 VM LSPs + router-facing + system).
  - **0 LSPs carry `options:requested-chassis`** — `ovn-nbctl list
    logical_switch_port | grep -i 'requested-chassis'` returns empty.
- Sentinel static-NAT bindings (3 calls returned `"success": true`):
  - `217.179.89.36 ↔ 10.97.1.87` (test20-vdpa-1)
  - `217.179.89.37 ↔ 10.97.2.229` (test20-vf-1)
  - `217.179.89.38 ↔ 10.97.3.103` (test20-tap-1)
- OVN NAT rows: 1 SNAT (pre-existing) + 3 dnat_and_snat (new) = 4 total.

**Severity.** HIGH — silently broke OVN port-binding for every new VM in the
zone for ~4 days. No log line at WARN/ERROR pointed at the ConfigKey as
source; only `requested-chassis=...` showed up in the LSP options dump,
which was visible only on direct OVN NB inspection.

**Fix commit.** None — config-only revert. JAR md5 unchanged from
`b878c25a5f356f9ed6d7f232a8a10035`.

**Status.** FIXED — config revert applied 2026-05-10, verified via Stage 2
rebuild (20 fresh VMs, 0 LSPs with `requested-chassis` contamination,
sentinel bindings landed correctly).

## Lessons

- **Verification must be evidence-based, not narrative.** HANDOFF Phase B
  Step 11 said "ConfigKey defaults restored" without a captured `cmk list
  configurations` output. The revert never ran. Always paste the post-revert
  API output into the handoff doc.
- **ZONE-scope ConfigKeys persist across deploys.** `dpkg -i` +
  `cloudstack-management restart` does not reset `cloud.configuration`. The
  only path is explicit `cmk update configuration value=<empty>`.
- **OVN ConfigKey side-effects are silent.** Setting
  `ovn.requested_chassis` without realising every new LSP picks it up means
  long-tailed contamination. Future ConfigKey verification work MUST always
  end with the revert + the proof.
- **Phase audits MUST capture before/after states.** A future Phase X audit
  that touches any `ovn.*` ConfigKey is required to:
  1. Capture pre-state (`cmk list configurations name=<key>` output).
  2. Apply mutation.
  3. Run intended verification.
  4. Apply revert.
  5. Capture post-revert state (`cmk list configurations name=<key>` output).
  6. Paste both pre + post API outputs into the audit log.

## Skip list for future audits

A future "find all the bugs" dispatch on the OVN fork SHOULD NOT re-flag any
of the items above without first reading this file and the
`2026-05-09-ovn-fork-audit.md` and confirming whether the relevant code
surface has actually drifted. Specifically:

- The `ovn.requested_chassis` + `ovn.ha_chassis_priority` ConfigKey leak is
  `FIXED` (no fix commit — config revert only, value `''` and `'0'`
  confirmed via `cmk list configurations` 2026-05-10). Do NOT re-flag the
  same `requested-chassis` contamination unless `cmk list configurations`
  shows non-empty values for either key.
- `OvnNetworkElement.applyLspOptions` correctly reads ConfigKey fallback —
  no code-path bug. The line 902–914 implementation is the intended
  behaviour. Do NOT propose a code change here unless ConfigKey semantics
  themselves are being re-designed.
- OVN NB cleanliness (0 LSPs with `requested-chassis` option) was the
  acceptance gate. Future audits MUST run
  `ovn-nbctl list logical_switch_port | grep -i requested-chassis` and
  confirm empty before re-opening this bug.

## Open / deferred items

(None opened by this audit. Bug 10 from `2026-05-09-ovn-fork-audit.md`
remains the only OPEN entry across the audit log.)

## Operational concerns flagged during forensic (NOT bugs in the OVN code path)

These were observed during forensic but are not Bug 13 root-cause:

1. **`ovn-northd` crash loop.** 28 segfault crashes observed on voldemort.
   NB DB is consistent but compile pipeline unstable. Auto-restart works.
   Filed as op concern in `~/dev/dc/HANDOFF-2026-05-10.md`. Out of scope
   for this audit.
2. **Stuck `ovn-nbctl` pid 3373119 (since 2026-05-08).** Holding the
   `ptcp:6641` listener; TCP 6641 returns EOF until killed. Workaround:
   use `unix:/var/run/ovn/ovnnb_db.sock`. Out of scope for this audit.
3. **3 floating IPs `.36/.37/.38` in VPC `a1992656`.** Were leftover from
   prior session, allocated but unbound. Reused as sentinels in Stage 2
   rebuild — saved 3 fresh allocations. Documented in HANDOFF.
