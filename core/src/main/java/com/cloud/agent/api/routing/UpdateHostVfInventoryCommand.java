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

package com.cloud.agent.api.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.cloud.agent.api.Command;

/**
 * Periodic agent → mgmt advertise of the host's SR-IOV / vDPA inventory.
 *
 * <p>Carries three lists:
 * <ul>
 *   <li>{@link Pf}: every PF the host exposes (name, BDF, total VFs).
 *   <li>{@link Vf}: every VF (BDF, representor netdev, current PF-pinned MAC,
 *       inferred state from sysfs driver bind).
 *   <li>{@link VdpaSf}: every {@code vdpa dev show -j} entry (name, mgmtdev
 *       BDF, MAC, max_vqs, /dev/vhost-vdpa-N path).
 * </ul>
 *
 * <p>The mgmt-side {@code VfPoolReconcilerImpl} consumes the payload to:
 * <ol>
 *   <li>refresh {@code last_seen} on every VF the agent confirms;
 *   <li>flip {@code ALLOCATED} rows to {@code SUSPECT} when no agent has
 *       refreshed them past the suspect timeout;
 *   <li>convert {@code PASSTHROUGH} rows to {@code VDPA} when the agent
 *       reports a vDPA SF on top of the matching VF;
 *   <li>insert synthetic {@code ORPHAN_MANUAL} rows for SFs the agent
 *       reports but the DB has no row for.
 * </ol>
 *
 * <p>Sent at agent startup and on a periodic timer driven by
 * {@code vdpa.sf.reconcile.interval.seconds} on the agent. Idempotent on the
 * mgmt side — repeated identical advertises only update {@code last_seen}.
 */
public class UpdateHostVfInventoryCommand extends Command {

    private String hostUuid;
    private List<Pf> pfList;
    private List<Vf> vfList;
    private List<VdpaSf> vdpaSfList;

    protected UpdateHostVfInventoryCommand() {
    }

    public UpdateHostVfInventoryCommand(String hostUuid,
            List<Pf> pfList, List<Vf> vfList, List<VdpaSf> vdpaSfList) {
        this.hostUuid = hostUuid;
        this.pfList = pfList != null ? new ArrayList<>(pfList) : new ArrayList<>();
        this.vfList = vfList != null ? new ArrayList<>(vfList) : new ArrayList<>();
        this.vdpaSfList = vdpaSfList != null ? new ArrayList<>(vdpaSfList) : new ArrayList<>();
    }

    public String getHostUuid() {
        return hostUuid;
    }

    public List<Pf> getPfList() {
        return pfList != null ? Collections.unmodifiableList(pfList) : Collections.emptyList();
    }

    public List<Vf> getVfList() {
        return vfList != null ? Collections.unmodifiableList(vfList) : Collections.emptyList();
    }

    public List<VdpaSf> getVdpaSfList() {
        return vdpaSfList != null ? Collections.unmodifiableList(vdpaSfList) : Collections.emptyList();
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    /** Physical Function descriptor (one per PF on the host). */
    public static class Pf {
        private String name;
        private String pciAddress;
        private int numVfs;

        protected Pf() {
        }

        public Pf(String name, String pciAddress, int numVfs) {
            this.name = name;
            this.pciAddress = pciAddress;
            this.numVfs = numVfs;
        }

        public String getName() {
            return name;
        }

        public String getPciAddress() {
            return pciAddress;
        }

        public int getNumVfs() {
            return numVfs;
        }
    }

    /**
     * Virtual Function descriptor. {@code state} is the agent-inferred lifecycle:
     * {@code FREE}, {@code VDPA_BOUND}, {@code PASSTHROUGH_BOUND},
     * {@code UNAVAILABLE}.
     */
    public static class Vf {
        private String pciAddress;
        private String representorName;
        private String currentMac;
        private String state;

        protected Vf() {
        }

        public Vf(String pciAddress, String representorName, String currentMac, String state) {
            this.pciAddress = pciAddress;
            this.representorName = representorName;
            this.currentMac = currentMac;
            this.state = state;
        }

        public String getPciAddress() {
            return pciAddress;
        }

        public String getRepresentorName() {
            return representorName;
        }

        public String getCurrentMac() {
            return currentMac;
        }

        public String getState() {
            return state;
        }
    }

    /**
     * vDPA Sub-Function descriptor — one per {@code vdpa dev show -j} entry on
     * the host.
     */
    public static class VdpaSf {
        private String name;
        private String mgmtdevPci;
        private String mac;
        private Integer maxVqs;
        private String devicePath;

        protected VdpaSf() {
        }

        public VdpaSf(String name, String mgmtdevPci, String mac,
                Integer maxVqs, String devicePath) {
            this.name = name;
            this.mgmtdevPci = mgmtdevPci;
            this.mac = mac;
            this.maxVqs = maxVqs;
            this.devicePath = devicePath;
        }

        public String getName() {
            return name;
        }

        public String getMgmtdevPci() {
            return mgmtdevPci;
        }

        public String getMac() {
            return mac;
        }

        public Integer getMaxVqs() {
            return maxVqs;
        }

        public String getDevicePath() {
            return devicePath;
        }
    }
}
