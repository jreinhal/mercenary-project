package com.jreinhal.mercenary.filter;

import com.jreinhal.mercenary.service.AuditService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Pre-authentication rate limiting to throttle anonymous abuse before auth checks.
 */
@Component
public class PreAuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PreAuthRateLimitFilter.class);

    private final AuditService auditService;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.anonymous-rpm:30}")
    private int anonymousRpm;

    // Cache of rate limit buckets per IP
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    public PreAuthRateLimitFilter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        if (SecurityContext.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isExemptPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String rateLimitKey = "ip:" + getClientIp(request);
        Bucket bucket = bucketCache.get(rateLimitKey, k -> createBucket(anonymousRpm));

        if (bucket.tryConsume(1)) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(anonymousRpm));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getAvailableTokens()));
            chain.doFilter(request, response);
            return;
        }

        log.warn("Pre-auth rate limit exceeded for key: {} on path: {}", rateLimitKey, path);
        auditService.logAccessDenied(null, path, "Pre-auth rate limit exceeded", request);

        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", "60");
        response.setHeader("X-RateLimit-Limit", String.valueOf(anonymousRpm));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.getWriter().write(
                "{\"error\": \"Rate limit exceeded. Please wait before making more requests.\", " +
                        "\"errorCode\": \"ERR-429\", " +
                        "\"retryAfter\": 60}");
    }

    private Bucket createBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.classic(
                requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isExemptPath(String path) {
        return path.startsWith("/css/") ||
                path.startsWith("/js/") ||
                path.startsWith("/images/") ||
                path.startsWith("/fonts/") ||
                path.equals("/favicon.ico") ||
                path.equals("/api/health") ||
                path.equals("/api/status") ||
                path.startsWith("/swagger-ui") ||
                path.startsWith("/v3/api-docs");
    }
}
