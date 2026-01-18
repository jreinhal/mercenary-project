package com.jreinhal.mercenary.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CAC/PIV X.509 certificate authentication.
 *
 * Uses programmatically generated test certificates - no real PKI required.
 */
class X509AuthenticationTest {

    private CacCertificateParser parser;

    @BeforeEach
    void setUp() {
        parser = new CacCertificateParser();
    }

    @Test
    @DisplayName("Parse DoD CAC certificate DN format")
    void testParseDodCacDn() {
        // DoD CAC format: CN=LASTNAME.FIRSTNAME.MIDDLE.1234567890
        String dn = "CN=DOE.JOHN.MIDDLE.1234567890, OU=USA, OU=PKI, OU=DoD, O=U.S. Government, C=US";

        CacCertificateParser.CacIdentity identity = parser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("1234567890", identity.edipi());
        assertEquals("John", identity.firstName());
        assertEquals("Doe", identity.lastName());
        assertEquals("DOE.JOHN.MIDDLE.1234567890", identity.commonName());
    }

    @Test
    @DisplayName("Parse PIV certificate DN format")
    void testParsePivDn() {
        // PIV format varies but typically has FASC-N or UUID
        String dn = "CN=John Doe, OU=Department of State, O=U.S. Government, C=US";

        CacCertificateParser.CacIdentity identity = parser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("John Doe", identity.commonName());
    }

    @Test
    @DisplayName("Parse certificate with email in SAN")
    void testParseWithEmail() {
        String dn = "CN=DOE.JOHN.Q.1234567890, E=john.doe@mail.mil";

        CacCertificateParser.CacIdentity identity = parser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("1234567890", identity.edipi());
    }

    @Test
    @DisplayName("Handle malformed DN gracefully")
    void testMalformedDn() {
        String dn = "not a valid DN";

        CacCertificateParser.CacIdentity identity = parser.parseDn(dn);

        // Should not throw, should return partial/default identity
        assertNotNull(identity);
    }

    @Test
    @DisplayName("Extract EDIPI from various CN formats")
    void testEdipiExtraction() {
        // Standard DoD format
        assertEquals("1234567890", parser.extractEdipi("DOE.JOHN.M.1234567890"));

        // Without middle initial
        assertEquals("9876543210", parser.extractEdipi("SMITH.JANE.9876543210"));

        // Contractor format (sometimes different)
        assertEquals("1111111111", parser.extractEdipi("CONTRACTOR.BOB.A.1111111111.CTR"));
    }

    @Test
    @DisplayName("Validate certificate is within validity period")
    void testCertificateValidity() throws Exception {
        // Create a mock certificate that's currently valid
        X509Certificate validCert = createMockCertificate(
            "CN=DOE.JOHN.M.1234567890",
            -30, // Started 30 days ago
            365  // Valid for 365 days
        );

        assertTrue(parser.isValid(validCert));
    }

    @Test
    @DisplayName("Reject expired certificate")
    void testExpiredCertificate() throws Exception {
        X509Certificate expiredCert = createMockCertificate(
            "CN=DOE.JOHN.M.1234567890",
            -400, // Started 400 days ago
            365   // Was valid for 365 days (now expired)
        );

        assertFalse(parser.isValid(expiredCert));
    }

    @Test
    @DisplayName("Reject not-yet-valid certificate")
    void testFutureCertificate() throws Exception {
        X509Certificate futureCert = createMockCertificate(
            "CN=DOE.JOHN.M.1234567890",
            30,  // Starts 30 days from now
            365
        );

        assertFalse(parser.isValid(futureCert));
    }

    /**
     * Creates a mock X509Certificate for testing.
     * Uses BouncyCastle-free approach with a simple self-signed cert.
     */
    private X509Certificate createMockCertificate(String subjectDn, int startDaysOffset, int validityDays)
            throws Exception {

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now + (startDaysOffset * 24L * 60 * 60 * 1000));
        Date notAfter = new Date(notBefore.getTime() + (validityDays * 24L * 60 * 60 * 1000));

        // Create a minimal self-signed certificate
        // Note: In production, use BouncyCastle for full cert generation
        // This is a simplified mock for testing the parser logic
        return new MockX509Certificate(subjectDn, notBefore, notAfter);
    }

    /**
     * Minimal mock X509Certificate for testing without BouncyCastle.
     */
    private static class MockX509Certificate extends X509Certificate {
        private final String subjectDn;
        private final Date notBefore;
        private final Date notAfter;

        MockX509Certificate(String subjectDn, Date notBefore, Date notAfter) {
            this.subjectDn = subjectDn;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
        }

        @Override
        public X500Principal getSubjectX500Principal() {
            return new X500Principal(subjectDn);
        }

        @Override
        public Date getNotBefore() {
            return notBefore;
        }

        @Override
        public Date getNotAfter() {
            return notAfter;
        }

        @Override
        public void checkValidity() throws java.security.cert.CertificateExpiredException,
                java.security.cert.CertificateNotYetValidException {
            checkValidity(new Date());
        }

        @Override
        public void checkValidity(Date date) throws java.security.cert.CertificateExpiredException,
                java.security.cert.CertificateNotYetValidException {
            if (date.before(notBefore)) {
                throw new java.security.cert.CertificateNotYetValidException();
            }
            if (date.after(notAfter)) {
                throw new java.security.cert.CertificateExpiredException();
            }
        }

        // Required abstract method implementations (minimal stubs)
        @Override public int getVersion() { return 3; }
        @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
        @Override public Principal getIssuerDN() { return getSubjectX500Principal(); }
        @Override public Principal getSubjectDN() { return getSubjectX500Principal(); }
        @Override public byte[] getTBSCertificate() { return new byte[0]; }
        @Override public byte[] getSignature() { return new byte[0]; }
        @Override public String getSigAlgName() { return "SHA256withRSA"; }
        @Override public String getSigAlgOID() { return "1.2.840.113549.1.1.11"; }
        @Override public byte[] getSigAlgParams() { return null; }
        @Override public boolean[] getIssuerUniqueID() { return null; }
        @Override public boolean[] getSubjectUniqueID() { return null; }
        @Override public boolean[] getKeyUsage() { return null; }
        @Override public int getBasicConstraints() { return -1; }
        @Override public byte[] getEncoded() { return new byte[0]; }
        @Override public void verify(PublicKey key) {}
        @Override public void verify(PublicKey key, String sigProvider) {}
        @Override public String toString() { return subjectDn; }
        @Override public PublicKey getPublicKey() { return null; }
        @Override public boolean hasUnsupportedCriticalExtension() { return false; }
        @Override public java.util.Set<String> getCriticalExtensionOIDs() { return null; }
        @Override public java.util.Set<String> getNonCriticalExtensionOIDs() { return null; }
        @Override public byte[] getExtensionValue(String oid) { return null; }
    }
}
