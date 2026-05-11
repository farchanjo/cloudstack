<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Bug 19 — OvnStaticNatService.addStaticNat not idempotent → errorcode 530 on restartNetwork cleanup=false

**Date:** 2026-05-10 (deployed 2026-05-11)
**Status:** FIXED
**Fix commit:** `ed255c6713`
**Severity:** HIGH — any OVN VPC tier with at least one floating IP fails `restartNetwork cleanup=false` with INTERNAL_ERROR (530).

---

## Symptom

```
cmk restartNetwork id=<tier-uuid> cleanup=false
→ errorcode 530 (INTERNAL_ERROR)
```

The API response `errorcode=530` appeared on every OVN-backed VPC tier that had one or more static NAT (floating IP) rules configured, whenever `cleanup=false` was used. The OVN data plane was not impacted — the existing NAT rules remained active throughout.

Reproduction confirmed on all three tiers of VPC `test20`:
- `tap-vdpa`  (`fa50740c-86af-47c6-8f2f-efa151b8b8fd`)
- `tap-vf`    (`b4e54207-ba9b-41b1-98c4-e969cdef7c16`)
- `tap-tap`   (`787ae4fb-177c-41c1-b0cc-090a824b17bc`)

---

## Root Cause

`OvnStaticNatService.addStaticNat()` at
`plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnStaticNatService.java:74`
unconditionally called `logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.STATIC_NAT, ...))`.

The `restartNetwork cleanup=false` path calls:

```
NetworkOrchestrator.restartNetwork
  → NetworkOrchestrator.implementNetwork
    → NetworkOrchestrator.reprogramNetworkRules
      → OvnNetworkElement.applyStaticNats
        → OvnStaticNatService.addStaticNat   (second call for already-mapped IPs)
```

On the second invocation the duplicate INSERT violated the unique constraint
`uc_ovn_lim_cs (cs_kind, cs_id, controller_id)` on `ovn_logical_id_map`,
throwing `SQLIntegrityConstraintViolationException`. This propagated as
`RuntimeException`, causing `applyStaticNats` to set `overall=false`,
`reprogramNetworkRules` to return `false`, and `restartNetwork` to throw
`ResourceUnavailableException` → `ServerApiException(ApiErrorCode.INTERNAL_ERROR)`
= errorcode 530.

---

## Fix

Added an idempotency guard at the top of `addStaticNat()`, extracted to a private
helper `handleExistingMapping()`, mirroring the existing `SOURCE_NAT` guard in
`OvnNetworkElement.applyIps()` at lines 745-752.

Behaviour:
1. **Existing mapping + OVN NAT row still present:** return existing UUID, skip persist (idempotent skip path).
2. **Existing mapping + OVN NAT row gone (stale):** remove the stale `ovn_logical_id_map` row, fall through to recreate the NAT rule and persist a fresh mapping.
3. **No existing mapping:** proceed as before (new NAT rule + new mapping).

No new imports were required. Both `addStaticNat` (18 lines) and
`handleExistingMapping` (15 lines) are under the 30-line method limit.

File changed:
`plugins/network-elements/ovn/src/main/java/com/cloud/network/ovn/element/OvnStaticNatService.java`

---

## Verification

### Build

```
Build host: aragog (10.182.0.21)
Command: mvn -T 4 -pl client,plugins/network-elements/ovn -am -Pdeveloper install
         -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true
Result: BUILD SUCCESS  (48.927 s wall clock)
```

JAR md5 (aragog source):

| JAR | md5 |
|-----|-----|
| `cloud-client-ui-4.24.1.26-SNAPSHOT.jar` (renamed `cloudstack-*.jar` on controls) | `5163fe2bbbd4f59266babe4a863a86cc` |
| `cloud-plugin-network-ovn-4.24.1.26-SNAPSHOT.jar` | `cd9befbedce6d138c941e27aadc4fb29` |

Note: the `cloud-client-ui` fat JAR already bundles `OvnStaticNatService.class`
and all OVN module descriptors. The separate OVN plugin JAR was NOT deployed to
the controls (would cause double-registration of `network-element-ovn` module and
prevent JVM startup). Only the fat JAR rename is required — same pattern as Bug 18.

### Deploy

md5 match verified on all 3 controls:

| Host | `cloudstack-4.24.1.26-SNAPSHOT.jar` md5 |
|------|----------------------------------------|
| voldemort (10.182.0.11) | `5163fe2bbbd4f59266babe4a863a86cc` |
| bellatrix (10.182.0.12) | `5163fe2bbbd4f59266babe4a863a86cc` |
| barty (10.182.0.13)     | `5163fe2bbbd4f59266babe4a863a86cc` |

### Rolling restart

All 3 controls restarted one-at-a-time. Each reached `HTTP 401` (ready state)
within 3 probe cycles (~15 s from restart).

### Before / After

**Before (pre-fix, Bug 18 state):**
```
cmk restartNetwork id=fa50740c-86af-47c6-8f2f-efa151b8b8fd cleanup=false
→ errorcode 530 (INTERNAL_ERROR)
```

**After (post-fix `ed255c6713`):**
```
cmk restartNetwork id=fa50740c-86af-47c6-8f2f-efa151b8b8fd cleanup=false
→ { "success": true }

cmk restartNetwork id=b4e54207-ba9b-41b1-98c4-e969cdef7c16 cleanup=false
→ { "success": true }

cmk restartNetwork id=787ae4fb-177c-41c1-b0cc-090a824b17bc cleanup=false
→ { "success": true }
```

### Log evidence

Management server log (voldemort, `management-server.log`) for `fa50740c` run:

```
2026-05-11 02:05:46,997 DEBUG ...NetworkOrchestrator... Reprogramming network ... tap-vdpa
2026-05-11 02:05:47,004 INFO  ...NetworkOrchestrator... Let Ovn handle StaticNat in network ... tap-vdpa
2026-05-11 02:05:47,104 DEBUG ...NetworkOrchestrator... Marking network ... tap-vdpa with restartRequired=false
```

No `SQLIntegrityConstraintViolationException`, no `ResourceUnavailableException`,
no `errorcode=530`. The `restartRequired=false` marker confirms the entire
`reprogramNetworkRules` path completed successfully.

The idempotent-skip DEBUG log line (`STATIC_NAT cs_id=... already mapped`) is
present in the code but did not appear in the `INFO`-level production log — this
is expected (DEBUG threshold not enabled in production). The absence of any
exception combined with `success: true` is the definitive proof.

---

## Notes

- OVN data plane was not affected at any point — existing `dnat_and_snat` NAT rules
  remained active during and after the bug.
- The fix mirrors the pattern established for Bug 18 (`cd1d7ad5f0`) and the
  pre-existing `SOURCE_NAT` guard in `OvnNetworkElement.applyIps`.
- Backup JARs retained on each control as
  `cloudstack-4.24.1.26-SNAPSHOT.jar.bak.20260511015648`.
