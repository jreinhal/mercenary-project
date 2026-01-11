package com.jreinhal.mercenary.security;

import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for CAC/PIV authentication flow.
 *
 * Tests the complete flow from certificate presentation to user authentication
 * using mock certificates and repository.
 */
class CacAuthenticationIntegrationTest {

    @Mock
    private UserRepository userRepository;

    private CacCertificateParser certParser;
    private CacUserDetailsService userDetailsService;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        certParser = new CacCertificateParser();
        userDetailsService = new CacUserDetailsService(userRepository, certParser);
        
        // Manual injection of @Value properties
        org.springframework.test.util.ReflectionTestUtils.setField(userDetailsService, "autoProvision", true);
        org.springframework.test.util.ReflectionTestUtils.setField(userDetailsService, "defaultRole", "ANALYST");
        org.springframework.test.util.ReflectionTestUtils.setField(userDetailsService, "defaultClearance", "SECRET");
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    @DisplayName("Authenticate existing DoD CAC user")
    void testAuthenticateExistingDodUser() {
        // Create mock certificate
        X509Certificate cert = createDodCacCertificate("DOE.JOHN.M.1234567890");

        // Create existing user
        User existingUser = new User();
        existingUser.setUsername("1234567890");
        existingUser.setDisplayName("John Doe");
        existingUser.setExternalId("1234567890");
        existingUser.setRoles(Set.of(UserRole.ANALYST));
        existingUser.setClearance(ClearanceLevel.SECRET);
        existingUser.setActive(true);

        when(userRepository.findByUsername("1234567890")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        // Authenticate
        User result = userDetailsService.authenticateWithCertificate(cert);

        assertNotNull(result);
        assertEquals("1234567890", result.getUsername());
        assertEquals("John Doe", result.getDisplayName());
        assertNotNull(result.getLastLoginAt());

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Auto-provision new DoD CAC user")
    void testAutoProvisionNewDodUser() {
        // Create mock certificate
        X509Certificate cert = createDodCacCertificate("SMITH.JANE.A.9876543210");

        // No existing user
        when(userRepository.findByUsername("9876543210")).thenReturn(Optional.empty());
        when(userRepository.findByExternalId("9876543210")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        // Authenticate (should auto-provision)
        User result = userDetailsService.authenticateWithCertificate(cert);

        assertNotNull(result);
        assertEquals("9876543210", result.getUsername());
        assertEquals("Jane Smith", result.getDisplayName());
        assertEquals("9876543210", result.getExternalId());
        assertEquals(User.AuthProvider.CAC, result.getAuthProvider());
        assertTrue(result.isActive());

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Reject expired certificate")
    void testRejectExpiredCertificate() {
        X509Certificate expiredCert = createExpiredCertificate("DOE.JOHN.M.1234567890");

        User result = userDetailsService.authenticateWithCertificate(expiredCert);

        assertNull(result);
        verify(userRepository, never()).findByUsername(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Reject null certificate")
    void testRejectNullCertificate() {
        User result = userDetailsService.authenticateWithCertificate(null);

        assertNull(result);
        verify(userRepository, never()).findByUsername(any());
    }

    @Test
    @DisplayName("Parse PIV certificate format")
    void testParsePivCertificate() {
        // PIV certs often use full name format
        X509Certificate pivCert = createPivCertificate("John Q. Public", "Department of State");

        CacCertificateParser.CacIdentity identity = certParser.parse(pivCert);

        assertNotNull(identity);
        assertEquals("John Q. Public", identity.commonName());
    }

    @Test
    @DisplayName("Handle certificate with email in SAN")
    void testCertificateWithEmail() {
        String dn = "CN=DOE.JOHN.M.1234567890, E=john.doe@mail.mil, O=U.S. Government";
        CacCertificateParser.CacIdentity identity = certParser.parseDn(dn);

        assertNotNull(identity);
        assertEquals("john.doe@mail.mil", identity.email());
        assertEquals("U.S. Government", identity.organization());
    }

    @Test
    @DisplayName("Extract username for Spring Security")
    void testExtractUsername() {
        X509Certificate cert = createDodCacCertificate("DOE.JOHN.M.1234567890");

        String username = userDetailsService.extractUsername(cert);

        assertEquals("1234567890", username);
    }

    @Test
    @DisplayName("Lookup user by EDIPI when username fails")
    void testLookupByEdipi() {
        X509Certificate cert = createDodCacCertificate("DOE.JOHN.M.1234567890");

        User existingUser = new User();
        existingUser.setUsername("old_username");
        existingUser.setExternalId("1234567890");
        existingUser.setActive(true);

        // Username lookup fails, but EDIPI lookup succeeds
        when(userRepository.findByUsername("1234567890")).thenReturn(Optional.empty());
        when(userRepository.findByExternalId("1234567890")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User result = userDetailsService.authenticateWithCertificate(cert);

        assertNotNull(result);
        assertEquals("1234567890", result.getExternalId());
    }

    // ===========================================
    // Helper Methods for Creating Mock Certificates
    // ===========================================

    private X509Certificate createDodCacCertificate(String cn) {
        String dn = "CN=" + cn + ", OU=USA, OU=PKI, OU=DoD, O=U.S. Government, C=US";
        return createMockCertificate(dn, -30, 365);
    }

    private X509Certificate createPivCertificate(String name, String org) {
        String dn = "CN=" + name + ", OU=" + org + ", O=U.S. Government, C=US";
        return createMockCertificate(dn, -30, 365);
    }

    private X509Certificate createExpiredCertificate(String cn) {
        String dn = "CN=" + cn + ", O=U.S. Government";
        return createMockCertificate(dn, -400, 365); // Expired
    }

    private X509Certificate createMockCertificate(String dn, int startDaysOffset, int validityDays) {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now + (startDaysOffset * 24L * 60 * 60 * 1000));
        Date notAfter = new Date(notBefore.getTime() + (validityDays * 24L * 60 * 60 * 1000));

        return new MockX509Certificate(dn, notBefore, notAfter);
    }

    /**
     * Minimal mock X509Certificate for testing.
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
        public X500Principal getIssuerX500Principal() {
            return new X500Principal("CN=Test CA, O=Test");
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

        // Required abstract method implementations
        @Override public int getVersion() { return 3; }
        @Override public BigInteger getSerialNumber() { return BigInteger.valueOf(System.currentTimeMillis()); }
        @Override public Principal getIssuerDN() { return getIssuerX500Principal(); }
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
