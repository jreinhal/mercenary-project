package com.jreinhal.mercenary.core.license;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages license validation for SENTINEL editions.
 *
 * <p>License key format: {@code BASE64(edition:expiry:customerId):HMAC_SHA256_HEX}</p>
 *
 * <p>When no license key or signing secret is configured, the service runs in
 * "unlicensed mode" (backward compatible). When a key is present but cannot be
 * validated (missing secret or invalid signature), the license is marked invalid.</p>
 */
@Service
public class LicenseService {
    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${sentinel.license.edition:ENTERPRISE}")
    private String editionString;
    @Value("${sentinel.license.key:}")
    private String licenseKey;
    @Value("${sentinel.license.signing-secret:}")
    private String signingSecret;
    @Value("${sentinel.license.trial-start:}")
    private String trialStartDate;
    @Value("${sentinel.license.trial-days:30}")
    private int trialDays;

    private Edition edition;
    private Instant trialExpiration;
    private boolean licenseValid = false;
    private String customerId;

    @PostConstruct
    public void initialize() {
        this.edition = this.parseEdition(this.editionString);
        if (this.edition == Edition.TRIAL) {
            this.initializeTrial();
        } else {
            this.validateLicenseKey();
        }
        log.info("SENTINEL License: Edition={}, Valid={}, Expires={}",
                new Object[]{this.edition, this.licenseValid,
                this.trialExpiration != null ? this.trialExpiration : "Never"});
    }

    private Edition parseEdition(String value) {
        String normalized = value.toUpperCase().trim();
        // Backward compatibility: PROFESSIONAL was renamed to ENTERPRISE
        if ("PROFESSIONAL".equals(normalized)) {
            log.info("Mapping legacy edition PROFESSIONAL to ENTERPRISE");
            return Edition.ENTERPRISE;
        }
        try {
            return Edition.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown edition '{}', defaulting to TRIAL", value);
            return Edition.TRIAL;
        }
    }

