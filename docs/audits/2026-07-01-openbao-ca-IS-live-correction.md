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

# CORRECTION: `openbao-ca` IS deployed and (very likely) IS the live CA provider

**Date:** 2026-07-01
**Status:** RETRACTS `2026-07-01-production-ca-provider-package-gap.md` (append-only —
that file's OPEN status/verification is superseded by the evidence below, not deleted).
**Severity:** N/A — corrects a wrong prior finding, not a new defect.

---

## What was wrong

`2026-07-01-production-ca-provider-package-gap.md` concluded `openbao-ca` was never
deployed, based on `dpkg -l cloudstack-management` reporting `4.24.1.18-20260425T183750~noble`
(unchanged since 2026-04-25) and a filename-only search
(`find /usr/share/cloudstack-management -iname '*openbao*'`) that found nothing.

**Both checks were insufficient.** `dpkg` tracks *package metadata*, not the actual files on
disk — someone replaced the runtime JAR directly, outside `dpkg`/`apt`, so the package
database never updated while the real file did. The filename search missed it because the
plugin isn't a separate `cloud-plugin-ca-openbao-*.jar` (the shape a plain `mvn package`
build produces) — production runs a single shaded/uber JAR containing the whole server.

## Corrected evidence (all 3 control nodes: voldemort, bellatrix, barty)

`/usr/share/cloudstack-management/lib/cloudstack-4.24.1.26-SNAPSHOT.jar` (144,525,880 bytes,
mtime 2026-06-27 12:37) — **identical SHA256 `054cd7f9270b28ff90ddbd5cd7739c8575fc0e04b3ca14c880fb1d7efe7c6143` on all 3 nodes.**

`unzip -l` on that JAR contains, timestamped 2026-06-27 12:39–12:42:

```
org/apache/cloudstack/ca/provider/OpenBaoClient.class
org/apache/cloudstack/ca/provider/OpenBaoClient$1.class
org/apache/cloudstack/ca/provider/OpenBaoCACustomTrustManager.class
org/apache/cloudstack/ca/provider/OpenBaoCAProvider.class
META-INF/cloudstack/openbao-ca/module.properties
META-INF/cloudstack/openbao-ca/spring-openbao-ca-context.xml
```

`META-INF/MANIFEST.MF` (identical on all 3 nodes):

```
Implementation-Version: 4.24.1.26-SNAPSHOT
Implementation-Branch: main
Implementation-Revision: a53021c1e84497c9f1548fdb4b170961f06e62d2
X-Git-Branch: main
X-Git-Revision: a53021c1e84497c9f1548fdb4b170961f06e62d2
X-Git-Tag: 4.24.1.26-SNAPSHOT
```

`a53021c1e84497c9f1548fdb4b170961f06e62d2` **is an ancestor of this repo's current `main`**
(`ca(openbao): add legacy-trust ConfigKey for Path B migration`). The deployed JAR is a clean
build of code already in this repository's history — nothing to recover, nothing missing from
git.

## Revised timeline

| When | What |
|---|---|
| 2026-06-27 ~12:37–12:42 | Uber-JAR built from commit `a53021c1` and placed directly into `/usr/share/cloudstack-management/lib/` on all 3 control nodes, bypassing `dpkg` (package DB still shows `.18`) |
| 2026-06-27 23:04:47 | `ca.plugin.openbao.*` + `ca.framework.provider.plugin=openbao` written to the `configuration` table |
| 2026-06-28 02:07:42 | `aragog` agent certificate issued — chain `CN=aragog → CN=Slytherin CloudStack Issuing CA → CN=Slytherin Root CA`, 1-year validity (matches `pki_cloudstack`'s `max_ttl`) |
| 2026-06-29 05:04:14 | Most recent `cloudstack-management` service (re)start (`ActiveEnterTimestamp`) — reloads the June 27 JAR + the `openbao` provider config |

Re-reading the June 28 aragog cert against this corrected timeline: it is **fully consistent
with `openbao-ca` actually issuing it** (the JAR already existed since June 27 morning, config
since June 27 evening) — the earlier audit's "must be the March-imported root-ca custom
certificate" explanation was a plausible-sounding but wrong alternative, reached by trusting
`dpkg` over the actual deployed bytes.

## What is NOT yet re-verified

This correction confirms the JAR + config are live and consistent with `openbao-ca` being the
active provider — it does not re-run a fresh end-to-end issuance test against production
(the `2026-06-30` audit's live validation used a scratch AppRole login directly against
OpenBao, not through `cloudstack-management`). If a definitive runtime confirmation is needed
(vs. this strong circumstantial chain), the next step is checking `management-server.log`
around 2026-06-28 02:07 for the actual issuance call, or triggering a new host certificate
issuance and observing which provider serves it.

## Housekeeping

The dead-config recommendation in `2026-07-01-production-ca-provider-package-gap.md`
("reverting `ca.framework.provider.plugin` to `root` is optional hygiene") is **withdrawn** —
do not do that; it is the live, working config.
