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
