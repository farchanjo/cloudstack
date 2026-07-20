// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.vm;

/** Capability decision made before any migration allocation or agent side effect. */
public final class MigrationCapability {
    private final boolean coldOnly;
    private final boolean hasVdpa;
    private final boolean hasVf;
    private final String rejectionReason;

    public MigrationCapability(final boolean coldOnly, final boolean hasVdpa, final boolean hasVf,
            final String rejectionReason) {
        this.coldOnly = coldOnly;
        this.hasVdpa = hasVdpa;
        this.hasVf = hasVf;
        this.rejectionReason = rejectionReason;
    }

    public boolean coldOnly() { return coldOnly; }
    public boolean hasVdpa() { return hasVdpa; }
    public boolean hasVf() { return hasVf; }
    public String rejectionReason() { return rejectionReason; }
    public static MigrationCapability ordinary() {
        return new MigrationCapability(false, false, false, null);
    }

    public boolean isAllowed() {
        return rejectionReason == null;
    }
}
