package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.User.AuthProvider;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.model.ClearanceLevel;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.repository.UserRepository;

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
 * Supports Azure AD, Okta, and other OIDC providers.
 * 
 * Activated when: app.auth-mode=OIDC
 * 
 * Required configuration:
 * app.oidc.issuer: The OIDC issuer URL
 * app.oidc.client-id: The application's client ID
 */
@Service
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "OIDC")
public class OidcAuthenticationService implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthenticationService.class);

    private final UserRepository userRepository;

    @Value("${app.oidc.issuer:}")
    private String issuer;

    @Value("${app.oidc.client-id:}")
    private String clientId;

    public OidcAuthenticationService(UserRepository userRepository) {
        this.userRepository = userRepository;
        log.info(">>> OIDC AUTHENTICATION MODE ACTIVE <<<");
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

        // TODO: Implement full JWT validation with JWKS
        // For now, decode the token payload and extract claims
        // In production, use Spring Security OAuth2 Resource Server

        try {
            // Decode JWT (simplified - production should validate signature)
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                log.warn("Invalid JWT format");
                return null;
            }

            // Decode payload (base64)
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));

            // Extract subject (simplified parsing)
            String subject = extractClaim(payload, "sub");
            String email = extractClaim(payload, "email");
            String name = extractClaim(payload, "name");

            if (subject == null) {
                log.warn("No subject in JWT");
                return null;
            }

            // Look up or create user
            User user = userRepository.findByExternalId(subject).orElse(null);
            if (user == null) {
                // Auto-provision new user with default permissions
                user = new User();
                user.setExternalId(subject);
                user.setUsername(email != null ? email : subject);
                user.setDisplayName(name != null ? name : subject);
                user.setEmail(email);
                user.setAuthProvider(AuthProvider.OIDC);
                user.setRoles(Set.of(UserRole.VIEWER)); // Default to read-only
                user.setClearance(ClearanceLevel.UNCLASSIFIED);
                user.setAllowedSectors(Set.of(Department.OPERATIONS));
                user.setCreatedAt(Instant.now());
                user.setActive(true);
                user = userRepository.save(user);
                log.info("Auto-provisioned new OIDC user: {}", user.getUsername());
            }

            user.setLastLoginAt(Instant.now());
            userRepository.save(user);

            return user;
        } catch (Exception e) {
            log.error("OIDC authentication failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractClaim(String payload, String claim) {
        // Simplified JSON parsing - production should use proper JSON library
        String search = "\"" + claim + "\":\"";
        int start = payload.indexOf(search);
        if (start == -1)
            return null;
        start += search.length();
        int end = payload.indexOf("\"", start);
        if (end == -1)
            return null;
        return payload.substring(start, end);
    }

    @Override
    public String getAuthMode() {
        return "OIDC";
    }
}
