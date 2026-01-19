/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.github.benmanes.caffeine.cache.Cache
 *  com.github.benmanes.caffeine.cache.Caffeine
 *  com.jreinhal.mercenary.filter.PreAuthRateLimitFilter
 *  com.jreinhal.mercenary.filter.SecurityContext
 *  com.jreinhal.mercenary.service.AuditService
 *  io.github.bucket4j.Bandwidth
 *  io.github.bucket4j.Bucket
 *  io.github.bucket4j.Refill
 *  jakarta.servlet.FilterChain
 *  jakarta.servlet.ServletException
 *  jakarta.servlet.ServletRequest
 *  jakarta.servlet.ServletResponse
 *  jakarta.servlet.http.HttpServletRequest
 *  jakarta.servlet.http.HttpServletResponse
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 *  org.springframework.web.filter.OncePerRequestFilter
 */
package com.jreinhal.mercenary.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.service.AuditService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
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
    @Value(value="${app.rate-limit.enabled:true}")
    private boolean enabled;
    @Value(value="${app.rate-limit.anonymous-rpm:30}")
    private int anonymousRpm;
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder().maximumSize(10000L).expireAfterAccess(1L, TimeUnit.HOURS).build();

    public PreAuthRateLimitFilter(AuditService auditService) {
        this.auditService = auditService;
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
        String rateLimitKey = "ip:" + this.getClientIp(request);
        Bucket bucket = (Bucket)this.bucketCache.get((Object)rateLimitKey, k -> this.createBucket(this.anonymousRpm));
        if (bucket.tryConsume(1L)) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(this.anonymousRpm));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getAvailableTokens()));
            chain.doFilter((ServletRequest)request, (ServletResponse)response);
            return;
        }
        log.warn("Pre-auth rate limit exceeded for key: {} on path: {}", (Object)rateLimitKey, (Object)path);
        this.auditService.logAccessDenied(null, path, "Pre-auth rate limit exceeded", request);
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", "60");
        response.setHeader("X-RateLimit-Limit", String.valueOf(this.anonymousRpm));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.getWriter().write("{\"error\": \"Rate limit exceeded. Please wait before making more requests.\", \"errorCode\": \"ERR-429\", \"retryAfter\": 60}");
    }

    private Bucket createBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.classic((long)requestsPerMinute, (Refill)Refill.greedy((long)requestsPerMinute, (Duration)Duration.ofMinutes(1L)));
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
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.startsWith("/fonts/") || path.equals("/favicon.ico") || path.equals("/api/health") || path.equals("/api/status") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }
}

