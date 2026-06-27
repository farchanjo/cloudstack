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

package org.apache.cloudstack.ca.provider;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.framework.ca.CAProvider;
import org.apache.cloudstack.framework.ca.Certificate;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.utils.security.CertUtils;
import org.apache.cloudstack.utils.security.KeyStoreUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.x509.GeneralName;

import com.cloud.certificate.dao.CrlDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.google.gson.JsonObject;

/**
 * CA provider that delegates the certificate lifecycle (sign/issue/revoke and
 * the served CA chain) to an OpenBao/Vault PKI secrets engine, authenticating
 * via AppRole. Host private keys never leave the host: agent enrolment uses the
 * CSR-based {@code sign} endpoint. The management-server self certificate uses
 * the {@code issue} endpoint so OpenBao returns the private key for the keystore.
 */
public final class OpenBaoCAProvider extends AdapterBase implements CAProvider, Configurable {

    public static final String PROVIDER_NAME = "openbao";
    public static final String caAlias = "root";
    public static final String managementAlias = "management";

    @Inject
    private CrlDao crlDao;

    private static ConfigKey<String> openBaoUrl = new ConfigKey<>("Secure", String.class,
            "ca.plugin.openbao.url",
            null,
            "The OpenBao/Vault API base URL, e.g. https://openbao.example.org:8200", true);

    private static ConfigKey<String> openBaoMount = new ConfigKey<>("Advanced", String.class,
            "ca.plugin.openbao.mount",
            "pki_int",
            "The OpenBao PKI secrets-engine mount path used for issuance.", true);

    private static ConfigKey<String> openBaoSignRole = new ConfigKey<>("Advanced", String.class,
            "ca.plugin.openbao.sign.role",
            "cloudstack",
            "The OpenBao PKI role used to sign agent/host CSRs.", true);

    private static ConfigKey<String> openBaoIssueRole = new ConfigKey<>("Advanced", String.class,
            "ca.plugin.openbao.issue.role",
            "cloudstack",
            "The OpenBao PKI role used to issue the management-server certificate.", true);

    private static ConfigKey<String> openBaoAppRoleId = new ConfigKey<>("Secure", String.class,
            "ca.plugin.openbao.approle.roleid",
            null,
            "The OpenBao AppRole role_id used for authentication.", true);

    private static ConfigKey<String> openBaoAppRoleSecretId = new ConfigKey<>("Hidden", String.class,
            "ca.plugin.openbao.approle.secretid",
            null,
            "The OpenBao AppRole secret_id used for authentication.", true);

    private static ConfigKey<Boolean> openBaoTlsSkipVerify = new ConfigKey<>("Advanced", Boolean.class,
            "ca.plugin.openbao.tls.skipverify",
            "false",
            "When true, the OpenBao client skips TLS verification (do not use in production).", true);

    private static ConfigKey<Long> openBaoCaChainCacheTtl = new ConfigKey<>("Advanced", Long.class,
            "ca.plugin.openbao.cachain.cache.ttl",
            "3600",
            "The cache TTL in seconds for the OpenBao CA chain.", true);

    protected static ConfigKey<Boolean> openBaoAuthStrictness = new ConfigKey<>("Advanced", Boolean.class,
            "ca.plugin.openbao.auth.strictness",
            "false",
            "Set client authentication strictness; when true a valid client certificate is required.", true);

    private static ConfigKey<Boolean> openBaoAllowExpiredCert = new ConfigKey<>("Advanced", Boolean.class,
            "ca.plugin.openbao.allow.expired.cert",
            "true",
            "When true, expired client certificates are allowed during the SSL handshake.", true);

    private static ConfigKey<String> openBaoLegacyTrustPem = new ConfigKey<>("Advanced", String.class,
            "ca.plugin.openbao.legacy.trust.pem",
            "",
            "Zero or more PEM CA certificates to additionally serve as trusted CAs during the migration "
                    + "(e.g. the legacy CloudStack root CA). Appended to the OpenBao CA chain; empty disables it.", true);

