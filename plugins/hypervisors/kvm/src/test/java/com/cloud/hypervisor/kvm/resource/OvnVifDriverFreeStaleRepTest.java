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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.script.Script;

/**
 * Residual Chaos-B heal: FREE VF representors that still carry
 * {@code external_ids:iface-id} must be freed; ALLOCATED (vfio / vDPA) must
 * never be touched.
 */
public class OvnVifDriverFreeStaleRepTest {

    private static final Logger LOG = LogManager.getLogger(OvnVifDriverFreeStaleRepTest.class);

    @Test
    public void isSafeToFreeStaleRep_freeOnMlx5_isTrue() {
        assertTrue(OvnVifDriver.isSafeToFreeStaleRep("mlx5_core", false));
        assertTrue("unbound VF is FREE", OvnVifDriver.isSafeToFreeStaleRep(null, false));
    }

    @Test
    public void isSafeToFreeStaleRep_allocatedPassthrough_isFalse() {
        assertFalse(OvnVifDriver.isSafeToFreeStaleRep("vfio-pci", false));
    }

    @Test
    public void isSafeToFreeStaleRep_allocatedVdpa_isFalse() {
        // vDPA keeps VF on mlx5_core — FREE check must use hasVdpa, not driver alone.
        assertFalse(OvnVifDriver.isSafeToFreeStaleRep("mlx5_core", true));
        assertFalse(OvnVifDriver.isSafeToFreeStaleRep("vfio-pci", true));
    }

    @Test
    public void parseOvsIfaceNames_stripsQuotesAndBlanks() {
        final String raw = "\"dx6p0vf4\"\n\n  dx6p1vf6  \nvnet101\n";
        final List<String> names = OvnVifDriver.parseOvsIfaceNames(raw);
        assertEquals(3, names.size());
        assertEquals("dx6p0vf4", names.get(0));
        assertEquals("dx6p1vf6", names.get(1));
        assertEquals("vnet101", names.get(2));
    }

    @Test
    public void parseOvsIfaceNames_blankOrNull_isEmpty() {
        assertTrue(OvnVifDriver.parseOvsIfaceNames(null).isEmpty());
        assertTrue(OvnVifDriver.parseOvsIfaceNames("").isEmpty());
        assertTrue(OvnVifDriver.parseOvsIfaceNames("  \n\n ").isEmpty());
    }

    @Test
    public void parseVdpaDevShowPci_keepsAllLines_notJustFirst() {
        // Regression: Script.runSimpleBashScript is OneLineParser — freeStale
        // used to only see the first vDPA PCI and del-port the rest (live CKS).
        final String multi = "vdpa-r1: type network mgmtdev pci/0000:01:00.3 max_vqs 33\n"
                + "vdpa-r2: type network mgmtdev pci/0000:01:04.2 vendor_id 5555\n"
                + "vdpa-r3: type network mgmtdev pci/0000:01:01.0\n";
        final Set<String> pci = OvnVifDriver.parseVdpaDevShowPci(multi);
        assertEquals(3, pci.size());
        assertTrue(pci.contains("0000:01:00.3"));
        assertTrue(pci.contains("0000:01:04.2"));
        assertTrue(pci.contains("0000:01:01.0"));
    }

    @Test
    public void parseVdpaDevShowPci_blank_isEmpty() {
        assertTrue(OvnVifDriver.parseVdpaDevShowPci(null).isEmpty());
        assertTrue(OvnVifDriver.parseVdpaDevShowPci("").isEmpty());
        assertTrue(OvnVifDriver.parseVdpaDevShowPci("no mgmtdev here\n").isEmpty());
    }

