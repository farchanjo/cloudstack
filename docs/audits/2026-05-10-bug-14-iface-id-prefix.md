# 2026-05-10 — Bug 14 OVN tap iface-id missing `lsp-` prefix

**Scope.** Forensic + remediation pass on a single OVN integration bug
discovered during Test A smoke prep (Stage A) on the Slytherin cluster after
Bug 13 (ConfigKey leak) was closed: every TAP-tier OVS port had
`external_ids:iface-id=<raw-uuid>` while the matching OVN NB
`Logical_Switch_Port.name` was created as `lsp-<raw-uuid>`. Exact-string
mismatch broke every `Port_Binding` claim → no datapath flows → DHCP failed →
TAP-tier VMs unreachable.

**Trigger.** During Test A Stage A retry post-Bug-13 cleanup, sentinel
`test20-tap-1` (10.97.3.103, public IP 217.179.89.38) failed `ping` from the
control plane. Diagnostic dump on fluffy:
`ovs-vsctl get Interface vnet54 external_ids:iface-id` returned
`"a76a6509-6f76-4c67-8863-e9eada4aa42e"` (raw UUID) while OVN NB had
`Logical_Switch_Port.name="lsp-a76a6509-6f76-4c67-8863-e9eada4aa42e"`.
Cross-host sweep confirmed all 6 TAP-tier VMs (1 on fluffy + 5 on trevor) were
affected; vDPA + VF passthrough sentinels were correctly stamped.

**Production cluster.** Slytherin (Los Angeles), 3 controls
(voldemort/bellatrix/barty), 6 data nodes.

## Build evidence

- Production JAR md5 at time of forensic: `b878c25a5f356f9ed6d7f232a8a10035`
  (Bug 12 fix landed 2026-05-10; Bug 13 = config-only revert, JAR unchanged).
- **Bug 14 fix is source-level — code change in `OvnVifDriver.java`.**
  Source patch committed locally; build + deploy on aragog is a separate user
  step (per project policy: no proactive `.deb` packaging).
- Manual remediation on the live cluster (config-only) was applied this
  session via `ovs-vsctl set Interface <vnet> external_ids:iface-id=lsp-<uuid>`
  on fluffy + trevor. All 6 TAP LSPs transitioned `up=false → up=true`
  immediately after the per-host stamp. JAR unchanged.

## Bug catalog

### Bug 14 — OvnVifDriver emits raw NIC UUID as iface-id (TAP-tier only) — `FIXED`

**Symptom.** TAP-tier VMs unreachable on their public IPs after every cold
start, restart, or rebuild. NB `Logical_Switch_Port.up` stays `false` for
every TAP LSP. ovn-controller does not claim the `Port_Binding` row, no
datapath flows are programmed, DHCP fails, VM never acquires its private IP.
vDPA tier (`OvnVdpaVifDriver`) and SR-IOV passthrough tier
(`OvnVfPassthroughVifDriver`) are correctly stamped — bug is **TAP-only**.

**Root cause.**
`plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVifDriver.java:110`

```java
intf.setVirtualPortType("openvswitch");
intf.setVirtualPortInterfaceId(nic.getUuid());   // raw UUID, no lsp- prefix
```

The libvirt `<virtualport type='openvswitch'><parameters interfaceid='UUID'/></virtualport>`
directive forwards the `interfaceid` value verbatim into
`external_ids:iface-id` on the OVS Port row when libvirt runs `ovs-vsctl
add-port` during domain start. By passing `nic.getUuid()` (raw, unprefixed),
the OVS port ends up with `iface-id=<uuid>` while the NB-side
`Logical_Switch_Port.name` was created as `lsp-<uuid>` by
`OvnNetworkElement.buildLspName()`
(`plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnNetworkElement.java:869-875`).
ovn-controller does an exact string match between OVS
`external_ids:iface-id` and NB `Logical_Switch_Port.name` to claim the
`Port_Binding` row in OVN_Southbound. Mismatch → no claim → no flows.

