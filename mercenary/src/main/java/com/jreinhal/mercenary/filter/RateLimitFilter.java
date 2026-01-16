package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.service.AuditService;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting filter to prevent abuse and DoS attacks.
 *
 * SECURITY: Implements token bucket algorithm per user/IP with configurable limits.
 *
 * Rate limits (per minute):
 * - ADMIN: 200 requests (higher for administrative tasks)
 * - ANALYST: 100 requests (standard for power users)
 * - VIEWER: 60 requests (sufficient for normal use)
 * - ANONYMOUS/IP: 30 requests (strict for unauthenticated)
 *
 * Configuration:
 * - app.rate-limit.enabled: Enable/disable rate limiting
 * - app.rate-limit.viewer-rpm: Requests per minute for VIEWER role
 * - app.rate-limit.analyst-rpm: Requests per minute for ANALYST role
 * - app.rate-limit.admin-rpm: Requests per minute for ADMIN role
 * - app.rate-limit.anonymous-rpm: Requests per minute for unauthenticated
 */
@Component
@Order(1) // Run before SecurityFilter
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final AuditService auditService;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.viewer-rpm:60}")
    private int viewerRpm;

    @Value("${app.rate-limit.analyst-rpm:100}")
    private int analystRpm;

    @Value("${app.rate-limit.admin-rpm:200}")
    private int adminRpm;

    @Value("${app.rate-limit.anonymous-rpm:30}")
    private int anonymousRpm;

    // Cache of rate limit buckets per user/IP
    // Max 10,000 entries to prevent memory exhaustion from IP spoofing attacks
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    public RateLimitFilter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip rate limiting for static resources and health checks
        String path = httpRequest.getRequestURI();
        if (isExemptPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Get rate limit key (user ID or IP)
        String rateLimitKey = getRateLimitKey(httpRequest);
        int allowedRpm = getAllowedRpm();

        // Get or create bucket for this key
        Bucket bucket = bucketCache.get(rateLimitKey, k -> createBucket(allowedRpm));

        // Try to consume a token
        if (bucket.tryConsume(1)) {
            // Add rate limit headers
            httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(allowedRpm));
            httpResponse.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getAvailableTokens()));

            chain.doFilter(request, response);
        } else {
            // Rate limit exceeded
            log.warn("Rate limit exceeded for key: {} on path: {}", rateLimitKey, path);

            // Audit the rate limit violation
            User user = SecurityContext.getCurrentUser();
            if (user != null) {
                auditService.logAccessDenied(user, path, "Rate limit exceeded", httpRequest);
            }

            // Return 429 Too Many Requests
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.setHeader("Retry-After", "60");
            httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(allowedRpm));
            httpResponse.setHeader("X-RateLimit-Remaining", "0");

            httpResponse.getWriter().write(
                    "{\"error\": \"Rate limit exceeded. Please wait before making more requests.\", " +
                    "\"errorCode\": \"ERR-429\", " +
                    "\"retryAfter\": 60}"
            );
        }
    }

    /**
     * Create a token bucket with the specified rate limit.
     */
    private Bucket createBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.classic(
                requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Get the rate limit key for this request.
     * Uses user ID if authenticated, otherwise falls back to IP address.
     */
    private String getRateLimitKey(HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }

        // Fall back to IP address for anonymous requests
        String ip = getClientIp(request);
        return "ip:" + ip;
    }

    /**
     * Get allowed requests per minute based on user role.
     */
    private int getAllowedRpm() {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return anonymousRpm;
        }

        if (user.hasRole(UserRole.ADMIN)) {
            return adminRpm;
        } else if (user.hasRole(UserRole.ANALYST)) {
            return analystRpm;
        } else {
            return viewerRpm;
        }
    }

    /**
     * Extract client IP, handling proxies (X-Forwarded-For).
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            // Take first IP in chain (original client)
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Check if path is exempt from rate limiting.
     */
    private boolean isExemptPath(String path) {
        return path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.startsWith("/images/") ||
               path.equals("/favicon.ico") ||
               path.equals("/api/health") ||
               path.equals("/api/status") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }
}