    @Test
    public void listVdpaMgmtPciFromCli_usesFullResult_notOneLine() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa"), anyInt()))
                    .thenReturn("{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"},"
                            + "\"vdpa-r2\":{\"mgmtdev\":\"pci/0000:01:04.2\"}}}");

            final Set<String> pci = OvnVifDriver.listVdpaMgmtPciFromCli(LOG);
            assertEquals(2, pci.size());
            assertTrue(pci.contains("0000:01:00.3"));
            assertTrue(pci.contains("0000:01:04.2"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void strictVdpaInventory_rejectsMalformedJson() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenReturn("not-json");
            OvnVifDriver.listVdpaDevicesStrict();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void strictVdpaInventory_propagatesUnavailableCli() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenThrow(new IllegalStateException("vdpa unavailable"));
            OvnVifDriver.listVdpaDevicesStrict();
        }
    }

    @Test
    public void strictVdpaDelete_requiresFreshInventoryPostcondition() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenReturn("{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}",
                            "{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}");
            OvnVifDriver.deleteVdpaDevsForPciStrict(LOG, "test", "0000:01:00.3");
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("surviving vDPA device must fail strict cleanup");
    }

    @Test
    public void strictVdpaDelete_abortsOnPartialMultiDeviceFailure() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenReturn("{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"},"
                            + "\"vdpa-r2\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("vdpa dev del vdpa-r1")))
                    .thenReturn("");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("vdpa dev del vdpa-r2")))
                    .thenThrow(new IllegalStateException("delete failed"));
            try {
                OvnVifDriver.deleteVdpaDevsForPciStrict(LOG, "test", "0000:01:00.3");
            } catch (IllegalStateException expected) {
                return;
            }
        }
        throw new AssertionError("partial vDPA deletion must fail");
    }

    @Test
    public void freeStaleCasFailureDeletesVdpaButDoesNotClearVfOrIncrementFreed() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("find Interface"), anyInt()))
                    .thenReturn("rep0\n");
            driverMock.when(() -> OvnVifDriver.isVfRepresentor("rep0")).thenReturn(true);
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("rep0"))
                    .thenReturn("0000:01:00.3");
            driverMock.when(() -> OvnVifDriver.readPciDriver("0000:01:00.3"))
                    .thenReturn("mlx5_core");
            driverMock.when(() -> OvnVifDriver.listVdpaMgmtPciAddresses(LOG))
                    .thenReturn(Collections.singleton("0000:01:00.3"));
            driverMock.when(OvnVifDriver::collectLiveDomainMacs).thenReturn(Collections.emptySet());
            driverMock.when(() -> OvnVifDriver.readAttachedMac("rep0"))
                    .thenReturn("aa:bb:cc:dd:ee:ff");
            stubVfIdentity(vfMock);
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenReturn("{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}",
                            "{\"dev\":{}}");
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("rep0")))
                    .thenReturn("lsp-1");
            casMock.when(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), eq("lsp-1")))
                    .thenReturn(false);

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(0, result.freed);
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("vdpa dev del")));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), never());
        }
    }

    @Test
    public void freeStaleVdpaDeletionFailureLeavesOvsAndVfUntouched() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("find Interface"), anyInt()))
                    .thenReturn("rep0\n");
            driverMock.when(() -> OvnVifDriver.isVfRepresentor("rep0")).thenReturn(true);
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("rep0"))
                    .thenReturn("0000:01:00.3");
            driverMock.when(() -> OvnVifDriver.readPciDriver("0000:01:00.3"))
                    .thenReturn("mlx5_core");
            driverMock.when(() -> OvnVifDriver.listVdpaMgmtPciAddresses(LOG))
                    .thenReturn(Collections.singleton("0000:01:00.3"));
            driverMock.when(OvnVifDriver::collectLiveDomainMacs).thenReturn(Collections.emptySet());
            driverMock.when(() -> OvnVifDriver.readAttachedMac("rep0"))
                    .thenReturn("aa:bb:cc:dd:ee:ff");
            stubVfIdentity(vfMock);
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenReturn("{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("vdpa dev del")))
                    .thenThrow(new IllegalStateException("delete failed"));

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(0, result.freed);
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), anyString()), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), never());
        }
    }

    @Test
    public void freeStaleVdpaVerificationFailureLeavesOvsAndVfUntouched() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("find Interface"), anyInt()))
                    .thenReturn("rep0\n");
            driverMock.when(() -> OvnVifDriver.isVfRepresentor("rep0")).thenReturn(true);
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("rep0"))
                    .thenReturn("0000:01:00.3");
            driverMock.when(() -> OvnVifDriver.readPciDriver("0000:01:00.3"))
                    .thenReturn("mlx5_core");
            driverMock.when(() -> OvnVifDriver.listVdpaMgmtPciAddresses(LOG))
                    .thenReturn(Collections.singleton("0000:01:00.3"));
            driverMock.when(OvnVifDriver::collectLiveDomainMacs).thenReturn(Collections.emptySet());
            driverMock.when(() -> OvnVifDriver.readAttachedMac("rep0"))
                    .thenReturn("aa:bb:cc:dd:ee:ff");
            stubVfIdentity(vfMock);
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenReturn("{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}",
                            "{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}");
            scriptMock.when(() -> Script.runSimpleBashScript(contains("vdpa dev del"))).thenReturn("");

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(0, result.freed);
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), anyString()), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), never());
        }
    }

    @Test
    public void freeStaleMissingPfTargetLeavesOvsAndVfUntouched() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            stubOrphanCandidate(scriptMock, driverMock, casMock);
            vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf("0000:01:00.3")).thenReturn(null);
            vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci("0000:01:00.3")).thenReturn(3);

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(0, result.freed);
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), anyString()), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), never());
        }
    }

    @Test
    public void freeStaleIdentityClearFailureDoesNotReportSuccess() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            stubOrphanCandidate(scriptMock, driverMock, casMock);
            stubVfIdentity(vfMock);
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("rep0")))
                    .thenReturn("lsp-1");
            casMock.when(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), eq("lsp-1")))
                    .thenReturn(true);
            scriptMock.when(() -> Script.runSimpleBashScript(contains("ip link set")))
                    .thenThrow(new IllegalStateException("identity clear failed"));

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(0, result.freed);
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), eq("lsp-1")));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")));
        }
    }

    @Test
    public void freeStaleHoldsBdfLockThroughVdpaCasAndIdentityClear() {
        final String bdf = "0000:01:00.3";
        final java.util.concurrent.locks.ReentrantLock lock = VfHostLifecycleLock.forBdf(bdf);
        final List<String> order = new ArrayList<>();
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("find Interface"), anyInt()))
                    .thenReturn("rep0\n");
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenAnswer(invocation -> {
                        assertTrue(lock.isHeldByCurrentThread());
                        order.add("inventory");
                        return order.size() == 1
                                ? "{\"dev\":{\"vdpa-r1\":{\"mgmtdev\":\"pci/0000:01:00.3\"}}}"
                                : "{\"dev\":{}}";
                    });
            driverMock.when(() -> OvnVifDriver.isVfRepresentor("rep0")).thenReturn(true);
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("rep0"))
                    .thenReturn(bdf);
            driverMock.when(() -> OvnVifDriver.readPciDriver(bdf)).thenReturn("mlx5_core");
            driverMock.when(OvnVifDriver::collectLiveDomainMacs).thenReturn(Collections.emptySet());
            driverMock.when(() -> OvnVifDriver.readAttachedMac("rep0"))
                    .thenReturn("aa:bb:cc:dd:ee:ff");
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("rep0")))
                    .thenReturn("lsp-1");
            casMock.when(() -> OvsRepresentorCas.remove(any(), anyString(), eq("rep0"), eq("lsp-1")))
                    .thenAnswer(invocation -> {
                        assertTrue(lock.isHeldByCurrentThread());
                        order.add("cas");
                        return true;
                    });
            vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf(bdf)).thenReturn("pf0");
            vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci(bdf)).thenReturn(3);
            scriptMock.when(() -> Script.runSimpleBashScript(contains("vdpa dev del")))
                    .thenAnswer(invocation -> {
                        assertTrue(lock.isHeldByCurrentThread());
                        order.add("delete");
                        return "";
                    });
            scriptMock.when(() -> Script.runSimpleBashScript(contains("ip link set")))
                    .thenAnswer(invocation -> {
                        assertTrue(lock.isHeldByCurrentThread());
                        order.add("identity");
                        return "";
                    });

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(1, result.freed);
            assertEquals(List.of("inventory", "delete", "inventory", "cas", "identity"), order);
        }
    }

    @Test
    public void freeStaleOwnershipAppearingDuringLockedRevalidationPreventsMutation() {
        final String bdf = "0000:01:00.3";
        final java.util.concurrent.locks.ReentrantLock lock = VfHostLifecycleLock.forBdf(bdf);
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("find Interface"), anyInt()))
                    .thenReturn("rep0\n");
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                    .thenAnswer(invocation -> {
                        assertTrue(lock.isHeldByCurrentThread());
                        return "{\"dev\":{}}";
                    });
            driverMock.when(() -> OvnVifDriver.isVfRepresentor("rep0")).thenReturn(true);
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("rep0"))
                    .thenReturn(bdf);
            driverMock.when(() -> OvnVifDriver.readPciDriver(bdf)).thenReturn("mlx5_core");
            driverMock.when(OvnVifDriver::collectLiveDomainMacs).thenAnswer(invocation -> {
                assertTrue(lock.isHeldByCurrentThread());
                return Collections.singleton("aa:bb:cc:dd:ee:ff");
            });
            driverMock.when(() -> OvnVifDriver.readAttachedMac("rep0"))
                    .thenReturn("aa:bb:cc:dd:ee:ff");

            final OvnVifDriver.FreeStaleOvsResult result =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(0, result.freed);
            assertEquals(1, result.skippedAllocated);
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), anyString(), anyString()), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("vdpa dev del")), never());
        }
    }

    /**
     * End-to-end freeStale with mocked ovs + no real sysfs representors:
     * candidate list comes from ovs-vsctl, but isVfRepresentor returns false
     * for every name (no phys_port_name in the test env) so nothing is freed
     * and clear/del-port is never issued — proves we never free vnet taps by
     * name alone.
     */
    @Test
    public void freeStaleFreeVfRepresentors_skipsNonRepresentors() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface"), anyInt()))
                             .thenReturn("vnet101\nunit-test-vf4\n");
            // empty CLI + empty sysfs (no /sys in unit env) → no vDPA set
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa"), anyInt()))
                    .thenReturn("{\"dev\":{}}");
            scriptMock.when(() -> Script.runSimpleBashScript(anyString())).thenReturn("");

            final OvnVifDriver.FreeStaleOvsResult r =
                    OvnVifDriver.freeStaleFreeVfRepresentors(LOG, "test", false);

            assertEquals(2, r.scanned);
            assertEquals(0, r.freed);
            assertTrue("without sysfs phys_port_name every iface is non-rep",
                    r.skippedNonRep >= 2);
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("clear Interface")), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("del-port")), never());
        }
    }

    @Test
    public void freeRepresentorOnOvs_unknownBdfFailsClosed() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
            scriptMock.when(() -> Script.runSimpleBashScript(anyString())).thenReturn("");
            OvnVifDriver.freeRepresentorOnOvs(LOG, "test", "dx6p0vf9");
        }
    }

    @Test
    public void parseMacAddressesFromDomainXml_extractsAllMacs() {
        final String xml = "<domain>\n"
                + "  <interface type='bridge'>\n"
                + "    <mac address='AA:BB:CC:DD:EE:01'/>\n"
                + "  </interface>\n"
                + "  <interface type='vdpa'>\n"
                + "    <mac address=\"02:04:02:2e:00:01\"/>\n"
                + "  </interface>\n"
                + "</domain>\n";
        final Set<String> macs = OvnVifDriver.parseMacAddressesFromDomainXml(xml);
        assertEquals(2, macs.size());
        assertTrue(macs.contains("aa:bb:cc:dd:ee:01"));
        assertTrue(macs.contains("02:04:02:2e:00:01"));
    }

    @Test
    public void parseMacAddressesFromDomainXml_blank_isEmpty() {
        assertTrue(OvnVifDriver.parseMacAddressesFromDomainXml(null).isEmpty());
        assertTrue(OvnVifDriver.parseMacAddressesFromDomainXml("").isEmpty());
        assertTrue(OvnVifDriver.parseMacAddressesFromDomainXml("<domain/>").isEmpty());
    }

    /**
     * clearOrphanRepsByAttachedMac must free every rep line returned by
     * ovs-vsctl find — regression against OneLineParser dropping multi-rep.
     */
    @Test
    public void clearOrphanRepsByAttachedMac_freesAllFoundReps() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("dx6p0vf4\ndx6p1vf6\n");
            scriptMock.when(() -> Script.runSimpleBashScript(anyString())).thenReturn("");
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("dx6p0vf4"))
                    .thenReturn("0000:01:00.4");
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("dx6p1vf6"))
                    .thenReturn("0000:01:00.6");
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("dx6p0vf4")))
                    .thenReturn("lsp-4");
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("dx6p1vf6")))
                    .thenReturn("lsp-6");
            casMock.when(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p0vf4"), eq("lsp-4")))
                    .thenReturn(true);
            casMock.when(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p1vf6"), eq("lsp-6")))
                    .thenReturn(true);
            vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf("0000:01:00.4"))
                    .thenReturn("pf4");
            vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci("0000:01:00.4"))
                    .thenReturn(4);
            vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf("0000:01:00.6"))
                    .thenReturn("pf6");
            vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci("0000:01:00.6"))
                    .thenReturn(6);

            assertTrue(OvnVifDriver.clearOrphanRepsByAttachedMac(
                    LOG, "test", "br-overlay", "aa:bb:cc:dd:ee:ff"));

            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p0vf4"), eq("lsp-4")));
            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p1vf6"), eq("lsp-6")));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), times(2));
        }
    }

    @Test
    public void clearOrphanRepsByAttachedMac_missingPfFailsClosed() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("dx6p0vf4\n");
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("dx6p0vf4"))
                    .thenReturn("0000:01:00.4");
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("dx6p0vf4")))
                    .thenReturn("lsp-4");
            vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf("0000:01:00.4"))
                    .thenReturn(null);
            vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci("0000:01:00.4"))
                    .thenReturn(4);

            assertFalse(OvnVifDriver.clearOrphanRepsByAttachedMac(
                    LOG, "test", "br-overlay", "aa:bb:cc:dd:ee:ff"));

            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p0vf4"), eq("lsp-4")), never());
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")), never());
        }
    }

    @Test
    public void clearOrphanRepsByAttachedMac_identityFailureReportsPartialCleanup() {
        try (MockedStatic<Script> scriptMock = mockStatic(Script.class);
             MockedStatic<OvnVifDriver> driverMock = mockStatic(OvnVifDriver.class, CALLS_REAL_METHODS);
             MockedStatic<OvsRepresentorCas> casMock = mockStatic(OvsRepresentorCas.class);
             MockedStatic<VfPassthroughVifDriver> vfMock = mockStatic(VfPassthroughVifDriver.class)) {
            scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(
                            contains("find Interface external_ids:attached-mac"), anyInt()))
                    .thenReturn("dx6p0vf4\n");
            driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("dx6p0vf4"))
                    .thenReturn("0000:01:00.4");
            casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("dx6p0vf4")))
                    .thenReturn("lsp-4");
            casMock.when(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p0vf4"), eq("lsp-4")))
                    .thenReturn(true);
            vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf("0000:01:00.4"))
                    .thenReturn("pf4");
            vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci("0000:01:00.4"))
                    .thenReturn(4);
            scriptMock.when(() -> Script.runSimpleBashScript(contains("ip link set")))
                    .thenThrow(new IllegalStateException("identity clear failed"));

            assertFalse(OvnVifDriver.clearOrphanRepsByAttachedMac(
                    LOG, "test", "br-overlay", "aa:bb:cc:dd:ee:ff"));

            casMock.verify(() -> OvsRepresentorCas.remove(any(), anyString(), eq("dx6p0vf4"), eq("lsp-4")));
            scriptMock.verify(() -> Script.runSimpleBashScript(contains("ip link set")));
        }
    }

    private static void stubOrphanCandidate(final MockedStatic<Script> scriptMock,
                                            final MockedStatic<OvnVifDriver> driverMock,
                                            final MockedStatic<OvsRepresentorCas> casMock) {
        scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("find Interface"), anyInt()))
                .thenReturn("rep0\n");
        scriptMock.when(() -> Script.runSimpleBashScriptWithFullResult(contains("vdpa dev show -j"), anyInt()))
                .thenReturn("{\"dev\":{}}");
        driverMock.when(() -> OvnVifDriver.isVfRepresentor("rep0")).thenReturn(true);
        driverMock.when(() -> OvnVifDriver.resolveVfPciFromRepresentor("rep0"))
                .thenReturn("0000:01:00.3");
        driverMock.when(() -> OvnVifDriver.readPciDriver("0000:01:00.3"))
                .thenReturn("mlx5_core");
        driverMock.when(() -> OvnVifDriver.listVdpaMgmtPciAddresses(LOG))
                .thenReturn(Collections.emptySet());
        driverMock.when(OvnVifDriver::collectLiveDomainMacs).thenReturn(Collections.emptySet());
        driverMock.when(() -> OvnVifDriver.readAttachedMac("rep0"))
                .thenReturn("aa:bb:cc:dd:ee:ff");
        casMock.when(() -> OvsRepresentorCas.readIfaceId(any(), anyString(), eq("rep0")))
                .thenReturn("lsp-1");
    }

    private static void stubVfIdentity(final MockedStatic<VfPassthroughVifDriver> vfMock) {
        vfMock.when(() -> VfPassthroughVifDriver.lookupPfFromVf("0000:01:00.3"))
                .thenReturn("pf0");
        vfMock.when(() -> VfPassthroughVifDriver.lookupVfIdFromPci("0000:01:00.3"))
                .thenReturn(3);
    }
}
