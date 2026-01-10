package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Development mode authentication - no real authentication required.
 * 
 * Returns a demo user with full admin access for local development.
 * 
 * Activated when: app.auth-mode=DEV (default)
 */
@Service
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "DEV", matchIfMissing = true)
public class DevAuthenticationService implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(DevAuthenticationService.class);

    public DevAuthenticationService() {
        log.warn(">>> DEVELOPMENT AUTH MODE ACTIVE - NO REAL AUTHENTICATION <<<");
        log.warn(">>> Set app.auth-mode=OIDC or app.auth-mode=CAC for production <<<");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        // Check for custom operator header (for UI testing)
        String operatorId = request.getHeader("X-Operator-Id");
        if (operatorId == null || operatorId.isEmpty()) {
            operatorId = request.getParameter("operator");
        }
        if (operatorId == null || operatorId.isEmpty()) {
            operatorId = "DEMO_USER";
        }

        return User.devUser(operatorId);
    }

    @Override
    public String getAuthMode() {
        return "DEV";
    }
}
