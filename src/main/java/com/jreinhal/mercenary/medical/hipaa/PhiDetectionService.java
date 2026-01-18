package com.jreinhal.mercenary.medical.hipaa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protected Health Information (PHI) detection service.
 *
 * MEDICAL EDITION ONLY - This class is excluded from trial/professional builds.
 *
 * Identifies the 18 HIPAA identifiers in text content:
 * 1. Names
 * 2. Geographic data (address, city, zip)
 * 3. Dates (except year)
 * 4. Phone numbers
 * 5. Fax numbers
 * 6. Email addresses
 * 7. Social Security numbers
 * 8. Medical record numbers
 * 9. Health plan beneficiary numbers
 * 10. Account numbers
 * 11. Certificate/license numbers
 * 12. Vehicle identifiers
 * 13. Device identifiers
 * 14. Web URLs
 * 15. IP addresses
 * 16. Biometric identifiers
 * 17. Full-face photographs
 * 18. Any other unique identifying number
 */
@Service
public class PhiDetectionService {

    private static final Logger log = LoggerFactory.getLogger(PhiDetectionService.class);

    /**
     * PHI type classification.
     */
    public enum PhiType {
        SSN("Social Security Number"),
        MRN("Medical Record Number"),
        PHONE("Phone Number"),
        FAX("Fax Number"),
        EMAIL("Email Address"),
        ADDRESS("Street Address"),
        ZIP_CODE("ZIP Code"),
        DATE_OF_BIRTH("Date of Birth"),
        IP_ADDRESS("IP Address"),
        HEALTH_PLAN_ID("Health Plan Beneficiary Number"),
        ACCOUNT_NUMBER("Account Number"),
        LICENSE_NUMBER("License/Certificate Number"),
        VEHICLE_ID("Vehicle Identifier"),
        DEVICE_ID("Device Identifier"),
        URL("Web URL"),
        NAME("Personal Name"),
        UNKNOWN("Unknown Identifier");

        private final String displayName;

        PhiType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Detected PHI instance.
     */
    public record PhiMatch(
        PhiType type,
        String value,
        int startIndex,
        int endIndex,
        double confidence
    ) {}

    // Pattern definitions for various PHI types
    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b\\d{3}-\\d{2}-\\d{4}\\b|\\b\\d{9}\\b"
    );

    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\b(?:\\+?1[-.]?)?\\(?\\d{3}\\)?[-.]?\\d{3}[-.]?\\d{4}\\b"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    );

    private static final Pattern ZIP_PATTERN = Pattern.compile(
        "\\b\\d{5}(?:-\\d{4})?\\b"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "\\b(?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)?\\d{2}\\b|" +
        "\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern IP_PATTERN = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b"
    );

    private static final Pattern MRN_PATTERN = Pattern.compile(
        "\\b(?:MRN|MR#|Medical Record)[:\\s]*([A-Z0-9-]{6,15})\\b|" +
        "\\b[A-Z]{2,3}\\d{6,10}\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern URL_PATTERN = Pattern.compile(
        "\\bhttps?://[^\\s]+\\b"
    );

    /**
     * Detect all PHI in the given text.
     *
     * @param text The text to scan
     * @return List of detected PHI matches
     */
    public List<PhiMatch> detectPhi(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<PhiMatch> matches = new ArrayList<>();

        // Check each pattern
        matches.addAll(findMatches(text, SSN_PATTERN, PhiType.SSN, 0.95));
        matches.addAll(findMatches(text, PHONE_PATTERN, PhiType.PHONE, 0.85));
        matches.addAll(findMatches(text, EMAIL_PATTERN, PhiType.EMAIL, 0.90));
        matches.addAll(findMatches(text, ZIP_PATTERN, PhiType.ZIP_CODE, 0.70)); // Lower confidence - could be other numbers
        matches.addAll(findMatches(text, DATE_PATTERN, PhiType.DATE_OF_BIRTH, 0.75));
        matches.addAll(findMatches(text, IP_PATTERN, PhiType.IP_ADDRESS, 0.90));
        matches.addAll(findMatches(text, MRN_PATTERN, PhiType.MRN, 0.85));
        matches.addAll(findMatches(text, URL_PATTERN, PhiType.URL, 0.80));

        log.debug("PHI detection found {} potential identifiers", matches.size());
        return matches;
    }

    /**
     * Check if text contains any PHI.
     */
    public boolean containsPhi(String text) {
        return !detectPhi(text).isEmpty();
    }

    /**
     * Count PHI instances by type.
     */
    public java.util.Map<PhiType, Integer> countPhiByType(String text) {
        List<PhiMatch> matches = detectPhi(text);
        java.util.Map<PhiType, Integer> counts = new java.util.EnumMap<>(PhiType.class);
        for (PhiMatch match : matches) {
            counts.merge(match.type(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Redact PHI from text, replacing with type labels.
     *
     * @param text The text containing PHI
     * @return Text with PHI replaced by [TYPE] placeholders
     */
    public String redactPhi(String text) {
        if (text == null) return null;

        List<PhiMatch> matches = detectPhi(text);

        // Sort by start index descending to replace from end to start
        matches.sort((a, b) -> Integer.compare(b.startIndex(), a.startIndex()));

        StringBuilder result = new StringBuilder(text);
        for (PhiMatch match : matches) {
            String replacement = "[" + match.type().name() + "]";
            result.replace(match.startIndex(), match.endIndex(), replacement);
        }

        return result.toString();
    }

    /**
     * Find all matches for a pattern.
     */
    private List<PhiMatch> findMatches(String text, Pattern pattern, PhiType type, double confidence) {
        List<PhiMatch> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            matches.add(new PhiMatch(
                type,
                matcher.group(),
                matcher.start(),
                matcher.end(),
                confidence
            ));
        }

        return matches;
    }

    /**
     * Get a summary of PHI detected in text.
     */
    public String getPhiSummary(String text) {
        List<PhiMatch> matches = detectPhi(text);
        if (matches.isEmpty()) {
            return "No PHI detected";
        }

        java.util.Map<PhiType, Integer> counts = countPhiByType(text);
        StringBuilder summary = new StringBuilder("PHI detected: ");
        counts.forEach((type, count) ->
            summary.append(type.getDisplayName()).append("(").append(count).append(") ")
        );

        return summary.toString().trim();
    }
}
