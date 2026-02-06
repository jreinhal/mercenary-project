package com.jreinhal.mercenary.util;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Best-effort PII/PHI scrubbing for log output.
 *
 * This is intentionally conservative and only targets common high-risk tokens.
 * Regulated deployments should still keep log levels low and avoid logging raw user/doc content.
 */
public class PiiMaskingConverter extends ClassicConverter {
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-?\\d{2}-?\\d{4}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile(
        "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
        Pattern.CASE_INSENSITIVE
    );
    // EDIPI is commonly 10 digits; also catches similar identifiers.
    private static final Pattern TEN_DIGIT_ID = Pattern.compile("\\b\\d{10}\\b");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isEmpty()) {
            return "";
        }

        String sanitized = msg;
        sanitized = SSN.matcher(sanitized).replaceAll("[SSN-REDACTED]");
        sanitized = CREDIT_CARD.matcher(sanitized).replaceAll("[CC-REDACTED]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[EMAIL-REDACTED]");
        sanitized = TEN_DIGIT_ID.matcher(sanitized).replaceAll("[ID-REDACTED]");

        return sanitized;
    }
}

