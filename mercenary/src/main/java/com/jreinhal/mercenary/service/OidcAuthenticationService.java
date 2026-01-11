package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.User.AuthProvider;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.security.JwtValidator;
import com.jreinhal.mercenary.security.JwtValidator.ValidationResult;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.time.Instant;

/**
 * OIDC/OAuth2 authentication for enterprise deployments.
 *
 * Supports Azure AD, Okta, Auth0, and other OIDC providers.
 *
 * Activated when: app.auth-mode=OIDC
 *
 * Required configuration:
 * - app.oidc.issuer: The OIDC issuer URL
 * - app.oidc.client-id: The application's client ID
 * - app.oidc.jwks-uri: (Optional) JWKS endpoint URL
 * - app.oidc.local-jwks-path: (Optional) Path to local JWKS file for air-gap
 *
 * Security Features:
 * - Full cryptographic signature verification via JWKS
 * - Issuer and audience validation
 * - Token expiration enforcement
 * - Clock skew tolerance
 */
@Service
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "OIDC")
public class OidcAuthenticationService implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthenticationService.class);

    private final UserRepository userRepository;
    private final JwtValidator jwtValidator;

    @Value("${app.oidc.issuer:}")
    private String issuer;

    @Value("${app.oidc.client-id:}")
    private String clientId;

    @Value("${app.oidc.auto-provision:true}")
    private boolean autoProvision;

    @Value("${app.oidc.default-role:VIEWER}")
    private String defaultRole;

    @Value("${app.oidc.default-clearance:UNCLASSIFIED}")
    private String defaultClearance;

    public OidcAuthenticationService(UserRepository userRepository, JwtValidator jwtValidator) {
        this.userRepository = userRepository;
        this.jwtValidator = jwtValidator;
        log.info("==========================================================");
        log.info(">>> OIDC AUTHENTICATION MODE ACTIVE <<<");
        log.info(">>> JWT signature validation: ENABLED                  <<<");
        log.info("==========================================================");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        // Extract Bearer token from Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token in request");
            return null;
        }

        String token = authHeader.substring(7);

        // Validate JWT with full signature verification
        ValidationResult result = jwtValidator.validate(token);

        if (!result.isValid()) {
            log.warn("JWT validation failed: {}", result.getError());
            return null;
        }

        try {
            // Extract claims from validated token
            String subject = result.getSubject();
            String email = result.getStringClaim("email");
            String name = result.getStringClaim("name");

            // Fallback claim names (different providers use different claim names)
            if (name == null) {
                name = result.getStringClaim("preferred_username");
            }
            if (name == null) {
                name = result.getStringClaim("given_name");
                String familyName = result.getStringClaim("family_name");
                if (familyName != null) {
                    name = (name != null ? name + " " : "") + familyName;
                }
            }

            if (subject == null || subject.isEmpty()) {
                log.warn("No subject in validated JWT");
                return null;
            }

            log.debug("JWT validated for subject: {}, email: {}", subject, email);

            // Look up existing user by external ID (OIDC subject)
            User user = userRepository.findByExternalId(subject).orElse(null);

            if (user == null) {
                if (!autoProvision) {
                    log.warn("User '{}' not found and auto-provisioning disabled", subject);
                    return null;
                }

                // Auto-provision new user with default permissions
                user = new User();
                user.setExternalId(subject);
                user.setUsername(email != null ? email : subject);
                user.setDisplayName(name != null ? name : subject);
                user.setEmail(email);
                user.setAuthProvider(AuthProvider.OIDC);

                // Apply default role
                UserRole role;
                try {
                    role = UserRole.valueOf(defaultRole.toUpperCase());
                } catch (IllegalArgumentException e) {
                    role = UserRole.VIEWER;
                }
                user.setRoles(Set.of(role));

                // Apply default clearance
                ClearanceLevel clearance;
                try {
                    clearance = ClearanceLevel.valueOf(defaultClearance.toUpperCase());
                } catch (IllegalArgumentException e) {
                    clearance = ClearanceLevel.UNCLASSIFIED;
                }
                user.setClearance(clearance);

                user.setAllowedSectors(Set.of(Department.OPERATIONS));
                user.setCreatedAt(Instant.now());
                user.setActive(true);
                user = userRepository.save(user);

                log.info("Auto-provisioned new OIDC user: {} (role: {}, clearance: {})",
                        user.getUsername(), role, clearance);
            }

            // Check if user is active
            if (!user.isActive()) {
                log.warn("OIDC user '{}' is deactivated", user.getUsername());
                return null;
            }

            // Update last login
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);

            log.debug("OIDC user '{}' authenticated successfully", user.getUsername());
            return user;

        } catch (Exception e) {
            log.error("OIDC authentication failed: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String getAuthMode() {
        return "OIDC";
    }
}
