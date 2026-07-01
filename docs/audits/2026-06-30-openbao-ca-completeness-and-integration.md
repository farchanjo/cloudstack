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

# openbao-ca completeness audit + real-infra integration validation

**Date:** 2026-06-30
**Status:** CODE COMPLETE, BUILD VERIFIED, LIVE-VALIDATED — NOT YET ACTIVATED
**Severity:** N/A (audit + integration work, no defect)
**Scope:** `plugins/ca/openbao-ca` — CloudStack CA provider backed by OpenBao/Vault.

---

## Summary

`openbao-ca` (commits `d1ce143113`, `a53021c1e8`) had full interface parity with
`root-ca` and was already wired into the build (`plugins/pom.xml`, `client/pom.xml`,
Spring bean auto-discovery) but had never been compiled, never been unit-tested for
the HTTP client / trust manager / keystore-assembly layers, and had never been
validated against a real OpenBao instance. This audit closes all three gaps.

## Test coverage added (commit `a1b00e98be`)

Zero coverage existed for `OpenBaoClient` (login/token-cache/403-retry),
`OpenBaoCACustomTrustManager` (revocation/validity/ownership across a full
intermediate+root chain), and the `OpenBaoCAProvider` keystore-assembly path
(`createSSLEngine`, `getManagementKeyStore` caching, missing-private-key failure).

Added `wiremock-standalone` (already a project-wide test dependency, see root
`pom.xml` `cs.wiremock.version`) to `plugins/ca/openbao-ca/pom.xml`, mirroring the
`plugins/storage/volume/scaleio` usage pattern. New files:

- `OpenBaoClientTest.java` — 9 tests (login, missing-token failure, cached-token
  reuse, 403-triggers-relogin-and-retry via WireMock scenario states, non-2xx
  failure, `buildBody` null/empty filtering, JSON body serialization).
- `OpenBaoCACustomTrustManagerTest.java` — 13 tests, full chain (intermediate +
  root), strict/non-strict × revoked/expired/ownership-mismatch matrix, plus
  `getAcceptedIssuers` chain-length and null-chain cases.
- `OpenBaoCAProviderTest.java` — 5 new tests appended (`createSSLEngine` with/without
  auth strictness, keystore alias contents, keystore caching, missing-private-key
  failure), on top of the 12 pre-existing tests.

**Result: 40 tests, 0 failures, 0 errors** (`OpenBaoCACustomTrustManagerTest`: 13,
`OpenBaoCAProviderTest`: 18, `OpenBaoClientTest`: 9).

## Build verification (aragog, 2026-06-30 ~00:17 UTC)

`mvn -pl plugins/ca/openbao-ca test` — checkstyle clean, 40/40 tests pass. Dependency
modules (`cloud-utils`, `cloud-api`, `cloud-framework-ca`) built via a separate
`-am -DskipTests -Dcheckstyle.skip=true install` pass to avoid two pre-existing,
unrelated upstream failures blocking the reactor: `cloud-engine-schema`'s
`DatabaseUpgradeCheckerDoUpgradesTest` (requires a live MySQL socket, environment
issue, not a code defect) and `cloud-server`'s checkstyle backlog (92 pre-existing
violations, unrelated to this plugin). Neither blocks `openbao-ca` itself.

## Real-infrastructure discovery

OpenBao runs as a 3-node HA raft cluster on the LAX control nodes
(voldemort/bellatrix/barty), transit auto-unseal via Horcrux, active leader at
audit time = voldemort (`217.179.88.34`). `cloudstack-management` also runs
active/active on the same 3 control nodes.

**Config mismatch found and resolved (staging values, not yet applied):** the
plugin's `ca.plugin.openbao.mount` default is `pki_int` and the AppRole
implied by convention would be the generic `cloudstack` role — **both wrong for
this environment**. `pki_int` is the *shared* intermediate CA used by
ceph-client/mysql-client/mysql-server/nginx-internal/openbao-cluster (policy
`pki-issuer`); the `cloudstack` AppRole's policy (`cloudstack`) has no PKI grants
at all. The environment already had a dedicated, correctly-scoped setup
provisioned in advance:

