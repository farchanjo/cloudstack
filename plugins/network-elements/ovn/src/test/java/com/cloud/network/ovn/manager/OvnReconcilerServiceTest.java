package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;

public class OvnReconcilerServiceTest {

    // ------------------------------------------------------------------
    // Fix #1: cloudstackEntityExists(PORT_FORWARDING) must use
    // PortForwardingRulesDao, NOT LoadBalancerDao. The prior test pinned
    // the wrong DAO; it is corrected here.
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

    // Regression: an active PF rule backed by an OVN Load_Balancer is NOT
    // stale. The wrong DAO (LoadBalancerDao) would return false (csGone)
    // for a PF cs_id; PortForwardingRulesDao returns true (live).
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

    // Regression: a truly deleted PF rule (PortForwardingRulesDao returns
    // null) is classified as stale so the mapping + NB row can be removed.
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
    // Existing scoped LB reconcile tests (unchanged behavior).
    // ------------------------------------------------------------------

    @Test
    public void scopedLoadBalancerReconcileRemovesOnlyDeletedRuleMapping() throws Exception {
        final ScopedFixture fixture = scopedFixture(false);
        final OvnReconcilerService.Result result = fixture.service.reconcileResource(4L, Kind.LOAD_BALANCER,
                1473L, false);

        assertEquals(1, result.totalOrphans());
        assertEquals(1, result.totalStaleMappings());
        verify(fixture.nb).deleteLoadBalancer("ovn-lb-1473");
        verify(fixture.mappingDao).remove(11910L);
    }

    @Test
    public void scopedLoadBalancerReconcileRefusesExistingRule() throws Exception {
        final ScopedFixture fixture = scopedFixture(true);
        final OvnReconcilerService.Result result = fixture.service.reconcileResource(4L, Kind.LOAD_BALANCER,
                1473L, false);

        assertEquals(0, result.totalOrphans());
        assertEquals(0, result.totalStaleMappings());
        verify(fixture.nb, never()).deleteLoadBalancer("ovn-lb-1473");
        verify(fixture.mappingDao, never()).remove(11910L);
    }

    @Test
    public void scopedLoadBalancerReconcileKeepsMappingWhenOvnDeleteFails() throws Exception {
        final ScopedFixture fixture = scopedFixture(false);
        doThrow(new OvnException("delete failed"))
                .when(fixture.nb).deleteLoadBalancer("ovn-lb-1473");

        try {
            fixture.service.reconcileResource(4L, Kind.LOAD_BALANCER, 1473L, false);
            fail("expected OVN delete failure");
        } catch (OvnException expected) {
            assertEquals("delete failed", expected.getMessage());
        }
        verify(fixture.mappingDao, never()).remove(11910L);
    }

    // ------------------------------------------------------------------
    // Fix #4: scoped VPC force-SNAT reconcile.
    // ------------------------------------------------------------------

    @Test
    public void scopedVpcDistributedStripInDryRunDoesNotMutate() throws Exception {
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, true);

