package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name={"app.auth-mode"}, havingValue="DEV", matchIfMissing=true)
public class DevAuthenticationService
implements AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(DevAuthenticationService.class);
    @Value("${app.dev.allow-remote:false}")
    private boolean allowRemote;

    public DevAuthenticationService() {
        log.warn(">>> DEVELOPMENT AUTH MODE ACTIVE - NO REAL AUTHENTICATION <<<");
        log.warn(">>> Set app.auth-mode=OIDC or app.auth-mode=CAC for production <<<");
    }

    @Override
    public User authenticate(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!this.allowRemote && remoteAddr != null && !remoteAddr.equals("127.0.0.1") && !remoteAddr.equals("::1") && !remoteAddr.equals("0:0:0:0:0:0:0:1")) {
            log.error("DEV auth blocked for remote address: {}", remoteAddr);
            return null;
        }
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
