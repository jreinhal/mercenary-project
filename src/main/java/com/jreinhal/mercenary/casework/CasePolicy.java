package com.jreinhal.mercenary.casework;

import com.jreinhal.mercenary.core.license.LicenseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CasePolicy {
    private final LicenseService licenseService;

    @Value("${sentinel.casework.enabled:true}")
    private boolean caseworkEnabled;

    @Value("${sentinel.casework.allow-regulated:false}")
    private boolean allowRegulated;

    public CasePolicy(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    public boolean allowCasework() {
        if (!caseworkEnabled) return false;
        return allowRegulated || !isRegulatedEdition();
    }

    public boolean isRegulatedEdition() {
        LicenseService.Edition edition = licenseService.getEdition();
        return edition == LicenseService.Edition.MEDICAL || edition == LicenseService.Edition.GOVERNMENT;
    }
}
