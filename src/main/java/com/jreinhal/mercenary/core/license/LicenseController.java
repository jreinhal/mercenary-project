package com.jreinhal.mercenary.core.license;

import com.jreinhal.mercenary.core.license.LicenseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/license"})
public class LicenseController {
    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping(value={"/status"})
    public ResponseEntity<LicenseService.LicenseStatus> getStatus() {
        return ResponseEntity.ok(this.licenseService.getStatus());
    }

    @GetMapping(value={"/feature"})
    public ResponseEntity<FeatureResponse> checkFeature(String feature) {
        boolean available = this.licenseService.hasFeature(feature);
        return ResponseEntity.ok(new FeatureResponse(feature, available, this.licenseService.getEdition().name()));
    }

    public record FeatureResponse(String feature, boolean available, String edition) {
    }
}
