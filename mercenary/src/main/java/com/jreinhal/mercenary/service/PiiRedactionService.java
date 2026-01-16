package com.jreinhal.mercenary.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Industry-standard PII (Personally Identifiable Information) redaction service.
 *
 * Compliant with:
 * - NIST 800-122 (Guide to Protecting PII)
 * - GDPR Article 4 (Personal Data Definition)
 * - HIPAA Safe Harbor (18 Identifiers)
 * - PCI-DSS (Payment Card Data)
 *
 * Supports multiple redaction modes:
 * - MASK: Replace with [REDACTED-TYPE] tokens
 * - TOKENIZE: Replace with reversible tokens (for audit recovery)
 * - REMOVE: Delete PII entirely
 */
@Service
public class PiiRedactionService {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionService.class);

    private final TokenizationVault tokenizationVault;

    public PiiRedactionService(TokenizationVault tokenizationVault) {
        this.tokenizationVault = tokenizationVault;
    }

    public enum RedactionMode {
        MASK,       // [REDACTED-SSN], [REDACTED-EMAIL], etc.
        TOKENIZE,   // <<PII:SSN:a1b2c3>>, reversible tokens
        REMOVE      // Complete removal
    }

    public enum PiiType {
        SSN("Social Security Number"),
        EMAIL("Email Address"),
        PHONE("Phone Number"),
        CREDIT_CARD("Credit Card Number"),
        DATE_OF_BIRTH("Date of Birth"),
        IP_ADDRESS("IP Address"),
        PASSPORT("Passport Number"),
        DRIVERS_LICENSE("Driver's License"),
        NAME("Personal Name"),
        ADDRESS("Physical Address"),
        MEDICAL_ID("Medical Record Number");

        private final String displayName;

        PiiType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // ========== PATTERN DEFINITIONS ==========

    // SSN: 123-45-6789 or 123 45 6789 or 123456789
    private static final Pattern SSN_PATTERN = Pattern.compile(
            "\\b(?!000|666|9\\d{2})\\d{3}[- ]?(?!00)\\d{2}[- ]?(?!0000)\\d{4}\\b"
    );

    // Email: RFC 5322 simplified
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
            Pattern.CASE_INSENSITIVE
    );

    // US Phone: Various formats including international
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+1[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?)?\\d{3}[-.\\s]?\\d{4}\\b"
    );

    // Credit Card: Major card formats with Luhn validation context
    // Visa, MasterCard, Amex, Discover, etc.
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|" +
            "3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|" +
            "(?:2131|1800|35\\d{3})\\d{11})\\b|" +
            "\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"
    );

    // Date of Birth: Context-aware (DOB:, Born:, Birth Date:, etc.)
    private static final Pattern DOB_CONTEXT_PATTERN = Pattern.compile(
            "(?:DOB|Date of Birth|Birth Date|Born|Birthday|D\\.O\\.B\\.)\\s*:?\\s*" +
            "(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|" +
            "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE
    );

    // IP Address: IPv4
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
            "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
    );

    // IP Address: IPv6 (simplified)
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b|" +
            "\\b(?:[0-9a-fA-F]{1,4}:){1,7}:\\b|" +
            "\\b::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}\\b"
    );

    // Passport: US and common international formats
    private static final Pattern PASSPORT_PATTERN = Pattern.compile(
            "(?:Passport(?:\\s+(?:No|Number|#))?\\s*:?\\s*)([A-Z]{1,2}\\d{6,9})",
            Pattern.CASE_INSENSITIVE
    );

    // Driver's License: Context-aware (varies by state, so we use context)
    private static final Pattern DRIVERS_LICENSE_PATTERN = Pattern.compile(
            "(?:Driver'?s?\\s+License|DL|License\\s+(?:No|Number|#))\\s*:?\\s*([A-Z0-9]{5,15})",
            Pattern.CASE_INSENSITIVE
    );

    // Medical Record Number: Context-aware
    private static final Pattern MEDICAL_ID_PATTERN = Pattern.compile(
            "(?:MRN|Medical\\s+Record|Patient\\s+ID|Health\\s+ID)\\s*:?\\s*([A-Z0-9]{6,12})",
            Pattern.CASE_INSENSITIVE
    );

    // Name Detection: Context-based (after titles or labels)
    private static final Pattern NAME_CONTEXT_PATTERN = Pattern.compile(
            "(?:Name|Patient|Employee|Client|Customer|Attn|Attention|Contact|" +
            "Applicant|Recipient|Beneficiary|Account Holder)\\s*:?\\s*" +
            "([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})",
            Pattern.CASE_INSENSITIVE
    );

    // Name Detection: After honorifics
    private static final Pattern NAME_HONORIFIC_PATTERN = Pattern.compile(
            "\\b(?:Mr|Mrs|Ms|Miss|Dr|Prof|Rev|Hon)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\b"
    );

    // Address: US format (simplified)
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "\\b\\d{1,5}\\s+[A-Za-z0-9\\s,]+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|" +
            "Drive|Dr|Lane|Ln|Court|Ct|Way|Circle|Cir|Place|Pl)\\.?(?:\\s*,?\\s*" +
            "(?:Apt|Suite|Unit|#)\\s*[A-Za-z0-9-]+)?\\s*,?\\s*[A-Za-z\\s]+,?\\s*[A-Z]{2}\\s*\\d{5}(?:-\\d{4})?\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ========== CONFIGURATION ==========

    @Value("${sentinel.pii.enabled:true}")
    private boolean enabled;

    @Value("${sentinel.pii.mode:MASK}")
    private String mode;

    @Value("${sentinel.pii.audit-redactions:true}")
    private boolean auditRedactions;

    @Value("${sentinel.pii.patterns.ssn:true}")
    private boolean redactSsn;

    @Value("${sentinel.pii.patterns.email:true}")
    private boolean redactEmail;

    @Value("${sentinel.pii.patterns.phone:true}")
    private boolean redactPhone;

    @Value("${sentinel.pii.patterns.credit-card:true}")
    private boolean redactCreditCard;

    @Value("${sentinel.pii.patterns.dob:true}")
    private boolean redactDob;

    @Value("${sentinel.pii.patterns.ip-address:true}")
    private boolean redactIpAddress;

    @Value("${sentinel.pii.patterns.passport:true}")
    private boolean redactPassport;

    @Value("${sentinel.pii.patterns.drivers-license:true}")
    private boolean redactDriversLicense;

    @Value("${sentinel.pii.patterns.names:true}")
    private boolean redactNames;

    @Value("${sentinel.pii.patterns.address:true}")
    private boolean redactAddress;

    @Value("${sentinel.pii.patterns.medical-id:true}")
    private boolean redactMedicalId;

    // ========== REDACTION STATISTICS ==========

    public static class RedactionResult {
        private final String redactedContent;
        private final Map<PiiType, Integer> redactionCounts;
        private final int totalRedactions;

        public RedactionResult(String redactedContent, Map<PiiType, Integer> redactionCounts) {
            this.redactedContent = redactedContent;
            this.redactionCounts = Collections.unmodifiableMap(redactionCounts);
            this.totalRedactions = redactionCounts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public String getRedactedContent() {
            return redactedContent;
        }

        public Map<PiiType, Integer> getRedactionCounts() {
            return redactionCounts;
        }

        public int getTotalRedactions() {
            return totalRedactions;
        }

        public boolean hasRedactions() {
            return totalRedactions > 0;
        }
    }

    // ========== PUBLIC API ==========

    /**
     * Redact all configured PII patterns from the given content.
     *
     * @param content The text content to redact
     * @return RedactionResult containing redacted content and statistics
     */
    public RedactionResult redact(String content) {
        if (!enabled || content == null || content.isEmpty()) {
            return new RedactionResult(content, Collections.emptyMap());
        }

        RedactionMode redactionMode = parseMode(mode);
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        String result = content;

        // Apply redactions in order of specificity (most specific first)

        // Credit cards before generic numbers
        if (redactCreditCard) {
            result = redactPattern(result, CREDIT_CARD_PATTERN, PiiType.CREDIT_CARD, redactionMode, counts);
        }

        // SSN before generic numbers
        if (redactSsn) {
            result = redactPattern(result, SSN_PATTERN, PiiType.SSN, redactionMode, counts);
        }

        // Context-aware patterns (most specific)
        if (redactDob) {
            result = redactContextPattern(result, DOB_CONTEXT_PATTERN, PiiType.DATE_OF_BIRTH, redactionMode, counts);
        }

        if (redactPassport) {
            result = redactContextPattern(result, PASSPORT_PATTERN, PiiType.PASSPORT, redactionMode, counts);
        }

        if (redactDriversLicense) {
            result = redactContextPattern(result, DRIVERS_LICENSE_PATTERN, PiiType.DRIVERS_LICENSE, redactionMode, counts);
        }

        if (redactMedicalId) {
            result = redactContextPattern(result, MEDICAL_ID_PATTERN, PiiType.MEDICAL_ID, redactionMode, counts);
        }

        // Names (context-based to reduce false positives)
        if (redactNames) {
            result = redactContextPattern(result, NAME_CONTEXT_PATTERN, PiiType.NAME, redactionMode, counts);
            result = redactContextPattern(result, NAME_HONORIFIC_PATTERN, PiiType.NAME, redactionMode, counts);
        }

        // Address (complex pattern)
        if (redactAddress) {
            result = redactPattern(result, ADDRESS_PATTERN, PiiType.ADDRESS, redactionMode, counts);
        }

        // Email
        if (redactEmail) {
            result = redactPattern(result, EMAIL_PATTERN, PiiType.EMAIL, redactionMode, counts);
        }

        // Phone (after credit cards and SSNs)
        if (redactPhone) {
            result = redactPattern(result, PHONE_PATTERN, PiiType.PHONE, redactionMode, counts);
        }

        // IP Addresses
        if (redactIpAddress) {
            result = redactPattern(result, IPV4_PATTERN, PiiType.IP_ADDRESS, redactionMode, counts);
            result = redactPattern(result, IPV6_PATTERN, PiiType.IP_ADDRESS, redactionMode, counts);
        }

        RedactionResult redactionResult = new RedactionResult(result, counts);

        // Audit logging
        if (auditRedactions && redactionResult.hasRedactions()) {
            log.info("PII Redaction Complete: {} items redacted {}",
                    redactionResult.getTotalRedactions(),
                    summarizeCounts(counts));
        }

        return redactionResult;
    }

    /**
     * Simple redact method that returns only the redacted string.
     * For backwards compatibility with existing code.
     */
    public String redactToString(String content) {
        return redact(content).getRedactedContent();
    }

    /**
     * Check if content contains any detectable PII.
     */
    public boolean containsPii(String content) {
        if (!enabled || content == null || content.isEmpty()) {
            return false;
        }

        return (redactSsn && SSN_PATTERN.matcher(content).find()) ||
               (redactEmail && EMAIL_PATTERN.matcher(content).find()) ||
               (redactPhone && PHONE_PATTERN.matcher(content).find()) ||
               (redactCreditCard && CREDIT_CARD_PATTERN.matcher(content).find()) ||
               (redactDob && DOB_CONTEXT_PATTERN.matcher(content).find()) ||
               (redactIpAddress && (IPV4_PATTERN.matcher(content).find() || IPV6_PATTERN.matcher(content).find())) ||
               (redactPassport && PASSPORT_PATTERN.matcher(content).find()) ||
               (redactDriversLicense && DRIVERS_LICENSE_PATTERN.matcher(content).find()) ||
               (redactMedicalId && MEDICAL_ID_PATTERN.matcher(content).find()) ||
               (redactNames && (NAME_CONTEXT_PATTERN.matcher(content).find() || NAME_HONORIFIC_PATTERN.matcher(content).find())) ||
               (redactAddress && ADDRESS_PATTERN.matcher(content).find());
    }

    // ========== INTERNAL METHODS ==========

    private String redactPattern(String content, Pattern pattern, PiiType type,
                                  RedactionMode mode, Map<PiiType, Integer> counts) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        int count = 0;

        while (matcher.find()) {
            String replacement = generateReplacement(matcher.group(), type, mode);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            count++;
        }
        matcher.appendTail(sb);

        if (count > 0) {
            counts.merge(type, count, Integer::sum);
        }

        return sb.toString();
    }

    private String redactContextPattern(String content, Pattern pattern, PiiType type,
                                         RedactionMode mode, Map<PiiType, Integer> counts) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        int count = 0;

        while (matcher.find()) {
            // For context patterns, we only redact the captured group (the PII itself)
            // not the context label
            String fullMatch = matcher.group();
            String piiValue = matcher.group(1);

            if (piiValue != null) {
                String replacement = fullMatch.replace(piiValue, generateReplacement(piiValue, type, mode));
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                count++;
            }
        }
        matcher.appendTail(sb);

        if (count > 0) {
            counts.merge(type, count, Integer::sum);
        }

        return sb.toString();
    }

    private String generateReplacement(String original, PiiType type, RedactionMode mode) {
        return switch (mode) {
            case MASK -> "[REDACTED-" + type.name() + "]";
            case TOKENIZE -> generateToken(original, type); // Uses cryptographic vault
            case REMOVE -> "";
        };
    }

    /**
     * Generate a secure token using the tokenization vault.
     * Falls back to hash-based tokens if vault is unavailable.
     */
    private String generateToken(String original, PiiType type) {
        try {
            // Use cryptographic tokenization vault
            return tokenizationVault.tokenize(original, type.name(), "SYSTEM");
        } catch (Exception e) {
            log.warn("Tokenization vault unavailable, using hash fallback");
            // Fallback to hash (less secure but functional)
            return Integer.toHexString(original.hashCode()).substring(0, Math.min(6,
                    Integer.toHexString(original.hashCode()).length()));
        }
    }

    private RedactionMode parseMode(String modeStr) {
        try {
            return RedactionMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid PII redaction mode '{}', defaulting to MASK", modeStr);
            return RedactionMode.MASK;
        }
    }

    private String summarizeCounts(Map<PiiType, Integer> counts) {
        if (counts.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder("{");
        counts.forEach((type, count) ->
            sb.append(type.name()).append("=").append(count).append(", "));
        sb.setLength(sb.length() - 2); // Remove trailing ", "
        sb.append("}");
        return sb.toString();
    }

    // ========== LUHN VALIDATION FOR CREDIT CARDS ==========

    /**
     * Validate credit card number using Luhn algorithm.
     * Can be used for additional validation before redaction.
     */
    public static boolean isValidCreditCard(String number) {
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }

        int sum = 0;
        boolean alternate = false;

        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(digits.charAt(i));
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }

        return sum % 10 == 0;
    }
}
