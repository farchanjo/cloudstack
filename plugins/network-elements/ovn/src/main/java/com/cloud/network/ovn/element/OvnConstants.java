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
package com.cloud.network.ovn.element;

/**
 * Shared string constants for the OVN plugin (provider name, isolation
 * method, external_ids keys).
 */
public final class OvnConstants {

    /** CloudStack network provider name. */
    public static final String PROVIDER_NAME = "Ovn";

    /** Physical-network isolation method admin selects to opt the network in. */
    public static final String ISOLATION_METHOD = "OVN";

    /** Tag key the admin sets on a NetworkOffering to opt into the OVN plugin. */
    public static final String OFFERING_TAG = "useOvn";

    /** external_ids key holding the source CloudStack id type. */
    public static final String EXT_ID_KIND = "cs_kind";

    /** external_ids key holding the source CloudStack id. */
    public static final String EXT_ID_ID = "cs_id";

    /** external_ids key holding the originating CloudStack zone id. */
    public static final String EXT_ID_ZONE = "cs_zone_id";

    /** external_ids key marking a plugin-owned ECMP {@code Logical_Router_Static_Route}
     *  row (see {@code ovn.lr.ecmp.static.routes}). The value is the owning
     *  CloudStack network UUID, so the reconciler can add / diff / remove ONLY
     *  the routes it created and never disturb manual or other static routes. */
    public static final String EXT_ID_ECMP_ROUTE = "cs-ecmp-route";

    /** external_ids key marking a plugin-owned VPC {@code createStaticRoute}
     *  {@code Logical_Router_Static_Route} row. The value is the CloudStack
     *  {@code static_routes.uuid}, so {@code OvnNetworkElement.applyStaticRoutes}
     *  can add / diff / remove ONLY the routes it created. Distinct from
     *  {@link #EXT_ID_ECMP_ROUTE} (ConfigKey namespace) — the two namespaces
     *  must never touch each other's rows. Multi-NH ECMP is expressed as
     *  multiple rows sharing the same {@code ip_prefix} with different
     *  {@code nexthop} and different ownership UUIDs (OVN dst-ip ECMP). */
    public static final String EXT_ID_STATIC_ROUTE = "cs-static-route";

    /** external_ids key marking a plugin-owned public IPv6 {@code Load_Balancer}
     *  row (see {@code ovn.lr.public.ipv6.lb}). The value is a stable entry key
     *  {@code <network-uuid>|<vip>|<port>} so the reconciler can add / update /
     *  remove ONLY the LBs it created and never disturb CloudStack rule LBs or
     *  manual rows. */
    public static final String EXT_ID_PUBLIC_IPV6_LB = "cs-pub6-lb";

    private OvnConstants() {
    }
}
