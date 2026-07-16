package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
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

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true); f.set(target, value);
    }
}
