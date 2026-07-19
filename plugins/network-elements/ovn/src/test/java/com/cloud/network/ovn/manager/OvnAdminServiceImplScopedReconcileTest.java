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
package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.Test;

import com.cloud.network.ovn.api.response.OvnReconcileResultResponse;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * API-layer dispatch tests for the scoped reconciliation paths. These prove
 * that CMK parameters (resourcekind=VPC, resourcekind=OVS_POLICY) dispatch
 * the correct scope and cannot fall through to global reconcile. The
 * service-layer unit tests in {OvnReconcilerServiceTest} cover the scoped
 * behavior; here we assert the {OvnAdminServiceImpl.runReconciler} routing
 * maps API tokens to internal Kinds and that a scoped call never invokes
 * {reconcileZone}.
 */
public class OvnAdminServiceImplScopedReconcileTest {

    @Test
    public void resourcekindVpcDispatchesScopedVpcReconcile() throws Exception {
        final Fixture f = fixture();
        final OvnReconcileResultResponse resp = f.impl.runReconciler(4L, true, false, "VPC", 924L);

        assertNotNull(resp);
        assertTrue(resp.isDryRun());
        // VPC scope must NOT call reconcileZone.
        verify(f.reconciler, never()).reconcileZone(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        // VPC scope must call reconcileResource with Kind.VPC.
        verify(f.reconciler).reconcileResource(eq(4L), eq(Kind.VPC), eq(924L), eq(true));
    }

    @Test
    public void resourcekindOvsPolicyDispatchesScopedHostSweep() throws Exception {
        final Fixture f = fixture();
        f.impl.runReconciler(4L, true, false, "OVS_POLICY", 1L);

        verify(f.reconciler, never()).reconcileZone(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        // OVS_POLICY maps to Kind.NIC internally (host id carried on resourceId).
        verify(f.reconciler).reconcileResource(eq(4L), eq(Kind.NIC), eq(1L), eq(true));
    }

    @Test
    public void resourcekindLoadBalancerStillDispatchesScopedLbReconcile() throws Exception {
        final Fixture f = fixture();
        f.impl.runReconciler(4L, false, false, "LOAD_BALANCER", 1473L);

        verify(f.reconciler, never()).reconcileZone(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(f.reconciler).reconcileResource(eq(4L), eq(Kind.LOAD_BALANCER), eq(1473L), eq(false));
    }

    @Test
    public void missingResourcekindFallsThroughToZoneWideReconcile() throws Exception {
        final Fixture f = fixture();
        f.impl.runReconciler(4L, true, false, null, null);

        verify(f.reconciler).reconcileZone(eq(4L), eq(true), eq(false));
        verify(f.reconciler, never()).reconcileResource(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Kind.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void resourcekindMismatchedWithResourceIdFailsClosed() throws Exception {
        final Fixture f = fixture();
        try {
            f.impl.runReconciler(4L, true, false, "VPC", null);
            fail("expected CloudRuntimeException for mismatched resourcekind/resourceid");
        } catch (CloudRuntimeException expected) {
            assertTrue(expected.getMessage().contains("must be supplied together"));
        }
        verify(f.reconciler, never()).reconcileZone(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(f.reconciler, never()).reconcileResource(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Kind.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void purgeUntaggedWithScopedReconcileFailsClosed() throws Exception {
        final Fixture f = fixture();
        try {
            f.impl.runReconciler(4L, true, true, "VPC", 924L);
            fail("expected CloudRuntimeException for purgeUntagged on scoped reconcile");
        } catch (CloudRuntimeException expected) {
            assertTrue(expected.getMessage().contains("purgeuntagged is not valid for scoped"));
        }
    }

    @Test
    public void unknownResourcekindFailsClosed() throws Exception {
        final Fixture f = fixture();
        try {
            f.impl.runReconciler(4L, true, false, "UNKNOWN_KIND", 1L);
            fail("expected CloudRuntimeException for unknown resourcekind");
        } catch (CloudRuntimeException expected) {
            assertTrue(expected.getMessage().contains("LOAD_BALANCER | VPC | OVS_POLICY"));
        }
        verify(f.reconciler, never()).reconcileZone(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    public void responseContainsAcksByTableField() throws Exception {
        final Fixture f = fixture();
        final OvnReconcileResultResponse resp = f.impl.runReconciler(4L, true, false, "VPC", 924L);

        assertNotNull(resp.getAcksByTable());
        // The scoped VPC ack must be present.
        boolean foundForcesnat = false;
        for (final String key : resp.getAcksByTable().keySet()) {
            if (key.startsWith(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE)) {
                foundForcesnat = true;
                break;
            }
        }
        assertTrue("response must surface scoped force-SNAT ack", foundForcesnat);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Fixture fixture() throws Exception {
        final OvnAdminServiceImpl impl = new OvnAdminServiceImpl();
        final OvnReconcilerService reconciler = mock(OvnReconcilerService.class);
        inject(impl, "reconcilerService", reconciler);
        // Return a minimal Result so toReconcileResponse works.
        final OvnReconcilerService.Result result = new OvnReconcilerService.Result(true);
        result.recordScopedForcesnat("would_strip_legacy_router_ip", false, true);
        when(reconciler.reconcileResource(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Kind.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(result);
        when(reconciler.reconcileZone(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new OvnReconcilerService.Result(true));
        return new Fixture(impl, reconciler);
    }

    private static final class Fixture {
        final OvnAdminServiceImpl impl;
        final OvnReconcilerService reconciler;

        private Fixture(final OvnAdminServiceImpl impl, final OvnReconcilerService reconciler) {
            this.impl = impl;
            this.reconciler = reconciler;
        }
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
