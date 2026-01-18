package com.jreinhal.mercenary.core.license;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for license status information.
 */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    /**
     * Get current license status.
     */
    @GetMapping("/status")
    public ResponseEntity<LicenseService.LicenseStatus> getStatus() {
        return ResponseEntity.ok(licenseService.getStatus());
    }

    /**
     * Check if a specific feature is available.
     */
    @GetMapping("/feature")
    public ResponseEntity<FeatureResponse> checkFeature(String feature) {
        boolean available = licenseService.hasFeature(feature);
        return ResponseEntity.ok(new FeatureResponse(
            feature,
            available,
            licenseService.getEdition().name()
        ));
    }

    public record FeatureResponse(
        String feature,
        boolean available,
        String edition
    ) {}
}
