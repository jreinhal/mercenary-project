package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.AuthenticationService;
import com.jreinhal.mercenary.service.AuditService;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Security filter that authenticates requests and attaches user context.
 * 
 * Runs before all controller methods to establish the current user.
 */
@Component
@Order(1)
public class SecurityFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    private final AuthenticationService authService;
    private final AuditService auditService;

    // Paths that don't require authentication
    private static final String[] PUBLIC_PATHS = {
            "/",
            "/index.html",
            "/manual.html",
            "/css/",
            "/js/",
            "/favicon.ico",
            "/api/health",
            "/api/status",
            "/api/telemetry"
    };

    public SecurityFilter(AuthenticationService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
        log.info("Security filter initialized with auth mode: {}", authService.getAuthMode());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        // Allow public paths without authentication
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Authenticate the request
        User user = authService.authenticate(httpRequest);

        // In DEV mode, always create a demo user if authentication returns null
        if (user == null && "DEV".equals(authService.getAuthMode())) {
            user = User.devUser("DEMO_USER");
        }

        if (user == null) {
            // In non-DEV modes, require authentication
            log.warn("Authentication failed for path: {} from IP: {}",
                    path, httpRequest.getRemoteAddr());
            auditService.logAuthFailure("UNKNOWN", "No valid credentials", httpRequest);

            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }

        // Attach user to request context
        SecurityContext.setCurrentUser(user);
        httpRequest.setAttribute("authenticatedUser", user);

        try {
            chain.doFilter(request, response);
        } finally {
            // Clean up thread-local context
            SecurityContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            // Special handling for root path - exact match only
            if (publicPath.equals("/")) {
                if (path.equals("/"))
                    return true;
                continue;
            }
            // For other paths, prefix match prevents checking sub-resources individually
            if (path.equals(publicPath) || path.startsWith(publicPath)) {
                return true;
            }
        }
        return false;
    }
}
