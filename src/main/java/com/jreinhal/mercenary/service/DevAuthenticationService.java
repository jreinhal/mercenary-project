/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.model.User
 *  com.jreinhal.mercenary.service.AuthenticationService
 *  com.jreinhal.mercenary.service.DevAuthenticationService
 *  jakarta.servlet.http.HttpServletRequest
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="DEV", matchIfMissing=true)
public class DevAuthenticationService
implements AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(DevAuthenticationService.class);

    public DevAuthenticationService() {
        log.warn(">>> DEVELOPMENT AUTH MODE ACTIVE - NO REAL AUTHENTICATION <<<");
        log.warn(">>> Set app.auth-mode=OIDC or app.auth-mode=CAC for production <<<");
    }

    public User authenticate(HttpServletRequest request) {
        String operatorId = request.getHeader("X-Operator-Id");
        if (operatorId == null || operatorId.isEmpty()) {
            operatorId = request.getParameter("operator");
        }
        if (operatorId == null || operatorId.isEmpty()) {
            operatorId = "DEMO_USER";
        }
        return User.devUser((String)operatorId);
    }

    public String getAuthMode() {
        return "DEV";
    }
}

