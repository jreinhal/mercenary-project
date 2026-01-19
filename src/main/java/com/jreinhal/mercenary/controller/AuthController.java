/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.controller.AuthController
 *  com.jreinhal.mercenary.controller.AuthController$LoginRequest
 *  com.jreinhal.mercenary.controller.AuthController$LoginResponse
 *  com.jreinhal.mercenary.model.User
 *  com.jreinhal.mercenary.service.StandardAuthenticationService
 *  jakarta.servlet.http.HttpServletRequest
 *  jakarta.servlet.http.HttpSession
 *  org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
 *  org.springframework.http.HttpStatus
 *  org.springframework.http.HttpStatusCode
 *  org.springframework.http.ResponseEntity
 *  org.springframework.security.web.csrf.CsrfToken
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RestController
 */
package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.controller.AuthController;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.StandardAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/api/auth"})
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="STANDARD")
public class AuthController {
    private final StandardAuthenticationService standardAuthenticationService;

    public AuthController(StandardAuthenticationService standardAuthenticationService) {
        this.standardAuthenticationService = standardAuthenticationService;
    }

    @GetMapping(value={"/csrf"})
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping(value={"/login"})
    public ResponseEntity<?> login(@RequestBody(required=false) LoginRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.username() == null || request.username().isBlank() || request.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing credentials"));
        }
        String username = request.username().trim();
        User user = this.standardAuthenticationService.authenticateCredentials(username, request.password());
        if (user == null) {
            return ResponseEntity.status((HttpStatusCode)HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials"));
        }
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute("mercenary.auth.userId", (Object)user.getId());
        String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();
        return ResponseEntity.ok((Object)new LoginResponse(displayName));
    }

    @PostMapping(value={"/logout"})
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}

