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
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.framework.ca.Certificate;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.utils.security.CertUtils;
import org.bouncycastle.asn1.x509.GeneralName;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@RunWith(MockitoJUnitRunner.class)
public class OpenBaoCAProviderTest {

    private OpenBaoCAProvider provider;
    private OpenBaoClient client;

    private KeyPair caKeyPair;
    private X509Certificate caCertificate;
    private X509Certificate intermediateCertificate;
    private X509Certificate leafCertificate;
    private X509Certificate legacyCertificate;

    private Object originalLegacyTrustPem;

    @Before
    public void setUp() throws Exception {
        originalLegacyTrustPem = ReflectionTestUtils.getField(OpenBaoCAProvider.class, "openBaoLegacyTrustPem");
        caKeyPair = CertUtils.generateRandomKeyPair(1024);
        caCertificate = CertUtils.generateV3Certificate(null, caKeyPair, caKeyPair.getPublic(), "CN=Slytherin Root CA", "SHA256withRSA", 365, null, null);
        intermediateCertificate = CertUtils.generateV3Certificate(caCertificate, caKeyPair, caKeyPair.getPublic(), "CN=Slytherin Intermediate CA", "SHA256withRSA", 365, null, null);
        leafCertificate = CertUtils.generateV3Certificate(intermediateCertificate, caKeyPair, caKeyPair.getPublic(), "CN=host.slytherin", "SHA256withRSA", 365, null, null);

        final KeyPair legacyKeyPair = CertUtils.generateRandomKeyPair(1024);
        legacyCertificate = CertUtils.generateV3Certificate(null, legacyKeyPair, legacyKeyPair.getPublic(), "CN=ca.cloudstack.apache.org", "SHA256withRSA", 365, null, null);

        provider = new OpenBaoCAProvider();
        client = Mockito.mock(OpenBaoClient.class);
        ReflectionTestUtils.setField(provider, "client", client);
    }

    @After
    public void tearDown() {
        ReflectionTestUtils.setField(OpenBaoCAProvider.class, "openBaoLegacyTrustPem", originalLegacyTrustPem);
    }

    @SuppressWarnings("unchecked")
    private void setLegacyTrustPem(final String pem) {
        final ConfigKey<String> key = (ConfigKey<String>) Mockito.mock(ConfigKey.class);
        Mockito.when(key.value()).thenReturn(pem);
        ReflectionTestUtils.setField(OpenBaoCAProvider.class, "openBaoLegacyTrustPem", key);
    }

    private JsonObject wrapData(final JsonObject data) {
        final JsonObject response = new JsonObject();
        response.add("data", data);
        return response;
    }

    private JsonArray caChainArray() throws Exception {
        final JsonArray array = new JsonArray();
        array.add(CertUtils.x509CertificateToPem(intermediateCertificate));
        array.add(CertUtils.x509CertificateToPem(caCertificate));
        return array;
    }

    @Test
    public void testGetProviderName() {
        Assert.assertEquals("openbao", provider.getProviderName());
    }

    @Test
    public void testCanProvisionCertificates() {
        Assert.assertTrue(provider.canProvisionCertificates());
    }

    @Test
    public void testGetCaCertificateParsesFullChain() throws Exception {
        final JsonObject data = new JsonObject();
        data.addProperty("ca_chain", CertUtils.x509CertificateToPem(intermediateCertificate) + CertUtils.x509CertificateToPem(caCertificate));
        Mockito.when(client.get(ArgumentMatchers.contains("/cert/ca_chain"))).thenReturn(wrapData(data));

        final List<X509Certificate> chain = provider.getCaCertificate();
        Assert.assertEquals(2, chain.size());
        Assert.assertEquals(intermediateCertificate.getSubjectDN(), chain.get(0).getSubjectDN());
        Assert.assertEquals(caCertificate.getSubjectDN(), chain.get(1).getSubjectDN());
    }

    @Test
    public void testGetCaCertificateAppendsLegacyTrust() throws Exception {
        final JsonObject data = new JsonObject();
        data.addProperty("ca_chain", CertUtils.x509CertificateToPem(intermediateCertificate) + CertUtils.x509CertificateToPem(caCertificate));
        Mockito.when(client.get(ArgumentMatchers.contains("/cert/ca_chain"))).thenReturn(wrapData(data));
        setLegacyTrustPem(CertUtils.x509CertificateToPem(legacyCertificate));

        final List<X509Certificate> chain = provider.getCaCertificate();
        Assert.assertEquals("OpenBao chain (2) plus legacy CA (1)", 3, chain.size());
        Assert.assertEquals(intermediateCertificate.getSubjectDN(), chain.get(0).getSubjectDN());
        Assert.assertEquals(caCertificate.getSubjectDN(), chain.get(1).getSubjectDN());
        Assert.assertEquals(legacyCertificate.getSubjectDN(), chain.get(2).getSubjectDN());
    }