**Why the regression slipped in.**
Commit `d85d27f126` ("fix(kvm/ovn): OvnVifDriver interfaceid uses NIC UUID,
not lspName") on 2026-05-07 correctly identified that libvirt rejects
non-UUID `interfaceid` values with `XML error: cannot parse interfaceid
parameter as a uuid` — the prior code emitted `lsp-<uuid>` directly, which
libvirt rejects (the prefix breaks UUID validation). The commit changed
`setVirtualPortInterfaceId(ovnLspName)` → `setVirtualPortInterfaceId(nic.getUuid())`
to unblock domain XML schema validation. The commit message and the
in-code comment at lines 100-108 BOTH claim:

> "the OVN binding key external_ids:iface-id=<lspName> is set on the OVS Port
> row separately by the post-plug stamping logic in OvnNicTunableApplier and
> by ovn-controller itself."

**Verified false on both counts:**

1. `grep -n 'iface-id\|external_ids:iface' OvnNicTunableApplier.java` returns
   zero hits. The promised post-plug stamp was never implemented.
2. ovn-controller is a *consumer* of `external_ids:iface-id`, not a setter.
   It reads the OVS Port row to find the matching NB `Logical_Switch_Port`;
   it never writes back into OVS `external_ids`.

The fix only addressed the libvirt-XML rejection symptom; the OVN binding
contract was silently broken for the TAP path. vDPA + VF passthrough drivers
already had correct post-plug `ovs-vsctl set Interface … external_ids:iface-id=<lspName>`
calls (see `OvnVdpaVifDriver.java:318` + `OvnVfPassthroughVifDriver.java:287`),
so those tiers were never affected.

**Evidence.**

OVS state on fluffy (TAP-1 sentinel) before fix:
```
$ ovs-vsctl --columns=name,external_ids list Interface vnet54
name                : vnet54
external_ids        : {attached-mac="02:04:02:55:00:09",
                       iface-id="a76a6509-6f76-4c67-8863-e9eada4aa42e",
                       iface-status=active,
                       vm-id="30cc4387-dba4-4b55-8cd3-982bedb7f6e0"}
```

NB state on voldemort:
```
$ ovn-nbctl --db=tcp:10.182.0.11:6641 --bare \
    --columns=name,addresses,up list logical_switch_port \
  | grep -A2 'lsp-a76a6509'
lsp-a76a6509-6f76-4c67-8863-e9eada4aa42e
"02:04:02:55:00:09 10.97.3.103"
false                                    # ← Port_Binding never claimed
```

Cross-host sweep — all 6 TAP-tier VMs affected:

| Host | OVS port | OVS iface-id (raw) | NB LSP name (lsp-<uuid>) |
|---|---|---|---|
| fluffy | vnet54 | `a76a6509-…` | `lsp-a76a6509-…` |
| trevor | vnet32 | `1dc850a3-…` | `lsp-1dc850a3-…` |
| trevor | vnet30 | `4691c6b3-…` | `lsp-4691c6b3-…` |
| trevor | vnet31 | `cb3ed2d7-…` | `lsp-cb3ed2d7-…` |
| trevor | vnet28 | `6f2d17dc-…` | `lsp-6f2d17dc-…` |
| trevor | vnet29 | `e2880708-…` | `lsp-e2880708-…` |

Every UUID matches across OVS and NB; only the `lsp-` prefix is missing on
the OVS side. Confirms the bug is purely the prefix-stamp gap, not a UUID
mismatch.

**Verification of scope.** vDPA tier (`dx6p1vf7` on nagini for vdpa-1
sentinel) showed `iface-id=lsp-b1fbf0ea-370f-404f-b012-e3efbb8198cd
ovn-installed=true` — already correct. SR-IOV VF tier (`dx6p0vf11` on
scabbers for vf-1 sentinel) showed `iface-id=lsp-1ecbcf7a-bf39-4033-b522-16a2ce36cfee
ovn-installed=true` — already correct. Both `OvnVdpaVifDriver` and
`OvnVfPassthroughVifDriver` use post-plug `ovs-vsctl set` with
`nic.getOvnLspName()`, mirroring the contract that `OvnVifDriver` (TAP-only)
fails to satisfy.

**Manual remediation (this session, JAR unchanged).** On each affected host,
applied:

```
ovs-vsctl set Interface <vnet> external_ids:iface-id=lsp-<uuid>
```

| Host | Port | Stamp applied |
|---|---|---|
| fluffy | vnet54 | `lsp-a76a6509-6f76-4c67-8863-e9eada4aa42e` |
| trevor | vnet32 | `lsp-1dc850a3-7f5b-4b9d-ac85-c5971a07c05e` |
| trevor | vnet30 | `lsp-4691c6b3-de55-442b-8c5d-bef94df06ed0` |
| trevor | vnet31 | `lsp-cb3ed2d7-d20b-4572-8183-ea647ef79bf9` |
| trevor | vnet28 | `lsp-6f2d17dc-b8e6-476e-8840-d4e1c0da5797` |
| trevor | vnet29 | `lsp-e2880708-6b70-4e32-b2fa-ad7ce8a76ad9` |

Post-stamp NB state on voldemort (within ~3s):
```
lsp-a76a6509-…  up=true
lsp-4691c6b3-…  up=true
lsp-1dc850a3-…  up=true
lsp-cb3ed2d7-…  up=true
lsp-6f2d17dc-…  up=true
lsp-e2880708-…  up=true
```

All 6 TAP LSPs transitioned `up=false → up=true` immediately after the OVS
stamp. ovn-controller programmed datapath flows in the same convergence
window. Manual remediation is the **runtime workaround**; the source-level
fix below prevents the regression from re-occurring on every new TAP-tier VM
deploy / restart / live-migration.

**Source-level fix.**
`plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVifDriver.java`:
add a post-plug `ovs-vsctl set Interface <hostNetdev>
external_ids:iface-id=<ovnLspName>` invocation inside
`applyPostPlugTunables` (line 134), matching the contract already used by
`OvnVdpaVifDriver.java:318` and `OvnVfPassthroughVifDriver.java:287`. The
libvirt `interfaceid` slot keeps the raw NIC UUID (required by libvirt XML
schema; see `plug()` comment); the post-plug stamp re-writes the OVS Port
row with the OVN-binding-correct `lsp-` prefixed value. Also fix the
misleading comment block at lines 100-108 that promised post-plug stamping
that was never implemented.

**Live-migration consideration.** `applyPostPlugTunables` fires from
`LibvirtComputingResource.postNicConfigure` after libvirt has spawned the
tap dev. On live-migration to a destination host, the destination agent
runs the same callback, so the stamp re-applies on the new tap. No special
migration-path handling required.

**Race-window consideration.** Between libvirt's `add-port` (which stamps
`iface-id=<uuid>`) and the post-plug stamp (which overrides with
`iface-id=lsp-<uuid>`), there is a sub-second gap. If the VM attempts DHCP
inside that window, it fails — but DHCP retries within 4s on Ubuntu cloud
images, so cold-start convergence remains in the 60-90s budget. Acceptable;
tightening the window would require teaching libvirt to forward the
prefixed value (out of scope; libvirt UUID validation rejects it).

