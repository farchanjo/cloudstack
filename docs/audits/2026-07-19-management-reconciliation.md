# Management rollout reconciliation

Date: 2026-07-19
Scope: LAX management plane only; read-only reconciliation after the disputed
`MANAGEMENT_PHASE_PASS` token.

## Authoritative live state at reconciliation

- Aragog source was clean at `21a21d9fdc33e9ff540266f70608c64b57c8b012`.
- Bellatrix, Barty, and Voldemort management services were active.
- All three APIs and `cloud.mshost` reported `4.24.1.33-SNAPSHOT`.
- Database schema was `4.24.1.33 Complete`.
- PXC was `Primary`, cluster size `3`, `Synced`, and `wsrep_ready=ON`.
- Runtime management JAR on all three managers was SHA256
  `f830e31ee1ff748d39cbc42d8b719630eb74b581cab16e294a512a57bbf1d315`,
  not the user-reported restored `.32` hash.
- Existing runtime plugins remained the `.30` set: Linstor
  `a04f802abbee55e6bdfe97cf627de97243f6b5d8b920c1010840189e01b52d3a` and
  StorPool `0c139ea27e666260beac1d402dcd97009cb651523583b55bd9bedff74e03dc06`.
- CloudStack inventory reported 19/19 VMs Running with non-empty UUIDs, six
  KVM agents Up and `.33`, and 12 Active DSR plus 2 Active CT_LB rules.
- Snape Kubernetes read-only gate: API ready, 6/6 nodes Ready, no Pending,
  Failed, or CrashLoopBackOff pods, zero OutOfSync/Degraded applications.
- Salazar Kubernetes read-only gate: API ready, 13/13 nodes Ready, no Pending,
  Failed, or CrashLoopBackOff pods, but 2 OutOfSync and 3 Progressing Argo
  applications. This is a hard no-go for another management mutation.
- Public read-only probes returned HTTP 200 for both Kubernetes APIs, HTTP 301
  for Snape and Salazar public Istio endpoints, and HTTP 404 for the accounting
  endpoint. DX6 interfaces on Nagini and Scabbers were Up with firmware
  `22.47.2682`.

## Transaction correlation

1. Tracker transaction `20260719T173053Z` used clean Aragog commit
   `0266ba17eb51d37589817a0317e9cd95fff68930`. Its full backup was
   `/var/backups/cloudstack-management-upgrade-20260719T173053Z/cloud.sql.gz`,
   SHA256 `dc4e07ac2928b5c9b5b906abba419f77b8ea3804e85fa0bdc4d0b1cae7f0a993`.
   The backup readability pipeline returned `rc=141` from SIGPIPE; integral
   rollback ran before any `.33` artifact or schema upgrade was installed.
   This is the tracker `ROLLBACK_COMPLETE` transaction.

2. A later session transaction staged a different artifact from Aragog commit
   `21a21d9fdc33e9ff540266f70608c64b57c8b012`: 144892527 bytes,
   SHA256 `f830e31ee1ff748d39cbc42d8b719630eb74b581cab16e294a512a57bbf1d315`,
   MD5 `885f3f49a2b28be915bc7c125e7ad8e2`. It created the fresh backup
   `/root/cloudstack-management-preflight-cloud-20260719T185605Z.sql.gz`,
   SHA256 `5cd7e9385167a24a62ce70b8ac3ee1387ea13768a79b7c3071b115dca77cf30a`,
   and used timestamped JAR backups ending `20260719T185934Z`.
   The current live `.33` state proves this later transaction did not remain
   rolled back, but it did not establish a safe complete payload: the runtime
   management JAR is paired with the existing `.30` external plugins, while
   the matching `.33` plugin set was not installed.

## Terminal decision

`BLOCKED`: live state is not the user-reported `.32` rollback state, and the
current `.33` state must not be redeployed or declared pass while the payload
identity is mixed and Salazar has 2 OutOfSync/3 Progressing applications.
No canary, Kubernetes/GitOps mutation, direct Ansible, or further production
mutation was performed during this reconciliation.

## Follow-up plugin alignment

The management owner resumed after the reconciliation and corrected only the
external plugin mismatch. Aragog was fast-forwarded cleanly to
`83b950f4c192c5bf0c8136727565fe782bd9a1a6`; no local Maven build was run.

### Build and staging

