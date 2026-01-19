/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.service.PiiRedactionService
 *  com.jreinhal.mercenary.service.PiiRedactionService$PiiType
 *  com.jreinhal.mercenary.service.PiiRedactionService$RedactionMode
 *  com.jreinhal.mercenary.service.PiiRedactionService$RedactionResult
 *  com.jreinhal.mercenary.service.TokenizationVault
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.service.PiiRedactionService;
import com.jreinhal.mercenary.service.TokenizationVault;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
 * Exception performing whole class analysis ignored.
 */
@Service
public class PiiRedactionService {
    private static final Logger log = LoggerFactory.getLogger(PiiRedactionService.class);
    private final TokenizationVault tokenizationVault;
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b(?!000|666|9\\d{2})\\d{3}[- ]?(?!00)\\d{2}[- ]?(?!0000)\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", 2);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:\\+1[-.\\s]?)?(?:\\(?\\d{3}\\)?[-.\\s]?)?\\d{3}[-.\\s]?\\d{4}\\b");
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b|\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b");
    private static final Pattern DOB_CONTEXT_PATTERN = Pattern.compile("(?:DOB|Date of Birth|Birth Date|Born|Birthday|D\\.O\\.B\\.)\\s*:?\\s*(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4})", 2);
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b");
    private static final Pattern IPV6_PATTERN = Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b|\\b(?:[0-9a-fA-F]{1,4}:){1,7}:\\b|\\b::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}\\b");
    private static final Pattern PASSPORT_PATTERN = Pattern.compile("(?:Passport(?:\\s+(?:No|Number|#))?\\s*:?\\s*)([A-Z]{1,2}\\d{6,9})", 2);
    private static final Pattern DRIVERS_LICENSE_PATTERN = Pattern.compile("(?:Driver'?s?\\s+License|DL|License\\s+(?:No|Number|#))\\s*:?\\s*([A-Z0-9]{5,15})", 2);
    private static final Pattern MEDICAL_ID_PATTERN = Pattern.compile("(?:MRN|Medical\\s+Record|Patient\\s+ID|Health\\s+ID)\\s*:?\\s*([A-Z0-9]{6,12})", 2);
    private static final Pattern NAME_CONTEXT_PATTERN = Pattern.compile("(?:Name|Patient|Employee|Client|Customer|Attn|Attention|Contact|Applicant|Recipient|Beneficiary|Account Holder)\\s*:?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})", 2);
    private static final Pattern NAME_HONORIFIC_PATTERN = Pattern.compile("\\b(?:Mr|Mrs|Ms|Miss|Dr|Prof|Rev|Hon)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\b");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("\\b\\d{1,5}\\s+[A-Za-z0-9\\s,]+(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Court|Ct|Way|Circle|Cir|Place|Pl)\\.?(?:\\s*,?\\s*(?:Apt|Suite|Unit|#)\\s*[A-Za-z0-9-]+)?\\s*,?\\s*[A-Za-z\\s]+,?\\s*[A-Z]{2}\\s*\\d{5}(?:-\\d{4})?\\b", 2);
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

    public PiiRedactionService(TokenizationVault tokenizationVault) {
        this.tokenizationVault = tokenizationVault;
    }

    public RedactionResult redact(String content) {
        if (!this.enabled || content == null || content.isEmpty()) {
            return new RedactionResult(content, Collections.emptyMap());
        }
        RedactionMode redactionMode = this.parseMode(this.mode);
        EnumMap counts = new EnumMap(PiiType.class);
        String result = content;
        if (this.redactCreditCard) {
            result = this.redactPattern(result, CREDIT_CARD_PATTERN, PiiType.CREDIT_CARD, redactionMode, counts);
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
        if (this.redactNames) {
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
            log.info("PII Redaction Complete: {} items redacted {}", (Object)redactionResult.getTotalRedactions(), (Object)this.summarizeCounts(counts));
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
        return this.redactSsn && SSN_PATTERN.matcher(content).find() || this.redactEmail && EMAIL_PATTERN.matcher(content).find() || this.redactPhone && PHONE_PATTERN.matcher(content).find() || this.redactCreditCard && CREDIT_CARD_PATTERN.matcher(content).find() || this.redactDob && DOB_CONTEXT_PATTERN.matcher(content).find() || this.redactIpAddress && (IPV4_PATTERN.matcher(content).find() || IPV6_PATTERN.matcher(content).find()) || this.redactPassport && PASSPORT_PATTERN.matcher(content).find() || this.redactDriversLicense && DRIVERS_LICENSE_PATTERN.matcher(content).find() || this.redactMedicalId && MEDICAL_ID_PATTERN.matcher(content).find() || this.redactNames && (NAME_CONTEXT_PATTERN.matcher(content).find() || NAME_HONORIFIC_PATTERN.matcher(content).find()) || this.redactAddress && ADDRESS_PATTERN.matcher(content).find();
    }

    private String redactPattern(String content, Pattern pattern, PiiType type, RedactionMode mode, Map<PiiType, Integer> counts) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
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
        StringBuffer sb = new StringBuffer();
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
            default -> throw new MatchException(null, null);
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
            log.warn("Tokenization vault unavailable, using hash fallback");
            return Integer.toHexString(original.hashCode()).substring(0, Math.min(6, Integer.toHexString(original.hashCode()).length()));
        }
    }

    private RedactionMode parseMode(String modeStr) {
        try {
            return RedactionMode.valueOf((String)modeStr.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            log.warn("Invalid PII redaction mode '{}', defaulting to MASK", (Object)modeStr);
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
}

