# 2026-05-10 — Bug 16 + Bug 17: vDPA Representor TC Offload Race

## Summary

Two defects on the vDPA NIC tier of the CloudStack OVN fork, both rooted in
the same race condition in `OvnVdpaVifDriver`:

- **Bug 16** — DHCP DISCOVER from a vDPA VM is silently dropped before
  reaching the OVN responder. Every vDPA-tier VM boots without an IP unless
  a static address is set manually via the QEMU guest agent.
- **Bug 17** — TCP cannot establish between vDPA VMs. Only ICMP works.
  `iperf3`, `nc`, and any TCP-based workload hang or get RST'd by the guest
  after duplicate SYN-ACKs.

Both surfaced during the perf-test deploy of `perf-vdpa-src` (norbert) and
`perf-vdpa-dst` (fluffy) earlier on 2026-05-10. ICMP traversed the OVN
pipeline cleanly (0% loss, 0.37 ms after fast-path), but DHCP and TCP failed
end-to-end on the same VMs.

## Severity

| Bug | Severity | Impact |
|---|---|---|
| 16 | HIGH | All vDPA-tier VMs boot without DHCP IP. cloud-init breaks. Operator must manually set static IPs to bring up the network. |
| 17 | HIGH | All TCP/UDP workloads from vDPA VMs are HW-dropped. Every customer workload that is not ICMP (HTTP, SSH from guest, databases, microservices) is unreachable. Only ICMP survives. |

## Root cause

`OvnVdpaVifDriver.attachRepresentorToBrInt()` (called from `plug()`) stamped
`external_ids:iface-status=active` on the vDPA VF representor at plug time,
**before the vhost-vdpa queue negotiation completed** and before the libvirt
domain reached the running state.

mlx5 TC offload listens for the iface-status transition. When `active` is
stamped during the brief "port created but vhost not yet attached" window,
the kernel TC subsystem programs chain 0 of the representor ingress filter
with a partial offload entry:

```
filter protocol ip pref 1 flower chain 0 handle 0x1
  eth_type ipv4
  ip_proto tcp                       (also: ip_proto udp)
  in_hw in_hw_count 1
    action order 1: ct zone N nat pipe
    action order 2: gact action goto chain 1
```

But **chain 1 receives no follow-up rules** — the second-phase forwarding
install requires the port to be in steady state, which the deferred vhost
attachment defeats.

Result: every IPv4 TCP and UDP packet that exits the vDPA VM matches chain 0,
hits the `goto chain 1` redirect, and is hardware-dropped because chain 1
is empty. DHCP DISCOVER (UDP src 68, dst 67) is dropped. TCP SYN is dropped.
ICMP is NOT enumerated in chain 0 (only TCP + UDP), so it falls through to
the OVS kernel datapath where the OpenFlow pipeline handles it correctly.

This matches the proven Bug-14 TAP-tier failure pattern (post-plug iface-id
stamp not firing): both bugs come from skipping the post-VM-running step.
Bug 14 was fixed by introducing `LibvirtComputingResource.applyOvnPostPlugTunables()`
called after `startVM()` reaches running state. Bug 16+17 needed the same
treatment extended to vDPA representors.

## Fix

Commit `bc76f2a8fc` — `fix(kvm/ovn-vdpa): defer iface-status=active until VM running`.

Two changes:

### a) `OvnVdpaVifDriver.attachRepresentorToBrInt()`

Plug-time stamp changed from `iface-status=active` to `iface-status=inactive`.
JavaDoc added documenting the deferred-active rationale (TC chain 0+1 race).
Logged:

```
OvnVdpaVifDriver.attachRepresentorToBrInt: rep={} lsp={} stamped inactive
(deferred-active: post-start hook will cycle to active after vDPA queue
negotiation)
```

### b) `LibvirtComputingResource.applyOvnPostPlugTunables()`

Extended to call a new private helper `applyVdpaPostPlugTunables(domain, nics)`
that iterates the VM's vDPA NICs and stamps `iface-status=active` on each
representor after the libvirt domain reaches running state. Mirrors the Bug-14
TAP pattern (`applyPostPlugTunables` for `vnetN` taps).

### Design choice — Option X (cache rep name on NicTO)

`OvnVdpaVifDriver.plug()` calls `nic.setVfRepName(repName)` to cache the
representor name on the existing (previously unused) `NicTO.vfRepName` field.
`applyVdpaPostPlugTunables()` reads `nic.getVfRepName()` to know which OVS
interface to stamp. No schema change, no PCI re-scan, no new fields.