    private static String managementCertificateCustomSAN;

    private OpenBaoClient client;
    private List<X509Certificate> cachedCaChain;
    private long cachedCaChainExpiry;
    private KeyStore managementKeyStore;

    ///////////////////////////////////////////////////////////
    /////////////// OpenBao Issuance Helpers ///////////////////
    ///////////////////////////////////////////////////////////

    private OpenBaoClient getClient() {
        if (client == null) {
            client = new OpenBaoClient(openBaoUrl.value(), openBaoAppRoleId.value(),
                    openBaoAppRoleSecretId.value(), openBaoTlsSkipVerify.value());
        }
        return client;
    }

    private String ttl(final int validityDays) {
        return validityDays + "d";
    }

    private String csv(final List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        final List<String> nonEmpty = new ArrayList<>();
        for (final String value : values) {
            if (StringUtils.isNotEmpty(value)) {
                nonEmpty.add(value);
            }
        }
        return nonEmpty.isEmpty() ? null : String.join(",", nonEmpty);
    }

    private List<X509Certificate> parseChain(final String pem) {
        final List<X509Certificate> certificates = new ArrayList<>();
        if (StringUtils.isEmpty(pem)) {
            return certificates;
        }
        final String marker = "-----BEGIN CERTIFICATE-----";
        final String[] blocks = pem.split("(?=" + marker + ")");
        for (final String block : blocks) {
            if (!block.contains(marker)) {
                continue;
            }
            try {
                certificates.add(CertUtils.pemToX509Certificate(block.trim()));
            } catch (final CertificateException | IOException e) {
                throw new CloudRuntimeException("Failed to parse certificate returned by OpenBao", e);
            }
        }
        return certificates;
    }

    private List<X509Certificate> chainFromResponse(final JsonObject data) {
        final List<X509Certificate> chain = new ArrayList<>();
        if (data.has("ca_chain")) {
            for (final var element : data.getAsJsonArray("ca_chain")) {
                chain.addAll(parseChain(element.getAsString()));
            }
        } else if (data.has("issuing_ca")) {
            chain.addAll(parseChain(data.get("issuing_ca").getAsString()));
        }
        return chain;
    }

    ////////////////////////////////////////////////////////
    /////////////// OpenBao API Handlers ///////////////////
    ////////////////////////////////////////////////////////

    @Override
    public boolean canProvisionCertificates() {
        return true;
    }

    @Override
    public synchronized List<X509Certificate> getCaCertificate() {
        final long now = System.currentTimeMillis();
        if (cachedCaChain != null && now < cachedCaChainExpiry) {
            return cachedCaChain;
        }
        final JsonObject response = getClient().get(String.format("/v1/%s/cert/ca_chain", openBaoMount.value()));
        final JsonObject data = response.getAsJsonObject("data");
        if (data == null || !data.has("ca_chain")) {
            throw new CloudRuntimeException("OpenBao did not return a CA chain for mount " + openBaoMount.value());
        }
        final List<X509Certificate> chain = parseChain(data.get("ca_chain").getAsString());
        final String legacyPem = openBaoLegacyTrustPem.value();
        if (StringUtils.isNotEmpty(legacyPem)) {
            // Additively trust the legacy CloudStack CA during migration until all leaves rotate.
            chain.addAll(parseChain(legacyPem));
        }
        cachedCaChain = chain;
        cachedCaChainExpiry = now + (openBaoCaChainCacheTtl.value() * 1000L);
        return cachedCaChain;
    }

    @Override
    public Certificate issueCertificate(final List<String> domainNames, final List<String> ipAddresses, final int validityDays) {
        if (CollectionUtils.isEmpty(domainNames) || StringUtils.isEmpty(domainNames.get(0))) {
            throw new CloudRuntimeException("No domain name is specified, cannot issue certificate");
        }
        final Map<String, String> body = new LinkedHashMap<>();
        body.put("common_name", domainNames.get(0));
        body.put("alt_names", csv(domainNames));
        body.put("ip_sans", csv(ipAddresses));
        body.put("ttl", ttl(validityDays));
        final JsonObject response = getClient().post(
                String.format("/v1/%s/issue/%s", openBaoMount.value(), openBaoIssueRole.value()),
                OpenBaoClient.buildBody(body));
        return certificateFromIssueResponse(response);
    }

