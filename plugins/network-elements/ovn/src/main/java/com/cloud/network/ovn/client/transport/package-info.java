// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

/**
 * Native OVSDB transport for the OVN plugin.
 *
 * <p>OVSDB (RFC 7047) is a JSON-RPC 1.0 protocol over TCP. The plugin only
 * needs a small subset of the operations:
 * <ul>
 *   <li>{@code list_dbs}, {@code get_schema} for capability + sanity probe;
 *   <li>{@code transact} for all NB writes (LR / LRP / LS / LSP / NAT in one
 *       atomic transaction);
 *   <li>{@code monitor} / {@code monitor_cancel} for the read-only SB probe.
 * </ul>
 *
 * <p>The protocol layer in this package is intentionally generic: it exposes
 * a low-level {@link com.cloud.network.ovn.client.transport.OvsdbConnection}
 * that takes raw JSON-RPC envelopes and returns the decoded Jackson
 * {@code JsonNode}. The OVN-specific operation builders live one level up in
 * {@code com.cloud.network.ovn.client.op}.
 *
 * <p><b>Swap-out point.</b> If the project later wants to drop the native
 * transport in favour of the LF Networking {@code libovsdb-jvm}
 * ({@code org.opendaylight.ovsdb:library}), only this package needs to
 * change. The {@code OvnNbClient} surface that the rest of the plugin
 * consumes stays unchanged.
 */
package com.cloud.network.ovn.client.transport;