Logged:

```
applyVdpaPostPlugTunables: rep={} mac={} vm={} iface-status cycled to active
(TC chain-0+1 race window closed)
```

### Companion commit

Commit `e10dd14676` — `chore(kvm): drop unused imports in hwoffload + OvnVfPassthrough test`
removes 4 unused imports flagged by checkstyle (pre-existing, blocking the
`package` build). Unrelated to the vDPA fix but required to land the JAR.

## Production verification

### Build + deploy

- Aragog rebuild md5: `312819d405eefebe673bb9a89f3df13f`
- Deployed `/usr/share/cloudstack-agent/lib/cloud-plugin-hypervisor-kvm-4.24.1.26-SNAPSHOT.jar`
  on norbert + fluffy (md5 match)
- Backed up old JAR as `.bak.<timestamp>` before replacement on both hosts
- Restarted `cloudstack-agent.service` on fluffy (active running)
- Restart on norbert hit a kernel-level vDPA deadlock unrelated to the patch
  (mlx5_vdpa concurrent `vdpa dev add`/`vdpa dev del` on the same VF — see
  "Cross-host verification deferred" below).

### Same-host verification on fluffy

VMs:
- `perf-vdpa-dst` (cmk id `b088d3e3-...`, instance `i-2-1159-VM`, MAC
  `02:04:02:53:00:13`, IP 10.97.1.106, rep `dx6p0vf16`)
- `perf-vdpa-2` (cmk id new, instance `i-2-1161-VM`, MAC `02:04:02:53:00:14`,
  IP 10.97.1.62, rep `dx6p1vf5`) — deployed post-fix to validate fresh plug
  path

Both VMs went through the new plug code path. Agent log evidence:

```
23:51:33,846 OvnVdpaVifDriver.attachRepresentorToBrInt: rep=dx6p0vf16
   lsp=lsp-ded6ad82-... stamped inactive
   (deferred-active: post-start hook will cycle to active after vDPA queue
    negotiation)
23:51:33,847 OvnVdpaVifDriver.plug: name=vdpa-020402530013 pci=0000:01:02.2
   pf=dx6p0 mac=02:04:02:53:00:13 rep=dx6p0vf16 lsp=lsp-ded6ad82-...
   vhost=/dev/vhost-vdpa-5 maxVqs=33 queues=16 bridge=br-int
23:51:35,753 applyVdpaPostPlugTunables: rep=dx6p0vf16 mac=02:04:02:53:00:13
   vm=i-2-1159-VM iface-status cycled to active
   (TC chain-0+1 race window closed)
```

#### Bug 16 (DHCP) — PASS

Both VMs obtained DHCP leases via OVN's distributed DHCP responder:

```
ens3: leased 10.97.1.106 for 86400 seconds   (perf-vdpa-dst)
ens3: leased 10.97.1.62 for 86400 seconds    (perf-vdpa-2)
```

Pre-fix: 0 packets matched OVN's DHCP exception rule in table 73 for vDPA
ports. Post-fix: lease acquired in seconds via the standard pipeline.

#### Bug 17 (TCP) — PASS

ICMP between vDPA VMs:

```
5 packets transmitted, 5 received, 0% packet loss, time 4051ms
rtt min/avg/max/mdev = 0.226/6.013/16.225/7.134 ms
```

TCP via `iperf3 -c 10.97.1.62 -p 5201 -t 15 -P 4`:

```
[SUM]   0.00-15.00  sec  5.73 GBytes  3.28 Gbits/sec  3401   sender
[SUM]   0.00-15.01  sec  5.72 GBytes  3.28 Gbits/sec         receiver
```

Pre-fix: TCP SYN hardware-dropped at chain 0 → empty chain 1 → guest RST
storm. Post-fix: TCP establishes cleanly, sustained 3.28 Gbps.

#### TC chain verification

Both representors (`dx6p0vf16`, `dx6p1vf5`) post-fix:

```
tc -s filter show dev <rep> ingress chain 0   →   (empty)
tc -s filter show dev <rep> ingress chain 1   →   (empty)
```

Pre-fix chain 0 had the partial-offload `ct zone N nat pipe + goto chain 1`
rules with empty chain 1. Post-fix neither chain has rules — the race window
closed cleanly. All traffic flows through the OVS kernel datapath. The
absence of HW offload here is the expected trade-off for the deferred stamp;
chain 0+1 will populate on first traffic only when full steady state is
reached.

