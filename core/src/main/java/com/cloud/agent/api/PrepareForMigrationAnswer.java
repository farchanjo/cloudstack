//
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
//

package com.cloud.agent.api;

import com.cloud.agent.api.to.DpdkTO;
import com.cloud.agent.api.routing.ObserveVdpaMigrationAnswer;

import java.util.HashMap;
import java.util.Map;

public class PrepareForMigrationAnswer extends Answer {

    private Map<String, DpdkTO> dpdkInterfaceMapping = new HashMap<>();

    /**
     * Maps guest NIC MAC address (lower-case) to the destination-allocated
     * {@code /dev/vhost-vdpa-N} path for vDPA NICs.  Populated by
     * {@code LibvirtPrepareForMigrationCommandWrapper} when
     * {@code OvnVdpaVifDriver.plug()} allocates a VF on the destination host.
     * The source agent's {@code LibvirtMigrateCommandWrapper} reads this map
     * and rewrites each {@code <interface type='vdpa'><source dev=...>} in the
     * migration domain XML before calling {@code virDomainMigrate*}, ensuring
     * libvirt opens the correct destination cdev rather than the source path.
     */
    private Map<String, String> vdpaInterfaceMapping = new HashMap<>();

    private Integer newVmCpuShares = null;
    private Map<Long, ObserveVdpaMigrationAnswer.NicObservation> nicObservations = new HashMap<>();

    protected PrepareForMigrationAnswer() {
    }

    public PrepareForMigrationAnswer(PrepareForMigrationCommand cmd, String detail) {
        super(cmd, false, detail);
    }

    public PrepareForMigrationAnswer(PrepareForMigrationCommand cmd, Exception ex) {
        super(cmd, ex);
    }

    public PrepareForMigrationAnswer(PrepareForMigrationCommand cmd) {
        super(cmd, true, null);
    }

    public void setDpdkInterfaceMapping(Map<String, DpdkTO> mapping) {
        this.dpdkInterfaceMapping = mapping;
    }

    public Map<String, DpdkTO> getDpdkInterfaceMapping() {
        return this.dpdkInterfaceMapping;
    }

    public Map<String, String> getVdpaInterfaceMapping() {
        return vdpaInterfaceMapping;
    }

    public void setVdpaInterfaceMapping(final Map<String, String> vdpaInterfaceMapping) {
        this.vdpaInterfaceMapping = vdpaInterfaceMapping != null ? vdpaInterfaceMapping : new HashMap<>();
    }

    public Map<Long, ObserveVdpaMigrationAnswer.NicObservation> getNicObservations() {
        return nicObservations;
    }

    public void setNicObservations(final Map<Long, ObserveVdpaMigrationAnswer.NicObservation> observations) {
        nicObservations = observations == null ? new HashMap<>() : new HashMap<>(observations);
    }

    public Integer getNewVmCpuShares() {
        return newVmCpuShares;
    }

    public void setNewVmCpuShares(Integer newVmCpuShares) {
        this.newVmCpuShares = newVmCpuShares;
    }
}
