package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuthenticationService;
import com.jreinhal.mercenary.service.AuditService;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Security filter that authenticates requests and attaches user context.
 * 
 * Runs before all controller methods to establish the current user.
 */
@Component
@Order(2) // Run AFTER LicenseFilter(1), BEFORE RateLimitFilter(3)
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);

    private final AuthenticationService authService;
    private final AuditService auditService;

    // Paths that don't require authentication
    private static final String[] PUBLIC_PATHS = {
            "/",
            "/index.html",
            "/manual.html",
            "/readme.html",
            "/css/",
            "/js/",
            "/favicon.ico",
            "/api/auth/",
            "/api/health",
            "/api/status"
    };

    public SecurityFilter(AuthenticationService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
        log.info("Security filter initialized with auth mode: {}", authService.getAuthMode());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain chain)
            throws IOException, ServletException {
        String path = httpRequest.getRequestURI();

        // Allow public paths without authentication
        if (isPublicPath(path)) {
            chain.doFilter(httpRequest, httpResponse);
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
        setSpringSecurityContext(user);

        try {
            chain.doFilter(httpRequest, httpResponse);
        } finally {
            // Clean up thread-local context
            SecurityContext.clear();
            SecurityContextHolder.clearContext();
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

    private void setSpringSecurityContext(User user) {
        var context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null && context.getAuthentication().isAuthenticated()) {
            return;
        }

        Collection<GrantedAuthority> authorities = buildAuthorities(user);
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        context.setAuthentication(auth);
    }

    private Collection<GrantedAuthority> buildAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (UserRole role : user.getRoles()) {
            authorities.add(new SimpleGrantedAuthority(role.name()));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
            for (UserRole.Permission permission : role.getPermissions()) {
                authorities.add(new SimpleGrantedAuthority("PERM_" + permission.name()));
            }
        }
        return authorities;
    }
}
