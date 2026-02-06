package com.jreinhal.mercenary.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.security.ClientIpResolver;
import com.jreinhal.mercenary.service.AuditService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PreAuthRateLimitFilter
extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(PreAuthRateLimitFilter.class);
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;
    @Value(value="${app.rate-limit.enabled:true}")
    private boolean enabled;
    @Value(value="${app.rate-limit.anonymous-rpm:30}")
    private int anonymousRpm;
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder().maximumSize(10000L).expireAfterAccess(1L, TimeUnit.HOURS).build();

    public PreAuthRateLimitFilter(AuditService auditService, ClientIpResolver clientIpResolver) {
        this.auditService = auditService;
        this.clientIpResolver = clientIpResolver;
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!this.enabled) {
            chain.doFilter((ServletRequest)request, (ServletResponse)response);
            return;
        }
        if (SecurityContext.isAuthenticated()) {
            chain.doFilter((ServletRequest)request, (ServletResponse)response);
            return;
        }
        String path = request.getRequestURI();
        if (this.isExemptPath(path)) {
            chain.doFilter((ServletRequest)request, (ServletResponse)response);
            return;
        }
        String rateLimitKey = "ip:" + this.clientIpResolver.resolveClientIp(request);
        Bucket bucket = this.bucketCache.get(rateLimitKey, k -> this.createBucket(this.anonymousRpm));
        if (bucket.tryConsume(1L)) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(this.anonymousRpm));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getAvailableTokens()));
            chain.doFilter((ServletRequest)request, (ServletResponse)response);
            return;
        }
        log.warn("Pre-auth rate limit exceeded for key: {} on path: {}", rateLimitKey, path);
        this.auditService.logAccessDenied(null, path, "Pre-auth rate limit exceeded", request);
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", "60");
        response.setHeader("X-RateLimit-Limit", String.valueOf(this.anonymousRpm));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.getWriter().write("{\"error\": \"Rate limit exceeded. Please wait before making more requests.\", \"errorCode\": \"ERR-429\", \"retryAfter\": 60}");
    }

    private Bucket createBucket(int requestsPerMinute) {
        long capacity = requestsPerMinute;
        Bandwidth limit = Bandwidth.builder().capacity(capacity).refillGreedy(capacity, Duration.ofMinutes(1L)).build();
        return Bucket.builder().addLimit(limit).build();
    }

    private boolean isExemptPath(String path) {
        // L-03: Swagger/OpenAPI endpoints are no longer exempt from rate limiting
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.startsWith("/fonts/") || "/favicon.ico".equals(path) || "/api/health".equals(path) || "/api/status".equals(path);
    }
}
