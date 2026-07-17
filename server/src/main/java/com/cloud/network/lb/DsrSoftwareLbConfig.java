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
package com.cloud.network.lb;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.springframework.stereotype.Component;

/**
 * Feature gate and global knobs for {@code DSR_SOFTWARE} load balancer kind.
 * Disabled by default until the acceptance suite is green.
 */
@Component
public class DsrSoftwareLbConfig implements Configurable {

    public static final String NETWORK_LB_DSR_SOFTWARE_ENABLED = "network.lb.dsr.software.enabled";

    public static final ConfigKey<Boolean> DsrSoftwareEnabled = new ConfigKey<>(
            "Network", Boolean.class, NETWORK_LB_DSR_SOFTWARE_ENABLED, "false",
            "Feature gate for DSR_SOFTWARE load balancer kind. When false (default), "
                    + "create of lbkind=dsr_software is rejected. Enable only after acceptance suite is green.",
            true);

    @Override
    public String getConfigComponentName() {
        return DsrSoftwareLbConfig.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{DsrSoftwareEnabled};
    }
}
