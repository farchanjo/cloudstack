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

import com.cloud.agent.api.Answer;

/**
 * Mgmt-side acknowledgement for {@link UpdateHostVfInventoryCommand}.
 * The agent does not depend on the response — the command is fire-and-poll
 * style — but a structured ack lets us surface reconcile decisions
 * (suspect-flipped count, orphan inserted count) in agent logs for diagnosis.
 */
public class UpdateHostVfInventoryAnswer extends Answer {

    private int reconciledVfs;
    private int suspectFlipped;
    private int orphanInserted;
    private int vdpaConverted;

    protected UpdateHostVfInventoryAnswer() {
    }

    public UpdateHostVfInventoryAnswer(UpdateHostVfInventoryCommand cmd, boolean success, String details) {
        super(cmd, success, details);
    }

    public UpdateHostVfInventoryAnswer(UpdateHostVfInventoryCommand cmd, boolean success, String details,
            int reconciledVfs, int suspectFlipped, int orphanInserted, int vdpaConverted) {
        super(cmd, success, details);
        this.reconciledVfs = reconciledVfs;
        this.suspectFlipped = suspectFlipped;
        this.orphanInserted = orphanInserted;
        this.vdpaConverted = vdpaConverted;
    }

    public int getReconciledVfs() {
        return reconciledVfs;
    }

    public int getSuspectFlipped() {
        return suspectFlipped;
    }

    public int getOrphanInserted() {
        return orphanInserted;
    }

    public int getVdpaConverted() {
        return vdpaConverted;
    }

    public static UpdateHostVfInventoryAnswer success(UpdateHostVfInventoryCommand cmd,
            int reconciledVfs, int suspectFlipped, int orphanInserted, int vdpaConverted) {
        return new UpdateHostVfInventoryAnswer(cmd, true,
                String.format("reconciled=%d suspect=%d orphan=%d vdpaConverted=%d",
                        reconciledVfs, suspectFlipped, orphanInserted, vdpaConverted),
                reconciledVfs, suspectFlipped, orphanInserted, vdpaConverted);
    }

    public static UpdateHostVfInventoryAnswer failure(UpdateHostVfInventoryCommand cmd, String reason) {
        return new UpdateHostVfInventoryAnswer(cmd, false, reason);
    }
}
