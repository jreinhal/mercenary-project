package com.jreinhal.mercenary.controller;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = {"/api/auth"})
public class CsrfController {
    @GetMapping(value = {"/csrf"})
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }
}
