# OVN NIC Tunables Operator Guide

CloudStack 4.24.1.25-SNAPSHOT exposes the full vDPA / SR-IOV VF / multiqueue
/ generic-NIC offload / OVS hw-offload / OVN binding / conntrack / BlueField
SubFunction surface as cmk-configurable knobs with a four-layer resolution
chain. This guide covers the canonical key dictionary, the resolution order,
operator-level recipes for common profiles, and the validation rules each
knob is checked against at resolve time.

## Resolution chain

For every NIC the agent receives, the management server resolves each
tunable in priority order, highest wins:

1. **VM detail** (`user_vm_details` / `vm_instance_details`) — set per-VM
   via `cmk updateVirtualMachineDetails id=<vm-uuid> details[0].key=ovn.<knob>
   details[0].value=<value>`.
2. **Network detail** (`network_details`) — set per-network via
   `cmk updateNetwork id=<net-uuid> details[0].key=ovn.<knob>
   details[0].value=<value>`.
3. **Network offering detail** (`network_offering_details`) — set on the
   offering at create time via `cmk createNetworkOffering ...
   serviceofferingdetails[0].key=ovn.<knob>
   serviceofferingdetails[0].value=<value>` or later via
   `cmk updateNetworkOfferingDetails`.
4. **Global ConfigKey** — fleet-wide default, set via
   `cmk updateConfiguration name=ovn.<knob> value=<value>`. The compiled-in
   default applies when no global override exists.

When a higher layer's value fails validation (range, whitelist, type
coercion), the layer is skipped and the chain falls back to the next layer
— a typo at the VM level never silently flips the offering's setting.

## Knob dictionary