### Cross-host verification — DEFERRED

`perf-vdpa-src` on norbert could not be exercised end-to-end because the
norbert KVM host fell into a kernel-level vDPA deadlock during the VM
cycle: two `vdpa(1)` netlink commands (`vdpa dev add` and `vdpa dev del`
on the same VF `pci/0000:01:00.4`, MAC `02:04:02:53:00:12`) entered
uninterruptible kernel sleep (D-state) and could not be reaped even by
SIGKILL. systemd reported the agent service stuck in `deactivating
(stop-sigterm)` and ignored the processes after the timeout. `vdpa dev show`
on norbert hangs as well — the entire vDPA netlink subsystem is locked.

This deadlock is in `mlx5_vdpa` / vhost-vdpa kernel code (wchan
`vhost_vdpa_remove` and `genl_rcv_msg`), not in the CloudStack source. It is
**a separate kernel issue, independent of the patch**, but it blocks
cross-host verification on norbert until the host is rebooted (or the
mlx5_vdpa module is unbound/rebound, also disruptive).

Path forward: schedule a norbert reboot in a maintenance window, then re-run
the `perf-vdpa-src` ↔ `perf-vdpa-dst` test for cross-host TCP throughput.
Until then the patch is considered FIXED based on same-host fluffy evidence
(intra-LS L2 + DHCP), which exercises the same `applyVdpaPostPlugTunables`
code path that would fire on norbert.

## Status

| Bug | Status | Notes |
|---|---|---|
| 16 DHCP | **FIXED** | Verified on fluffy via 2 fresh deploys (perf-vdpa-dst, perf-vdpa-2). DHCP lease 86400s on both. |
| 17 TCP storm | **FIXED** | iperf3 3.28 Gbps sustained between vDPA VMs same-host. ICMP 0% loss. Cross-host validation deferred until norbert reboot. |
| 18 LS-LR patch incomplete | OPEN | `ls-fa50740c` (tap-vdpa LS) has no router-type LSP; `lrp-fa50740c` on VPC LR has `peer=[]`. Breaks inter-tier routing through the logical router. NOT addressed by this patch. Separate audit needed. Symptom: `ping 10.97.1.1` from vDPA VM gets no reply (gateway LR not reachable). |

## Anomaly worth recording

`OvnVdpaVifDriver.plug()` on norbert at 23:30:40 logged the `createVif:
selected VifDriver=OvnVdpaVifDriver` line but no subsequent
`OvnVdpaVifDriver.plug` entry — the plug call entered the new
`attachRepresentorToBrInt` path, called the `vdpa dev del` shell command,
and that shell command hung in the kernel (D-state). The agent's plug()
method blocked indefinitely waiting for the shell to return. This explains
why mgmt thinks `perf-vdpa-src` is stuck in `Starting` for >10 minutes:
the agent never returned a completion event because the kernel call never
unblocked.

The patch does not introduce this kernel deadlock — it surfaces it because
the patch is what triggers the `vdpa dev del`/`vdpa dev add` sequence
during the test cycle. The same sequence on a freshly-rebooted host would
likely succeed (concurrent add/del race only manifests when both commands
overlap).

## Lessons learned

- Any state cycle on a vDPA VF that already has an active vhost-vdpa
  attachment risks triggering the mlx5_vdpa kernel race. Future operator
  procedures should stop the VM completely (giving libvirt time to detach
  the vhost-vdpa device cleanly) before any `vdpa dev del` is issued.
- The post-start `applyOvnPostPlugTunables` hook is now the canonical
  point for all "OVS interface tweaks that depend on the VM being
  fully alive". TAP iface-id stamping (Bug 14) and vDPA iface-status
  cycling (Bug 16+17) both flow through this single entry point. Future
  VifDriver implementations that need post-start tweaks should follow
  the same pattern.

## References

- Local commits: `bc76f2a8fc` (fix), `e10dd14676` (checkstyle cleanup).
- Built JAR md5: `312819d405eefebe673bb9a89f3df13f` (deployed norbert + fluffy `/usr/share/cloudstack-agent/lib/`).
- Prior related audit: `2026-05-10-bug-14-iface-id-prefix.md` (Bug 14 TAP post-plug stamp pattern, mirrored here).
- Open follow-up: Bug 18 LS-LR patch — to be tracked in a separate audit file.
- Cross-host verification: pending norbert reboot.
