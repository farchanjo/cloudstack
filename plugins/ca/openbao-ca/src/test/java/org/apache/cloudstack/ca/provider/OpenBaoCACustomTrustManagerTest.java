//
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
//

package org.apache.cloudstack.ca.provider;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.utils.security.CertUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.certificate.CrlVO;
import com.cloud.certificate.dao.CrlDao;

@RunWith(MockitoJUnitRunner.class)
public class OpenBaoCACustomTrustManagerTest {

    @Mock
    private CrlDao crlDao;
    private KeyPair rootKeyPair;
    private KeyPair intermediateKeyPair;
    private KeyPair clientKeypair;
    private X509Certificate rootCertificate;
    private X509Certificate intermediateCertificate;
    private List<X509Certificate> caChain;
    private X509Certificate expiredClientCertificate;
    private final String clientIp = "10.97.3.96";
    private final Map<String, X509Certificate> certMap = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        certMap.clear();
        rootKeyPair = CertUtils.generateRandomKeyPair(1024);
        intermediateKeyPair = CertUtils.generateRandomKeyPair(1024);
        clientKeypair = CertUtils.generateRandomKeyPair(1024);
        rootCertificate = CertUtils.generateV3Certificate(null, rootKeyPair, rootKeyPair.getPublic(), "CN=Slytherin Root CA", "SHA256withRSA", 365, null, null);
        intermediateCertificate = CertUtils.generateV3Certificate(rootCertificate, rootKeyPair, intermediateKeyPair.getPublic(), "CN=Slytherin Intermediate CA", "SHA256withRSA", 365, null, null);
        caChain = Arrays.asList(intermediateCertificate, rootCertificate);
        expiredClientCertificate = CertUtils.generateV3Certificate(intermediateCertificate, intermediateKeyPair, clientKeypair.getPublic(),
                "CN=host.slytherin", "SHA256withRSA", 0, Collections.singletonList("host.slytherin"), Collections.singletonList(clientIp));
    }

    @Test
    public void testNonStrictWithNullCertificatesDoesNotThrow() throws Exception {
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, false, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(null, null);
        Assert.assertFalse(certMap.containsKey(clientIp));
    }

    @Test
    public void testNonStrictWithRevokedCertDoesNotThrow() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(new CrlVO());
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, false, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{intermediateCertificate}, "RSA");
        Assert.assertEquals(intermediateCertificate, certMap.get(clientIp));
    }

    @Test
    public void testNonStrictWithOwnershipMismatchDoesNotThrow() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(null);
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, false, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{intermediateCertificate}, "RSA");
        Assert.assertEquals(intermediateCertificate, certMap.get(clientIp));
    }

    @Test(expected = CertificateException.class)
    public void testNonStrictWithExpiredCertNotAllowedThrows() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(null);
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, false, false, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{expiredClientCertificate}, "RSA");
    }

    @Test
    public void testNonStrictWithExpiredCertAllowedDoesNotThrow() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(null);
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, false, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{expiredClientCertificate}, "RSA");
        Assert.assertEquals(expiredClientCertificate, certMap.get(clientIp));
    }

    @Test(expected = CertificateException.class)
    public void testStrictWithNullCertificatesThrows() throws Exception {
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(null, null);
    }

    @Test(expected = CertificateException.class)
    public void testStrictWithRevokedCertThrows() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(new CrlVO());
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{intermediateCertificate}, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testStrictWithOwnershipMismatchThrows() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(null);
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{intermediateCertificate}, "RSA");
    }

    @Test(expected = CertificateException.class)
    public void testStrictWithExpiredCertNotAllowedThrows() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(null);
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, false, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{expiredClientCertificate}, "RSA");
    }

    @Test
    public void testStrictWithValidChainCertificateDoesNotThrow() throws Exception {
        Mockito.when(crlDao.findBySerial(Mockito.any(BigInteger.class))).thenReturn(null);
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, caChain, crlDao);
        trustManager.checkClientTrusted(new X509Certificate[]{expiredClientCertificate}, "RSA");
        Assert.assertEquals(expiredClientCertificate, certMap.get(clientIp));
    }

    @Test
    public void testGetAcceptedIssuersReturnsFullChain() {
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, caChain, crlDao);
        final X509Certificate[] issuers = trustManager.getAcceptedIssuers();
        Assert.assertEquals(2, issuers.length);
        Assert.assertEquals(intermediateCertificate, issuers[0]);
        Assert.assertEquals(rootCertificate, issuers[1]);
    }

    @Test
    public void testGetAcceptedIssuersNullChainReturnsEmptyArray() {
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, null, crlDao);
        Assert.assertEquals(0, trustManager.getAcceptedIssuers().length);
    }

    @Test
    public void testCheckServerTrustedIsNoOp() throws Exception {
        final OpenBaoCACustomTrustManager trustManager = new OpenBaoCACustomTrustManager(clientIp, true, true, certMap, caChain, crlDao);
        trustManager.checkServerTrusted(new X509Certificate[]{rootCertificate}, "RSA");
        Mockito.verifyNoInteractions(crlDao);
    }
}