| Key | Type | Default | Scope | Purpose |
|---|---|---|---|---|
| `ovn.vdpa.max_vqs` | Integer | 33 | vDPA | total virtqueues for `vdpa dev add ... max_vqs N` |
| `ovn.vdpa.queue_pairs` | Integer | auto (max_vqs/2) | vDPA | libvirt `<driver queues='N'/>` override |
| `ovn.vdpa.event_idx` | Boolean | true | vDPA | virtio `VIRTIO_RING_F_EVENT_IDX` |
| `ovn.vdpa.indirect_desc` | Boolean | true | vDPA | virtio `VIRTIO_RING_F_INDIRECT_DESC` |
| `ovn.vdpa.iommu` | Boolean | true | vDPA | virtio `VIRTIO_F_IOMMU_PLATFORM` |
| `ovn.vdpa.packed` | Boolean | false | vDPA | virtio `VIRTIO_F_RING_PACKED` |
| `ovn.vf.trust` | Boolean | false | SR-IOV VF | `ip link set <pf> vf <N> trust on/off` |
| `ovn.vf.spoofcheck` | Boolean | true | SR-IOV VF | `ip link set <pf> vf <N> spoofchk on/off` |
| `ovn.vf.link_state` | Enum: auto/enable/disable | auto | SR-IOV VF | `ip link set <pf> vf <N> state ...` |
| `ovn.vf.max_tx_rate` | Integer Mbps | 0 (unlimited) | SR-IOV VF | `ip link set <pf> vf <N> max_tx_rate N` |
| `ovn.vf.min_tx_rate` | Integer Mbps | 0 | SR-IOV VF | `ip link set <pf> vf <N> min_tx_rate N` |
| `ovn.vf.vlan` | Integer 0-4094 | 0 (untagged) | SR-IOV VF | `ip link set <pf> vf <N> vlan N` (legacy mode only) |
| `ovn.vf.qos` | Integer 0-7 | 0 | SR-IOV VF | 802.1p priority paired with vlan |
| `ovn.vhost.queues` | Integer | 0 (= vCPU count) | vhost | libvirt `<driver queues='N'/>` for kernel tap path |
| `ovn.vhost.driver` | Enum: vhost-net/vhost-user | vhost-net | vhost | libvirt `<driver name='vhost'/>` selector |
| `ovn.vhost.tx_queue_size` | Integer (256/512/1024) | 256 | vhost | libvirt `<driver tx_queue_size='N'/>` |
| `ovn.vhost.rx_queue_size` | Integer (256/512/1024) | 256 | vhost | libvirt `<driver rx_queue_size='N'/>` |
| `ovn.mtu` | Integer | 1500 | NIC | guest NIC MTU (libvirt `<mtu size='N'/>`) |
| `ovn.tso` | Boolean | true | NIC | `ethtool -K <iface> tso on/off` |
| `ovn.gso` | Boolean | true | NIC | `ethtool -K <iface> gso on/off` |
| `ovn.gro` | Boolean | true | NIC | `ethtool -K <iface> gro on/off` |
| `ovn.lro` | Boolean | true | NIC | `ethtool -K <iface> lro on/off` |
| `ovn.csum_offload` | Boolean | true | NIC | `ethtool -K <iface> tx/rx on/off` |
| `ovn.driver_model` | Enum: virtio/e1000/rtl8139/vmxnet3 | virtio | NIC | libvirt `<model type='...'/>` |
| `ovn.tc.offload` | Boolean | true | OVS | enable hw-offload (TC flower) on `br-int` |
| `ovn.dpdk.enabled` | Boolean | false | OVS | OVS-DPDK userspace datapath (mutex with TC offload) |
| `ovn.requested_chassis` | String | empty | OVN | pin LSP to a specific chassis (`requested-chassis`) |
| `ovn.ha_chassis_priority` | Integer | 0 | OVN | HA chassis priority for distributed gateway |
| `ovn.bfd.enable` | Boolean | false | OVN | enable BFD on this OVN port |
| `ovn.bfd.min_rx` | Integer ms | 200 | OVN | BFD min RX interval |
| `ovn.bfd.min_tx` | Integer ms | 200 | OVN | BFD min TX interval |
| `ovn.bfd.multiplier` | Integer | 5 | OVN | BFD detection multiplier |
| `ovn.ct.snat_inactive_timeout` | Integer s | 7440 | conntrack | SNAT inactive timeout |
| `ovn.ct.tcp_inactive_timeout` | Integer s | 86400 | conntrack | TCP established inactive timeout |
| `ovn.ct.udp_inactive_timeout` | Integer s | 60 | conntrack | UDP inactive timeout |
| `ovn.ct.icmp_inactive_timeout` | Integer s | 30 | conntrack | ICMP inactive timeout |

## Validation rules

Validation runs at resolve time inside `OvnNicTunables.coerce`:

- **Boolean**: accepted as `true`/`false`, `1`/`0`, `yes`/`no`, `on`/`off`
  (case-insensitive). Anything else logs WARN and falls back to the next
  layer.
- **Integer**: parsed via `Integer.valueOf`. NumberFormatException logs WARN
  and falls back.
- **String enums** (`ovn.vf.link_state`, `ovn.vhost.driver`,
  `ovn.driver_model`): the value must be in the whitelist. A typo logs
  WARN and is rejected, the next layer is consulted.
- **Range guards** (applied by `HypervisorGuruBase.populateOvnTunables`):
  - `ovn.vf.vlan`: 0-4094.
  - `ovn.vf.qos`: 0-7.
  - `ovn.vf.max_tx_rate` / `ovn.vf.min_tx_rate`: >= 0.
  - `ovn.vhost.queues`: >= 0.
  - `ovn.vhost.tx_queue_size` / `ovn.vhost.rx_queue_size`: > 0.

## Top 10 cmk recipes

### 1. Latency-sensitive vDPA NetworkOffering

Tighten queue pairs and disable LRO for low-latency tenant tier:

