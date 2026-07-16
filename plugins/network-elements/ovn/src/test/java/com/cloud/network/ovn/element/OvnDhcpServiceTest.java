package com.cloud.network.ovn.element;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;

import org.junit.Test;

import com.cloud.network.Network;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnControllerVO;

/** Regression coverage for DHCP option identity and reuse drift. */
public class OvnDhcpServiceTest {
    @Test
    public void dhcpOptionsHaveCorrectServerIdentity() throws Exception {
        final OvnDhcpService service = new OvnDhcpService();
        final DataCenterDao dataCenterDao = mock(DataCenterDao.class);
        final Field dc = OvnDhcpService.class.getDeclaredField("dataCenterDao");
        dc.setAccessible(true); dc.set(service, dataCenterDao);
        final Network network = mock(Network.class);
        when(network.getDataCenterId()).thenReturn(1L);
        when(dataCenterDao.findById(1L)).thenReturn(null);
        when(network.getGateway()).thenReturn("");
        when(network.getIp6Gateway()).thenReturn("2001:db8::1");
        final Method v4 = OvnDhcpService.class.getDeclaredMethod("buildDhcpv4Options", Network.class);
        final Method v6 = OvnDhcpService.class.getDeclaredMethod("buildDhcpv6Options", Network.class);
        v4.setAccessible(true); v6.setAccessible(true);
        final Map<String, String> v4Options = (Map<String, String>) v4.invoke(service, network);
        final Map<String, String> v6Options = (Map<String, String>) v6.invoke(service, network);
        assertFalse(v4Options.containsKey("server_id"));
        assertTrue(v6Options.get("server_id").matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}"));
    }

    @Test
    public void reusedDhcpRowIsResynchronizedWhenItsContentDrifts() throws Exception {
        final OvnDhcpService service = new OvnDhcpService();
        final OvnLogicalIdMapDao mapDao = mock(OvnLogicalIdMapDao.class);
        final OvnNbClient nb = mock(OvnNbClient.class);
        final OvnControllerVO controller = mock(OvnControllerVO.class);
        final DataCenterDao dataCenterDao = mock(DataCenterDao.class);
        final Network network = mock(Network.class);
        inject(service, "logicalIdMapDao", mapDao);
        inject(service, "dataCenterDao", dataCenterDao);
        when(network.getId()).thenReturn(7L);
        when(network.getGateway()).thenReturn("10.0.0.1");
        when(network.getCidr()).thenReturn("10.0.0.0/24");
        when(controller.getId()).thenReturn(9L);
        final OvnLogicalIdMapVO existing =
                new OvnLogicalIdMapVO(Kind.DHCP_OPTIONS, 7L, 9L, "dhcp-uuid", "dhcp-7");
        when(mapDao.findByCsId(Kind.DHCP_OPTIONS, 7L, 9L)).thenReturn(existing);
        when(nb.rowExistsByUuid("DHCP_Options", "dhcp-uuid")).thenReturn(true);

        final Method ensure = OvnDhcpService.class.getDeclaredMethod("ensureDhcpOptionsRow",
                OvnNbClient.class, OvnControllerVO.class, Network.class);
        ensure.setAccessible(true);
        assertTrue("existing mapping should be reused", "dhcp-uuid".equals(ensure.invoke(service, nb, controller, network)));
        verify(nb).updateDhcpOptions("dhcp-uuid", serviceOptions(service, network));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> serviceOptions(final OvnDhcpService service, final Network network)
            throws Exception {
        final Method v4 = OvnDhcpService.class.getDeclaredMethod("buildDhcpv4Options", Network.class);
        v4.setAccessible(true);
        return (Map<String, String>) v4.invoke(service, network);
    }

    private static void inject(final Object target, final String field, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
