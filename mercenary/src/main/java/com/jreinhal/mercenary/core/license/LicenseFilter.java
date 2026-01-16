package com.jreinhal.mercenary.core.license;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that blocks API requests when the license is invalid.
 * Static resources and the license status endpoint remain accessible.
 */
@Component
@Order(1) // Run AFTER CspNonceFilter(0), BEFORE SecurityFilter(2)
public class LicenseFilter extends OncePerRequestFilter {

    private final LicenseService licenseService;

    public LicenseFilter(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Always allow static resources, health checks, and license status
        if (isExemptPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check license validity
        if (!licenseService.isValid()) {
            response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED); // 402
            response.setContentType("application/json");
            response.getWriter().write("""
                {
                    "error": "LICENSE_EXPIRED",
                    "message": "Your trial has expired. Please contact sales for licensing.",
                    "edition": "%s",
                    "contactUrl": "https://sentinel-platform.com/sales"
                }
                """.formatted(licenseService.getEdition()));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExemptPath(String path) {
        return path.startsWith("/static/") ||
               path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.startsWith("/images/") ||
               path.equals("/") ||
               path.equals("/index.html") ||
               path.equals("/favicon.ico") ||
               path.equals("/health") ||
               path.equals("/actuator/health") ||
               path.startsWith("/api/license"); // Allow license status checks
    }
}
