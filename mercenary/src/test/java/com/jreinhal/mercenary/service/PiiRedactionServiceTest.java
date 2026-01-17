package com.jreinhal.mercenary.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PII Redaction Service.
 * Covers all industry-standard PII patterns.
 */
@ExtendWith(MockitoExtension.class)
class PiiRedactionServiceTest {

    @Mock
    private TokenizationVault tokenizationVault;

    private PiiRedactionService service;

    @BeforeEach
    void setUp() {
        service = new PiiRedactionService(tokenizationVault);
        // Enable service and all patterns
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mode", "MASK");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactSsn", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactEmail", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactPhone", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactCreditCard", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactDob", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactIpAddress", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactPassport", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactDriversLicense", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactNames", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactAddress", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "redactMedicalId", true);
    }

    // ========== SSN TESTS ==========

    @Test
    @DisplayName("Should redact SSN in standard format (123-45-6789)")
    void testSsnRedaction_StandardFormat() {
        String input = "My SSN is 123-45-6789 and I need help.";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-SSN]"));
        assertFalse(result.getRedactedContent().contains("123-45-6789"));
        assertEquals(1, result.getRedactionCounts().get(PiiRedactionService.PiiType.SSN));
    }

    @Test
    @DisplayName("Should redact SSN without dashes (123456789)")
    void testSsnRedaction_NoDashes() {
        String input = "SSN: 123456789";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-SSN]"));
        assertFalse(result.getRedactedContent().contains("123456789"));
    }

    @Test
    @DisplayName("Should not redact invalid SSN starting with 000 or 666")
    void testSsnRedaction_InvalidPrefixes() {
        String input = "Invalid SSNs: 000-12-3456 and 666-12-3456";
        PiiRedactionService.RedactionResult result = service.redact(input);

        // These are invalid SSN formats and should NOT be redacted
        assertFalse(result.hasRedactions());
    }

    // ========== EMAIL TESTS ==========

    @Test
    @DisplayName("Should redact standard email addresses")
    void testEmailRedaction_Standard() {
        String input = "Contact me at john.doe@example.com for details.";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-EMAIL]"));
        assertFalse(result.getRedactedContent().contains("john.doe@example.com"));
    }

    @Test
    @DisplayName("Should redact multiple emails in same text")
    void testEmailRedaction_Multiple() {
        String input = "Send to alice@corp.org and bob@company.io";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertEquals(2, result.getRedactionCounts().get(PiiRedactionService.PiiType.EMAIL));
    }

    // ========== PHONE TESTS ==========

    @Test
    @DisplayName("Should redact US phone numbers in various formats")
    void testPhoneRedaction_VariousFormats() {
        String[] phones = {
            "Call me at 555-123-4567",
            "Phone: (555) 123-4567",
            "Tel: 555.123.4567",
            "Contact: +1-555-123-4567"
        };

        for (String input : phones) {
            PiiRedactionService.RedactionResult result = service.redact(input);
            assertTrue(result.getRedactedContent().contains("[REDACTED-PHONE]"),
                    "Failed to redact: " + input);
        }
    }

    // ========== CREDIT CARD TESTS ==========

    @Test
    @DisplayName("Should redact credit card numbers")
    void testCreditCardRedaction() {
        String input = "Card: 4111-1111-1111-1111";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-CREDIT_CARD]"));
        assertFalse(result.getRedactedContent().contains("4111"));
    }

    @Test
    @DisplayName("Should redact Visa, MasterCard, Amex patterns")
    void testCreditCardRedaction_MajorCards() {
        // Visa starts with 4
        String visa = "Visa: 4532015112830366";
        // MasterCard starts with 51-55
        String mastercard = "MC: 5425233430109903";
        // Amex starts with 34 or 37
        String amex = "Amex: 374245455400126";

        assertTrue(service.redact(visa).hasRedactions());
        assertTrue(service.redact(mastercard).hasRedactions());
        assertTrue(service.redact(amex).hasRedactions());
    }

    // ========== DATE OF BIRTH TESTS ==========

    @Test
    @DisplayName("Should redact DOB with context")
    void testDobRedaction_WithContext() {
        String[] inputs = {
            "DOB: 01/15/1990",
            "Date of Birth: 01-15-1990"
        };

        for (String input : inputs) {
            PiiRedactionService.RedactionResult result = service.redact(input);
            assertTrue(result.getRedactedContent().contains("[REDACTED-DATE_OF_BIRTH]"),
                    "Failed to redact: " + input);
        }
    }

    // ========== IP ADDRESS TESTS ==========

    @Test
    @DisplayName("Should redact IPv4 addresses")
    void testIpRedaction_IPv4() {
        String input = "User connected from 192.168.1.100 at 10.0.0.1";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-IP_ADDRESS]"));
        assertFalse(result.getRedactedContent().contains("192.168.1.100"));
        assertEquals(2, result.getRedactionCounts().get(PiiRedactionService.PiiType.IP_ADDRESS));
    }

    // ========== NAME TESTS ==========

    @Test
    @DisplayName("Should redact names with context labels")
    void testNameRedaction_WithContext() {
        String input = "Patient: John Smith was admitted today.";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-NAME]"));
        assertFalse(result.getRedactedContent().contains("John Smith"));
    }

    @Test
    @DisplayName("Should redact names after honorifics")
    void testNameRedaction_WithHonorific() {
        String input = "Dr. Jane Wilson prescribed the medication.";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-NAME]"));
    }

    // ========== PASSPORT TESTS ==========

    @Test
    @DisplayName("Should redact passport numbers with context")
    void testPassportRedaction() {
        String input = "Passport No: AB1234567";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-PASSPORT]"));
        assertFalse(result.getRedactedContent().contains("AB1234567"));
    }

    // ========== DRIVER'S LICENSE TESTS ==========

    @Test
    @DisplayName("Should redact driver's license with context")
    void testDriversLicenseRedaction() {
        String input = "Driver's License: D1234567";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-DRIVERS_LICENSE]"));
    }

    // ========== MEDICAL ID TESTS ==========

    @Test
    @DisplayName("Should redact medical record numbers")
    void testMedicalIdRedaction() {
        String input = "MRN: MR123456789";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getRedactedContent().contains("[REDACTED-MEDICAL_ID]"));
    }

    // ========== LUHN VALIDATION TESTS ==========

    @Test
    @DisplayName("Should validate credit cards using Luhn algorithm")
    void testLuhnValidation() {
        // Valid test card numbers
        assertTrue(PiiRedactionService.isValidCreditCard("4532015112830366"));
        assertTrue(PiiRedactionService.isValidCreditCard("5425233430109903"));
        assertTrue(PiiRedactionService.isValidCreditCard("374245455400126"));

        // Invalid card number
        assertFalse(PiiRedactionService.isValidCreditCard("1234567890123456"));
    }

    // ========== CONTAINSPII TESTS ==========

    @Test
    @DisplayName("Should detect presence of PII")
    void testContainsPii() {
        assertTrue(service.containsPii("My SSN is 123-45-6789"));
        assertTrue(service.containsPii("Email: test@example.com"));
        assertFalse(service.containsPii("This text has no PII"));
    }

    // ========== COMBINED TESTS ==========

    @Test
    @DisplayName("Should redact multiple PII types in same document")
    void testMultiplePiiTypes() {
        String input = """
            Employee Record:
            Name: John Smith
            SSN: 123-45-6789
            Email: john.smith@company.com
            Phone: (555) 123-4567
            DOB: 01/15/1985
            """;

        PiiRedactionService.RedactionResult result = service.redact(input);

        assertTrue(result.getTotalRedactions() >= 4);
        assertTrue(result.getRedactedContent().contains("[REDACTED-SSN]"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-EMAIL]"));
        assertTrue(result.getRedactedContent().contains("[REDACTED-PHONE]"));
    }

    @Test
    @DisplayName("Should handle empty and null input gracefully")
    void testEmptyInput() {
        PiiRedactionService.RedactionResult emptyResult = service.redact("");
        PiiRedactionService.RedactionResult nullResult = service.redact(null);

        assertEquals("", emptyResult.getRedactedContent());
        assertNull(nullResult.getRedactedContent());
        assertFalse(emptyResult.hasRedactions());
        assertFalse(nullResult.hasRedactions());
    }

    @Test
    @DisplayName("Should preserve non-PII content")
    void testPreservesNonPii() {
        String input = "The project deadline is Q4 2024. Budget: $50,000.";
        PiiRedactionService.RedactionResult result = service.redact(input);

        assertEquals(input, result.getRedactedContent());
        assertFalse(result.hasRedactions());
    }
}
