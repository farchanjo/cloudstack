package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.vpc.NetworkACLItemDao;

public class OvnReconcilerServiceTest {
    @Test
    public void probesUseRealDaosAndNeverReapOrphanNic() throws Exception {
        final OvnReconcilerService service = new OvnReconcilerService();
        final NetworkACLItemDao acl = mock(NetworkACLItemDao.class);
        final FirewallRulesDao firewall = mock(FirewallRulesDao.class);
        final LoadBalancerDao lb = mock(LoadBalancerDao.class);
        final NetworkDao network = mock(NetworkDao.class);
        inject(service, "networkACLItemDao", acl);
        inject(service, "firewallRulesDao", firewall);
        inject(service, "loadBalancerDao", lb);
        inject(service, "networkDao", network);
        when(acl.findById(1L)).thenReturn(mock(com.cloud.network.vpc.NetworkACLItemVO.class));
        when(firewall.findById(2L)).thenReturn(mock(com.cloud.network.rules.FirewallRuleVO.class));
        when(lb.findById(3L)).thenReturn(mock(com.cloud.network.dao.LoadBalancerVO.class));
        when(lb.findById(4L)).thenReturn(mock(com.cloud.network.dao.LoadBalancerVO.class));
        final Method probe = OvnReconcilerService.class.getDeclaredMethod("cloudstackEntityExists", Kind.class, long.class);
        probe.setAccessible(true);
        assertTrue((Boolean) probe.invoke(service, Kind.NETWORK_ACL, 1L));
        assertTrue((Boolean) probe.invoke(service, Kind.FIREWALL, 2L));
        assertTrue((Boolean) probe.invoke(service, Kind.LOAD_BALANCER, 3L));
        assertTrue((Boolean) probe.invoke(service, Kind.PORT_FORWARDING, 4L));
        assertTrue((Boolean) probe.invoke(service, Kind.ORPHAN_NIC, 999999L));
        verify(acl).findById(1L); verify(firewall).findById(2L); verify(lb).findById(3L); verify(lb).findById(4L);
    }

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
        doThrow(new com.cloud.network.ovn.client.OvnException("delete failed"))
                .when(fixture.nb).deleteLoadBalancer("ovn-lb-1473");

        try {
            fixture.service.reconcileResource(4L, Kind.LOAD_BALANCER, 1473L, false);
            fail("expected OVN delete failure");
        } catch (com.cloud.network.ovn.client.OvnException expected) {
            assertEquals("delete failed", expected.getMessage());
        }
        verify(fixture.mappingDao, never()).remove(11910L);
    }

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

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true); f.set(target, value);
    }
}
