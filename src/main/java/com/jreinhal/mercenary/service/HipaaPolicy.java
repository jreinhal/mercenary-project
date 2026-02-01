package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.core.license.LicenseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HipaaPolicy {
    private final LicenseService licenseService;
    @Value(value="${sentinel.hipaa.strict:false}")
    private boolean strictOverride;
    @Value(value="${sentinel.hipaa.enable-for-medical-edition:true}")
    private boolean enableForMedicalEdition;
    @Value(value="${sentinel.hipaa.redact-responses:true}")
    private boolean redactResponses;
    @Value(value="${sentinel.hipaa.disable-feedback:true}")
    private boolean disableFeedback;
    @Value(value="${sentinel.hipaa.disable-session-memory:true}")
    private boolean disableSessionMemory;
    @Value(value="${sentinel.hipaa.disable-session-export:true}")
    private boolean disableSessionExport;
    @Value(value="${sentinel.hipaa.disable-visual:true}")
    private boolean disableVisual;
    @Value(value="${sentinel.hipaa.disable-experience-learning:true}")
    private boolean disableExperienceLearning;
    @Value(value="${sentinel.hipaa.suppress-sensitive-logs:true}")
    private boolean suppressSensitiveLogs;
    @Value(value="${sentinel.hipaa.enforce-tls:true}")
    private boolean enforceTls;

    public HipaaPolicy(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    public boolean isStrict(Department department) {
        if (department != Department.MEDICAL) {
            return false;
        }
        if (this.strictOverride) {
            return true;
        }
        return this.enableForMedicalEdition && this.licenseService.getEdition() == LicenseService.Edition.MEDICAL;
    }

    public boolean isStrict(String sector) {
        if (sector == null || sector.isBlank()) {
            return false;
        }
        try {
            return this.isStrict(Department.valueOf(sector.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean shouldRedactResponses(Department department) {
        return this.isStrict(department) && this.redactResponses;
    }

    public boolean shouldDisableFeedback(Department department) {
        return this.isStrict(department) && this.disableFeedback;
    }

    public boolean shouldDisableFeedback(String sector) {
        return this.isStrict(sector) && this.disableFeedback;
    }

    public boolean shouldDisableSessionMemory(Department department) {
        return this.isStrict(department) && this.disableSessionMemory;
    }

    public boolean shouldDisableSessionExport(Department department) {
        return this.isStrict(department) && this.disableSessionExport;
    }

    public boolean shouldDisableVisual(Department department) {
        return this.isStrict(department) && this.disableVisual;
    }

    public boolean shouldDisableExperienceLearning(Department department) {
        return this.isStrict(department) && this.disableExperienceLearning;
    }

    public boolean shouldSuppressSensitiveLogs(Department department) {
        return this.isStrict(department) && this.suppressSensitiveLogs;
    }

    public boolean shouldEnforceTls() {
        return this.isStrict(Department.MEDICAL) && this.enforceTls;
    }
}
