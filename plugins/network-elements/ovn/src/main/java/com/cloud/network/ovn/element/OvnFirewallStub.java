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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Placeholder for the OVN ACL skeleton. ACLs land in Phase 2; this file
 * exists so the bean wiring is ready when the work begins. The MVP does not
 * surface any firewall capability in {@link OvnNetworkElement#getCapabilities()},
 * so this bean is unused at runtime.
 */
@Component
public class OvnFirewallStub {

    private static final Logger LOGGER = LogManager.getLogger(OvnFirewallStub.class);

    /** Placeholder method invoked at startup so the wiring can be verified. */
    public void announce() {
        LOGGER.debug("OvnFirewallStub loaded; ACL implementation deferred to Phase 2.");
    }
}
