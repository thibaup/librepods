package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;

import org.junit.Test;

public final class AppleProvisioningTrustTest {
    @Test
    public void embeddedRootMatchesOfficialAppleCertificate() throws Exception {
        final X509Certificate certificate = AppleProvisioningTrust.appleRootCertificate();
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());

        assertEquals(AppleProvisioningTrust.APPLE_ROOT_SHA256, toHex(digest));
        assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
    }

    @Test
    public void onlyCanonicalHttpsGsaEndpointIsAllowed() throws Exception {
        assertTrue(AppleProvisioningTrust.isAllowed(
                new URL("https://gsa.apple.com/grandslam/GsService2/lookup")));
        assertTrue(AppleProvisioningTrust.isAllowed(
                new URL("https://GSA.APPLE.COM:443/grandslam/GsService2/midStartProvisioning")));

        assertFalse(AppleProvisioningTrust.isAllowed(new URL("http://gsa.apple.com/")));
        assertFalse(AppleProvisioningTrust.isAllowed(new URL("https://gsa.apple.com.evil.test/")));
        assertFalse(AppleProvisioningTrust.isAllowed(new URL("https://apple.com/")));
        assertFalse(AppleProvisioningTrust.isAllowed(new URL("https://gsa.apple.com:444/")));
        assertFalse(AppleProvisioningTrust.isAllowed(new URL("https://user@gsa.apple.com/")));
    }

    private static String toHex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            out.append(String.format("%02X", value & 0xff));
        }
        return out.toString();
    }
}
