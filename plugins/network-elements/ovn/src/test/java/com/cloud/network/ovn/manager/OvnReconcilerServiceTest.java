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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.OvnOvsPolicySweepAnswer;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;

public class OvnReconcilerServiceTest {

    // ------------------------------------------------------------------
    // Fix #1: cloudstackEntityExists(PORT_FORWARDING) must use
    // PortForwardingRulesDao, NOT LoadBalancerDao.
    // ------------------------------------------------------------------

    @Test
    public void probesUseRealDaosAndNeverReapOrphanNic() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final NetworkACLItemDao acl = mock(NetworkACLItemDao.class);
        final FirewallRulesDao firewall = mock(FirewallRulesDao.class);
        final LoadBalancerDao lb = mock(LoadBalancerDao.class);
        final NetworkDao network = mock(NetworkDao.class);
        final PortForwardingRulesDao pfDao = mock(PortForwardingRulesDao.class);
        inject(service, "networkACLItemDao", acl);
        inject(service, "firewallRulesDao", firewall);
        inject(service, "loadBalancerDao", lb);
        inject(service, "networkDao", network);
        inject(service, "portForwardingRulesDao", pfDao);
        when(acl.findById(1L)).thenReturn(mock(com.cloud.network.vpc.NetworkACLItemVO.class));
        when(firewall.findById(2L)).thenReturn(mock(com.cloud.network.rules.FirewallRuleVO.class));
        when(lb.findById(3L)).thenReturn(mock(com.cloud.network.dao.LoadBalancerVO.class));
        when(pfDao.findById(4L)).thenReturn(mock(PortForwardingRuleVO.class));
        final Method probe = OvnReconcilerService.class.getDeclaredMethod("cloudstackEntityExists", Kind.class, long.class);
        probe.setAccessible(true);
        assertTrue((Boolean) probe.invoke(service, Kind.NETWORK_ACL, 1L));
        assertTrue((Boolean) probe.invoke(service, Kind.FIREWALL, 2L));
        assertTrue((Boolean) probe.invoke(service, Kind.LOAD_BALANCER, 3L));
        assertTrue((Boolean) probe.invoke(service, Kind.PORT_FORWARDING, 4L));
        assertTrue((Boolean) probe.invoke(service, Kind.ORPHAN_NIC, 999999L));
        verify(acl).findById(1L);
        verify(firewall).findById(2L);
        verify(lb).findById(3L);
        verify(pfDao).findById(4L);
    }

    @Test
    public void portForwardingActiveRuleIsNotStale() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final PortForwardingRulesDao pfDao = mock(PortForwardingRulesDao.class);
        inject(service, "portForwardingRulesDao", pfDao);
        when(pfDao.findById(1431L)).thenReturn(mock(PortForwardingRuleVO.class));
        final Method probe = OvnReconcilerService.class.getDeclaredMethod("cloudstackEntityExists", Kind.class, long.class);
        probe.setAccessible(true);
        assertTrue("active PF rule must be live, not stale",
                (Boolean) probe.invoke(service, Kind.PORT_FORWARDING, 1431L));
    }

    @Test
    public void portForwardingDeletedRuleIsStale() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final PortForwardingRulesDao pfDao = mock(PortForwardingRulesDao.class);
        inject(service, "portForwardingRulesDao", pfDao);
        when(pfDao.findById(9999L)).thenReturn(null);
        final Method probe = OvnReconcilerService.class.getDeclaredMethod("cloudstackEntityExists", Kind.class, long.class);
        probe.setAccessible(true);
        assertFalse("deleted PF rule must be stale",
                (Boolean) probe.invoke(service, Kind.PORT_FORWARDING, 9999L));
    }

    // ------------------------------------------------------------------
    // Blocker #1: sweepLegacyPortForwardingNat must never touch current
    // Load_Balancer rows. Tests prove LB-backed active PF is preserved even
    // when mapping is transiently absent, and legacy NAT cleanup works.
    // ------------------------------------------------------------------

    @Test
    public void legacySweepNeverQueriesLoadBalancerTable() throws Exception {
        // The sweep must query NAT table, not Load_Balancer. Verify by
        // checking that findUuidsByExternalIds is called with "NAT", never
        // with "Load_Balancer".
        final NatSweepFixture f = natSweepFixture();
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, true, f.out);

        verify(f.nb).findUuidsByExternalIds("NAT",
                com.cloud.network.ovn.element.OvnConstants.EXT_ID_KIND, Kind.PORT_FORWARDING.name());
        verify(f.nb, never()).findUuidsByExternalIds(eq("Load_Balancer"), any(), any());
    }

    @Test
    public void legacySweepPreservesLbBackedActivePfWithMapping() throws Exception {
        // A current PF rule has an LB row + a mapping pointing at the LB UUID.
        // The sweep queries NAT, not LB, so the LB row is never touched.
        final NatSweepFixture f = natSweepFixture();
        // Return an empty list for NAT (no legacy rows) — LB rows are never
        // queried.
        when(f.nb.findUuidsByExternalIds("NAT", any(), any())).thenReturn(List.of());
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, false, f.out);

        assertEquals(0, f.out.totalOrphans());
        verify(f.nb, never()).deleteLoadBalancer(any());
        verify(f.nb, never()).deleteNatRule(any());
    }

    @Test
    public void legacySweepPreservesLbBackedActivePfWithoutMapping() throws Exception {
        // Even if a PF mapping is transiently absent, the sweep queries NAT
        // only — it cannot discover or delete the LB row.
        final NatSweepFixture f = natSweepFixture();
        when(f.nb.findUuidsByExternalIds("NAT", any(), any())).thenReturn(List.of());
        when(f.mappingDao.findByOvnUuid(any())).thenReturn(null);
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, false, f.out);

        assertEquals(0, f.out.totalOrphans());
        verify(f.nb, never()).deleteLoadBalancer(any());
    }

    @Test
    public void legacySweepDeletesStaleNatWhenMappingMigratedToLb() throws Exception {
        // Legacy NAT row exists; mapping has moved to a different (LB) UUID.
        // The NAT row is truly orphan — safe to delete.
        final NatSweepFixture f = natSweepFixture();
        final String natUuid = "nat-legacy-pf-1431";
        final String lbUuid = "lb-current-pf-1431";
        when(f.nb.findUuidsByExternalIds("NAT", any(), any())).thenReturn(List.of(natUuid));
        final OvnLogicalIdMapVO migratedMapping = new OvnLogicalIdMapVO(Kind.PORT_FORWARDING, 1431L, 1L,
                lbUuid, "cs-pf-1431");
        when(f.mappingDao.findByOvnUuid(natUuid)).thenReturn(migratedMapping);
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, false, f.out);

        assertEquals(1, f.out.totalOrphans());
        verify(f.nb).deleteNatRule(natUuid);
        verify(f.nb, never()).deleteLoadBalancer(any());
    }

    @Test
    public void legacySweepSkipsNatWhenMappingStillPointsAtNat() throws Exception {
        // Mapping still references the legacy NAT UUID — the next applyPF
        // will migrate it. Leave it.
        final NatSweepFixture f = natSweepFixture();
        final String natUuid = "nat-legacy-pf-1431";
        when(f.nb.findUuidsByExternalIds("NAT", any(), any())).thenReturn(List.of(natUuid));
        final OvnLogicalIdMapVO natMapping = new OvnLogicalIdMapVO(Kind.PORT_FORWARDING, 1431L, 1L,
                natUuid, "cs-pf-1431");
        when(f.mappingDao.findByOvnUuid(natUuid)).thenReturn(natMapping);
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, false, f.out);

        assertEquals(0, f.out.totalOrphans());
        verify(f.nb, never()).deleteNatRule(any());
    }

    @Test
    public void legacySweepFailsClosedWhenMappingAbsent() throws Exception {
        // Legacy NAT row exists but no mapping at all — fail-closed. We
        // cannot prove the PF entity is gone without cs_id. Leave the
        // inert NAT row.
        final NatSweepFixture f = natSweepFixture();
        final String natUuid = "nat-legacy-pf-9999";
        when(f.nb.findUuidsByExternalIds("NAT", any(), any())).thenReturn(List.of(natUuid));
        when(f.mappingDao.findByOvnUuid(natUuid)).thenReturn(null);
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, false, f.out);

        assertEquals(0, f.out.totalOrphans());
        verify(f.nb, never()).deleteNatRule(any());
    }

    @Test
    public void legacySweepDryRunRecordsButDoesNotDelete() throws Exception {
        final NatSweepFixture f = natSweepFixture();
        final String natUuid = "nat-legacy-pf-1431";
        final String lbUuid = "lb-current-pf-1431";
        when(f.nb.findUuidsByExternalIds("NAT", any(), any())).thenReturn(List.of(natUuid));
        final OvnLogicalIdMapVO migratedMapping = new OvnLogicalIdMapVO(Kind.PORT_FORWARDING, 1431L, 1L,
                lbUuid, "cs-pf-1431");
        when(f.mappingDao.findByOvnUuid(natUuid)).thenReturn(migratedMapping);
        invokePrivate(f.service, "sweepLegacyPortForwardingNat", f.nb, f.controller, true, f.out);

        assertEquals(1, f.out.totalOrphans());
        verify(f.nb, never()).deleteNatRule(any());
    }

    // ------------------------------------------------------------------
    // Existing scoped LB reconcile tests (unchanged behavior).
    // ------------------------------------------------------------------

    @Test
    public void scopedLoadBalancerReconcileRemovesOnlyDeletedRuleMapping() throws Exception {
        final ScopedLbFixture f = scopedLbFixture(false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.LOAD_BALANCER,
                1473L, false);

        assertEquals(1, result.totalOrphans());
        assertEquals(1, result.totalStaleMappings());
        verify(f.nb).deleteLoadBalancer("ovn-lb-1473");
        verify(f.mappingDao).remove(11910L);
    }

    @Test
    public void scopedLoadBalancerReconcileRefusesExistingRule() throws Exception {
        final ScopedLbFixture f = scopedLbFixture(true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.LOAD_BALANCER,
                1473L, false);

        assertEquals(0, result.totalOrphans());
        assertEquals(0, result.totalStaleMappings());
        verify(f.nb, never()).deleteLoadBalancer("ovn-lb-1473");
        verify(f.mappingDao, never()).remove(11910L);
    }

    @Test
    public void scopedLoadBalancerReconcileKeepsMappingWhenOvnDeleteFails() throws Exception {
        final ScopedLbFixture f = scopedLbFixture(false);
        doThrow(new OvnException("delete failed"))
                .when(f.nb).deleteLoadBalancer("ovn-lb-1473");

        try {
            f.service.reconcileResource(4L, Kind.LOAD_BALANCER, 1473L, false);
            fail("expected OVN delete failure");
        } catch (OvnException expected) {
            assertEquals("delete failed", expected.getMessage());
        }
        verify(f.mappingDao, never()).remove(11910L);
    }

    // ------------------------------------------------------------------
    // Blocker #2 + #3 + #4: scoped VPC force-SNAT reconcile.
    // ------------------------------------------------------------------

    @Test
    public void scopedVpcDistributedStripInDryRunDoesNotMutate() throws Exception {
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, true);

        assertTrue(result.isDryRun());
        assertEquals(0, result.totalOrphans());
        assertEquals(0, result.totalStaleMappings());
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_strip_legacy_router_ip"));
        // Blocker #4: no zero-valued entries. topology absent = distributed.
        assertNull("distributed topology must NOT produce topology=0 entry",
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
        // Blocker #3: applied absent in dry-run.
        assertNull("dry-run must NOT produce applied entry",
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":applied"));
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
    }

    @Test
    public void scopedVpcDistributedStripOnApplyRecordsStrippedAndApplied() throws Exception {
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        assertFalse(result.isDryRun());
        assertEquals(0, result.totalOrphans());
        verify(f.nb).ensureLbForceSnat(f.lrUuid);
        // Blocker #3: apply uses "stripped_*" not "would_*".
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":stripped_legacy_router_ip"));
        // Blocker #3: applied=1 only after successful write.
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":applied"));
        // Blocker #4: no zero-valued entries.
        assertNull("distributed topology must NOT produce topology=0 entry",
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
        // Must NOT have would_* in apply mode.
        assertNull("apply must NOT produce would_* action",
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_strip_legacy_router_ip"));
    }

    @Test
    public void scopedVpcCleanDistributedRouterIsNoOp() throws Exception {
        final VpcFixture f = vpcFixture(true, null, false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        assertEquals(0, result.totalOrphans());
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":no_change"));
        // Blocker #4: no applied entry when no write.
        assertNull("no-change must NOT produce applied entry",
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":applied"));
    }

    @Test
    public void scopedVpcExplicitIpv4SnatIpPreserved() throws Exception {
        final VpcFixture f = vpcFixture(true, "203.0.113.10", false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":no_change"));
    }

    @Test
    public void scopedVpcCentralizedRouterIpPreserved() throws Exception {
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":no_change"));
        // Blocker #4: centralized=true produces topology=1.
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
    }

    @Test
    public void scopedVpcCentralizedRouterMissingRouterIpAssertsOnApply() throws Exception {
        final VpcFixture f = vpcFixture(true, null, true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        verify(f.nb).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        // Blocker #3: apply uses "asserted_*" not "would_*".
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":asserted_router_ip"));
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":applied"));
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
    }

    @Test
    public void scopedVpcCentralizedRouterMissingRouterIpDryRunReportsWouldAssert() throws Exception {
        final VpcFixture f = vpcFixture(true, null, true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, true);

        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_assert_router_ip"));
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
        assertNull("dry-run must NOT produce applied entry",
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":applied"));
    }

    @Test
    public void scopedVpcMissingVpcFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(false, null, false);
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for missing VPC");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.nb, never()).ensureLbForceSnat(any());
    }

    @Test
    public void scopedVpcWrongZoneFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(true, null, false);
        try {
            f.service.reconcileResource(999L, Kind.VPC, 924L, true);
            fail("expected OVN exception for wrong zone");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.nb, never()).ensureLbForceSnat(any());
    }

    @Test
    public void scopedVpcMissingMappingFailsClosed() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final OvnPluginManager pm = mock(OvnPluginManager.class);
        final VpcDao vpcDao = mock(VpcDao.class);
        final OvnLogicalIdMapDao mappingDao = mock(OvnLogicalIdMapDao.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        inject(service, "pluginManager", pm);
        inject(service, "vpcDao", vpcDao);
        inject(service, "logicalIdMapDao", mappingDao);
        when(controller.getId()).thenReturn(1L);
        when(pm.findControllerForZone(4L)).thenReturn(controller);
        final VpcVO vpc = mock(VpcVO.class);
        when(vpc.getZoneId()).thenReturn(4L);
        when(vpc.getState()).thenReturn(Vpc.State.Enabled);
        when(vpcDao.findById(924L)).thenReturn(vpc);
        when(mappingDao.findByCsId(Kind.VPC, 924L, 1L)).thenReturn(null);
        try {
            service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for missing mapping");
        } catch (OvnException expected) {
            // ok
        }
    }

    @Test
    public void scopedVpcLogicalRouterMissingFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(true, null, false);
        when(f.nb.rowExistsByUuid("Logical_Router", f.lrUuid)).thenReturn(false);
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for missing LR");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.nb, never()).ensureLbForceSnat(any());
    }

    // Blocker #2: transport failure must propagate, not be swallowed.
    @Test
    public void scopedVpcLrOptionsReadFailureFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(true, null, false);
        when(f.nb.readLogicalRouterOptionsPublic(f.lrUuid)).thenThrow(new OvnException("transport error"));
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for transport failure");
        } catch (OvnException expected) {
            assertTrue(expected.getMessage().contains("failed to read LR options"));
        }
        verify(f.nb, never()).ensureLbForceSnat(any());
    }

    // Blocker #2: null options after rowExistsByUuid=true = race; fail closed.
    @Test
    public void scopedVpcNullOptionsAfterExistsCheckFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(true, null, false);
        when(f.nb.readLogicalRouterOptionsPublic(f.lrUuid)).thenReturn(null);
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for null options race");
        } catch (OvnException expected) {
            assertTrue(expected.getMessage().contains("disappeared"));
        }
        verify(f.nb, never()).ensureLbForceSnat(any());
    }

    // Blocker #3: failed write propagates, no applied=1 in result.
    @Test
    public void scopedVpcFailedWritePropagatesNoAppliedEntry() throws Exception {
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, false);
        doThrow(new OvnException("write failed")).when(f.nb).ensureLbForceSnat(f.lrUuid);
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, false);
            fail("expected OVN exception for write failure");
        } catch (OvnException expected) {
            // ok — exception propagates, no result returned
        }
        // No result was returned, so no applied=1 could have been recorded.
        verify(f.nb).ensureLbForceSnat(f.lrUuid);
    }

    // ------------------------------------------------------------------
    // Blocker #5: scoped OVS_POLICY host sweep.
    // ------------------------------------------------------------------

    @Test
    public void scopedOvsPolicyHostSweepRunsEasySendExactlyOnceForRequestedHost() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Up, Host.Type.Routing, true, true);
        f.service.reconcileResource(4L, Kind.NIC, 1L, true);

        // Blocker #5: easySend verified exactly once for the requested host.
        verify(f.agentManager).easySend(eq(1L), any(com.cloud.agent.api.OvnOvsPolicySweepCommand.class));
        // No NB fallthrough.
        verify(f.pluginManager, never()).nbClient(4L);
    }

    @Test
    public void scopedOvsPolicyHostSweepRecordsDriftFromAnswer() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Up, Host.Type.Routing, true, true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.NIC, 1L, true);

        // The sweep answer returned 4 scanned, 4 drifted, 0 fixed, tcApplied=false.
        assertEquals(4, result.totalOrphans());
        assertEquals(Integer.valueOf(4),
                result.getOrphansByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_DRIFT_TABLE));
        // Host ack recorded.
        assertTrue(result.getAcksByTable().containsKey(
                OvnReconcilerService.Result.OVS_POLICY_HOST_TABLE + ":1"));
    }

    @Test
    public void scopedOvsPolicyWrongZoneFailsClosed() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Up, Host.Type.Routing, true, true);
        try {
            f.service.reconcileResource(999L, Kind.NIC, 1L, true);
            fail("expected OVN exception for wrong zone");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.agentManager, never()).easySend(any(), any());
    }

    @Test
    public void scopedOvsPolicyHostNotUpFailsClosed() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Down, Host.Type.Routing, true, true);
        try {
            f.service.reconcileResource(4L, Kind.NIC, 1L, true);
            fail("expected OVN exception for host not Up");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.agentManager, never()).easySend(any(), any());
    }

    // Blocker #6: non-Routing host type fails closed.
    @Test
    public void scopedOvsPolicyNonRoutingHostFailsClosed() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Up, Host.Type.SecondaryStorage, true, true);
        try {
            f.service.reconcileResource(4L, Kind.NIC, 1L, true);
            fail("expected OVN exception for non-Routing host");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.agentManager, never()).easySend(any(), any());
    }

    // Blocker #6: null chassis fails closed.
    @Test
    public void scopedOvsPolicyNullChassisFailsClosed() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Up, Host.Type.Routing, false, true);
        try {
            f.service.reconcileResource(4L, Kind.NIC, 1L, true);
            fail("expected OVN exception for null chassis");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.agentManager, never()).easySend(any(), any());
    }

    // Blocker #6: mismatched controller fails closed.
    @Test
    public void scopedOvsPolicyMismatchedControllerFailsClosed() throws Exception {
        final OvsPolicyFixture f = ovsPolicyFixture(true, Status.Up, Host.Type.Routing, true, false);
        try {
            f.service.reconcileResource(4L, Kind.NIC, 1L, true);
            fail("expected OVN exception for mismatched controller");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.agentManager, never()).easySend(any(), any());
    }

    // ------------------------------------------------------------------
    // Fix #3: synthetic ACK rows do not inflate totalorphans.
    // ------------------------------------------------------------------

    @Test
    public void ovsPolicyAckDoesNotInflateTotalOrphans() {
        final OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        out.recordOvsPolicyAck(4L);
        assertEquals(0, out.totalOrphans());
        assertEquals(Integer.valueOf(1),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_TABLE));
        assertEquals(Integer.valueOf(1),
                out.getAcksByTable().get(OvnReconcilerService.Result.OVS_TC_POLICY_TABLE));
    }

    @Test
    public void ovsPolicySweepDriftCountsAsOrphansButAckDoesNot() {
        final OvnReconcilerService.Result out = new OvnReconcilerService.Result(false);
        out.recordOvsPolicyAck(4L);
        out.recordOvsPolicySweep(1L, 4, 4, 0, false);
        assertEquals(4, out.totalOrphans());
        assertEquals(Integer.valueOf(4),
                out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_DRIFT_TABLE));
    }

    @Test
    public void scopedForcesnatAckDoesNotInflateTotalOrphans() {
        final OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        out.recordScopedForcesnat("would_strip_legacy_router_ip", false, false);
        assertEquals(0, out.totalOrphans());
        assertEquals(Integer.valueOf(1),
                out.getAcksByTable().get(
                        OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_strip_legacy_router_ip"));
        // Blocker #4: no zero-valued entries.
        assertNull("distributed must NOT produce topology entry",
                out.getAcksByTable().get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
        assertNull("no-write must NOT produce applied entry",
                out.getAcksByTable().get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":applied"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ScopedLbFixture scopedLbFixture(final boolean ruleExists) throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final OvnPluginManager pluginManager = mock(OvnPluginManager.class);
        final OvnLogicalIdMapDao mappingDao = mock(OvnLogicalIdMapDao.class);
        final LoadBalancerDao loadBalancerDao = mock(LoadBalancerDao.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        final OvnNbClient nb = mock(OvnNbClient.class);
        final OvnLogicalIdMapVO mapping = new OvnLogicalIdMapVO(Kind.LOAD_BALANCER, 1473L, 1L,
                "ovn-lb-1473", "cs-lb-1473");
        inject(mapping, "id", 11910L);
        inject(service, "pluginManager", pluginManager);
        inject(service, "logicalIdMapDao", mappingDao);
        inject(service, "loadBalancerDao", loadBalancerDao);
        when(controller.getId()).thenReturn(1L);
        when(pluginManager.findControllerForZone(4L)).thenReturn(controller);
        when(pluginManager.nbClient(4L)).thenReturn(nb);
        when(mappingDao.findByCsId(Kind.LOAD_BALANCER, 1473L, 1L)).thenReturn(mapping);
        when(mappingDao.listByKind(Kind.NETWORK, 1L)).thenReturn(List.of());
        when(mappingDao.listByKind(Kind.VPC, 1L)).thenReturn(List.of());
        when(nb.rowExistsByUuid("Load_Balancer", "ovn-lb-1473")).thenReturn(true);
        when(loadBalancerDao.findById(1473L)).thenReturn(ruleExists ? mock(com.cloud.network.dao.LoadBalancerVO.class) : null);
        return new ScopedLbFixture(service, mappingDao, nb);
    }

    private static VpcFixture vpcFixture(final boolean vpcExists, final String forceSnatValue,
                                          final boolean centralized) throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final OvnPluginManager pluginManager = mock(OvnPluginManager.class);
        final VpcDao vpcDao = mock(VpcDao.class);
        final OvnLogicalIdMapDao mappingDao = mock(OvnLogicalIdMapDao.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        final OvnNbClient nb = mock(OvnNbClient.class);
        final String lrUuid = "e74045b8-4734-4df4-a3f5-b5464879bc04";
        inject(service, "pluginManager", pluginManager);
        inject(service, "vpcDao", vpcDao);
        inject(service, "logicalIdMapDao", mappingDao);
        when(controller.getId()).thenReturn(1L);
        when(pluginManager.findControllerForZone(4L)).thenReturn(controller);
        when(pluginManager.nbClient(4L)).thenReturn(nb);
        if (vpcExists) {
            final VpcVO vpc = mock(VpcVO.class);
            when(vpc.getZoneId()).thenReturn(4L);
            when(vpc.getState()).thenReturn(Vpc.State.Enabled);
            when(vpcDao.findById(924L)).thenReturn(vpc);
        } else {
            when(vpcDao.findById(924L)).thenReturn(null);
        }
        final OvnLogicalIdMapVO mapping = new OvnLogicalIdMapVO(Kind.VPC, 924L, 1L, lrUuid,
                "lr-0d415ee9");
        when(mappingDao.findByCsId(Kind.VPC, 924L, 1L)).thenReturn(mapping);
        when(nb.rowExistsByUuid("Logical_Router", lrUuid)).thenReturn(true);
        final Map<String, String> options = new LinkedHashMap<>();
        if (centralized) {
            options.put(OvnNbClient.LR_OPT_CHASSIS, "gw1");
        }
        if (forceSnatValue != null) {
            options.put(OvnNbClient.LR_OPT_LB_FORCE_SNAT, forceSnatValue);
        }
        when(nb.readLogicalRouterOptionsPublic(lrUuid)).thenReturn(options);
        return new VpcFixture(service, nb, lrUuid);
    }

    private static OvsPolicyFixture ovsPolicyFixture(final boolean hostExists, final Status status,
                                                      final Host.Type type, final boolean chassisExists,
                                                      final boolean controllerMatches) throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final OvnPluginManager pluginManager = mock(OvnPluginManager.class);
        final HostDao hostDao = mock(HostDao.class);
        final OvnChassisMapDao chassisMapDao = mock(OvnChassisMapDao.class);
        final AgentManager agentManager = mock(AgentManager.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        inject(service, "pluginManager", pluginManager);
        inject(service, "hostDao", hostDao);
        inject(service, "chassisMapDao", chassisMapDao);
        inject(service, "agentManager", agentManager);
        when(controller.getId()).thenReturn(1L);
        when(pluginManager.findControllerForZone(4L)).thenReturn(controller);
        if (hostExists) {
            final HostVO host = mock(HostVO.class);
            when(host.getDataCenterId()).thenReturn(4L);
            when(host.getStatus()).thenReturn(status);
            when(host.getType()).thenReturn(type);
            when(hostDao.findById(1L)).thenReturn(host);
        } else {
            when(hostDao.findById(1L)).thenReturn(null);
        }
        if (chassisExists) {
            final OvnChassisMapVO chassis = mock(OvnChassisMapVO.class);
            when(chassis.getControllerId()).thenReturn(controllerMatches ? 1L : 999L);
            when(chassisMapDao.findByHostId(1L)).thenReturn(chassis);
        } else {
            when(chassisMapDao.findByHostId(1L)).thenReturn(null);
        }
        // Blocker #5: return a valid sweep answer with drift counts so
        // sweepOneChassis actually records the result.
        final OvnOvsPolicySweepAnswer answer = mock(OvnOvsPolicySweepAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getPortsScanned()).thenReturn(4);
        when(answer.getHairpinDrifted()).thenReturn(4);
        when(answer.getHairpinFixed()).thenReturn(0);
        when(answer.isTcPolicyApplied()).thenReturn(false);
        when(agentManager.easySend(eq(1L), any())).thenReturn(answer);
        return new OvsPolicyFixture(service, pluginManager, agentManager);
    }

    private static NatSweepFixture natSweepFixture() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final OvnLogicalIdMapDao mappingDao = mock(OvnLogicalIdMapDao.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        final OvnNbClient nb = mock(OvnNbClient.class);
        final OvnReconcilerService.Result out = new OvnReconcilerService.Result(false);
        inject(service, "logicalIdMapDao", mappingDao);
        when(controller.getId()).thenReturn(1L);
        return new NatSweepFixture(service, nb, controller, mappingDao, out);
    }

    /**
     * Invoke a private method by name with the given args. Maps wrapper types
     * (Boolean, Integer, Long) to their primitive equivalents so
     * {getDeclaredMethod} resolves methods declared with primitive
     * parameters (e.g. {boolean} in
     * {sweepLegacyPortForwardingNat(OvnNbClient, OvnControllerVO, boolean, Result)}).
     * Without this mapping, {args[i].getClass()} returns {Boolean.class} for
     * {true}/{false} and the lookup fails because {Boolean.class != boolean.class}.
     */
    private static void invokePrivate(final Object target, final String method, final Object... args) throws Exception {
        final Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            final Class<?> cls = args[i].getClass();
            if (cls == Boolean.class) {
                types[i] = boolean.class;
            } else if (cls == Integer.class) {
                types[i] = int.class;
            } else if (cls == Long.class) {
                types[i] = long.class;
            } else {
                types[i] = cls;
            }
        }
        final Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        m.invoke(target, args);
    }

    private static final class ScopedLbFixture {
        final OvnReconcilerService service;
        final OvnLogicalIdMapDao mappingDao;
        final OvnNbClient nb;

        ScopedLbFixture(final OvnReconcilerService s, final OvnLogicalIdMapDao m, final OvnNbClient n) {
            service = s; mappingDao = m; nb = n;
        }
    }

    private static final class VpcFixture {
        final OvnReconcilerService service;
        final OvnNbClient nb;
        final String lrUuid;

        VpcFixture(final OvnReconcilerService s, final OvnNbClient n, final String u) {
            service = s; nb = n; lrUuid = u;
        }
    }

    private static final class OvsPolicyFixture {
        final OvnReconcilerService service;
        final OvnPluginManager pluginManager;
        final AgentManager agentManager;

        OvsPolicyFixture(final OvnReconcilerService s, final OvnPluginManager p, final AgentManager a) {
            service = s; pluginManager = p; agentManager = a;
        }
    }

    private static final class NatSweepFixture {
        final OvnReconcilerService service;
        final OvnNbClient nb;
        final OvnControllerVO controller;
        final OvnLogicalIdMapDao mappingDao;
        final OvnReconcilerService.Result out;

        NatSweepFixture(final OvnReconcilerService s, final OvnNbClient n, final OvnControllerVO c,
                        final OvnLogicalIdMapDao m, final OvnReconcilerService.Result o) {
            service = s; nb = n; controller = c; mappingDao = m; out = o;
        }
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