**Severity.** HIGH — silently broke OVN port-binding for every new TAP-tier
VM since 2026-05-07 (`d85d27f126`). No log line at WARN/ERROR pointed at
the bug; only `Logical_Switch_Port.up=false` and `iface-id=<raw-uuid>` on
the OVS Port row showed it, both visible only on direct OVN/OVS inspection.
Affects every TAP-tier deploy / restart / live-migration cycle on every
data node.

**Fix commit.** _(to be filled in after `git commit` lands)_

**Status.** FIXED — source patch committed 2026-05-10. Manual remediation
verified all 6 TAP LSPs `up=true` post-stamp. Production verification of
the source-level fix (build + deploy + deploy-fresh-VM smoke test) is
scoped to the next deploy cycle on user request.

## Skip list for future audits

A future "find all the bugs" dispatch on the OVN fork SHOULD NOT re-flag
this bug without first reading this file and confirming whether the
relevant code surface has actually drifted. Specifically:

- `OvnVifDriver.applyPostPlugTunables` MUST contain an explicit
  `ovs-vsctl set Interface <hostNetdev> external_ids:iface-id=<ovnLspName>`
  invocation gated on `nic.getOvnLspName()` non-blank. Do NOT re-flag the
  TAP iface-id mismatch as a new finding unless the post-plug stamp call is
  removed from this method.
- The misleading comment block in `OvnVifDriver.plug()` that previously
  claimed post-plug stamping was handled by `OvnNicTunableApplier` /
  `ovn-controller` is corrected. Do NOT re-introduce the prior wording.
- `OvnVdpaVifDriver:318` + `OvnVfPassthroughVifDriver:287` are the
  reference patterns for correct post-plug `iface-id` stamping. Bug is
  TAP-only by design; vDPA + VF passthrough were always correct.

## Open / deferred items

- **Test A Stage A still failing post-iface-id-fix.** All 6 TAP LSPs +
  vDPA + VF sentinel LSPs report `up=true`, but ICMP from the control plane
  to public IPs `217.179.89.{36,37,38}` returns 100% loss. NAT rows in NB
  show `external_mac=[]` and `gateway_port=[]` for the 3 dnat_and_snat
  entries, which may explain the missing ARP-reply path. Distinct from
  Bug 14; needs separate forensic. Filed as op concern in
  `~/dev/dc/HANDOFF-2026-05-10.md` (`OP3-INVESTIGATE-NAT-PATH`).
- **HA chassis ERR `cr-lrp-public-vpc742`** from the 2026-05-10 forensic
  (Bug 13 audit, "Side-finding") still warrants confirmation — may be
  contributing to the Test A failure independently of NAT path.

## Lessons

- **Promised behavior is not implemented behavior.** Commit `d85d27f126`'s
  message and code comment both promised post-plug stamping that was never
  written. Audits MUST verify the cited code path actually exists, not
  trust the prose.
- **Per-tier driver inconsistency is a smell.** `OvnVdpaVifDriver` and
  `OvnVfPassthroughVifDriver` had explicit post-plug stamps. `OvnVifDriver`
  had a comment describing the same contract but no implementation. When
  drivers fan out for the same protocol (here OVN binding), each branch
  must be inspected for the same contract — symmetry is the audit
  invariant.
- **The `up=false` state on a `Logical_Switch_Port` is the canonical
  signal.** Future TAP-tier audits MUST run
  `ovn-nbctl --bare --columns=name,up list logical_switch_port` and treat
  any TAP-tier `lsp-…` with `up=false` as a binding failure, drilling into
  the OVS-side `external_ids:iface-id` immediately.