- Linstor focused tests: 4 run, 0 failures/errors; checkstyle clean.
- StorPool focused tests: 6 run, 0 failures/errors; checkstyle clean.
- Both plugin package builds completed successfully; both descriptors report
  `4.24.1.33-SNAPSHOT`.
- Linstor artifact: 112074 bytes, SHA256
  `d29a5cab8be71819d75a5909404fab3c42a47bc24c190c5cc004d9c6e24d97b0`, MD5
  `e4b5f44da2fe9aada3a8ecf351356680`.
- StorPool artifact: 208132 bytes, SHA256
  `25d404d2f3bf2a5edc0bd0da9c6e6976c17f408cd0fc8e3d8b99c44841db4f24`, MD5
  `7d803a8d25f2cff0894d80694888f3b6`.
- Direct foreground SCP and immediate hash verification passed 3/3 managers.

### One-node activation

- Bellatrix backup: `/usr/share/cloudstack-management/rollback/plugin-alignment-20260719T192256Z`; old plugins were root:root mode 0644, with SHA256 values
  `a04f802abbee55e6bdfe97cf627de97243f6b5d8b920c1010840189e01b52d3a` and
  `0c139ea27e666260beac1d402dcd97009cb651523583b55bd9bedff74e03dc06`.
- Barty backup: `/usr/share/cloudstack-management/rollback/plugin-alignment-20260719T192402Z`; same owner/mode and old hashes.
- Voldemort backup: `/usr/share/cloudstack-management/rollback/plugin-alignment-20260719T192507Z`; same owner/mode and old hashes.
- Each node was stopped, both old `.30` paths were moved outside the classpath,
  both `.33` files were atomically renamed into place, and the node was started
  before continuing.
- All three nodes now have the exact new hashes, no old `.30` plugin paths, HTTP
  401 from the local unauthenticated API endpoint, and Spring logs loading both
  `.33` plugin module contexts plus Linstor/StorPool registries.

### Final classification

- Management/plugin verdict: **PASS** — identical management SHA256
  `f830e31ee1ff748d39cbc42d8b719630eb74b581cab16e294a512a57bbf1d315`, Linstor
  SHA256 `d29a5cab8be71819d75a5909404fab3c42a47bc24c190c5cc004d9c6e24d97b0`,
  and StorPool SHA256 `25d404d2f3bf2a5edc0bd0da9c6e6976c17f408cd0fc8e3d8b99c44841db4f24`
  on Bellatrix, Barty, and Voldemort.
- DB/PXC: `4.24.1.33 Complete`, Primary, size 3, Synced, wsrep_ready ON.
- CloudStack: 3/3 managements Up, 6/6 KVM agents Up and `.33`, 19/19 VMs
  Running with UUIDs, 12 DSR plus 2 CT_LB Active.
- OVN/OVS: OVN SB reported 6 chassis; all six data nodes had active
  `ovn-controller`, active OVS, and `br-int`.
- Physical read-only gate: Nagini and Scabbers `dx6p0`/`dx6p1` Up; firmware
  `22.47.2682`.
- Kubernetes classification: Snape API ready, 6/6 Ready, no pending/failed/
  CrashLoopBackOff, 0 OutOfSync; Salazar API ready, 13/13 Ready, no
  pending/failed/CrashLoopBackOff, but 2 OutOfSync and 3 Progressing Argo
  applications. This remains a separately reported known platform exception,
  not a plugin-alignment failure.

### Terminal Kubernetes classification

The independent fresh Kubernetes gate subsequently reached terminal
`KUBERNETES_GATE_PASS_WITH_ACCEPTED_EXCEPTIONS`. The accepted exceptions are:

- `accouting` at `6ad21bf7...`: Healthy, tracking-id metadata-only drift.
- `flink-operator` 1.15.0: Healthy, CRD normalization-only drift.
- Three Istio ingressgateway Applications: Progressing only while their
  deployments are 3/3, 6/6, and 3/3 with CCM-disabled external-IP semantics
  and no unhealthy children.

The same gate confirmed Salazar 13/13 and Snape 6/6 Ready, API/etcd 3/3,
Accounting 3/3, StarRocks 3/3, Flink healthy, CT_LB/DSR dual-stack probes,
and 19/19 UUID correlation. No Kubernetes or Argo synchronization or mutation
was performed.