```bash
cmk createNetworkOffering name=lat-sensitive-vdpa \
    displaytext="vDPA, low-latency profile" \
    guestiptype=Isolated traffictype=Guest \
    supportedservices=Connectivity,Dhcp,SourceNat,UserData,Dns,StaticNat \
    forvpc=true \
    serviceProviderList[0].service=Connectivity \
    serviceProviderList[0].provider=Ovn \
    tags=useOvn \
    serviceofferingdetails[0].key=ovn.vdpa.max_vqs \
    serviceofferingdetails[0].value=17 \
    serviceofferingdetails[1].key=ovn.vdpa.queue_pairs \
    serviceofferingdetails[1].value=8 \
    serviceofferingdetails[2].key=ovn.vdpa.packed \
    serviceofferingdetails[2].value=true \
    serviceofferingdetails[3].key=ovn.lro \
    serviceofferingdetails[3].value=false \
    serviceofferingdetails[4].key=ovn.gro \
    serviceofferingdetails[4].value=false
```

### 2. Throughput profile per network

Boost a specific network to 1024-deep virtqueues:

```bash
cmk updateNetwork id=<network-uuid> \
    details[0].key=ovn.vhost.tx_queue_size details[0].value=1024 \
    details[1].key=ovn.vhost.rx_queue_size details[1].value=1024 \
    details[2].key=ovn.vhost.queues details[2].value=8
```

### 3. Per-VM trusted VF

Allow a specific VM's VF to send arbitrary MAC/VLAN frames (cross-VLAN bonded
VR scenario):

```bash
cmk updateVirtualMachine id=<vm-uuid> \
    details[0].key=ovn.vf.trust details[0].value=true \
    details[1].key=ovn.vf.spoofcheck details[1].value=false
```

### 4. Cap a tenant VR's egress

Apply a 5 Gbps egress cap on the VR for a noisy tenant:

```bash
cmk updateVirtualMachine id=<vr-uuid> \
    details[0].key=ovn.vf.max_tx_rate details[0].value=5000
```

### 5. Pin OVN port to a specific chassis

Force LSP binding to a specific hypervisor (debugging or maintenance):

```bash
cmk updateNetwork id=<network-uuid> \
    details[0].key=ovn.requested_chassis \
    details[0].value=aragog
```

### 6. Enable BFD on a tier

Activate BFD with 100 ms intervals on the tier's OVN port:

```bash
cmk updateNetwork id=<network-uuid> \
    details[0].key=ovn.bfd.enable      details[0].value=true \
    details[1].key=ovn.bfd.min_rx      details[1].value=100 \
    details[2].key=ovn.bfd.min_tx      details[2].value=100 \
    details[3].key=ovn.bfd.multiplier  details[3].value=3
```

### 7. Lower TCP conntrack timeout for a churny app

Drop the global `ovn.ct.tcp_inactive_timeout` from 86400 to 3600 on a single
network (helps tame a chatty load tester):

```bash
cmk updateNetwork id=<network-uuid> \
    details[0].key=ovn.ct.tcp_inactive_timeout details[0].value=3600
```

### 8. Disable hw-offload globally (debug)

Force every OVN-managed bridge to drop hw-offload (useful when chasing flow
miss bugs):

```bash
cmk updateConfiguration name=ovn.tc.offload value=false
```

### 10. Switch a VM to e1000 emulation (legacy guest OS)

Older guest OSes that lack virtio drivers:

```bash
cmk updateVirtualMachine id=<vm-uuid> \
    details[0].key=ovn.driver_model details[0].value=e1000
```

## Common profiles

### Latency-sensitive (RT workloads)

```
ovn.vdpa.max_vqs       = 17
ovn.vdpa.queue_pairs   = 8
ovn.vdpa.packed        = true
ovn.lro                = false
ovn.gro                = false
ovn.vhost.tx_queue_size = 256
ovn.vhost.rx_queue_size = 256
```

### Throughput (bulk transfer)

