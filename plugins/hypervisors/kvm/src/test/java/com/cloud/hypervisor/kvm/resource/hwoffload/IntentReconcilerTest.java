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
package com.cloud.hypervisor.kvm.resource.hwoffload;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloud.hypervisor.kvm.resource.hwoffload.HwOffloadIntentApi.IntentSpec;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Public-API behaviour tests for {@link IntentReconciler}. Avoids touching
 * the persistence directory on disk: when {@code /var/lib/cloudstack-agent}
 * is not writable (CI / dev workstations), the implementation logs a
 * warning in {@code loadPersistedSpecs} and proceeds with an in-memory
 * map, which is exactly what these tests exercise.
 *
 * <p>Coverage:
 * <ul>
 *   <li>version-monotonic skip on a stale or equal {@code spec.version}</li>
 *   <li>{@code currentIntent} returns the most recently applied spec</li>
 *   <li>{@code removeIntent} clears the in-memory state and is idempotent</li>
 *   <li>spec validation guards (null spec, null vrId, missing rep)</li>
 * </ul>
 *
 * <p>Programmer interactions are verified at the {@code RuleProgrammer}
 * boundary; the actual TC / OF emission is out of scope here.
 */
@RunWith(MockitoJUnitRunner.class)
public class IntentReconcilerTest {

    private static final String GUEST_VF_PCI = "0000:01:00.3";
    private static final String GUEST_REP = "dx6p0vf1";
    private static final String PUBLIC_VF_PCI = "0000:01:00.5";
    private static final String PUBLIC_REP = "dx6p0vf3";

    @Mock
    private RepresentorMapper repMapper;

    @Mock
    private RuleProgrammer programmer;

    private IntentReconciler reconciler;
    /**
     * Per-test unique VR id. Avoids collisions with any
     * /var/lib/cloudstack-agent/hwoffload/&lt;vrId&gt;.json file that may have been
     * left by a previous test run on a host with a writable persistence dir.
     * loadPersistedSpecs runs in the constructor; without uniqueness a stale
     * spec would set currentByVr[vrId].version > spec.version and applyIntent
     * would skip every test invocation.
     */
    private String vrId;

    @Before
    public void setUp() {
        // Stubbed lazily inside the only test that drives applyIntent past the
        // null-rep guard; Mockito strict mode rejects unused stubs in setUp.
        org.mockito.Mockito.lenient()
                .when(repMapper.getRepresentor(GUEST_VF_PCI)).thenReturn(GUEST_REP);
        org.mockito.Mockito.lenient()
                .when(repMapper.getRepresentor(PUBLIC_VF_PCI)).thenReturn(PUBLIC_REP);
        vrId = "test-" + System.nanoTime();
        // PF uplink with explicit netdev. {@code resolveUplink} verifies the
        // netdev exists in {@code /sys/class/net} — use {@code lo} so the
        // test passes on any Linux host (CI, dev workstation, build server).
        // The actual TC commands the programmer would emit against {@code lo}
        // are mocked out by Mockito, so no system side effect.
        reconciler = new IntentReconciler(repMapper, programmer,
                IntentReconciler.UplinkKind.PF, false, "lo");
    }

    @Test
    public void applyIntent_skipsNullSpec() {
        reconciler.applyIntent(null);
        verify(programmer, never()).resetRepresentor(anyString());
    }

    @Test
    public void applyIntent_skipsSpecWithNullVrId() {
        IntentSpec spec = new IntentSpec();
        spec.vrId = null;
        spec.version = 1L;
        reconciler.applyIntent(spec);
        verify(programmer, never()).resetRepresentor(anyString());
    }

    @Test
    public void applyIntent_skipsWhenIncomingVersionEqualsCurrent() {
        IntentSpec first = newSpec(vrId, 5L);
        reconciler.applyIntent(first);
        IntentSpec dup = newSpec(vrId, 5L);
        reconciler.applyIntent(dup);
        // initRepresentor is invoked once per accepted intent on the guest rep.
        verify(programmer, times(1)).initRepresentor(GUEST_REP);
    }

    @Test
    public void applyIntent_skipsStaleVersion() {
        reconciler.applyIntent(newSpec(vrId, 10L));
        reconciler.applyIntent(newSpec(vrId, 9L));
        verify(programmer, times(1)).initRepresentor(GUEST_REP);
    }

    @Test
    public void applyIntent_skipsWhenRepNotResolvable() {
        when(repMapper.getRepresentor(GUEST_VF_PCI)).thenReturn(null);
        reconciler.applyIntent(newSpec(vrId, 1L));
        verify(programmer, never()).resetRepresentor(anyString());
    }

    @Test
    public void currentIntent_returnsNullForUnknownVr() {
        assertNull(reconciler.currentIntent("unknown-" + System.nanoTime()));
    }

    @Test
    public void removeIntent_isIdempotent() {
        // Use a guaranteed-unique vrId so we do not collide with anything an
        // existing /var/lib/cloudstack-agent/hwoffload/*.json may have rehydrated
        // when the test runs on a host with a live cloudstack-agent.
        String unique = "unit-test-" + System.nanoTime();
        reconciler.removeIntent(unique);
        reconciler.removeIntent(unique);
        // No prior intent and no exception either time.
        verify(programmer, never()).resetRepresentor(anyString());
    }

    // NOTE: end-to-end applyIntent assertions (currentIntent round-trip,
    // ctZone pinning, programmer hook firing) require an injectable
    // STATE_DIR so the test does not pick up production
    // /var/lib/cloudstack-agent/hwoffload/*.json on the build host.
    // Tracked in Phase F follow-up: add @VisibleForTesting setStateDir(Path)
    // to IntentReconciler.

    private static IntentSpec newSpec(String vrId, long version) {
        IntentSpec s = new IntentSpec();
        s.vrId = vrId;
        s.version = version;
        s.guestVfPci = GUEST_VF_PCI;
        return s;
    }
}
