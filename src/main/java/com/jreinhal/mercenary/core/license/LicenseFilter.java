/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.servlet.FilterChain
 *  jakarta.servlet.ServletException
 *  jakarta.servlet.ServletRequest
 *  jakarta.servlet.ServletResponse
 *  jakarta.servlet.http.HttpServletRequest
 *  jakarta.servlet.http.HttpServletResponse
 *  org.springframework.core.annotation.Order
 *  org.springframework.stereotype.Component
 *  org.springframework.web.filter.OncePerRequestFilter
 */
package com.jreinhal.mercenary.core.license;

import com.jreinhal.mercenary.core.license.LicenseService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(value=1)
public class LicenseFilter
extends OncePerRequestFilter {
    private final LicenseService licenseService;

    public LicenseFilter(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (this.isExemptPath(path)) {
            filterChain.doFilter((ServletRequest)request, (ServletResponse)response);
            return;
        }
        if (!this.licenseService.isValid()) {
            response.setStatus(402);
            response.setContentType("application/json");
            response.getWriter().write("{\n    \"error\": \"LICENSE_EXPIRED\",\n    \"message\": \"Your trial has expired. Please contact sales for licensing.\",\n    \"edition\": \"%s\",\n    \"contactUrl\": \"https://sentinel-platform.com/sales\"\n}\n".formatted(new Object[]{this.licenseService.getEdition()}));
            return;
        }
        filterChain.doFilter((ServletRequest)request, (ServletResponse)response);
    }

    private boolean isExemptPath(String path) {
        return path.startsWith("/static/") || path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico") || path.equals("/health") || path.equals("/actuator/health") || path.startsWith("/api/license");
    }
}
