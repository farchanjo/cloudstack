# Management 4.24.1.33 production rollout

Date: 2026-07-19
Commit: `21a21d9fdc33e9ff540266f70608c64b57c8b012`
Hosts: Bellatrix, Barty, Voldemort

## Build and preflight

- Existing Aragog Maven reactor completed with `BUILD SUCCESS`.
- `MigrationVfPreflightSpringContextTest`: 1 test, 0 failures, 0 errors.
- Checkstyle: 0 violations.
- Management artifact: `/root/cloudstack/client/target/cloud-client-ui-4.24.1.33-SNAPSHOT.jar`
- Artifact size: 144892527 bytes.
- Artifact SHA256: `f830e31ee1ff748d39cbc42d8b719630eb74b581cab16e294a512a57bbf1d315`.
- Artifact MD5: `885f3f49a2b28be915bc7c125e7ad8e2`.
- Schema resources include `schema-424132to424133.sql` and its cleanup resource.
- Fat-JAR contains `MigrationVfPreflight`, `MigrationPreflightServiceImpl`, and
  `VirtualMachineManagerImpl`; the real Spring context test passed constructor wiring.

## Distribution and activation

- Fresh direct foreground SCP from Aragog completed sequentially:
  Bellatrix, Barty, Voldemort.
- Staged management artifact verification: 3/3.
- Existing approved plugin verification: 6/6.
  - Linstor SHA256: `a04f802abbee55e6bdfe97cf627de97243f6b5d8b920c1010840189e01b52d3a`.
  - StorPool SHA256: `0c139ea27e666260beac1d402dcd97009cb651523583b55bd9bedff74e03dc06`.
- Strict combined staged verification: 9/9.
- Fresh database backup was created and gzip-validated before activation:
  `/root/cloudstack-management-preflight-cloud-20260719T185605Z.sql.gz`.
  - Size: 8638904 bytes.
  - SHA256: `5cd7e9385167a24a62ce70b8ac3ee1387ea13768a79b7c3071b115dca77cf30a`.
- All three management services were stopped, timestamped backups were created,
  the fat JAR was atomically installed, and all three services were started.
- Rollback artifacts are present with suffix `20260719T185934Z`.

## Postchecks

- Installed fat-JAR SHA256 on all three hosts:
  `f830e31ee1ff748d39cbc42d8b719630eb74b581cab16e294a512a57bbf1d315`.
- Installed fat-JAR MD5 on all three hosts:
  `885f3f49a2b28be915bc7c125e7ad8e2`.
- Management servers: 3/3 Up, all reporting `4.24.1.33-SNAPSHOT`.
- KVM hosts: 6/6 Up.
- Load-balancer rules: 14/14 Active: 12 DSR and 2 CT rules.

An earlier activation attempt stopped the services but failed before installation
because of an incorrectly escaped read-only MySQL gate. All services were restored
before the final activation attempt; no production JAR was installed by that attempt.