    private void initializeTrial() {
        LocalDate startDate;
        if (this.trialStartDate != null && !this.trialStartDate.isBlank()) {
            startDate = LocalDate.parse(this.trialStartDate);
        } else {
            startDate = LocalDate.now();
            log.info("Trial started: {}", startDate);
        }
        LocalDate expirationDate = startDate.plusDays(this.trialDays);
        this.trialExpiration = expirationDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        this.licenseValid = Instant.now().isBefore(this.trialExpiration);
        if (!this.licenseValid) {
            log.warn("Trial expired on {}. Contact sales for licensing.", expirationDate);
        } else {
            long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), expirationDate);
            log.info("Trial active: {} days remaining", daysRemaining);
        }
    }

    /**
     * Validates the license key using HMAC-SHA256 signature verification.
     *
     * <p>License key format: {@code BASE64(edition:expiry:customerId):HMAC_HEX}</p>
     *
     * <p>Behavior matrix:</p>
     * <ul>
     *   <li>No key + no secret → unlicensed mode (valid, backward compatible)</li>
     *   <li>Key present + no secret → invalid (cannot verify signature)</li>
     *   <li>Key present + secret → HMAC validation (checks signature, edition, expiry)</li>
     * </ul>
     */
    void validateLicenseKey() {
        if ((this.licenseKey == null || this.licenseKey.isBlank())
                && (this.signingSecret == null || this.signingSecret.isBlank())) {
            log.warn("No license key configured. Running in unlicensed mode.");
            this.licenseValid = true;
            return;
        }

        if (this.licenseKey == null || this.licenseKey.isBlank()) {
            log.warn("Signing secret configured but no license key provided.");
            this.licenseValid = false;
            return;
        }

        if (this.signingSecret == null || this.signingSecret.isBlank()) {
            log.error("License key provided but no signing secret configured. Cannot validate.");
            this.licenseValid = false;
            return;
        }

        try {
            this.licenseValid = this.verifyAndParseLicenseKey(this.licenseKey, this.signingSecret);
        } catch (Exception e) {
            log.error("License key validation failed: {}", e.getMessage());
            this.licenseValid = false;
        }
    }

    /**
     * Verifies HMAC signature and parses the license key payload.
     *
     * @return true if the key is valid, not expired, and matches the configured edition
     */
    private boolean verifyAndParseLicenseKey(String key, String secret) {
        // Key format: BASE64_PAYLOAD:HMAC_HEX
        int separatorIndex = key.lastIndexOf(':');
        if (separatorIndex <= 0 || separatorIndex >= key.length() - 1) {
            log.error("Invalid license key format: missing signature separator");
            return false;
        }

        String payloadBase64 = key.substring(0, separatorIndex);
        String providedSignature = key.substring(separatorIndex + 1);

        // Verify HMAC signature
        String expectedSignature = computeHmac(payloadBase64, secret);
        if (expectedSignature == null || !constantTimeEquals(expectedSignature, providedSignature)) {
            log.error("License key signature verification failed");
            return false;
        }

        // Decode and parse payload
        String payload;
        try {
            payload = new String(Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("License key payload is not valid Base64");
            return false;
        }

        // Payload format: edition:expiryDate:customerId
        String[] parts = payload.split(":", 3);
        if (parts.length < 3) {
            log.error("Invalid license key payload format: expected edition:expiry:customerId");
            return false;
        }

        String keyEdition = parts[0].toUpperCase().trim();
        String keyExpiry = parts[1].trim();
        this.customerId = parts[2].trim();

        // Validate edition matches
        Edition keyEditionEnum;
        try {
            keyEditionEnum = Edition.valueOf(keyEdition);
        } catch (IllegalArgumentException e) {
            log.error("License key contains unknown edition: {}", keyEdition);
            return false;
        }

        if (keyEditionEnum != this.edition) {
            log.error("License key edition mismatch: key={}, configured={}", keyEditionEnum, this.edition);
            return false;
        }

        // Validate expiry
        try {
            LocalDate expiryDate = LocalDate.parse(keyExpiry);
            this.trialExpiration = expiryDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
            if (Instant.now().isAfter(this.trialExpiration)) {
                log.error("License key expired on {}", expiryDate);
                return false;
            }
            long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
            log.info("License validated: edition={}, customer={}, expires in {} days",
                    keyEditionEnum, this.customerId, daysRemaining);
        } catch (Exception e) {
            log.error("Invalid expiry date in license key: {}", keyExpiry);
            return false;
        }

        return true;
    }

    /**
     * Computes HMAC-SHA256 of the given data using the provided secret.
     */
    static String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmacBytes);
        } catch (Exception e) {
            log.error("HMAC computation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generates a license key for the given parameters.
     * This method is exposed for the license generation tool.
     */
    public static String generateLicenseKey(String edition, String expiryDate, String customerId, String secret) {
        String payload = edition + ":" + expiryDate + ":" + customerId;
        String payloadBase64 = Base64.getEncoder().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmac(payloadBase64, secret);
        return payloadBase64 + ":" + signature;
    }

    /**
     * Constant-time comparison to prevent timing attacks on signature verification.
     */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public boolean isValid() {
        if (this.edition == Edition.TRIAL) {
            return Instant.now().isBefore(this.trialExpiration);
        }
        return this.licenseValid;
    }

    public Edition getEdition() {
        return this.edition;
    }

    public String getCustomerId() {
        return this.customerId;
    }

    public boolean hasFeature(String feature) {
        if (!this.isValid()) {
            return false;
        }
        return switch (feature.toUpperCase()) {
            case "RAG", "DOCUMENT_UPLOAD", "SEARCH" -> true;
            case "ADAPTIVE_RAG", "CITATION_VERIFICATION", "SELF_REFLECTIVE_RAG",
                 "QUERY_DECOMPOSITION", "HYBRID_SEARCH", "CONVERSATION_MEMORY",
                 "MULTI_USER", "ADMIN_DASHBOARD", "ANALYTICS" -> {
                if (this.edition != Edition.TRIAL || this.isValid()) {
                    yield true;
                }
                yield false;
            }
            case "HIPAA_AUDIT", "PHI_REDACTION", "MEDICAL_COMPLIANCE" -> {
                if (this.edition == Edition.MEDICAL || this.edition == Edition.GOVERNMENT) {
                    yield true;
                }
                yield false;
            }
            case "CAC_AUTH", "CLEARANCE_LEVELS", "SCIF_MODE", "CLASSIFICATION_BANNERS" -> {
                if (this.edition == Edition.GOVERNMENT) {
                    yield true;
                }
                yield false;
            }
            default -> {
                log.debug("Unknown feature requested: {}", feature);
                yield false;
            }
        };
    }

    public long getTrialDaysRemaining() {
        if (this.edition != Edition.TRIAL || this.trialExpiration == null) {
            return -1L;
        }
        long days = ChronoUnit.DAYS.between(Instant.now(), this.trialExpiration);
        return Math.max(0L, days);
    }

    public LicenseStatus getStatus() {
        return new LicenseStatus(this.edition, this.isValid(), this.getTrialDaysRemaining(),
                this.trialExpiration);
    }

    public enum Edition {
        TRIAL,
        ENTERPRISE,
        MEDICAL,
        GOVERNMENT;
    }

    public record LicenseStatus(Edition edition, boolean valid, long trialDaysRemaining,
            Instant expirationDate) {
    }
}
