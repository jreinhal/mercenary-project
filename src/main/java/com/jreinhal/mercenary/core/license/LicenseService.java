package com.jreinhal.mercenary.core.license;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * License management service for SENTINEL editions.
 *
 * Editions:
 * - TRIAL: Full professional features, 30-day time limit
 * - PROFESSIONAL: Full features, no time limit
 * - MEDICAL: Professional + HIPAA compliance features
 * - GOVERNMENT: All features including SCIF/CAC
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    public enum Edition {
        TRIAL,
        PROFESSIONAL,
        MEDICAL,
        GOVERNMENT
    }

    @Value("${sentinel.license.edition:PROFESSIONAL}")
    private String editionString;

    @Value("${sentinel.license.key:}")
    private String licenseKey;

    @Value("${sentinel.license.trial-start:}")
    private String trialStartDate;

    @Value("${sentinel.license.trial-days:30}")
    private int trialDays;

    private Edition edition;
    private Instant trialExpiration;
    private boolean licenseValid = false;

    @PostConstruct
    public void initialize() {
        this.edition = parseEdition(editionString);

        if (edition == Edition.TRIAL) {
            initializeTrial();
        } else {
            validateLicenseKey();
        }

        log.info("SENTINEL License: Edition={}, Valid={}, Expires={}",
            edition, licenseValid, trialExpiration != null ? trialExpiration : "Never");
    }

    private Edition parseEdition(String value) {
        try {
            return Edition.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown edition '{}', defaulting to TRIAL", value);
            return Edition.TRIAL;
        }
    }

    private void initializeTrial() {
        LocalDate startDate;
        if (trialStartDate != null && !trialStartDate.isBlank()) {
            startDate = LocalDate.parse(trialStartDate);
        } else {
            // First run - trial starts today
            startDate = LocalDate.now();
            log.info("Trial started: {}", startDate);
        }

        LocalDate expirationDate = startDate.plusDays(trialDays);
        this.trialExpiration = expirationDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        this.licenseValid = Instant.now().isBefore(trialExpiration);

        if (!licenseValid) {
            log.warn("Trial expired on {}. Contact sales for licensing.", expirationDate);
        } else {
            long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), expirationDate);
            log.info("Trial active: {} days remaining", daysRemaining);
        }
    }

    private void validateLicenseKey() {
        if (licenseKey == null || licenseKey.isBlank()) {
            // No license key - allow operation but log warning
            log.warn("No license key configured. Running in unlicensed mode.");
            this.licenseValid = true; // Allow for now, implement validation later
            return;
        }

        // TODO: Implement license key validation
        // For now, any non-empty key is valid
        this.licenseValid = true;
    }

    /**
     * Check if the license is currently valid.
     */
    public boolean isValid() {
        if (edition == Edition.TRIAL) {
            return Instant.now().isBefore(trialExpiration);
        }
        return licenseValid;
    }

    /**
     * Get the current edition.
     */
    public Edition getEdition() {
        return edition;
    }

    /**
     * Check if a feature is available in the current edition.
     */
    public boolean hasFeature(String feature) {
        if (!isValid()) {
            return false;
        }

        return switch (feature.toUpperCase()) {
            // Core features - all editions
            case "RAG", "DOCUMENT_UPLOAD", "SEARCH" -> true;

            // Professional features - all paid editions
            case "ADAPTIVE_RAG", "CITATION_VERIFICATION", "SELF_REFLECTIVE_RAG",
                 "QUERY_DECOMPOSITION", "HYBRID_SEARCH", "CONVERSATION_MEMORY",
                 "MULTI_USER", "ADMIN_DASHBOARD", "ANALYTICS" ->
                edition != Edition.TRIAL || isValid(); // Trial gets these while valid

            // Medical features - medical and government only
            case "HIPAA_AUDIT", "PHI_REDACTION", "MEDICAL_COMPLIANCE" ->
                edition == Edition.MEDICAL || edition == Edition.GOVERNMENT;

            // Government features - government only
            case "CAC_AUTH", "CLEARANCE_LEVELS", "SCIF_MODE", "CLASSIFICATION_BANNERS" ->
                edition == Edition.GOVERNMENT;

            default -> {
                log.debug("Unknown feature requested: {}", feature);
                yield false;
            }
        };
    }

    /**
     * Get days remaining in trial, or -1 if not a trial.
     */
    public long getTrialDaysRemaining() {
        if (edition != Edition.TRIAL || trialExpiration == null) {
            return -1;
        }
        long days = ChronoUnit.DAYS.between(Instant.now(), trialExpiration);
        return Math.max(0, days);
    }

    /**
     * Get license status for display.
     */
    public LicenseStatus getStatus() {
        return new LicenseStatus(
            edition,
            isValid(),
            getTrialDaysRemaining(),
            trialExpiration
        );
    }

    public record LicenseStatus(
        Edition edition,
        boolean valid,
        long trialDaysRemaining,
        Instant expirationDate
    ) {}
}
