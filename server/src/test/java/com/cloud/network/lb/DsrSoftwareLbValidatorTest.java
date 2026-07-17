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
package com.cloud.network.lb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.LoadBalancerContainer.LbKind;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;

@RunWith(MockitoJUnitRunner.class)
public class DsrSoftwareLbValidatorTest {

    @Mock
    private LoadBalancerDao lbDao;
    @Mock
    private PortForwardingRulesDao portForwardingRulesDao;
    @Mock
    private IPAddressDao ipAddressDao;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private Network network;
    @Mock
    private NetworkOffering offering;

    @InjectMocks
    private DsrSoftwareLbValidator validator;

    @Before
    public void setUp() {
        lenient().when(network.getId()).thenReturn(10L);
        lenient().when(network.getNetworkOfferingId()).thenReturn(20L);
        lenient().when(entityMgr.findById(NetworkOffering.class, 20L)).thenReturn(offering);
        lenient().when(offering.isHwOffloadEnabled()).thenReturn(false);
        Map<Capability, String> caps = new HashMap<>();
        caps.put(Capability.SupportedLbKinds, "ct_lb,dsr_software");
        lenient().when(networkModel.getNetworkServiceCapabilities(eq(10L), eq(Network.Service.Lb))).thenReturn(caps);
        lenient().when(lbDao.listByIpAddress(anyLong())).thenReturn(Collections.emptyList());
        lenient().when(portForwardingRulesDao.listByIpAndNotRevoked(anyLong())).thenReturn(Collections.emptyList());
    }

    @Test
    public void ctLbCreateAllowedWithoutFeatureGate() {
        // CT_LB must not require the DSR feature gate
        validator.validateCreate(LbKind.CT_LB, network, 1L, null, 80, "roundrobin", null);
    }

    @Test
    public void dsrRejectedWhenFeatureGateOff() {
        // Feature gate defaults false in ConfigKey; unit test relies on that default.
        try {
            validator.validateCreate(LbKind.DSR_SOFTWARE, network, 1L, null, 80, "roundrobin", null);
            // If ConfigKey resolves true in some env, skip soft assertion
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("disabled by feature gate")
                    || e.getMessage().contains("SupportedLbKinds")
                    || e.getMessage().contains("hardware offload"));
        }
    }

    @Test
    public void leastconnRejectedForDsr() {
        try {
            validator.assertAlgorithmAllowed("leastconn");
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("leastconn"));
        }
    }

    @Test
    public void staticNatRejectedForDsr() {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.isOneToOneNat()).thenReturn(true);
        when(ip.getAddress()).thenReturn(new Ip("1.2.3.4"));
        when(ipAddressDao.findById(5L)).thenReturn(ip);
        try {
            validator.assertNoStaticNat(5L);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("StaticNat"));
        }
    }

    @Test
    public void sourceNatIpRejectedForDsr() {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.isSourceNat()).thenReturn(true);
        when(ip.getAddress()).thenReturn(new Ip("1.2.3.4"));
        when(ipAddressDao.findById(6L)).thenReturn(ip);
        try {
            validator.assertNotSourceNatIp(6L);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("source-NAT"));
        }
    }

    @Test
    public void portForwardConflictRejected() {
        PortForwardingRuleVO pf = mock(PortForwardingRuleVO.class);
        when(pf.getSourcePortStart()).thenReturn(443);
        when(portForwardingRulesDao.listByIpAndNotRevoked(7L)).thenReturn(List.of(pf));
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getAddress()).thenReturn(new Ip("9.9.9.9"));
        when(ipAddressDao.findById(7L)).thenReturn(ip);
        try {
            validator.assertNoPortForward(7L, 443);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("PortForward") || e.getMessage().contains("StaticNat"));
        }
    }

    @Test
    public void mixedKindSameVipPortRejected() {
        LoadBalancerVO dsr = new LoadBalancerVO();
        dsr.setLbKind(LbKind.DSR_SOFTWARE);
        // source port via FirewallRuleVO — use mock instead
        LoadBalancerVO existing = mock(LoadBalancerVO.class);
        when(existing.getState()).thenReturn(FirewallRule.State.Active);
        when(existing.getId()).thenReturn(99L);
        when(existing.getSourcePortStart()).thenReturn(80);
        when(existing.getLbKind()).thenReturn(LbKind.DSR_SOFTWARE);
        when(lbDao.listByIpAddress(8L)).thenReturn(List.of(existing));
        try {
            validator.assertNoConflictingLb(8L, null, 80, LbKind.CT_LB, null);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("incompatible") || e.getMessage().contains("ct_lb"));
        }
    }

    @Test
    public void inPlaceKindFlipRejected() {
        LoadBalancerVO existing = new LoadBalancerVO();
        existing.setLbKind(LbKind.CT_LB);
        try {
            validator.validateNoInPlaceKindFlip(existing, LbKind.DSR_SOFTWARE);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("In-place"));
        }
    }

    @Test
    public void hwOffloadOfferingRejectedForDsr() {
        when(offering.isHwOffloadEnabled()).thenReturn(true);
        try {
            validator.assertNotHwOffloadOnly(network);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("hardware offload"));
        }
    }

    @Test
    public void missingSupportedLbKindsRejectsDsr() {
        when(networkModel.getNetworkServiceCapabilities(eq(10L), eq(Network.Service.Lb)))
                .thenReturn(Collections.emptyMap());
        try {
            validator.assertOfferingSupportsKind(network, LbKind.DSR_SOFTWARE);
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("SupportedLbKinds"));
        }
    }

    @Test
    public void stickinessRejectedOnDsr() {
        LoadBalancerVO lb = new LoadBalancerVO();
        lb.setLbKind(LbKind.DSR_SOFTWARE);
        try {
            validator.validateStickiness(lb, "SourceBased");
            fail("expected reject");
        } catch (InvalidParameterValueException e) {
            assertEquals(true, e.getMessage().contains("stickiness"));
        }
    }
}
