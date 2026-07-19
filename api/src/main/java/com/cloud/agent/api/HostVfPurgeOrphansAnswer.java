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
package com.cloud.agent.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answer to {@link HostVfPurgeOrphansCommand}. A management caller may use a
 * per-target result as release evidence only when {@link TargetResult#isSuccess()}
 * is true and the returned BDF/MAC/authorization matches its exact plan.
 */
public class HostVfPurgeOrphansAnswer extends Answer {

    /** Result for one explicitly authorized PCI BDF. */
    public static class TargetResult {
        private String pciBdf;
        private boolean success;
        private boolean devicePresent;
        private boolean representorRemoved;
        private boolean vdpaRemoved;
        private boolean vfioRebound;
        /** Actual actions completed for this target; booleans remain legacy compatibility fields. */
        private int representorsRemovedCount;
        private int vdpaRemovedCount;
        private int vfioReboundCount;
        private String currentMac;
        /** MAC observation state: READ_ERROR, UNASSIGNED_ZERO, or NONZERO. */
        private String macObservation;
        private String expectedMac;
        private String ownerOperationId;
        private String ownerPurpose;
        private String ownerToken;
        private String bindingState;
        private String driver;
        private String vdpaName;
        private List<String> vdpaNames = new ArrayList<>();
        private String representorName;
        private boolean domainReferenced;
        private String domainState;
        private boolean lifecycleAuthorizationUsed;
        private boolean observationComplete;
        private String details;

        public TargetResult() {
        }

        public TargetResult(final String pciBdf, final boolean success, final boolean devicePresent,
                            final boolean representorRemoved, final boolean vdpaRemoved,
                            final boolean vfioRebound, final String details) {
            this.pciBdf = pciBdf;
            this.success = success;
            this.devicePresent = devicePresent;
            this.representorRemoved = representorRemoved;
            this.vdpaRemoved = vdpaRemoved;
            this.vfioRebound = vfioRebound;
            this.details = details;
        }

        public String getPciBdf() {
            return pciBdf;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isDevicePresent() {
            return devicePresent;
        }

        public boolean isRepresentorRemoved() {
            return representorRemoved;
        }

        public void setRepresentorRemoved(final boolean representorRemoved) {
            this.representorRemoved = representorRemoved;
            if (!representorRemoved) {
                representorsRemovedCount = 0;
            } else if (representorsRemovedCount == 0) {
                representorsRemovedCount = 1;
            }
        }

        public boolean isVdpaRemoved() {
            return vdpaRemoved;
        }

        public void setVdpaRemoved(final boolean vdpaRemoved) {
            this.vdpaRemoved = vdpaRemoved;
            if (!vdpaRemoved) {
                vdpaRemovedCount = 0;
            } else if (vdpaRemovedCount == 0) {
                vdpaRemovedCount = 1;
            }
        }

        public boolean isVfioRebound() {
            return vfioRebound;
        }

        public void setVfioRebound(final boolean vfioRebound) {
            this.vfioRebound = vfioRebound;
            if (!vfioRebound) {
                vfioReboundCount = 0;
            } else if (vfioReboundCount == 0) {
                vfioReboundCount = 1;
            }
        }

        public int getRepresentorsRemovedCount() { return representorsRemovedCount > 0 ? representorsRemovedCount
                : representorRemoved ? 1 : 0; }
        public void setRepresentorsRemovedCount(final int count) {
            representorsRemovedCount = Math.max(0, count);
            representorRemoved = representorsRemovedCount > 0;
        }
        public int getVdpaRemovedCount() { return vdpaRemovedCount > 0 ? vdpaRemovedCount : vdpaRemoved ? 1 : 0; }
        public void setVdpaRemovedCount(final int count) {
            vdpaRemovedCount = Math.max(0, count);
            vdpaRemoved = vdpaRemovedCount > 0;
        }
        public int getVfioReboundCount() { return vfioReboundCount > 0 ? vfioReboundCount : vfioRebound ? 1 : 0; }
        public void setVfioReboundCount(final int count) {
            vfioReboundCount = Math.max(0, count);
            vfioRebound = vfioReboundCount > 0;
        }

        public String getDetails() {
            return details;
        }

        public String getCurrentMac() {
            return currentMac;
        }

        public void setCurrentMac(final String currentMac) {
            this.currentMac = currentMac;
        }

        public String getMacObservation() {
            return macObservation;
        }

        public void setMacObservation(final String macObservation) {
            this.macObservation = macObservation;
        }

        public String getExpectedMac() { return expectedMac; }
        public void setExpectedMac(final String expectedMac) { this.expectedMac = expectedMac; }
        public String getOwnerOperationId() { return ownerOperationId; }
        public void setOwnerOperationId(final String ownerOperationId) { this.ownerOperationId = ownerOperationId; }
        public String getOwnerPurpose() { return ownerPurpose; }
        public void setOwnerPurpose(final String ownerPurpose) { this.ownerPurpose = ownerPurpose; }
        public String getOwnerToken() { return ownerToken; }
        public void setOwnerToken(final String ownerToken) { this.ownerToken = ownerToken; }

        public String getBindingState() {
            return bindingState;
        }

        public void setBindingState(final String bindingState) {
            this.bindingState = bindingState;
        }

        public String getDriver() {
            return driver;
        }

        public void setDriver(final String driver) {
            this.driver = driver;
        }

        public String getVdpaName() {
            return vdpaName;
        }

        public void setVdpaName(final String vdpaName) {
            this.vdpaName = vdpaName;
        }

        public List<String> getVdpaNames() { return vdpaNames == null ? Collections.emptyList() : vdpaNames; }
        public void setVdpaNames(final List<String> vdpaNames) {
            this.vdpaNames = vdpaNames == null ? new ArrayList<>() : new ArrayList<>(vdpaNames);
            this.vdpaName = this.vdpaNames.isEmpty() ? null : this.vdpaNames.get(0);
        }
        public String getRepresentorName() { return representorName; }
        public void setRepresentorName(final String representorName) { this.representorName = representorName; }

        public boolean isDomainReferenced() {
            return domainReferenced;
        }

        public void setDomainReferenced(final boolean domainReferenced) {
            this.domainReferenced = domainReferenced;
        }

        public String getDomainState() {
            return domainState;
        }

        public void setDomainState(final String domainState) {
            this.domainState = domainState;
        }

        public boolean isLifecycleAuthorizationUsed() {
            return lifecycleAuthorizationUsed;
        }

        public void setLifecycleAuthorizationUsed(final boolean lifecycleAuthorizationUsed) {
            this.lifecycleAuthorizationUsed = lifecycleAuthorizationUsed;
        }

        public boolean isObservationComplete() {
            return observationComplete;
        }

        public void setObservationComplete(final boolean observationComplete) {
            this.observationComplete = observationComplete;
        }
    }

    /** Per-BDF cleanup results; empty means no destructive target was supplied. */
    private List<TargetResult> targetResults = new ArrayList<>();

    /** Total vdpa-net devices found via {@code vdpa dev show}. */
    private int vdpaFound;

    /** vdpa-net devices skipped because they were in the keep-set. */
    private int vdpaKept;

    /** vdpa-net devices actually removed from the kernel. */
    private int vdpaDeleted;

    /** Names of deleted vdpa-net devices (capped to the first 64). */
    private List<String> vdpaDeletedNames = new ArrayList<>();

    /** Total VFs scanned across all PFs. */
    private int vfsScanned;

    /** VFs found bound to {@code vfio-pci}. */
    private int vfsBoundVfio;

    /** VFs in the keep-set (left bound to {@code vfio-pci} on purpose). */
    private int vfsKept;

    /** VFs actually rebound from {@code vfio-pci} to {@code mlx5_core}. */
    private int vfsRebound;

    /** PCI BDFs of rebound VFs (capped to the first 64). */
    private List<String> vfsReboundBdfs = new ArrayList<>();

    /** OVS Interfaces with iface-id scanned for residual FREE-rep heal. */
    private int ovsRepsScanned;

    /** FREE VF representors actually freed (external_ids + del-port). */
    private int ovsRepsFreed;

    /** Names of freed OVS representors (capped to the first 64). */
    private List<String> ovsRepsFreedNames = new ArrayList<>();

    /** No-arg constructor for serialization frameworks. */
    public HostVfPurgeOrphansAnswer() {
        // No-op.
    }

    public HostVfPurgeOrphansAnswer(final Command command, final boolean success, final String details) {
        super(command, success, details);
    }

    public int getVdpaFound() {
        return vdpaFound;
    }

    public void setVdpaFound(final int vdpaFound) {
        this.vdpaFound = vdpaFound;
    }

    public int getVdpaKept() {
        return vdpaKept;
    }

    public void setVdpaKept(final int vdpaKept) {
        this.vdpaKept = vdpaKept;
    }

    public int getVdpaDeleted() {
        return vdpaDeleted;
    }

    public void setVdpaDeleted(final int vdpaDeleted) {
        this.vdpaDeleted = vdpaDeleted;
    }

    public List<String> getVdpaDeletedNames() {
        return vdpaDeletedNames;
    }

    public void setVdpaDeletedNames(final List<String> vdpaDeletedNames) {
        this.vdpaDeletedNames = vdpaDeletedNames == null ? new ArrayList<>() : new ArrayList<>(vdpaDeletedNames);
    }

    public int getVfsScanned() {
        return vfsScanned;
    }

    public void setVfsScanned(final int vfsScanned) {
        this.vfsScanned = vfsScanned;
    }

    public int getVfsBoundVfio() {
        return vfsBoundVfio;
    }

    public void setVfsBoundVfio(final int vfsBoundVfio) {
        this.vfsBoundVfio = vfsBoundVfio;
    }

    public int getVfsKept() {
        return vfsKept;
    }

    public void setVfsKept(final int vfsKept) {
        this.vfsKept = vfsKept;
    }

    public int getVfsRebound() {
        return vfsRebound;
    }

    public void setVfsRebound(final int vfsRebound) {
        this.vfsRebound = vfsRebound;
    }

    public List<String> getVfsReboundBdfs() {
        return vfsReboundBdfs;
    }

    public void setVfsReboundBdfs(final List<String> vfsReboundBdfs) {
        this.vfsReboundBdfs = vfsReboundBdfs == null ? new ArrayList<>() : new ArrayList<>(vfsReboundBdfs);
    }

    public int getOvsRepsScanned() {
        return ovsRepsScanned;
    }

    public void setOvsRepsScanned(final int ovsRepsScanned) {
        this.ovsRepsScanned = ovsRepsScanned;
    }

    public int getOvsRepsFreed() {
        return ovsRepsFreed;
    }

    public void setOvsRepsFreed(final int ovsRepsFreed) {
        this.ovsRepsFreed = ovsRepsFreed;
    }

    public List<String> getOvsRepsFreedNames() {
        return ovsRepsFreedNames;
    }

    public void setOvsRepsFreedNames(final List<String> ovsRepsFreedNames) {
        this.ovsRepsFreedNames = ovsRepsFreedNames == null
                ? new ArrayList<>() : new ArrayList<>(ovsRepsFreedNames);
    }

    public List<TargetResult> getTargetResults() {
        return targetResults == null ? Collections.emptyList() : targetResults;
    }

    public void setTargetResults(final List<TargetResult> targetResults) {
        this.targetResults = targetResults == null ? new ArrayList<>() : new ArrayList<>(targetResults);
    }
}
