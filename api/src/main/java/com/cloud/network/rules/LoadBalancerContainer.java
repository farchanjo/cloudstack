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
package com.cloud.network.rules;

public interface LoadBalancerContainer {

    public enum Scheme {
        Public, Internal;
    }

    /**
     * First-class load-balancer datapath kind.
     * <ul>
     *   <li>{@link #CT_LB} — OVN {@code Load_Balancer} / {@code ct_lb} (default).</li>
     *   <li>{@link #DSR_SOFTWARE} — software Direct Server Return; never programs
     *       OVN LB/NAT selection for the VIP.</li>
     * </ul>
     * Wire API names are lowercase ({@code ct_lb}, {@code dsr_software}).
     */
    public enum LbKind {
        CT_LB("ct_lb"),
        DSR_SOFTWARE("dsr_software");

        private final String apiName;

        LbKind(String apiName) {
            this.apiName = apiName;
        }

        /** Stable en-US wire name for create/list responses. */
        public String getApiName() {
            return apiName;
        }

        /**
         * Parse API/DB value. Null/blank defaults to {@link #CT_LB}.
         * @throws IllegalArgumentException if the token is non-blank and unknown
         */
        public static LbKind fromString(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return CT_LB;
            }
            String token = raw.trim();
            for (LbKind kind : values()) {
                if (kind.name().equalsIgnoreCase(token) || kind.apiName.equalsIgnoreCase(token)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown load balancer kind '" + raw + "'; expected ct_lb or dsr_software");
        }

        public boolean isDsr() {
            return this == DSR_SOFTWARE;
        }

        public boolean isCtLb() {
            return this == CT_LB;
        }
    }

    String getName();

    String getDescription();

    String getAlgorithm();

    String getLbProtocol();

    Scheme getScheme();

    /**
     * Datapath kind for this rule. Default {@link LbKind#CT_LB} for legacy rows.
     */
    default LbKind getLbKind() {
        return LbKind.CT_LB;
    }

}
