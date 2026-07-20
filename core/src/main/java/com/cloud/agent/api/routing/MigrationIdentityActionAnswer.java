// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
package com.cloud.agent.api.routing;

import java.util.List;

import com.cloud.agent.api.Answer;

/** Result of one identity-fenced migration action. */
public class MigrationIdentityActionAnswer extends Answer {
    public enum Status { SUCCESS, ALREADY_SATISFIED, PRECONDITION_FAILED, POSTCONDITION_FAILED,
        OBSERVATION_UNAVAILABLE, MANUAL_REQUIRED, DATAPLANE_RESTORED_DOMAIN_START_REQUIRED }
    private boolean preconditionProven;
    private boolean postconditionProven;
    private Status status;
    private List<ObserveVdpaMigrationAnswer.NicObservation> observations;

    protected MigrationIdentityActionAnswer() { }

    public MigrationIdentityActionAnswer(final MigrationIdentityActionCommand command, final boolean success,
            final String details, final boolean preconditionProven, final boolean postconditionProven,
            final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        super(command, success, details);
        this.preconditionProven = preconditionProven;
        this.postconditionProven = postconditionProven;
        this.observations = observations;
        this.status = success ? Status.SUCCESS : Status.MANUAL_REQUIRED;
    }

    public MigrationIdentityActionAnswer(final MigrationIdentityActionCommand command, final boolean success,
            final String details, final Status status, final boolean preconditionProven,
            final boolean postconditionProven, final List<ObserveVdpaMigrationAnswer.NicObservation> observations) {
        this(command, success, details, preconditionProven, postconditionProven, observations);
        this.status = status;
    }

    public boolean isPreconditionProven() { return preconditionProven; }
    public boolean isPostconditionProven() { return postconditionProven; }
    public List<ObserveVdpaMigrationAnswer.NicObservation> getObservations() { return observations; }
    public Status getStatus() { return status; }
}
