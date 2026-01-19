/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.filter.SecurityContext
 *  com.jreinhal.mercenary.filter.SecurityFilter
 *  com.jreinhal.mercenary.model.User
 *  com.jreinhal.mercenary.model.UserRole
 *  com.jreinhal.mercenary.model.UserRole$Permission
 *  com.jreinhal.mercenary.service.AuditService
 *  com.jreinhal.mercenary.service.AuthenticationService
 *  jakarta.servlet.FilterChain
 *  jakarta.servlet.ServletException
 *  jakarta.servlet.ServletRequest
 *  jakarta.servlet.ServletResponse
 *  jakarta.servlet.http.HttpServletRequest
 *  jakarta.servlet.http.HttpServletResponse
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.core.annotation.Order
 *  org.springframework.security.authentication.UsernamePasswordAuthenticationToken
 *  org.springframework.security.core.Authentication
 *  org.springframework.security.core.GrantedAuthority
 *  org.springframework.security.core.authority.SimpleGrantedAuthority
 *  org.springframework.security.core.context.SecurityContext
 *  org.springframework.security.core.context.SecurityContextHolder
 *  org.springframework.stereotype.Component
 *  org.springframework.web.filter.OncePerRequestFilter
 */
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
    private static final String[] PUBLIC_PATHS = new String[]{"/", "/index.html", "/manual.html", "/readme.html", "/css/", "/js/", "/favicon.ico", "/api/auth/", "/api/health", "/api/status"};

    public SecurityFilter(AuthenticationService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
        log.info("Security filter initialized with auth mode: {}", (Object)authService.getAuthMode());
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    protected void doFilterInternal(HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain chain) throws IOException, ServletException {
        String path = httpRequest.getRequestURI();
        if (this.isPublicPath(path)) {
            chain.doFilter((ServletRequest)httpRequest, (ServletResponse)httpResponse);
            return;
        }
        User user = this.authService.authenticate(httpRequest);
        if (user == null && "DEV".equals(this.authService.getAuthMode())) {
            user = User.devUser((String)"DEMO_USER");
        }
        if (user == null) {
            log.warn("Authentication failed for path: {} from IP: {}", (Object)path, (Object)httpRequest.getRemoteAddr());
            this.auditService.logAuthFailure("UNKNOWN", "No valid credentials", httpRequest);
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }
        SecurityContext.setCurrentUser((User)user);
        httpRequest.setAttribute("authenticatedUser", (Object)user);
        this.setSpringSecurityContext(user);
        try {
            chain.doFilter((ServletRequest)httpRequest, (ServletResponse)httpResponse);
        }
        finally {
            SecurityContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (!(publicPath.equals("/") ? path.equals("/") : path.equals(publicPath) || path.startsWith(publicPath))) continue;
            return true;
        }
        return false;
    }

    private void setSpringSecurityContext(User user) {
        org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null && context.getAuthentication().isAuthenticated()) {
            return;
        }
        Collection authorities = this.buildAuthorities(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken((Object)user, null, authorities);
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