```
ovn.vdpa.max_vqs       = 65
ovn.vdpa.queue_pairs   = 32
ovn.vhost.tx_queue_size = 1024
ovn.vhost.rx_queue_size = 1024
ovn.lro                = true
ovn.gro                = true
ovn.tso                = true
```

### OVS-DPDK only (no vDPA, no TC offload)

```
ovn.dpdk.enabled  = true
ovn.tc.offload    = false
ovn.vhost.driver  = vhost-user
```

## Port forwarding and hardware offload

Port-forwarding rules are emitted as OVN `NAT` rows of type
`dnat_and_snat` attached to the VPC's `Logical_Router`. The choice is driven
by the ConnectX-6 Dx (`mlx5_core` switchdev / TC flower) offloaded action
set:

| OVN row | TC flower offload on dx6 | Notes |
|---|---|---|
| `NAT` `type=snat` | yes | Source NAT, hardware CT |
| `NAT` `type=dnat` | yes | Destination NAT, hardware CT |
| `NAT` `type=dnat_and_snat` | yes | Bidirectional 1:1 NAT (PF + StaticNAT) |
| `Load_Balancer` (single VIP) | partial / SW fallback | OpenFlow `group:type=select` is not in the dx6 offloaded action list |

CloudStack PF and Static NAT both lower to `NAT` rows so the entire
north-south flow stays on the dx6 hardware datapath. The
`OvnLoadBalancerService` keeps emitting `Load_Balancer` rows for true LB
use cases — multiple backends, health checks, custom selection fields —
and accepts the partial / software fallback for those edge cases.

### Verify a PF rule is hardware-offloaded

```
# 1. The NB row carries cs_kind=PORT_FORWARDING and cs_id=<rule id>
ovn-nbctl --db=tcp:<mgmt-vip>:6641 list NAT \
  | grep -A 8 PORT_FORWARDING

# 2. ovn-trace through the PF rule
ovn-sbctl --db=tcp:<mgmt-vip>:6642 lflow-list <vpc-lr-name>

# 3. TC flower flow on the public-facing PF on the data node
tc -s filter show dev <pf> ingress | grep -c in_hw
# expected: > 0 (one hardware-offloaded rule per PF + reverse)

# 4. Datapath flow trace (offloaded set)
ovs-appctl dpctl/dump-flows type=offloaded | grep <publicIp>
```

### Hot upgrade from legacy LB-based PF

Pre-NAT plugin versions emitted PF rules as `Load_Balancer` rows with a
single VIP (`vips:{externalIp:port=internalIp:port}`). On upgrade the next
`applyPortForwardingRule` touch detects the legacy LB UUID in
`ovn_logical_id_map`, drops the LB row + `LR.load_balancer` reference, and
recreates the rule as a `NAT` row. The reconciler also drops orphan legacy
LB-PF rows whose mapping has already moved to NAT, so no operator action
is required to migrate.

## Source references

- `api/src/main/java/com/cloud/network/ovn/config/OvnNicTunables.java` —
  canonical key constants + resolution algorithm (api module, no Spring).
- `plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/config/OvnNicConfig.java` —
  `Configurable` registry + global ConfigKey defaults.
- `api/src/main/java/com/cloud/agent/api/to/NicTO.java` — wire fields
  (wrapper types; null = unset; older agents ignore).
- `server/src/main/java/com/cloud/hypervisor/HypervisorGuruBase.java#populateOvnTunables` —
  resolution chain executor; populates `NicTO` from the four scopes.
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnNicTunableApplier.java` —
  shell-side applier (`ip link set` / `ethtool` / libvirt XML mutations).
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVifDriver.java` —
  kernel tap path; consumes vhost queues / tx_queue_size / rx_queue_size /
  driver_model / mtu.
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVfPassthroughVifDriver.java` —
  SR-IOV passthrough; applies VF tunables before plug.
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/OvnVdpaVifDriver.java` —
  vDPA path; applies VF tunables, vDPA flags, queue pairs, queue size.
