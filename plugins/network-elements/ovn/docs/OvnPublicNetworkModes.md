# OVN Public Network Integration Modes

The OVN plugin attaches each VPC's logical router to a per-zone public
`Logical_Switch` whose `localnet` LSP physically exits OVN onto the host
underlay (typically `br-bond` via `ovn-bridge-mappings`). Two opt-in knobs
let the operator choose how the plugin manages the boundary between the OVN
overlay and the data-center underlay:

1. **Public localnet VLAN tag** — `ovn.public.vlan.auto` /
   `ovn.public.vlan.override`. Drives the `tag` column on the public-side
   `localnet` LSP so OVS-OVN tags egressing frames to match the access-port
   VLAN configured on the host bridge. Without this the localnet LSP is
   untagged and the host bridge silently drops the frames when its access
   port enforces a VLAN id.
2. **/32 BGP redistribute** — `ovn.bgp.redistribute.public_ips`. Asks the
   gateway-chassis host to announce one `/32` per allocated public IP via
   its already-running FRR daemon. The plugin does not replace FRR; it only
   writes `network <ip>/32` into the running BGP config via host-side
   `vtysh`. Solves inbound-DNAT loss when the public prefix is announced
   ECMP from every data node and the conntrack state lives only on the OVN
   gateway-chassis.

## Modes at a glance

| Mode | VLAN auto | BGP /32 | Use case |
|------|-----------|---------|----------|
| **A** — plugin-managed BGP   | true  | true  | DC with FRR underlay; OVN handles inbound DNAT via /32 redistribute |
| **B** — simple / VRRP-fronted | true  | false | VRRP/keepalived externos manage public-IP HA; OVN only needs the VLAN |
| **C** — manual                | false | false | Operator hand-rolls everything (legacy / test setups) |

## Mode A — full plugin-managed (recommended for BGP DC)

Enable both knobs at zone or global scope:

```bash
cmk update configuration name=ovn.public.vlan.auto value=true       # default
cmk update configuration name=ovn.bgp.redistribute.public_ips value=true
```

Per-VPC override (a single VPC opts out of the BGP path):

```bash
cmk update vpc id=<vpc-uuid> customparams[0].key=ovn.bgp.redistribute customparams[0].value=false
```

## Mode B — VLAN auto only (default)

`ovn.bgp.redistribute.public_ips` ships at `false`. Existing deployments
keep the same behaviour they had before this feature shipped. The plugin
still auto-detects the VLAN tag on the public localnet — this is the fix
for the silent-drop on egress that motivated the feature.

## Mode C — fully manual

Set both to false. The operator runs `ovn-nbctl set logical_switch_port
lsp-public-localnet tag=<id>` by hand and announces routes through whatever
upstream control plane they prefer.

## ConfigKey reference

| Key | Type | Default | Scope |
|-----|------|---------|-------|
| `ovn.public.vlan.auto` | Boolean | `true` | global / dynamic |
| `ovn.public.vlan.override` | Integer | `0` (= use auto) | global / dynamic |
| `ovn.bgp.redistribute.public_ips` | Boolean | `false` | global / dynamic |
| `ovn.bgp.frr.vtysh.path` | String | `/usr/bin/vtysh` | global / dynamic |
| `ovn.bgp.frr.asn` | Integer | `0` (= auto-detect from FRR) | global / dynamic |
| `ovn.bgp.reconcile.interval.seconds` | Integer | `60` | global / startup |
| `ovn.bgp.frr.instance_tag` | String | `BGP-AUTO` | global / dynamic |
| `ovn.bgp.respect_manual` | Boolean | `true` | global / dynamic |

VLAN resolution chain (highest wins):

1. `ovn.public.vlan.override` (non-zero)
2. Auto-detect from CloudStack Public network `broadcastUri=vlan://<id>`
3. `null` (untagged localnet)

BGP enable resolution chain (highest wins):

1. VPC detail `ovn.bgp.redistribute` (`true` / `false`)
2. Global ConfigKey `ovn.bgp.redistribute.public_ips`

## Verification

Public localnet tag programmed:

```bash
ovn-nbctl --db=tcp:<nb-host>:6641 list logical_switch_port lsp-public-localnet \
  | grep -E '^tag'
```

BGP /32 announced on the gateway-chassis:

```bash
vtysh -c "show running-config" | grep -E '^\s+network <public-ip>/32'
vtysh -c "show ip bgp neighbors" | head
```

CloudStack-side bookkeeping:

```sql
SELECT cs_kind, cs_id, ovn_uuid, ovn_name, created
FROM cloud.ovn_logical_id_map
WHERE cs_kind = 'BGP_ANNOUNCE';
```

`ovn_uuid` carries the agent host id (as a string); `ovn_name` carries the
public IPv4. The reconcile task (`OvnBgpReconcileTask`) walks these rows
every `ovn.bgp.reconcile.interval.seconds` and re-announces on the new
gateway-chassis when the top-priority `HA_Chassis` member changed since the
last tick.

## Failover behaviour

OVN's `HA_Chassis_Group` selection is northd-driven. When the top-priority
chassis goes down, ovn-controller migrates the chassis-redirect port to the
next-priority chassis in the group. The plugin observes this through its
periodic reconcile pass:

1. Every `ovn.bgp.reconcile.interval.seconds`, the task walks
   `BGP_ANNOUNCE` rows.
2. For each row, the manager re-resolves the gateway-chassis (via the NB
   `HA_Chassis_Group` top-priority member -> `chassis_name` ->
   `ovn_chassis_map` -> `host_id`).
3. If the host id differs from the row's recorded host id, the manager
   announces the `/32` on the new host first, then withdraws on the old
   host. The bookkeeping row is updated atomically.

End-to-end failover convergence is `interval + iBGP propagation +
RIB-IN/RIB-OUT`. With the default 60-second reconcile and a tight
RR-config it lands in the order of seconds — consistent with the
keepalived/VRRP comparison the plugin replaces.

## Operational caveats

- `vtysh` runs as root on the agent host. The cloudstack-agent service is
  itself root, so no extra capability is required.
- Agents predating the wrapper return `Unsupported command`. The
  management server logs a warning and skips — the rest of the OVN state is
  unaffected and the inbound-DNAT path falls back to the
  ECMP-without-/32 behaviour described above.
- The plugin never restarts FRR. Operators retain full control of the
  iBGP / route-reflector / EVPN configuration; the plugin only writes
  `network <ip>/32` (and `no network ...`) into the running config.
- No DB schema changes. The reverse-lookup table
  (`ovn_logical_id_map`) stores `BGP_ANNOUNCE` rows internally; the kind
  is an enum value, not a new column.

## Source references

- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/config/OvnNetworkConfig.java`
  — `Configurable` registry for the six ConfigKeys.
- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnPublicNetworkManager.java`
  — VLAN auto-detect + drift fix on the per-zone public localnet LSP.
- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnBgpRedistributeManager.java`
  — announce / withdraw / reconcile for `/32` host routes.
- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnBgpReconcileTask.java`
  — periodic scheduler that drives the reconcile pass.
- `api/src/main/java/com/cloud/agent/api/OvnBgpAnnounceCommand.java`
  + `OvnBgpAnnounceAnswer.java` — agent wire protocol.
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtOvnBgpAnnounceCommandWrapper.java`
  — host-side `vtysh` invocation.
- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/manager/OvnReconcilerService.java`
  — VLAN drift sweep + stale `BGP_ANNOUNCE` row sweep.
