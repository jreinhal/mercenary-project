package com.jreinhal.mercenary.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = {"/api/auth"})
public class CsrfController {
    @GetMapping(value = {"/csrf"})
    public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
        // When CSRF is disabled (e.g., dev profile), Spring may not resolve a CsrfToken argument.
        // The UI treats 404 as "CSRF unavailable" and proceeds without setting the header.
        if (token == null || token.getToken() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("token", token.getToken()));
    }
}
