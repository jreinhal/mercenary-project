package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;
import com.jreinhal.mercenary.service.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(value=2)
public class SecurityFilter
extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);
    private final AuthenticationService authService;
    private final AuditService auditService;

    // API paths that are publicly accessible regardless of HTTP method.
    private static final String[] PUBLIC_API_PATHS = new String[]{
            "/api/auth/",
            "/api/health"
    };

    // Static asset paths — only accessible via GET/HEAD (non-idempotent methods are rejected).
    private static final String[] STATIC_ASSET_PATHS = new String[]{
            "/",
            "/index.html",
            "/manual.html",
            "/readme.html",
            "/docs-index.html",
            "/docs-index.md",
            "/favicon.ico",
            "/css/",
            "/js/",
            "/vendor/",
            "/fonts/",
            "/images/"
    };

    public SecurityFilter(AuthenticationService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
        log.info("Security filter initialized with auth mode: {}", authService.getAuthMode());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Bypass the filter only for idempotent (GET/HEAD) requests to static assets or public API paths.
        if (!this.isIdempotentMethod(request)) {
            return false;
        }
        String path = request.getRequestURI();
        return path != null && (this.isStaticAssetPath(path) || this.isPublicApiPath(path));
    }

    protected void doFilterInternal(HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain chain) throws IOException, ServletException {
        String path = httpRequest.getRequestURI();
        // Public API paths (e.g., /api/health, /api/auth/**) are accessible for any HTTP method.
        if (this.isPublicApiPath(path)) {
            chain.doFilter((ServletRequest)httpRequest, (ServletResponse)httpResponse);
            return;
        }
        // Static asset paths are only accessible unauthenticated for GET/HEAD — other methods fall through to auth.
        if (this.isStaticAssetPath(path) && this.isIdempotentMethod(httpRequest)) {
            chain.doFilter((ServletRequest)httpRequest, (ServletResponse)httpResponse);
            return;
        }
        User user = this.authService.authenticate(httpRequest);
        if (user == null && "DEV".equals(this.authService.getAuthMode())) {
            user = User.devUser("DEMO_USER");
        }
        if (user == null) {
            log.warn("Authentication failed for path: {} from IP: {}", path, httpRequest.getRemoteAddr());
            this.auditService.logAuthFailure("UNKNOWN", "No valid credentials", httpRequest);
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }
        SecurityContext.setCurrentUser(user);
        httpRequest.setAttribute("authenticatedUser", user);
        this.setSpringSecurityContext(user);
        try {
            chain.doFilter((ServletRequest)httpRequest, (ServletResponse)httpResponse);
        }
        finally {
            SecurityContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isPublicApiPath(String path) {
        for (String apiPath : PUBLIC_API_PATHS) {
            // Entries ending with "/" are path prefixes; others require exact match.
            if (apiPath.endsWith("/") ? path.startsWith(apiPath) : apiPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStaticAssetPath(String path) {
        for (String staticPath : STATIC_ASSET_PATHS) {
            // "/" is exact-match only (root page); directory prefixes like "/css/" use startsWith;
            // file entries like "/index.html" use exact match.
            if ("/".equals(staticPath)) {
                if ("/".equals(path)) {
                    return true;
                }
            } else if (staticPath.endsWith("/")) {
                if (path.startsWith(staticPath)) {
                    return true;
                }
            } else {
                if (staticPath.equals(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isIdempotentMethod(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        return "GET".equals(m) || "HEAD".equals(m);
    }

    private void setSpringSecurityContext(User user) {
        org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null && context.getAuthentication().isAuthenticated()) {
            return;
        }
        Collection<GrantedAuthority> authorities = this.buildAuthorities(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        context.setAuthentication((Authentication)auth);
    }

    private Collection<GrantedAuthority> buildAuthorities(User user) {
        ArrayList<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
        for (UserRole role : user.getRoles()) {
            authorities.add((GrantedAuthority)new SimpleGrantedAuthority(role.name()));
            authorities.add((GrantedAuthority)new SimpleGrantedAuthority("ROLE_" + role.name()));
            for (UserRole.Permission permission : role.getPermissions()) {
                authorities.add((GrantedAuthority)new SimpleGrantedAuthority("PERM_" + permission.name()));
            }
        }
        return authorities;
    }
}