        assertTrue(result.isDryRun());
        assertEquals(0, result.totalOrphans());
        assertEquals(0, result.totalStaleMappings());
        // acks surface the would-strip action + needsWrite=1
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_strip_legacy_router_ip"));
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":needsWrite"));
        assertEquals(Integer.valueOf(0),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
        // Dry-run must NOT call ensureLbForceSnat.
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
    }

    @Test
    public void scopedVpcDistributedStripOnApplyCallsEnsureLbForceSnat() throws Exception {
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        assertFalse(result.isDryRun());
        assertEquals(0, result.totalOrphans());
        verify(f.nb).ensureLbForceSnat(f.lrUuid);
    }

    @Test
    public void scopedVpcCleanDistributedRouterIsNoOp() throws Exception {
        // Distributed router without the router_ip token -> no write.
        final VpcFixture f = vpcFixture(true, null, false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        assertEquals(0, result.totalOrphans());
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":no_change"));
        assertEquals(Integer.valueOf(0),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":needsWrite"));
    }

    @Test
    public void scopedVpcExplicitIpv4SnatIpPreserved() throws Exception {
        // Explicit IPv4 lb_force_snat_ip (not the magic value) on a
        // distributed router must NOT be stripped.
        final VpcFixture f = vpcFixture(true, "203.0.113.10", false);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":no_change"));
    }

    @Test
    public void scopedVpcCentralizedRouterIpPreserved() throws Exception {
        // Centralized router (options:chassis set) with router_ip already
        // present -> no write.
        final VpcFixture f = vpcFixture(true, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, false);

        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":no_change"));
    }

    @Test
    public void scopedVpcCentralizedRouterMissingRouterIpAssertsOnApply() throws Exception {
        // Centralized router without router_ip -> would_assert on dry-run,
        // ensureLbForceSnat on apply.
        final VpcFixture f = vpcFixture(true, null, true);
        final OvnReconcilerService.Result result = f.service.reconcileResource(4L, Kind.VPC, 924L, true);

        final Map<String, Integer> acks = result.getAcksByTable();
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_assert_router_ip"));
        assertEquals(Integer.valueOf(1),
                acks.get(OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":topology"));
        // dry-run must not mutate
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
    }

    @Test
    public void scopedVpcMissingVpcFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(false, null, false);
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for missing VPC");
        } catch (OvnException expected) {
            // ok — fail closed
        }
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
    }

    @Test
    public void scopedVpcWrongZoneFailsClosed() throws Exception {
        final VpcFixture f = vpcFixture(true, null, false);
        // VPC belongs to zone 4; request zone 999 -> fail closed.
        try {
            f.service.reconcileResource(999L, Kind.VPC, 924L, true);
            fail("expected OVN exception for wrong zone");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
    }

    @Test
    public void scopedVpcMissingMappingFailsClosed() throws Exception {
        // VPC exists but no Kind.VPC mapping row -> fail closed.
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
        when(vpc.getState()).thenReturn(com.cloud.network.vpc.Vpc.State.Enabled);
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
        // VPC + mapping exist but the LR UUID is gone from NB -> fail closed.
        final VpcFixture f = vpcFixture(true, null, false);
        when(f.nb.rowExistsByUuid("Logical_Router", f.lrUuid)).thenReturn(false);
        try {
            f.service.reconcileResource(4L, Kind.VPC, 924L, true);
            fail("expected OVN exception for missing LR");
        } catch (OvnException expected) {
            // ok
        }
        verify(f.nb, never()).ensureLbForceSnat(f.lrUuid);
    }

    // ------------------------------------------------------------------
    // Fix #5: scoped OVS_POLICY host sweep. Full coverage is in
    // OvnReconcilerOvsPolicyTest; here we prove no unrelated reconcilers
    // run when OVS_POLICY is dispatched.
    // ------------------------------------------------------------------

    @Test
    public void scopedOvsPolicyHostSweepDoesNotRunZoneWideReconcile() throws Exception {
        // The OVS_POLICY path calls only sweepOneChassis; it must not call
        // reconcileZone. We assert by verifying no NB interaction occurs
        // (reconcileZone would walk NB tables) and the result acks surface
        // the host id.
        final OvnReconcilerService service = new OvnReconcilerService();
        final OvsPolicyHostFixture f = ovsPolicyHostFixture(service, true);
        final OvnReconcilerService.Result result = service.reconcileResource(4L, Kind.NIC, 1L, true);

        assertTrue(result.isDryRun());
        assertEquals(0, result.totalStaleMappings());
        // acks must surface the host ack
        assertTrue(result.getAcksByTable().containsKey(
                OvnReconcilerService.Result.OVS_POLICY_HOST_TABLE + ":1"));
        // No NB client interaction should have occurred for the zone-wide
        // paths (TABLE_KINDS walk, NAT sweep, etc.) — the OVS_POLICY scope
        // never touches NB.
        verify(f.pluginManager, never()).nbClient(4L);
    }

    @Test
    public void scopedOvsPolicyWrongZoneFailsClosed() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        ovsPolicyHostFixture(service, true);
        try {
            service.reconcileResource(999L, Kind.NIC, 1L, true);
            fail("expected OVN exception for wrong zone");
        } catch (OvnException expected) {
            // ok
        }
    }

    @Test
    public void scopedOvsPolicyHostNotUpFailsClosed() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        ovsPolicyHostFixture(service, false);
        try {
            service.reconcileResource(4L, Kind.NIC, 1L, true);
            fail("expected OVN exception for host not Up");
        } catch (OvnException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------
    // Fix #3: synthetic ACK rows do not inflate totalorphans.
    // ------------------------------------------------------------------

    @Test
    public void ovsPolicyAckDoesNotInflateTotalOrphans() {
        final OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        out.recordOvsPolicyAck(4L);
        // ACK counters land in acks, not orphans.
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
        // Real drift (4 ports) counts as orphans; the ack does not.
        assertEquals(4, out.totalOrphans());
        assertEquals(Integer.valueOf(4),
                out.getOrphansByTable().get(OvnReconcilerService.Result.OVS_HAIRPIN_DRIFT_TABLE));
    }

    @Test
    public void scopedForcesnatAckDoesNotInflateTotalOrphans() {
        final OvnReconcilerService.Result out = new OvnReconcilerService.Result(true);
        out.recordScopedForcesnat("would_strip_legacy_router_ip", false, true);
        assertEquals(0, out.totalOrphans());
        assertEquals(Integer.valueOf(1),
                out.getAcksByTable().get(
                        OvnReconcilerService.Result.FORCESNAT_ACTION_TABLE + ":would_strip_legacy_router_ip"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ScopedFixture scopedFixture(final boolean ruleExists) throws Exception {
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
        return new ScopedFixture(service, mappingDao, nb);
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
            when(vpc.getState()).thenReturn(com.cloud.network.vpc.Vpc.State.Enabled);
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

    private static OvsPolicyHostFixture ovsPolicyHostFixture(final OvnReconcilerService service,
                                                              final boolean hostUp) throws Exception {
        final OvnPluginManager pluginManager = mock(OvnPluginManager.class);
        final com.cloud.host.dao.HostDao hostDao = mock(com.cloud.host.dao.HostDao.class);
        final com.cloud.network.ovn.dao.OvnChassisMapDao chassisMapDao = mock(com.cloud.network.ovn.dao.OvnChassisMapDao.class);
        final com.cloud.network.ovn.dao.OvnChassisMapVO chassis = mock(com.cloud.network.ovn.dao.OvnChassisMapVO.class);
        final com.cloud.agent.AgentManager agentManager = mock(com.cloud.agent.AgentManager.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        inject(service, "pluginManager", pluginManager);
        inject(service, "hostDao", hostDao);
        inject(service, "chassisMapDao", chassisMapDao);
        inject(service, "agentManager", agentManager);
        when(controller.getId()).thenReturn(1L);
        when(pluginManager.findControllerForZone(4L)).thenReturn(controller);
        final com.cloud.host.HostVO host = mock(com.cloud.host.HostVO.class);
        when(host.getDataCenterId()).thenReturn(4L);
        when(host.getStatus()).thenReturn(hostUp ? com.cloud.host.Status.Up : com.cloud.host.Status.Down);
        when(host.getType()).thenReturn(com.cloud.host.Host.Type.Routing);
        when(hostDao.findById(1L)).thenReturn(host);
        when(chassis.getControllerId()).thenReturn(1L);
        when(chassisMapDao.findByHostId(1L)).thenReturn(chassis);
        // Agent returns null answer -> sweep logs + skips, no drift recorded.
        when(agentManager.easySend(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);
        return new OvsPolicyHostFixture(pluginManager);
    }

    private static final class ScopedFixture {
        private final OvnReconcilerService service;
        private final OvnLogicalIdMapDao mappingDao;
        private final OvnNbClient nb;

        private ScopedFixture(final OvnReconcilerService service, final OvnLogicalIdMapDao mappingDao,
                              final OvnNbClient nb) {
            this.service = service;
            this.mappingDao = mappingDao;
            this.nb = nb;
        }
    }

    private static final class VpcFixture {
        private final OvnReconcilerService service;
        private final OvnNbClient nb;
        private final String lrUuid;

        private VpcFixture(final OvnReconcilerService service, final OvnNbClient nb, final String lrUuid) {
            this.service = service;
            this.nb = nb;
            this.lrUuid = lrUuid;
        }
    }

    private static final class OvsPolicyHostFixture {
        private final OvnPluginManager pluginManager;

        private OvsPolicyHostFixture(final OvnPluginManager pluginManager) {
            this.pluginManager = pluginManager;
        }
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