    @Test
    public void testGetCaCertificateEmptyLegacyTrustUnchanged() throws Exception {
        final JsonObject data = new JsonObject();
        data.addProperty("ca_chain", CertUtils.x509CertificateToPem(intermediateCertificate) + CertUtils.x509CertificateToPem(caCertificate));
        Mockito.when(client.get(ArgumentMatchers.contains("/cert/ca_chain"))).thenReturn(wrapData(data));
        setLegacyTrustPem("");

        final List<X509Certificate> chain = provider.getCaCertificate();
        Assert.assertEquals("Empty legacy trust must not change the chain", 2, chain.size());
        Assert.assertEquals(intermediateCertificate.getSubjectDN(), chain.get(0).getSubjectDN());
        Assert.assertEquals(caCertificate.getSubjectDN(), chain.get(1).getSubjectDN());
    }

    @Test
    public void testIssueCertificateWithCsrKeepsHostKey() throws Exception {
        final JsonObject data = new JsonObject();
        data.addProperty("certificate", CertUtils.x509CertificateToPem(leafCertificate));
        data.add("ca_chain", caChainArray());
        Mockito.when(client.post(ArgumentMatchers.contains("/sign/"), ArgumentMatchers.any(JsonObject.class))).thenReturn(wrapData(data));

        final Certificate certificate = provider.issueCertificate("-----BEGIN CERTIFICATE REQUEST-----\nMIIB\n-----END CERTIFICATE REQUEST-----",
                Arrays.asList("host.slytherin"), Arrays.asList("10.0.0.10"), 365);

        Assert.assertNotNull(certificate);
        Assert.assertNull("sign flow must not return a private key", certificate.getPrivateKey());
        Assert.assertEquals(leafCertificate.getSubjectDN(), certificate.getClientCertificate().getSubjectDN());
        Assert.assertEquals(2, certificate.getCaCertificates().size());
    }

    @Test
    public void testIssueCertificateWithoutCsrReturnsKey() throws Exception {
        final JsonObject data = new JsonObject();
        data.addProperty("certificate", CertUtils.x509CertificateToPem(leafCertificate));
        data.addProperty("private_key", CertUtils.privateKeyToPem(caKeyPair.getPrivate()));
        data.add("ca_chain", caChainArray());
        Mockito.when(client.post(ArgumentMatchers.contains("/issue/"), ArgumentMatchers.any(JsonObject.class))).thenReturn(wrapData(data));

        final Certificate certificate = provider.issueCertificate(Arrays.asList("host.slytherin"), Arrays.asList("10.0.0.10"), 365);

        Assert.assertNotNull(certificate);
        Assert.assertNotNull("issue flow must return a private key", certificate.getPrivateKey());
        Assert.assertEquals(leafCertificate.getSubjectDN(), certificate.getClientCertificate().getSubjectDN());
    }

    @Test
    public void testRevokeCertificate() {
        Mockito.when(client.post(ArgumentMatchers.contains("/revoke"), ArgumentMatchers.any(JsonObject.class))).thenReturn(new JsonObject());
        Assert.assertTrue(provider.revokeCertificate(new BigInteger("171"), "host.slytherin"));
    }

    @Test
    public void testRevokeCertificateNullSerial() {
        Assert.assertFalse(provider.revokeCertificate(null, "host.slytherin"));
    }

    @Test
    public void testFormatSerial() {
        // 171 = 0xAB -> "ab"; 43707 = 0xAABB -> "aa:bb"
        Assert.assertEquals("ab", provider.formatSerial(new BigInteger("171")));
        Assert.assertEquals("aa:bb", provider.formatSerial(new BigInteger("43707")));
    }

    @Test
    public void testIsManagementCertificateNotX509() throws CertificateParsingException {
        Assert.assertFalse(provider.isManagementCertificate(Mockito.mock(java.security.cert.Certificate.class)));
    }

    @Test
    public void testIsManagementCertificateMatch() throws CertificateParsingException {
        final String customSAN = "cloudstack.internal";
        ReflectionTestUtils.setField(provider, "managementCertificateCustomSAN", customSAN);
        final X509Certificate certificate = Mockito.mock(X509Certificate.class);
        final List<List<?>> altNames = new ArrayList<>();
        altNames.add(List.of(GeneralName.dNSName, customSAN));
        altNames.add(List.of(GeneralName.dNSName, UUID.randomUUID().toString()));
        final Collection<List<?>> collection = new ArrayList<>(altNames);
        Mockito.when(certificate.getSubjectAlternativeNames()).thenReturn(collection);
        Assert.assertTrue(provider.isManagementCertificate(certificate));
    }

    @Test
    public void testIsManagementCertificateNoMatch() throws CertificateParsingException {
        ReflectionTestUtils.setField(provider, "managementCertificateCustomSAN", "cloudstack.internal");
        final X509Certificate certificate = Mockito.mock(X509Certificate.class);
        final List<List<?>> altNames = new ArrayList<>();
        altNames.add(List.of(GeneralName.dNSName, UUID.randomUUID().toString()));
        final Collection<List<?>> collection = new ArrayList<>(altNames);
        Mockito.when(certificate.getSubjectAlternativeNames()).thenReturn(collection);
        Assert.assertFalse(provider.isManagementCertificate(certificate));
    }
}
