/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.core.license.LicenseService
 *  com.jreinhal.mercenary.core.license.LicenseService$Edition
 *  com.jreinhal.mercenary.core.license.LicenseService$LicenseStatus
 *  jakarta.annotation.PostConstruct
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.core.license;

import com.jreinhal.mercenary.core.license.LicenseService;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
 * Exception performing whole class analysis ignored.
 */
@Service
public class LicenseService {
    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    @Value(value="${sentinel.license.edition:PROFESSIONAL}")
    private String editionString;
    @Value(value="${sentinel.license.key:}")
    private String licenseKey;
    @Value(value="${sentinel.license.trial-start:}")
    private String trialStartDate;
    @Value(value="${sentinel.license.trial-days:30}")
    private int trialDays;
    private Edition edition;
    private Instant trialExpiration;
    private boolean licenseValid = false;

    @PostConstruct
    public void initialize() {
        this.edition = this.parseEdition(this.editionString);
        if (this.edition == Edition.TRIAL) {
            this.initializeTrial();
        } else {
            this.validateLicenseKey();
        }
        log.info("SENTINEL License: Edition={}, Valid={}, Expires={}", new Object[]{this.edition, this.licenseValid, this.trialExpiration != null ? this.trialExpiration : "Never"});
    }

    private Edition parseEdition(String value) {
        try {
            return Edition.valueOf((String)value.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            log.warn("Unknown edition '{}', defaulting to TRIAL", (Object)value);
            return Edition.TRIAL;
        }
    }

    private void initializeTrial() {
        LocalDate startDate;
        if (this.trialStartDate != null && !this.trialStartDate.isBlank()) {
            startDate = LocalDate.parse(this.trialStartDate);
        } else {
            startDate = LocalDate.now();
            log.info("Trial started: {}", (Object)startDate);
        }
        LocalDate expirationDate = startDate.plusDays(this.trialDays);
        this.trialExpiration = expirationDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        this.licenseValid = Instant.now().isBefore(this.trialExpiration);
        if (!this.licenseValid) {
            log.warn("Trial expired on {}. Contact sales for licensing.", (Object)expirationDate);
        } else {
            long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), expirationDate);
            log.info("Trial active: {} days remaining", (Object)daysRemaining);
        }
    }

    private void validateLicenseKey() {
        if (this.licenseKey == null || this.licenseKey.isBlank()) {
            log.warn("No license key configured. Running in unlicensed mode.");
            this.licenseValid = true;
            return;
        }
        this.licenseValid = true;
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

    public boolean hasFeature(String feature) {
        if (!this.isValid()) {
            return false;
        }
        return switch (feature.toUpperCase()) {
            case "RAG", "DOCUMENT_UPLOAD", "SEARCH" -> true;
            case "ADAPTIVE_RAG", "CITATION_VERIFICATION", "SELF_REFLECTIVE_RAG", "QUERY_DECOMPOSITION", "HYBRID_SEARCH", "CONVERSATION_MEMORY", "MULTI_USER", "ADMIN_DASHBOARD", "ANALYTICS" -> {
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
                log.debug("Unknown feature requested: {}", (Object)feature);
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
        return new LicenseStatus(this.edition, this.isValid(), this.getTrialDaysRemaining(), this.trialExpiration);
    }
}

