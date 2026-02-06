package com.jreinhal.mercenary.core.license;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/license")
public class LicenseController {
    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping("/status")
    public ResponseEntity<LicenseService.LicenseStatus> getStatus() {
        // M-01: Defense-in-depth auth check
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(this.licenseService.getStatus());
    }

    @GetMapping("/feature")
    public ResponseEntity<FeatureResponse> checkFeature(@RequestParam String feature) {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (feature == null || feature.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        boolean available = this.licenseService.hasFeature(feature);
        return ResponseEntity.ok(new FeatureResponse(feature, available, this.licenseService.getEdition().name()));
    }

    public record FeatureResponse(String feature, boolean available, String edition) {
    }
}
