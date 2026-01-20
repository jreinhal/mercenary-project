package com.jreinhal.mercenary.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PiiRedactionServiceTest {

    private PiiRedactionService redactionService;

    @BeforeEach
    void setUp() {
        redactionService = new PiiRedactionService();
    }

    @Test
    void shouldRedactSocialSecurityNumbers() {
        String input = "My SSN is 123-45-6789";

        String result = redactionService.redact(input);

        assertFalse(result.contains("123-45-6789"));
        assertTrue(result.contains("[REDACTED-SSN]"));
    }

    @Test
    void shouldRedactCreditCardNumbers() {
        String input = "Card number: 4111-1111-1111-1111";

        String result = redactionService.redact(input);

        assertFalse(result.contains("4111-1111-1111-1111"));
        assertTrue(result.contains("[REDACTED-CC]"));
    }

    @Test
    void shouldRedactEmailAddresses() {
        String input = "Contact me at john.doe@example.com";

        String result = redactionService.redact(input);

        assertFalse(result.contains("john.doe@example.com"));
        assertTrue(result.contains("[REDACTED-EMAIL]"));
    }

    @Test
    void shouldRedactPhoneNumbers() {
        String input = "Call me at (555) 123-4567";

        String result = redactionService.redact(input);

        assertFalse(result.contains("(555) 123-4567"));
        assertTrue(result.contains("[REDACTED-PHONE]"));
    }

    @Test
    void shouldHandleNullInput() {
        String result = redactionService.redact(null);

        assertNull(result);
    }

    @Test
    void shouldHandleEmptyInput() {
        String result = redactionService.redact("");

        assertEquals("", result);
    }

    @Test
    void shouldNotRedactNonPiiContent() {
        String input = "This is a normal business document with no PII.";

        String result = redactionService.redact(input);

        assertEquals(input, result);
    }

    @Test
    void shouldRedactMultiplePiiInstances() {
        String input = "SSN: 111-22-3333, Email: test@test.com, Phone: 555-555-5555";

        String result = redactionService.redact(input);

        assertFalse(result.contains("111-22-3333"));
        assertFalse(result.contains("test@test.com"));
        assertFalse(result.contains("555-555-5555"));
    }
}
