package com.jreinhal.mercenary.core.license;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LicenseServiceTest {

    private static final String SIGNING_SECRET = "test-secret-for-hmac-validation";

    private LicenseService createService(String edition, String key, String secret) {
        LicenseService service = new LicenseService();
        ReflectionTestUtils.setField(service, "editionString", edition);
        ReflectionTestUtils.setField(service, "licenseKey", key);
        ReflectionTestUtils.setField(service, "signingSecret", secret);
        ReflectionTestUtils.setField(service, "trialStartDate", "");
        ReflectionTestUtils.setField(service, "trialDays", 30);
        return service;
    }

    // ===== Unlicensed mode (backward compatibility) =====

    @Test
    void shouldRunInUnlicensedModeWhenNoKeyAndNoSecret() {
        LicenseService service = createService("ENTERPRISE", "", "");
        service.initialize();

        assertTrue(service.isValid());
        assertEquals(LicenseService.Edition.ENTERPRISE, service.getEdition());
    }

    @Test
    void shouldRunInUnlicensedModeWhenNullKeyAndNullSecret() {
        LicenseService service = createService("ENTERPRISE", null, null);
        service.initialize();

        assertTrue(service.isValid());
    }

    // ===== Valid license key =====

    @Test
    void shouldValidateCorrectLicenseKey() {
        String futureDate = LocalDate.now().plusYears(1).toString();
        String key = LicenseService.generateLicenseKey("ENTERPRISE", futureDate, "acme-corp", SIGNING_SECRET);

        LicenseService service = createService("ENTERPRISE", key, SIGNING_SECRET);
        service.initialize();

        assertTrue(service.isValid());
        assertEquals(LicenseService.Edition.ENTERPRISE, service.getEdition());
        assertEquals("acme-corp", service.getCustomerId());
    }

    @Test
    void shouldValidateMedicalLicenseKey() {
        String futureDate = LocalDate.now().plusMonths(6).toString();
        String key = LicenseService.generateLicenseKey("MEDICAL", futureDate, "hospital-abc", SIGNING_SECRET);

        LicenseService service = createService("MEDICAL", key, SIGNING_SECRET);
        service.initialize();

        assertTrue(service.isValid());
        assertEquals(LicenseService.Edition.MEDICAL, service.getEdition());
        assertEquals("hospital-abc", service.getCustomerId());
    }

    @Test
    void shouldValidateGovernmentLicenseKey() {
        String futureDate = LocalDate.now().plusYears(2).toString();
        String key = LicenseService.generateLicenseKey("GOVERNMENT", futureDate, "agency-xyz", SIGNING_SECRET);

        LicenseService service = createService("GOVERNMENT", key, SIGNING_SECRET);
        service.initialize();

        assertTrue(service.isValid());
        assertEquals(LicenseService.Edition.GOVERNMENT, service.getEdition());
    }

    // ===== Invalid license key scenarios =====

    @Test
    void shouldRejectTamperedLicenseKey() {
        String futureDate = LocalDate.now().plusYears(1).toString();
        String key = LicenseService.generateLicenseKey("ENTERPRISE", futureDate, "acme-corp", SIGNING_SECRET);

        // Tamper with the key by flipping a hex character in the signature
        char lastChar = key.charAt(key.length() - 1);
        char flipped = (lastChar == '0') ? '1' : '0';
        String tampered = key.substring(0, key.length() - 1) + flipped;

        LicenseService service = createService("ENTERPRISE", tampered, SIGNING_SECRET);
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectExpiredLicenseKey() {
        String pastDate = LocalDate.now().minusDays(1).toString();
        String key = LicenseService.generateLicenseKey("ENTERPRISE", pastDate, "acme-corp", SIGNING_SECRET);

        LicenseService service = createService("ENTERPRISE", key, SIGNING_SECRET);
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectEditionMismatch() {
        String futureDate = LocalDate.now().plusYears(1).toString();
        // Key generated for MEDICAL but service configured for ENTERPRISE
        String key = LicenseService.generateLicenseKey("MEDICAL", futureDate, "acme-corp", SIGNING_SECRET);

        LicenseService service = createService("ENTERPRISE", key, SIGNING_SECRET);
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectWrongSigningSecret() {
        String futureDate = LocalDate.now().plusYears(1).toString();
        String key = LicenseService.generateLicenseKey("ENTERPRISE", futureDate, "acme-corp", SIGNING_SECRET);

        LicenseService service = createService("ENTERPRISE", key, "wrong-secret");
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectKeyWithNoSecret() {
        String futureDate = LocalDate.now().plusYears(1).toString();
        String key = LicenseService.generateLicenseKey("ENTERPRISE", futureDate, "acme-corp", SIGNING_SECRET);

        LicenseService service = createService("ENTERPRISE", key, "");
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectSecretWithNoKey() {
        LicenseService service = createService("ENTERPRISE", "", SIGNING_SECRET);
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectMalformedKeyNoSeparator() {
        LicenseService service = createService("ENTERPRISE", "not-a-valid-key", SIGNING_SECRET);
        service.initialize();

        assertFalse(service.isValid());
    }

    @Test
    void shouldRejectMalformedKeyInvalidBase64() {
        LicenseService service = createService("ENTERPRISE", "!!!invalid!!!:abcdef", SIGNING_SECRET);
        service.initialize();

        assertFalse(service.isValid());
    }

    // ===== Key generation =====

    @Test
    void shouldGenerateConsistentKeys() {
        String key1 = LicenseService.generateLicenseKey("ENTERPRISE", "2027-12-31", "acme", SIGNING_SECRET);
        String key2 = LicenseService.generateLicenseKey("ENTERPRISE", "2027-12-31", "acme", SIGNING_SECRET);

        assertEquals(key1, key2, "Same inputs should produce same key");
    }

    @Test
    void shouldGenerateDifferentKeysForDifferentSecrets() {
        String key1 = LicenseService.generateLicenseKey("ENTERPRISE", "2027-12-31", "acme", "secret1");
        String key2 = LicenseService.generateLicenseKey("ENTERPRISE", "2027-12-31", "acme", "secret2");

        assertNotEquals(key1, key2, "Different secrets should produce different keys");
    }

    // ===== Constant-time comparison =====

    @Test
    void constantTimeEqualsShouldMatchIdenticalStrings() {
        assertTrue(LicenseService.constantTimeEquals("abc", "abc"));
    }

    @Test
    void constantTimeEqualsShouldRejectDifferentStrings() {
        assertFalse(LicenseService.constantTimeEquals("abc", "def"));
    }

    @Test
    void constantTimeEqualsShouldRejectDifferentLengths() {
        assertFalse(LicenseService.constantTimeEquals("abc", "abcd"));
    }

    @Test
    void constantTimeEqualsShouldHandleNulls() {
        assertFalse(LicenseService.constantTimeEquals(null, "abc"));
        assertFalse(LicenseService.constantTimeEquals("abc", null));
        assertFalse(LicenseService.constantTimeEquals(null, null));
    }

    // ===== HMAC computation =====

    @Test
    void computeHmacShouldReturnHexString() {
        String hmac = LicenseService.computeHmac("test-data", "test-secret");
        assertNotNull(hmac);
        // HMAC-SHA256 produces 64 hex characters
        assertEquals(64, hmac.length());
        assertTrue(hmac.matches("[0-9a-f]+"), "HMAC should be lowercase hex");
    }

    @Test
    void computeHmacShouldBeDeterministic() {
        String hmac1 = LicenseService.computeHmac("data", "secret");
        String hmac2 = LicenseService.computeHmac("data", "secret");
        assertEquals(hmac1, hmac2);
    }

    // ===== Trial edition (unchanged behavior) =====

    @Test
    void shouldHandleTrialEdition() {
        LicenseService service = new LicenseService();
        ReflectionTestUtils.setField(service, "editionString", "TRIAL");
        ReflectionTestUtils.setField(service, "licenseKey", "");
        ReflectionTestUtils.setField(service, "signingSecret", "");
        ReflectionTestUtils.setField(service, "trialStartDate", LocalDate.now().toString());
        ReflectionTestUtils.setField(service, "trialDays", 30);
        service.initialize();

        assertTrue(service.isValid());
        assertEquals(LicenseService.Edition.TRIAL, service.getEdition());
        assertTrue(service.getTrialDaysRemaining() > 0);
    }

    @Test
    void shouldHandleExpiredTrial() {
        LicenseService service = new LicenseService();
        ReflectionTestUtils.setField(service, "editionString", "TRIAL");
        ReflectionTestUtils.setField(service, "licenseKey", "");
        ReflectionTestUtils.setField(service, "signingSecret", "");
        ReflectionTestUtils.setField(service, "trialStartDate", "2020-01-01");
        ReflectionTestUtils.setField(service, "trialDays", 30);
        service.initialize();

        assertFalse(service.isValid());
    }

    // ===== Legacy backward compatibility =====

    @Test
    void shouldMapLegacyProfessionalToEnterprise() {
        LicenseService service = createService("PROFESSIONAL", "", "");
        service.initialize();

        assertEquals(LicenseService.Edition.ENTERPRISE, service.getEdition());
        assertTrue(service.isValid());
    }
}
