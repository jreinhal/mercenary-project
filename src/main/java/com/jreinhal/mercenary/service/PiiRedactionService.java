package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.service.TokenizationVault;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PiiRedactionService {
    private static final Logger log = LoggerFactory.getLogger(PiiRedactionService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final TokenizationVault tokenizationVault;
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b(?!000|666|9\\d{2})\\d{3}[- ]?(?!00)\\d{2}[- ]?(?!0000)\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", 2);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<![A-Za-z0-9])(?:\\+1[\\s.-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b");
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b|\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b");
    private static final Pattern DOB_CONTEXT_PATTERN = Pattern.compile("(?:DOB|Date of Birth|Birth Date|Born|Birthday|D\\.O\\.B\\.)\\s*:?\\s*(\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4})", 2);
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
    private static final Pattern IPV6_PATTERN = Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b|\\b(?:[0-9a-fA-F]{1,4}:){1,7}:\\b|\\b::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}\\b");
    private static final Pattern PASSPORT_PATTERN = Pattern.compile("(?:Passport(?:\\s+(?:No|Number|#))?\\s*:?\\s*)([A-Z]{1,2}\\d{6,9})", 2);
    private static final Pattern DRIVERS_LICENSE_PATTERN = Pattern.compile("(?:Driver'?s?\\s+License|DL|License\\s+(?:No|Number|#))\\s*:?\\s*([A-Z0-9]{5,15})", 2);
    private static final Pattern MEDICAL_ID_PATTERN = Pattern.compile("(?:MRN|Medical\\s+Record|Patient\\s+ID|Health\\s+ID)\\s*:?\\s*([A-Z0-9][A-Z0-9-]{4,17})", 2);
    private static final Pattern ACCOUNT_CONTEXT_PATTERN = Pattern.compile("(?:account|acct|account\\s+number|acct\\s+no|member\\s+id|subscriber\\s+id|policy\\s+number|insurance\\s+id|beneficiary\\s+id)\\s*:?\\s*([A-Z0-9-]{6,})", 2);
    private static final Pattern HEALTH_PLAN_PATTERN = Pattern.compile("(?:health\\s+plan\\s+id|plan\\s+id|group\\s+number)\\s*:?\\s*([A-Z0-9-]{6,})", 2);
    private static final Pattern CERTIFICATE_PATTERN = Pattern.compile("(?:certificate|cert|license)\\s*(?:no|number|#)?\\s*:?\\s*([A-Z0-9-]{5,})", 2);
    private static final Pattern VIN_PATTERN = Pattern.compile("(?:VIN|Vehicle\\s+ID|Vehicle\\s+Identification\\s+Number)\\s*:?\\s*([A-HJ-NPR-Z0-9]{17})", 2);
    private static final Pattern LICENSE_PLATE_PATTERN = Pattern.compile("(?:license\\s+plate|plate)\\s*:?\\s*([A-Z0-9-]{4,8})", 2);
    private static final Pattern LICENSE_PLATE_VALUE_PATTERN = Pattern.compile("\\b[A-Z]{1,3}-\\d{3,4}\\b", 2);
    private static final Pattern MAC_ADDRESS_PATTERN = Pattern.compile("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b");
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("(?:IMEI|MEID|ESN|Device\\s+ID|Device\\s+Serial|Serial\\s+Number)\\s*:?\\s*([A-Za-z0-9-]{6,})", 2);
    private static final Pattern URL_PATTERN = Pattern.compile("\\bhttps?://[^\\s]+\\b|\\bwww\\.[^\\s]+\\b", 2);
    private static final Pattern BIOMETRIC_PATTERN = Pattern.compile("(?:fingerprint|retina|iris|voiceprint|faceprint|biometric)\\s*(?:id|hash|template)?\\s*:?\\s*([A-Za-z0-9-]{6,})", 2);
    private static final Pattern DATE_CONTEXT_PATTERN = Pattern.compile("(?:Admission|Discharge|Visit|Appointment|Service|Procedure|Encounter|Death)\\s*Date\\s*:?\\s*(\\d{1,2}[/\\-.]\\d{1,2}(?:[/\\-.]\\d{2,4})?|\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4})", 2);
    private static final Pattern AGE_PATTERN = Pattern.compile("\\b(?:age|aged)\\s*(9\\d|[1-9]\\d{2,})\\b", 2);
    private static final Pattern NAME_CONTEXT_PATTERN = Pattern.compile("(?:Name|Patient|Employee|Client|Customer|Attn|Attention|Contact|Applicant|Recipient|Beneficiary|Account Holder)\\s*:?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})", 2);
    private static final Pattern NAME_HONORIFIC_PATTERN = Pattern.compile("\\b(?:Mr|Mrs|Ms|Miss|Dr|Prof|Rev|Hon)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\b");
    // M-18: Use possessive quantifiers (++) to prevent catastrophic backtracking (ReDoS)
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("\\b\\d{1,5}\\s++[A-Za-z0-9\\s,]++(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Court|Ct|Way|Circle|Cir|Place|Pl)\\.?(?:\\s*+,?\\s*+(?:Apt|Suite|Unit|#)\\s*+[A-Za-z0-9-]++)?\\s*+,?\\s*+[A-Za-z\\s]++,?\\s*+[A-Z]{2}\\s*+\\d{5}(?:-\\d{4})?\\b", 2);
    @Value(value="${sentinel.pii.enabled:true}")
    private boolean enabled;
    @Value(value="${sentinel.pii.mode:MASK}")
    private String mode;
    @Value(value="${sentinel.pii.audit-redactions:true}")
    private boolean auditRedactions;
    @Value(value="${sentinel.pii.patterns.ssn:true}")
    private boolean redactSsn;
    @Value(value="${sentinel.pii.patterns.email:true}")
    private boolean redactEmail;
    @Value(value="${sentinel.pii.patterns.phone:true}")
    private boolean redactPhone;
    @Value(value="${sentinel.pii.patterns.credit-card:true}")
    private boolean redactCreditCard;
    @Value(value="${sentinel.pii.patterns.dob:true}")
    private boolean redactDob;
    @Value(value="${sentinel.pii.patterns.ip-address:true}")
    private boolean redactIpAddress;
    @Value(value="${sentinel.pii.patterns.passport:true}")
    private boolean redactPassport;
    @Value(value="${sentinel.pii.patterns.drivers-license:true}")
    private boolean redactDriversLicense;
    @Value(value="${sentinel.pii.patterns.names:true}")
    private boolean redactNames;
    @Value(value="${sentinel.pii.patterns.address:true}")
    private boolean redactAddress;
    @Value(value="${sentinel.pii.patterns.medical-id:true}")
    private boolean redactMedicalId;
    @Value(value="${sentinel.pii.patterns.account-number:true}")
    private boolean redactAccountNumber;
    @Value(value="${sentinel.pii.patterns.health-plan-id:true}")
    private boolean redactHealthPlanId;
    @Value(value="${sentinel.pii.patterns.certificate-number:true}")
    private boolean redactCertificateNumber;
    @Value(value="${sentinel.pii.patterns.vehicle-id:true}")
    private boolean redactVehicleId;
    @Value(value="${sentinel.pii.patterns.device-id:true}")
    private boolean redactDeviceId;
    @Value(value="${sentinel.pii.patterns.url:true}")
    private boolean redactUrl;
    @Value(value="${sentinel.pii.patterns.biometric:true}")
    private boolean redactBiometric;
    @Value(value="${sentinel.pii.patterns.date:true}")
    private boolean redactDate;
    @Value(value="${sentinel.pii.patterns.age:true}")
    private boolean redactAge;

    public PiiRedactionService(TokenizationVault tokenizationVault) {
        this.tokenizationVault = tokenizationVault;
    }

    public RedactionResult redact(String content) {
        return this.redact(content, null);
    }

    public RedactionResult redact(String content, Boolean redactNamesOverride) {
        if (!this.enabled || content == null || content.isEmpty()) {
            return new RedactionResult(content, Collections.emptyMap());
        }
        // C-08: Normalize Unicode to defeat zero-width char and homoglyph bypass
        content = normalizeUnicode(content);
        boolean applyNames = redactNamesOverride != null ? redactNamesOverride : this.redactNames;
        RedactionMode redactionMode = this.parseMode(this.mode);
        EnumMap<PiiType, Integer> counts = new EnumMap<PiiType, Integer>(PiiType.class);
        String result = content;
        if (this.redactCreditCard) {
            // M-15: Validate generic 16-digit matches with Luhn check to avoid false positives
            result = this.redactCreditCardPattern(result, redactionMode, counts);
        }
        if (this.redactSsn) {
            result = this.redactPattern(result, SSN_PATTERN, PiiType.SSN, redactionMode, counts);
        }
        if (this.redactDob) {
            result = this.redactContextPattern(result, DOB_CONTEXT_PATTERN, PiiType.DATE_OF_BIRTH, redactionMode, counts);
        }
        if (this.redactPassport) {
            result = this.redactContextPattern(result, PASSPORT_PATTERN, PiiType.PASSPORT, redactionMode, counts);
        }
        if (this.redactDriversLicense) {
            result = this.redactContextPattern(result, DRIVERS_LICENSE_PATTERN, PiiType.DRIVERS_LICENSE, redactionMode, counts);
        }
        if (this.redactMedicalId) {
            result = this.redactContextPattern(result, MEDICAL_ID_PATTERN, PiiType.MEDICAL_ID, redactionMode, counts);
        }
        if (this.redactAccountNumber) {
            result = this.redactContextPattern(result, ACCOUNT_CONTEXT_PATTERN, PiiType.ACCOUNT_NUMBER, redactionMode, counts);
        }
        if (this.redactHealthPlanId) {
            result = this.redactContextPattern(result, HEALTH_PLAN_PATTERN, PiiType.HEALTH_PLAN_ID, redactionMode, counts);
        }
        if (this.redactCertificateNumber) {
            result = this.redactContextPattern(result, CERTIFICATE_PATTERN, PiiType.CERTIFICATE_NUMBER, redactionMode, counts);
        }
        if (this.redactVehicleId) {
            result = this.redactContextPattern(result, VIN_PATTERN, PiiType.VEHICLE_ID, redactionMode, counts);
            result = this.redactContextPattern(result, LICENSE_PLATE_PATTERN, PiiType.VEHICLE_ID, redactionMode, counts);
            result = this.redactPattern(result, LICENSE_PLATE_VALUE_PATTERN, PiiType.VEHICLE_ID, redactionMode, counts);
        }
        if (this.redactDeviceId) {
            result = this.redactPattern(result, MAC_ADDRESS_PATTERN, PiiType.DEVICE_ID, redactionMode, counts);
            result = this.redactContextPattern(result, DEVICE_ID_PATTERN, PiiType.DEVICE_ID, redactionMode, counts);
        }
        if (this.redactUrl) {
            result = this.redactPattern(result, URL_PATTERN, PiiType.URL, redactionMode, counts);
        }
        if (this.redactBiometric) {
            result = this.redactContextPattern(result, BIOMETRIC_PATTERN, PiiType.BIOMETRIC_ID, redactionMode, counts);
        }
        if (this.redactDate) {
            result = this.redactContextPattern(result, DATE_CONTEXT_PATTERN, PiiType.DATE, redactionMode, counts);
        }
        if (this.redactAge) {
            result = this.redactContextPattern(result, AGE_PATTERN, PiiType.AGE, redactionMode, counts);
        }
        if (applyNames) {
            result = this.redactContextPattern(result, NAME_CONTEXT_PATTERN, PiiType.NAME, redactionMode, counts);
            result = this.redactContextPattern(result, NAME_HONORIFIC_PATTERN, PiiType.NAME, redactionMode, counts);
        }
        if (this.redactAddress) {
            result = this.redactPattern(result, ADDRESS_PATTERN, PiiType.ADDRESS, redactionMode, counts);
        }
        if (this.redactEmail) {
            result = this.redactPattern(result, EMAIL_PATTERN, PiiType.EMAIL, redactionMode, counts);
        }
        if (this.redactPhone) {
            result = this.redactPattern(result, PHONE_PATTERN, PiiType.PHONE, redactionMode, counts);
        }
        if (this.redactIpAddress) {
            result = this.redactPattern(result, IPV4_PATTERN, PiiType.IP_ADDRESS, redactionMode, counts);
            result = this.redactPattern(result, IPV6_PATTERN, PiiType.IP_ADDRESS, redactionMode, counts);
        }
        RedactionResult redactionResult = new RedactionResult(result, counts);
        if (this.auditRedactions && redactionResult.hasRedactions()) {
            log.info("PII Redaction Complete: {} items redacted {}", redactionResult.getTotalRedactions(), this.summarizeCounts(counts));
        }
        return redactionResult;
    }

    public String redactToString(String content) {
        return this.redact(content).getRedactedContent();
    }

    public boolean containsPii(String content) {
        if (!this.enabled || content == null || content.isEmpty()) {
            return false;
        }
        content = normalizeUnicode(content);
        return this.redactSsn && SSN_PATTERN.matcher(content).find()
            || this.redactEmail && EMAIL_PATTERN.matcher(content).find()
            || this.redactPhone && PHONE_PATTERN.matcher(content).find()
            || this.redactCreditCard && CREDIT_CARD_PATTERN.matcher(content).find()
            || this.redactDob && DOB_CONTEXT_PATTERN.matcher(content).find()
            || this.redactIpAddress && (IPV4_PATTERN.matcher(content).find() || IPV6_PATTERN.matcher(content).find())
            || this.redactPassport && PASSPORT_PATTERN.matcher(content).find()
            || this.redactDriversLicense && DRIVERS_LICENSE_PATTERN.matcher(content).find()
            || this.redactMedicalId && MEDICAL_ID_PATTERN.matcher(content).find()
            || this.redactAccountNumber && ACCOUNT_CONTEXT_PATTERN.matcher(content).find()
            || this.redactHealthPlanId && HEALTH_PLAN_PATTERN.matcher(content).find()
            || this.redactCertificateNumber && CERTIFICATE_PATTERN.matcher(content).find()
            || this.redactVehicleId && (VIN_PATTERN.matcher(content).find() || LICENSE_PLATE_PATTERN.matcher(content).find())
            || this.redactVehicleId && LICENSE_PLATE_VALUE_PATTERN.matcher(content).find()
            || this.redactDeviceId && (MAC_ADDRESS_PATTERN.matcher(content).find() || DEVICE_ID_PATTERN.matcher(content).find())
            || this.redactUrl && URL_PATTERN.matcher(content).find()
            || this.redactBiometric && BIOMETRIC_PATTERN.matcher(content).find()
            || this.redactDate && DATE_CONTEXT_PATTERN.matcher(content).find()
            || this.redactAge && AGE_PATTERN.matcher(content).find()
            || this.redactNames && (NAME_CONTEXT_PATTERN.matcher(content).find() || NAME_HONORIFIC_PATTERN.matcher(content).find())
            || this.redactAddress && ADDRESS_PATTERN.matcher(content).find();
    }

    private String redactPattern(String content, Pattern pattern, PiiType type, RedactionMode mode, Map<PiiType, Integer> counts) {
        Matcher matcher = pattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            String replacement = this.generateReplacement(matcher.group(), type, mode);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            ++count;
        }
        matcher.appendTail(sb);
        if (count > 0) {
            counts.merge(type, count, Integer::sum);
        }
        return sb.toString();
    }

    private String redactContextPattern(String content, Pattern pattern, PiiType type, RedactionMode mode, Map<PiiType, Integer> counts) {
        Matcher matcher = pattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            String fullMatch = matcher.group();
            String piiValue = matcher.group(1);
            if (piiValue == null) continue;
            String replacement = fullMatch.replace(piiValue, this.generateReplacement(piiValue, type, mode));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            ++count;
        }
        matcher.appendTail(sb);
        if (count > 0) {
            counts.merge(type, count, Integer::sum);
        }
        return sb.toString();
    }

    private String generateReplacement(String original, PiiType type, RedactionMode mode) {
        return switch (mode.ordinal()) {
            default -> throw new IllegalArgumentException("Unknown PII redaction mode: " + mode);
            case 0 -> "[REDACTED-" + type.name() + "]";
            case 1 -> this.generateToken(original, type);
            case 2 -> "";
        };
    }

    private String generateToken(String original, PiiType type) {
        try {
            return this.tokenizationVault.tokenize(original, type.name(), "SYSTEM");
        }
        catch (Exception e) {
            // C-06: Use cryptographically secure random token instead of reversible hashCode
            log.warn("Tokenization vault unavailable, using secure random fallback");
            byte[] randomBytes = new byte[8];
            SECURE_RANDOM.nextBytes(randomBytes);
            return "OPAQUE-" + HexFormat.of().formatHex(randomBytes);
        }
    }

    private String redactCreditCardPattern(String content, RedactionMode mode, Map<PiiType, Integer> counts) {
        Matcher matcher = CREDIT_CARD_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            String match = matcher.group();
            String digits = match.replaceAll("[^0-9]", "");
            // Brand-specific patterns (first regex alternative) have prefix constraints;
            // the generic fallback is 4-groups-of-4 digits which is always exactly 16 digits.
            // Check known brand prefixes on the digits-only string to distinguish the two.
            boolean isBrandSpecific = digits.matches("^4[0-9]{12,15}$")           // Visa 13-16
                || digits.matches("^5[1-5][0-9]{14}$")                            // Mastercard
                || digits.matches("^3[47][0-9]{13}$")                             // Amex
                || digits.matches("^3(?:0[0-5]|[68][0-9])[0-9]{11}$")             // Diners
                || digits.matches("^6(?:011|5[0-9]{2})[0-9]{12}$")                // Discover
                || digits.matches("^(?:2131|1800|35[0-9]{3})[0-9]{11}$");         // JCB
            if (!isBrandSpecific && !isValidCreditCard(match)) {
                // Preserve non-Luhn 16-digit numbers as-is (e.g., order IDs)
                matcher.appendReplacement(sb, Matcher.quoteReplacement(match));
                continue;
            }
            String replacement = this.generateReplacement(match, PiiType.CREDIT_CARD, mode);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            ++count;
        }
        matcher.appendTail(sb);
        if (count > 0) {
            counts.merge(PiiType.CREDIT_CARD, count, Integer::sum);
        }
        return sb.toString();
    }

    /**
     * C-08: Strip zero-width characters and normalize Unicode to prevent PII pattern bypass.
     * Applies NFKC normalization to convert fullwidth/variant digits to ASCII equivalents.
     */
    static String normalizeUnicode(String input) {
        if (input == null) {
            return null;
        }
        // Strip zero-width characters that can split PII tokens
        String stripped = input
            .replace("\u200B", "")  // zero-width space
            .replace("\u200C", "")  // zero-width non-joiner
            .replace("\u200D", "")  // zero-width joiner
            .replace("\uFEFF", "")  // byte order mark
            .replace("\u00AD", "")  // soft hyphen
            .replace("\u2060", "")  // word joiner
            .replace("\u180E", ""); // Mongolian vowel separator
        // NFKC normalizes fullwidth digits, mathematical digits, etc. to ASCII
        return Normalizer.normalize(stripped, Normalizer.Form.NFKC);
    }

    private RedactionMode parseMode(String modeStr) {
        try {
            return RedactionMode.valueOf(modeStr.toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            log.warn("Invalid PII redaction mode '{}', defaulting to MASK", modeStr);
            return RedactionMode.MASK;
        }
    }

    private String summarizeCounts(Map<PiiType, Integer> counts) {
        if (counts.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        counts.forEach((type, count) -> sb.append(type.name()).append("=").append(count).append(", "));
        sb.setLength(sb.length() - 2);
        sb.append("}");
        return sb.toString();
    }

    public static boolean isValidCreditCard(String number) {
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; --i) {
            int digit = Character.getNumericValue(digits.charAt(i));
            if (alternate && (digit *= 2) > 9) {
                digit -= 9;
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

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
            return this.redactedContent;
        }

        public Map<PiiType, Integer> getRedactionCounts() {
            return this.redactionCounts;
        }

        public int getTotalRedactions() {
            return this.totalRedactions;
        }

        public boolean hasRedactions() {
            return this.totalRedactions > 0;
        }
    }

    public static enum RedactionMode {
        MASK,
        TOKENIZE,
        REMOVE;

    }

    public static enum PiiType {
        SSN("Social Security Number"),
        EMAIL("Email Address"),
        PHONE("Phone Number"),
        CREDIT_CARD("Credit Card Number"),
        DATE_OF_BIRTH("Date of Birth"),
        DATE("Sensitive Date"),
        AGE("Age 90+"),
        IP_ADDRESS("IP Address"),
        PASSPORT("Passport Number"),
        DRIVERS_LICENSE("Driver's License"),
        ACCOUNT_NUMBER("Account Number"),
        HEALTH_PLAN_ID("Health Plan Beneficiary Number"),
        CERTIFICATE_NUMBER("Certificate/License Number"),
        VEHICLE_ID("Vehicle Identifier"),
        DEVICE_ID("Device Identifier"),
        URL("URL"),
        BIOMETRIC_ID("Biometric Identifier"),
        NAME("Personal Name"),
        ADDRESS("Physical Address"),
        MEDICAL_ID("Medical Record Number");

        private final String displayName;

        private PiiType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return this.displayName;
        }
    }
}
