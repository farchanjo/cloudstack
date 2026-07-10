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
import java.util.List;

/**
 * Answer to {@link HostVfPurgeOrphansCommand}. Reports per-host counts so
 * the management caller can log aggregate cleanup numbers next to the DB
 * release counts already produced by {@code forceReleaseByHostId}.
 */
public class HostVfPurgeOrphansAnswer extends Answer {

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
}
