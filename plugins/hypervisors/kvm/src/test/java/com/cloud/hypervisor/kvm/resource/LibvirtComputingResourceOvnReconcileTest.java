/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.hypervisor.kvm.resource;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression coverage for the startup OVN observation parser.
 *
 * <p>Bug 25 (`2026-05-11-bug-25-old-vm-unreachable-after-agent-restart.md`):
 * after a {@code cloudstack-agent} restart, an iface-id alone is not proof of
 * a live libvirt attachment or exact VF/vDPA ownership. The startup pass
 * ({@link LibvirtComputingResource#reconcileOvnInstalledOnStartup}) therefore
 * observes matching interfaces but leaves ownership state unchanged.
 *
 * <p>This suite exercises the pure parser
 * ({@link LibvirtComputingResource#parseOvnReps}) against canonical
 * production output captured on 2026-05-11 on aragog:
 *
 * <ul>
 *   <li>OVN representor with {@code ovn-installed=true} already stamped
 *       (idempotent re-stamp case);</li>
 *   <li>OVN representor missing {@code ovn-installed=true} (load-bearing
 *       re-stamp case);</li>
 *   <li>Control NIC with raw-UUID {@code iface-id} (must be skipped — not
 *       OVN-managed);</li>
 *   <li>Empty input (must return an empty list, not NPE).</li>
 * </ul>
 */
public class LibvirtComputingResourceOvnReconcileTest {

    @Test
    public void parsesProductionOvsVsctlOutput() {
        // Synthetic but byte-faithful to ovs-vsctl --bare output captured
        // on aragog 2026-05-11. Format: name line, external_ids line, blank
        // separator. Names + iface-ids are real production values.
        final String raw = ""
                + "dx6p1vf2\n"
                + "attached-mac=02:04:02:2e:00:01 iface-id=lsp-e8784396-d911-4c96-9a4c-fcde3f6925cf"
                + " iface-status=active ovn-installed-ts=1778256592351\n"
                + "\n"
                + "dx6p1vf17\n"
                + "attached-mac=02:04:02:53:00:12 iface-id=lsp-8e28b788-e06b-4efc-bc88-01081d1e9395"
                + " iface-status=active ovn-installed=true ovn-installed-ts=1778481638705\n"
                + "\n"
                + "vnet101\n"
                + "attached-mac=0e:00:a9:fe:62:f4 iface-id=89176be8-0536-4cd0-89da-6760cb1458d5"
                + " iface-status=active vm-id=175bdbed-d141-4fdb-a81e-572207a6c577\n"
                + "\n";

        final List<String> reps = LibvirtComputingResource.parseOvnReps(raw);

        Assert.assertEquals("two OVN-managed reps; Control NIC must be skipped",
                2, reps.size());
        Assert.assertTrue("vDPA rep missing ovn-installed flag must be discovered",
                reps.contains("dx6p1vf2"));
        Assert.assertTrue("vDPA rep already stamped is included (idempotent re-stamp)",
                reps.contains("dx6p1vf17"));
        Assert.assertFalse("Control NIC vnet101 (iface-id is raw NIC UUID, no lsp- prefix) must be skipped",
                reps.contains("vnet101"));
    }

    @Test
    public void emptyInputReturnsEmptyList() {
        Assert.assertTrue("blank input yields no reps",
                LibvirtComputingResource.parseOvnReps("").isEmpty());
        Assert.assertTrue("null input yields no reps",
                LibvirtComputingResource.parseOvnReps(null).isEmpty());
        Assert.assertTrue("whitespace input yields no reps",
                LibvirtComputingResource.parseOvnReps("   \n\n   ").isEmpty());
    }

    @Test
    public void mixedTrailingWhitespaceTolerated() {
        // Some ovs-vsctl builds emit a trailing space after each key=value.
        // The parser must not trip on it.
        final String raw = ""
                + "vnet99 \n"
                + " attached-mac=02:04:02:55:00:09 iface-id=lsp-a76a6509-6f76-4c67-8863-e9eada4aa42e"
                + " iface-status=active vm-id=30cc4387-dba4-4b55-8cd3-982bedb7f6e0 \n"
                + "\n";
        final List<String> reps = LibvirtComputingResource.parseOvnReps(raw);
        Assert.assertEquals("trailing whitespace should not eat the row", 1, reps.size());
        Assert.assertEquals("name parsed without trailing whitespace", "vnet99", reps.get(0));
    }

    @Test
    public void singleEntryNoTrailingBlankLine() {
        // ovs-vsctl --bare may omit the trailing blank line on a single match.
        final String raw = ""
                + "dx6p0vf10\n"
                + "attached-mac=02:04:02:53:00:0a iface-id=lsp-b1fbf0ea-370f-404f-b012-e3efbb8198cd"
                + " iface-status=active ovn-installed=true ovn-installed-ts=1778471890787";
        final List<String> reps = LibvirtComputingResource.parseOvnReps(raw);
        Assert.assertEquals(1, reps.size());
        Assert.assertEquals("dx6p0vf10", reps.get(0));
    }
}