    @Override
    public Certificate issueCertificate(final String csr, final List<String> domainNames, final List<String> ipAddresses, final int validityDays) {
        if (StringUtils.isEmpty(csr)) {
            throw new CloudRuntimeException("No CSR provided, cannot sign certificate");
        }
        final Map<String, String> body = new LinkedHashMap<>();
        body.put("csr", csr);
        if (CollectionUtils.isNotEmpty(domainNames)) {
            body.put("common_name", domainNames.get(0));
        }
        body.put("alt_names", csv(domainNames));
        body.put("ip_sans", csv(ipAddresses));
        body.put("ttl", ttl(validityDays));
        final JsonObject response = getClient().post(
                String.format("/v1/%s/sign/%s", openBaoMount.value(), openBaoSignRole.value()),
                OpenBaoClient.buildBody(body));
        final JsonObject data = requireData(response);
        final X509Certificate leaf = parseChain(data.get("certificate").getAsString()).get(0);
        // Host keeps its own private key for the sign flow.
        return new Certificate(leaf, null, chainFromResponse(data));
    }

    private Certificate certificateFromIssueResponse(final JsonObject response) {
        final JsonObject data = requireData(response);
        final X509Certificate leaf = parseChain(data.get("certificate").getAsString()).get(0);
        PrivateKey privateKey = null;
        if (data.has("private_key")) {
            try {
                privateKey = CertUtils.pemToPrivateKey(data.get("private_key").getAsString());
            } catch (final Exception e) {
                throw new CloudRuntimeException("Failed to parse private key returned by OpenBao", e);
            }
        }
        return new Certificate(leaf, privateKey, chainFromResponse(data));
    }

    private JsonObject requireData(final JsonObject response) {
        final JsonObject data = response.getAsJsonObject("data");
        if (data == null || !data.has("certificate")) {
            throw new CloudRuntimeException("OpenBao response did not contain a certificate");
        }
        return data;
    }

    @Override
    public boolean revokeCertificate(final BigInteger certSerial, final String certCn) {
        if (certSerial == null) {
            return false;
        }
        final Map<String, String> body = new HashMap<>();
        body.put("serial_number", formatSerial(certSerial));
        getClient().post(String.format("/v1/%s/revoke", openBaoMount.value()), OpenBaoClient.buildBody(body));
        return true;
    }

