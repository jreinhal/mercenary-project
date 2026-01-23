package com.jreinhal.mercenary.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiiRedactionServiceTest {

    private PiiRedactionService redactionService;
    private TokenizationVault mockVault;

    @BeforeEach
    void setUp() {
        mockVault = mock(TokenizationVault.class);
        redactionService = new PiiRedactionService(mockVault);
        ReflectionTestUtils.setField(redactionService, "enabled", true);
        ReflectionTestUtils.setField(redactionService, "mode", "MASK");
        ReflectionTestUtils.setField(redactionService, "auditRedactions", false);
        ReflectionTestUtils.setField(redactionService, "redactSsn", true);
        ReflectionTestUtils.setField(redactionService, "redactEmail", true);
        ReflectionTestUtils.setField(redactionService, "redactPhone", true);
        ReflectionTestUtils.setField(redactionService, "redactCreditCard", true);
        ReflectionTestUtils.setField(redactionService, "redactDob", true);
        ReflectionTestUtils.setField(redactionService, "redactIpAddress", true);
        ReflectionTestUtils.setField(redactionService, "redactPassport", true);
        ReflectionTestUtils.setField(redactionService, "redactDriversLicense", true);
        ReflectionTestUtils.setField(redactionService, "redactNames", true);
        ReflectionTestUtils.setField(redactionService, "redactAddress", true);
        ReflectionTestUtils.setField(redactionService, "redactMedicalId", true);
    }

    @Test
    @DisplayName("Should redact Social Security Numbers")
    void shouldRedactSocialSecurityNumbers() {
        String input = "My SSN is 123-45-6789";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("123-45-6789"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-SSN]"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should redact Credit Card Numbers")
    void shouldRedactCreditCardNumbers() {
        String input = "Card number: 4111-1111-1111-1111";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("4111-1111-1111-1111"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-CREDIT_CARD]"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should redact Email Addresses")
    void shouldRedactEmailAddresses() {
        String input = "Contact me at john.doe@example.com";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("john.doe@example.com"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-EMAIL]"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should redact Phone Numbers")
    void shouldRedactPhoneNumbers() {
        String input = "Call me at 555-123-4567";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("555-123-4567"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-PHONE]"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should redact IP addresses")
    void shouldRedactIpAddresses() {
        String input = "Server at 192.168.1.100";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("192.168.1.100"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-IP_ADDRESS]"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should redact Medical Record Numbers")
    void shouldRedactMedicalRecordNumbers() {
        String input = "MRN: ABC123456";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("ABC123456"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should redact Date of Birth in context")
    void shouldRedactDateOfBirthInContext() {
        String input = "DOB: 01/15/1985";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("01/15/1985"));
        assertTrue(result.hasRedactions());
    }

    @Test
    @DisplayName("Should handle null input gracefully")
    void shouldHandleNullInput() {
        PiiRedactionService.RedactionResult result = redactionService.redact(null);

        assertNull(result.getRedactedContent());
        assertFalse(result.hasRedactions());
    }

    @Test
    @DisplayName("Should handle empty input gracefully")
    void shouldHandleEmptyInput() {
        PiiRedactionService.RedactionResult result = redactionService.redact("");

        assertEquals("", result.getRedactedContent());
        assertFalse(result.hasRedactions());
    }

    @Test
    @DisplayName("Should not redact content without PII")
    void shouldNotRedactNonPiiContent() {
        String input = "This is a normal business document with no PII.";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertEquals(input, result.getRedactedContent());
        assertFalse(result.hasRedactions());
        assertEquals(0, result.getTotalRedactions());
    }

    @Test
    @DisplayName("Should redact multiple PII instances")
    void shouldRedactMultiplePiiInstances() {
        String input = "SSN: 111-22-3333, Email: test@test.com, Phone: 555-555-5555";

        PiiRedactionService.RedactionResult result = redactionService.redact(input);

        assertFalse(result.getRedactedContent().contains("111-22-3333"));
        assertFalse(result.getRedactedContent().contains("test@test.com"));
        assertFalse(result.getRedactedContent().contains("555-555-5555"));
        assertTrue(result.getTotalRedactions() >= 3);
    }

    @Test
    @DisplayName("Should detect PII with containsPii method")
    void shouldDetectPiiPresence() {
        assertTrue(redactionService.containsPii("My SSN is 123-45-6789"));
        assertTrue(redactionService.containsPii("Email: test@example.com"));
        assertFalse(redactionService.containsPii("No sensitive data here"));
    }

    @Test
    @DisplayName("Should provide redactToString convenience method")
    void shouldProvideRedactToStringMethod() {
        String input = "SSN: 123-45-6789";

        String result = redactionService.redactToString(input);

        assertFalse(result.contains("123-45-6789"));
        assertTrue(result.contains("[REDACTED-SSN]"));
    }

    @Test
    @DisplayName("Should validate credit card with Luhn algorithm")
    void shouldValidateCreditCardWithLuhn() {
        // Valid test card number
        assertTrue(PiiRedactionService.isValidCreditCard("4111111111111111"));

        // Invalid card number
        assertFalse(PiiRedactionService.isValidCreditCard("1234567890123456"));
    }
}
