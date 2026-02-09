package com.jreinhal.mercenary.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the current authentication mode for frontend detection.
 * This endpoint is public (under /api/auth/) so the frontend can
 * determine whether to show username/password or SSO login.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthModeController {

    @Value("${app.auth-mode:DEV}")
    private String authMode;

    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getMode() {
        boolean ssoEnabled = "OIDC".equalsIgnoreCase(authMode);
        return ResponseEntity.ok(Map.of(
                "mode", authMode,
                "ssoEnabled", ssoEnabled,
                "authorizeUrl", ssoEnabled ? "/api/auth/oidc/authorize" : ""
        ));
    }
}