    /**
     * OpenBao expects the colon-delimited lowercase hex serial (e.g. 0a:1b:2c).
     */
    protected String formatSerial(final BigInteger certSerial) {
        final String hex = certSerial.toString(16);
        final String padded = (hex.length() % 2 == 0) ? hex : "0" + hex;
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < padded.length(); i += 2) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(padded, i, i + 2);
        }
        return builder.toString();
    }

    ////////////////////////////////////////////////////////////
    /////////////// OpenBao Trust Management ///////////////////
    ////////////////////////////////////////////////////////////

    @Override
    public SSLEngine createSSLEngine(final SSLContext sslContext, final String remoteAddress, final Map<String, X509Certificate> certMap) throws GeneralSecurityException, IOException {
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        final KeyStore ks = getManagementKeyStore();
        kmf.init(ks, getKeyStorePassphrase());

        final boolean authStrictness = openBaoAuthStrictness.value();
        final boolean allowExpiredCertificate = openBaoAllowExpiredCert.value();
        final TrustManager[] tms = new TrustManager[]{
                new OpenBaoCACustomTrustManager(remoteAddress, authStrictness, allowExpiredCertificate, certMap, getCaCertificate(), crlDao)};

        sslContext.init(kmf.getKeyManagers(), tms, new SecureRandom());
        final SSLEngine sslEngine = sslContext.createSSLEngine();
        if (authStrictness) {
            sslEngine.setNeedClientAuth(true);
        } else {
            sslEngine.setWantClientAuth(true);
        }
        return sslEngine;
    }

    @Override
    public synchronized KeyStore getManagementKeyStore() throws KeyStoreException {
        if (managementKeyStore != null) {
            return managementKeyStore;
        }
        managementKeyStore = buildManagementKeyStore();
        return managementKeyStore;
    }

    private KeyStore buildManagementKeyStore() throws KeyStoreException {
        final List<String> domainNames = new ArrayList<>();
        domainNames.add(NetUtils.getHostName());
        domainNames.add(CAManager.CertManagementCustomSubjectAlternativeName.value());
        final List<String> nicIps = NetUtils.getAllDefaultNicIps();

        final Certificate serverCertificate = issueCertificate(domainNames, nicIps, CAManager.CertValidityPeriod.value());
        if (serverCertificate == null || serverCertificate.getPrivateKey() == null) {
            throw new CloudRuntimeException("Failed to issue management-server certificate from OpenBao");
        }
        return assembleKeyStore(serverCertificate);
    }

    private KeyStore assembleKeyStore(final Certificate serverCertificate) throws KeyStoreException {
        try {
            final List<X509Certificate> caChain = getCaCertificate();
            final List<X509Certificate> chain = new ArrayList<>();
            chain.add(serverCertificate.getClientCertificate());
            chain.addAll(caChain);

            final KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            for (int i = 0; i < caChain.size(); i++) {
                ks.setCertificateEntry(caAlias + (i == 0 ? "" : i), caChain.get(i));
            }
            ks.setKeyEntry(managementAlias, serverCertificate.getPrivateKey(), getKeyStorePassphrase(),
                    chain.toArray(new X509Certificate[0]));
            return ks;
        } catch (final NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new CloudRuntimeException("Failed to assemble OpenBao management-server keystore", e);
        }
    }

    @Override
    public char[] getKeyStorePassphrase() {
        return KeyStoreUtils.DEFAULT_KS_PASSPHRASE;
    }

    /////////////////////////////////////////////////
    /////////////// OpenBao Setup ///////////////////
    /////////////////////////////////////////////////

    @Override
    public boolean start() {
        managementCertificateCustomSAN = CAManager.CertManagementCustomSubjectAlternativeName.value();
        return true;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        managementCertificateCustomSAN = CAManager.CertManagementCustomSubjectAlternativeName.value();
        return true;
    }

    ///////////////////////////////////////////////////////
    /////////////// OpenBao Descriptors ///////////////////
    ///////////////////////////////////////////////////////

    @Override
    public String getConfigComponentName() {
        return OpenBaoCAProvider.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                openBaoUrl,
                openBaoMount,
                openBaoSignRole,
                openBaoIssueRole,
                openBaoAppRoleId,
                openBaoAppRoleSecretId,
                openBaoTlsSkipVerify,
                openBaoCaChainCacheTtl,
                openBaoAuthStrictness,
                openBaoAllowExpiredCert,
                openBaoLegacyTrustPem
        };
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String getDescription() {
        return "CloudStack's OpenBao/Vault CA provider plugin";
    }

    @Override
    public boolean isManagementCertificate(final java.security.cert.Certificate certificate) throws CertificateParsingException {
        if (!(certificate instanceof X509Certificate)) {
            return false;
        }
        final X509Certificate x509Certificate = (X509Certificate) certificate;
        final Collection<List<?>> altNames = x509Certificate.getSubjectAlternativeNames();
        if (CollectionUtils.isEmpty(altNames)) {
            return false;
        }
        for (final List<?> altName : altNames) {
            final int type = (Integer) altName.get(0);
            final String altNameValue = (String) altName.get(1);
            if (type == GeneralName.dNSName && managementCertificateCustomSAN.equals(altNameValue)) {
                return true;
            }
        }
        return false;
    }
}
