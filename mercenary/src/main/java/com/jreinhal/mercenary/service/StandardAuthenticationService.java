package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * STANDARD AUTHENTICATION SERVICE (Basic Auth with BCrypt)
 *
 * For Commercial/On-Premise deployments without SSO infrastructure.
 * Activated when: app.auth-mode=STANDARD
 *
 * Supports:
 * - HTTP Basic Authentication (RFC 7617)
 * - BCrypt password hashing
 * - Account lockout tracking (via lastLoginAt)
 *
 * Security Features:
 * - Passwords stored as BCrypt hashes (never plaintext)
 * - Timing-safe password comparison
 * - Failed login logging for audit trail
 */
@Service
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "STANDARD")
public class StandardAuthenticationService implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(StandardAuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public StandardAuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        log.info("==========================================================");
        log.info(">>> STANDARD AUTHENTICATION MODE ACTIVE (Basic Auth) <<<");
        log.info(">>> Users authenticate via HTTP Basic Auth header     <<<");
        log.info("==========================================================");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            log.debug("No Basic Auth header present");
            return null;
        }

        try {
            // Decode Base64: "Basic dXNlcm5hbWU6cGFzc3dvcmQ=" -> "username:password"
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(credDecoded, StandardCharsets.UTF_8);

            // Split on first colon only (password may contain colons)
            final String[] values = credentials.split(":", 2);
            if (values.length != 2) {
                log.warn("Malformed Basic Auth credentials");
                return null;
            }

            String username = values[0];
            String password = values[1];

            // Look up user
            Optional<User> userOpt = userRepository.findByUsername(username);

            if (userOpt.isEmpty()) {
                log.warn("Authentication failed: User '{}' not found", username);
                return null;
            }

            User user = userOpt.get();

            // Check if user is active
            if (!user.isActive()) {
                log.warn("Authentication failed: User '{}' is deactivated", username);
                return null;
            }

            // Verify password using BCrypt (timing-safe comparison)
            if (user.getPasswordHash() != null && passwordEncoder.matches(password, user.getPasswordHash())) {
                // Update last login timestamp
                user.setLastLoginAt(Instant.now());
                userRepository.save(user);

                log.info("User '{}' authenticated successfully via Basic Auth", username);
                return user;
            }

            log.warn("Authentication failed: Invalid password for user '{}'", username);
            return null;

        } catch (IllegalArgumentException e) {
            log.error("Invalid Base64 in Authorization header", e);
            return null;
        } catch (Exception e) {
            log.error("Authentication error", e);
            return null;
        }
    }

    @Override
    public String getAuthMode() {
        return "STANDARD";
    }
}
