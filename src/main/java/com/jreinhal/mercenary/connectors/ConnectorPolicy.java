package com.jreinhal.mercenary.connectors;

import com.jreinhal.mercenary.core.license.LicenseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConnectorPolicy {
    private final LicenseService licenseService;

    @Value("${sentinel.connectors.enabled:true}")
    private boolean connectorsEnabled;

    @Value("${sentinel.connectors.allow-regulated:false}")
    private boolean allowRegulated;

    public ConnectorPolicy(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    public boolean allowConnectors() {
        if (!connectorsEnabled) return false;
        return allowRegulated || !isRegulatedEdition();
    }

    public boolean isRegulatedEdition() {
        LicenseService.Edition edition = licenseService.getEdition();
        return edition == LicenseService.Edition.MEDICAL || edition == LicenseService.Edition.GOVERNMENT;
    }
}
