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

import java.math.BigInteger;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import javax.net.ssl.X509TrustManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.certificate.dao.CrlDao;

/**
 * Trust manager for the OpenBao CA provider. Behaves identically to the root-ca
 * trust manager (revocation via the local CRL table, validity and SAN-ownership
 * checks) but accepts the full OpenBao issuer chain so leaf certificates signed
 * by the intermediate validate correctly.
 */
public final class OpenBaoCACustomTrustManager implements X509TrustManager {
    protected Logger logger = LogManager.getLogger(getClass());

    private String clientAddress = "Unknown";
    private boolean authStrictness = true;
    private boolean allowExpiredCertificate = true;
    private final CrlDao crlDao;
    private final List<X509Certificate> caCertificates;
    private final Map<String, X509Certificate> activeCertMap;

    public OpenBaoCACustomTrustManager(final String clientAddress, final boolean authStrictness, final boolean allowExpiredCertificate,
                                       final Map<String, X509Certificate> activeCertMap, final List<X509Certificate> caCertificates, final CrlDao crlDao) {
        if (StringUtils.isNotEmpty(clientAddress)) {
            this.clientAddress = clientAddress.replace("/", "").split(":")[0];
        }
        this.authStrictness = authStrictness;
        this.allowExpiredCertificate = allowExpiredCertificate;
        this.activeCertMap = activeCertMap;
        this.caCertificates = caCertificates;
        this.crlDao = crlDao;
    }

    private void printCertificateChain(final X509Certificate[] certificates) {
        if (certificates == null) {
            return;
        }
        final StringBuilder builder = new StringBuilder();
        builder.append("A client/agent attempting connection from address=").append(clientAddress).append(" has presented these certificate(s):");
        int counter = 1;
        for (final X509Certificate certificate : certificates) {
            builder.append("\nCertificate [").append(counter++).append("] :");
            builder.append(String.format("\n  Serial: %x", certificate.getSerialNumber()));
            builder.append("\n  Subject DN:").append(certificate.getSubjectDN());
            builder.append("\n  Issuer DN:").append(certificate.getIssuerDN());
        }
        logger.debug(builder.toString());
    }

    @Override
    public void checkClientTrusted(final X509Certificate[] certificates, final String s) throws CertificateException {
        if (logger.isDebugEnabled()) {
            printCertificateChain(certificates);
        }

        final X509Certificate primaryClientCertificate = (certificates != null && certificates.length > 0 && certificates[0] != null) ? certificates[0] : null;
        String exceptionMsg = "";

        if (authStrictness && primaryClientCertificate == null) {
            throw new CertificateException("In strict auth mode, certificate(s) are expected from client:" + clientAddress);
        } else if (primaryClientCertificate == null) {
            logger.info("No certificate was received from client, but continuing since strict auth mode is disabled");
            return;
        }

        exceptionMsg = checkRevocation(primaryClientCertificate, exceptionMsg);
        checkValidity(primaryClientCertificate);
        exceptionMsg = checkOwnership(primaryClientCertificate, exceptionMsg);

        if (authStrictness && StringUtils.isNotEmpty(exceptionMsg)) {
            throw new CertificateException(exceptionMsg);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Client/agent connection from ip=" + clientAddress
                    + (authStrictness ? " has been validated and trusted." : " accepted without certificate validation."));
        }

        if (activeCertMap != null && StringUtils.isNotEmpty(clientAddress)) {
            activeCertMap.put(clientAddress, primaryClientCertificate);
        }
    }

    private String checkRevocation(final X509Certificate certificate, final String exceptionMsg) {
        final BigInteger serialNumber = certificate.getSerialNumber();
        if (serialNumber == null || crlDao.findBySerial(serialNumber) != null) {
            final String errorMsg = String.format("Client is using revoked certificate of serial=%x, subject=%s from address=%s",
                    certificate.getSerialNumber(), certificate.getSubjectDN(), clientAddress);
            logger.error(errorMsg);
            return StringUtils.isEmpty(exceptionMsg) ? errorMsg : (exceptionMsg + ". " + errorMsg);
        }
        return exceptionMsg;
    }

    private void checkValidity(final X509Certificate certificate) throws CertificateException {
        try {
            certificate.checkValidity();
        } catch (final CertificateExpiredException | CertificateNotYetValidException e) {
            final String errorMsg = String.format("Client certificate has expired with serial=%x, subject=%s from address=%s",
                    certificate.getSerialNumber(), certificate.getSubjectDN(), clientAddress);
            logger.error(errorMsg);
            if (!allowExpiredCertificate) {
                throw new CertificateException(errorMsg);
            }
        }
    }

    private String checkOwnership(final X509Certificate certificate, final String exceptionMsg) throws CertificateException {
        boolean certMatchesOwnership = false;
        if (certificate.getSubjectAlternativeNames() != null) {
            for (final List<?> list : certificate.getSubjectAlternativeNames()) {
                if (list != null && list.size() == 2 && list.get(1) instanceof String && clientAddress.equals(list.get(1))) {
                    certMatchesOwnership = true;
                }
            }
        }
        if (!certMatchesOwnership) {
            final String errorMsg = "Certificate ownership verification failed for client: " + clientAddress;
            logger.error(errorMsg);
            return StringUtils.isEmpty(exceptionMsg) ? errorMsg : (exceptionMsg + ". " + errorMsg);
        }
        return exceptionMsg;
    }

    @Override
    public void checkServerTrusted(final X509Certificate[] x509Certificates, final String s) throws CertificateException {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        if (caCertificates == null) {
            return new X509Certificate[0];
        }
        return caCertificates.toArray(new X509Certificate[0]);
    }
}
