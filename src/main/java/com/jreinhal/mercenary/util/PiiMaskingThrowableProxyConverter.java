package com.jreinhal.mercenary.util;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Preserves exception stack traces while scrubbing common PII/PHI tokens from the rendered output.
 *
 * Note: This does not make stack traces "safe" to share; it is a best-effort guardrail.
 */
public class PiiMaskingThrowableProxyConverter extends ThrowableProxyConverter {
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-?\\d{2}-?\\d{4}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile(
        "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEN_DIGIT_ID = Pattern.compile("\\b\\d{10}\\b");
    // M-12: Additional PII patterns missing from log scrubbing
    private static final Pattern PHONE = Pattern.compile("\\b(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");

    @Override
    public String convert(ILoggingEvent event) {
        String rendered = super.convert(event);
        if (rendered == null || rendered.isEmpty()) {
            return "";
        }

        String sanitized = rendered;
        sanitized = SSN.matcher(sanitized).replaceAll("[SSN-REDACTED]");
        sanitized = CREDIT_CARD.matcher(sanitized).replaceAll("[CC-REDACTED]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[EMAIL-REDACTED]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[PHONE-REDACTED]");
        sanitized = IPV4.matcher(sanitized).replaceAll("[IP-REDACTED]");
        sanitized = TEN_DIGIT_ID.matcher(sanitized).replaceAll("[ID-REDACTED]");

        return sanitized;
    }
}