| Setting | Correct value for this environment |
|---|---|
| `ca.plugin.openbao.mount` | `pki_cloudstack` (not the `pki_int` default) |
| `ca.plugin.openbao.sign.role` / `.issue.role` | `cloudstack` (matches default — role name is local to the `pki_cloudstack` mount) |
| AppRole | `cloudstack-ca` (NOT the generic `cloudstack` AppRole) |

Policy `cloudstack-ca` grants exactly: `pki_cloudstack/sign/cloudstack` (update),
`pki_cloudstack/issue/cloudstack` (update), `pki_cloudstack/revoke` (update),
`pki_cloudstack/cert/ca_chain` + `pki_cloudstack/ca_chain` (read). No more, no less
than what `OpenBaoCAProvider` calls.

## Live end-to-end validation (voldemort, read-only + one scratch AppRole login)

Full HTTP contract exercised against production OpenBao via the `cloudstack-ca`
AppRole (fresh scratch `secret_id`, revoked/shredded after use — no long-lived
credential left on disk):

1. AppRole login — OK.
2. `POST pki_cloudstack/issue/cloudstack` (management-cert style) — returned
   `certificate` + `private_key` + `ca_chain`, matching `issueCertificate(domainNames,
   ipAddresses, validityDays)`.
3. `GET pki_cloudstack/cert/ca_chain` — returned a parseable chain, matching
   `getCaCertificate()`.
4. `POST pki_cloudstack/sign/cloudstack` (CSR-based, agent style) — returned
   `certificate` WITHOUT `private_key` (correct — host keeps its own key), matching
   `issueCertificate(csr, domainNames, ipAddresses, validityDays)`.
5. `POST pki_cloudstack/revoke` × 2 — both test certs revoked, matching
   `revokeCertificate(serial, cn)`.

Every OpenBao API call the Java client makes has now been proven against this
exact production mount + role + policy triad.

## Known gap — NOT fixed, blocks real (non-scratch) usage

`vault.slytherin.eonf.ltd` (the nginx `apparate-occulo` anycast front for OpenBao,
`217.179.88.51:443`, Puppet-managed `profile::nginx::internal_lb`) **does not
resolve** — confirmed from voldemort itself using the DC-local PowerDNS recursors
(`217.179.88.54/55/56`), i.e. this is not an aragog-specific resolver gap, it is
missing from the zone cluster-wide. Validation above worked around it via
`curl --resolve vault.slytherin.eonf.ltd:443:217.179.88.51` (pins the IP, keeps the
correct SNI for nginx `server_name` routing). The JDK `HttpClient` used by
`OpenBaoClient` has no equivalent of `--resolve`; without a DNS fix (or a static
`/etc/hosts` entry on the 3 control nodes as a stop-gap) the real plugin will fail
to resolve `ca.plugin.openbao.url` once configured. **Needs an explicit fix
decision — not applied by this audit** (PowerDNS zone edit / `/etc/hosts` stop-gap
both touch shared control-plane infra outside this repo's scope).

## Deliberately NOT done

`ca.framework.provider.plugin` (default `root`) was **not** changed on the live
`cloudstack-management` cluster. Flipping it switches TLS auth for the entire
agent fleet and requires a rolling restart of all 3 management-server nodes —
correctly out of scope for a code/build audit; needs an explicit, separate
operator decision plus a maintenance window.

## Next steps (not implemented here)

1. Decide + apply the DNS fix for `vault.slytherin.eonf.ltd`.
2. Stage the 5 environment-specific global settings (`url`, `mount=pki_cloudstack`,
   sign/issue role, AppRole `cloudstack-ca` role-id/secret-id) — safe to set without
   flipping the active provider.
3. Separate, explicit go/no-go on flipping `ca.framework.provider.plugin=openbao` +
   coordinated rolling restart of voldemort/bellatrix/barty.
