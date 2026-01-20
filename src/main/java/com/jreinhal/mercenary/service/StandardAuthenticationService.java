/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.servlet.http.HttpServletRequest
 *  jakarta.servlet.http.HttpSession
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
 *  org.springframework.security.crypto.password.PasswordEncoder
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.repository.UserRepository;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="STANDARD")
public class StandardAuthenticationService
implements AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(StandardAuthenticationService.class);
    public static final String SESSION_USER_ID = "mercenary.auth.userId";
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    @Value(value="${app.standard.allow-basic:false}")
    private boolean allowBasic;

    public StandardAuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        log.info("==========================================================");
        log.info(">>> STANDARD AUTHENTICATION MODE ACTIVE (Session) <<<");
        log.info(">>> Users authenticate via session or optional Basic <<<");
        log.info("==========================================================");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        User sessionUser = this.authenticateSession(request);
        if (sessionUser != null) {
            return sessionUser;
        }
        if (!this.allowBasic) {
            return null;
        }
        return this.authenticateBasic(request);
    }

    public User authenticateCredentials(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            log.warn("Authentication failed: Missing credentials");
            return null;
        }
        Optional<User> userOpt = this.userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            log.warn("Authentication failed: User '{}' not found", username);
            return null;
        }
        User user = userOpt.get();
        if (!user.isActive()) {
            log.warn("Authentication failed: User '{}' is deactivated", username);
            return null;
        }
        if (user.isPendingApproval()) {
            log.warn("Authentication failed: User '{}' pending approval", username);
            return null;
        }
        if (user.getPasswordHash() != null && this.passwordEncoder.matches((CharSequence)password, user.getPasswordHash())) {
            user.setLastLoginAt(Instant.now());
            this.userRepository.save(user);
            log.info("User '{}' authenticated successfully", username);
            return user;
        }
        log.warn("Authentication failed: Invalid password for user '{}'", username);
        return null;
    }

    private User authenticateSession(HttpServletRequest request) {
        String userId;
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object userIdObj = session.getAttribute(SESSION_USER_ID);
        if (!(userIdObj instanceof String) || (userId = (String)userIdObj).isBlank()) {
            return null;
        }
        Optional userOpt = this.userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            session.removeAttribute(SESSION_USER_ID);
            return null;
        }
        User user = (User)userOpt.get();
        if (!user.isActive() || user.isPendingApproval()) {
            return null;
        }
        return user;
    }

    private User authenticateBasic(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            log.debug("No Basic Auth header present");
            return null;
        }
        try {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(credDecoded, StandardCharsets.UTF_8);
            String[] values = credentials.split(":", 2);
            if (values.length != 2) {
                log.warn("Malformed Basic Auth credentials");
                return null;
            }
            String username = values[0];
            String password = values[1];
            User user = this.authenticateCredentials(username, password);
            if (user != null) {
                log.info("User '{}' authenticated successfully via Basic Auth", username);
            }
            return user;
        }
        catch (IllegalArgumentException e) {
            log.error("Invalid Base64 in Authorization header", (Throwable)e);
            return null;
        }
        catch (Exception e) {
            log.error("Authentication error", (Throwable)e);
            return null;
        }
    }

    @Override
    public String getAuthMode() {
        return "STANDARD";
    }
}
