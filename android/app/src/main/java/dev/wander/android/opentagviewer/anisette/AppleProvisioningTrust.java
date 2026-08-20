package dev.wander.android.opentagviewer.anisette;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS policy for Apple's anonymous ADI provisioning endpoint.
 *
 * <p>The current {@code gsa.apple.com} chain terminates at the legacy Apple Root CA. Its
 * self-signature uses SHA-1, so Android 16's system trust path rejects it even though the
 * certificate is still published by Apple and is valid through 2035. Treating that exact
 * certificate as the trust anchor means its self-signature is outside the validated path;
 * the leaf and intermediate signatures remain subject to the platform TLS checks.
 *
 * <p>This is deliberately not a general-purpose extra CA. The socket factory is used only for
 * HTTPS on the canonical GSA host and the platform hostname verifier is left untouched.
 */
final class AppleProvisioningTrust {
    static final String APPLE_ROOT_SHA256 =
            "B0B1730ECBC7FF4505142C49F1295E6EDA6BCAED7E2C68C5BE91B5A11001F024";

    private static final String HOST = "gsa.apple.com";

    // Apple Inc. Root certificate, downloaded from Apple's public PKI repository:
    // https://www.apple.com/appleca/AppleIncRootCertificate.cer
    private static final String APPLE_ROOT_DER_BASE64 =
            "MIIEuzCCA6OgAwIBAgIBAjANBgkqhkiG9w0BAQUFADBiMQswCQYDVQQGEwJVUzET"
            + "MBEGA1UEChMKQXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlv"
            + "biBBdXRob3JpdHkxFjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwHhcNMDYwNDI1MjE0"
            + "MDM2WhcNMzUwMjA5MjE0MDM2WjBiMQswCQYDVQQGEwJVUzETMBEGA1UEChMKQXBw"
            + "bGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkx"
            + "FjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw"
            + "ggEKAoIBAQDkkakJH5HbHkdQ6wXtXnmELes2oldMVeyLGYne+Uts9QerIjAC6Bg+"
            + "+FAJ039BqJj50cpmnCRrEdCju+QbKsMflZ56DKRHi1vUFjczy8QPTc4UadHJGXL1"
            + "XQ7Vf1+b8iUDulWPTV0N8WQ1IxVLFVkds5T39pyez1C6wVhQZ48ItCD3y6wsIG9w"
            + "tj8BMIy3Q88PnT3zK0koGsj+zrW5DtleHNbLPbU6rfQPDgCSC7EhFi501TwN22IW"
            + "q6NxkkdTVcGvL0Gz+PvjcM3mo0xFfh9Ma1CWQYnEdGILEINBhzOKgbEwWOxaBDKM"
            + "aLOPHd5lc/9nXmW8Sdh2nzMUZaF3lMktAgMBAAGjggF6MIIBdjAOBgNVHQ8BAf8E"
            + "BAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUK9BpR5R2Cf70a40uQKb3"
            + "R01/CF4wHwYDVR0jBBgwFoAUK9BpR5R2Cf70a40uQKb3R01/CF4wggERBgNVHSAE"
            + "ggEIMIIBBDCCAQAGCSqGSIb3Y2QFATCB8jAqBggrBgEFBQcCARYeaHR0cHM6Ly93"
            + "d3cuYXBwbGUuY29tL2FwcGxlY2EvMIHDBggrBgEFBQcCAjCBthqBs1JlbGlhbmNl"
            + "IG9uIHRoaXMgY2VydGlmaWNhdGUgYnkgYW55IHBhcnR5IGFzc3VtZXMgYWNjZXB0"
            + "YW5jZSBvZiB0aGUgdGhlbiBhcHBsaWNhYmxlIHN0YW5kYXJkIHRlcm1zIGFuZCBj"
            + "b25kaXRpb25zIG9mIHVzZSwgY2VydGlmaWNhdGUgcG9saWN5IGFuZCBjZXJ0aWZp"
            + "Y2F0aW9uIHByYWN0aWNlIHN0YXRlbWVudHMuMA0GCSqGSIb3DQEBBQUAA4IBAQBc"
            + "NplMLXi37Yyb3PN3m/J20ncwT8EfhYOFG5k9RzfyqZtAjizUsZAS2L70c5vu0mQP"
            + "y3lPNNiiPvl4/2vIB+x9OYOLUyDTOMSxv5pPCmv/K/xZpwUJfBdAVhEedNO3iyM7"
            + "R6PVbyTi69G3cN8PReEnyvFteO3ntRcXqNx+IjXKJdXZD9Zr1KIkIxH3oayPc4Fg"
            + "xhtbCS+SsvhESPBgOJ4V9T0mZyCKM2r3DYLP3uujL/lTaltkwGMzd/c6ByxW69oP"
            + "IQ7aunMZT7XZNn/Bh1XZp5m5MkL72NVxnn6hUrcbvZNCJBIqxw8dtk2cXmPIS4AX"
            + "UKqK1drk/NAJBzewdXUh";

    private AppleProvisioningTrust() {}

    static HttpsURLConnection open(URL url) throws IOException {
        if (!isAllowed(url)) {
            throw new IOException("refusing non-canonical Apple provisioning URL");
        }

        final HttpsURLConnection connection =
                (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(Holder.SOCKET_FACTORY);
        // Never forward machine-identity headers across a redirect. Apple's URL bag currently
        // points directly to its provisioning methods, so a redirect is an actionable failure.
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    static boolean isAllowed(URL url) {
        final int port = url.getPort();
        return "https".equalsIgnoreCase(url.getProtocol())
                && HOST.equalsIgnoreCase(url.getHost())
                && (port == -1 || port == 443)
                && url.getUserInfo() == null;
    }

    static X509Certificate appleRootCertificate() throws GeneralSecurityException {
        final byte[] der;
        try {
            der = Base64.getDecoder().decode(APPLE_ROOT_DER_BASE64);
        } catch (final IllegalArgumentException e) {
            throw new GeneralSecurityException("embedded Apple root is not valid base64", e);
        }

        final X509Certificate certificate = (X509Certificate) CertificateFactory
                .getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        final byte[] actual = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        final byte[] expected = hexToBytes(APPLE_ROOT_SHA256);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new GeneralSecurityException("embedded Apple root fingerprint mismatch");
        }
        return certificate;
    }

    private static SSLSocketFactory createSocketFactory() {
        try {
            final X509Certificate appleRoot = appleRootCertificate();
            appleRoot.checkValidity();

            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("apple-inc-root", appleRoot);

            final TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);

            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (final GeneralSecurityException | IOException e) {
            throw new IllegalStateException("could not create Apple provisioning TLS policy", e);
        }
    }

    private static byte[] hexToBytes(String hex) throws GeneralSecurityException {
        if ((hex.length() & 1) != 0) {
            throw new GeneralSecurityException("invalid fingerprint length");
        }
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            try {
                result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (final NumberFormatException e) {
                throw new GeneralSecurityException("invalid fingerprint", e);
            }
        }
        return result;
    }

    private static final class Holder {
        private static final SSLSocketFactory SOCKET_FACTORY = createSocketFactory();
    }
}
