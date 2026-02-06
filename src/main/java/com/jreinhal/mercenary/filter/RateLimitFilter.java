package com.jreinhal.mercenary.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.filter.SecurityContext;
import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.model.UserRole;
import com.jreinhal.mercenary.security.ClientIpResolver;
import com.jreinhal.mercenary.service.AuditService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.Filter;
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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(value=4)
public class RateLimitFilter
implements Filter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;
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
    private final Cache<String, Bucket> bucketCache = Caffeine.newBuilder().maximumSize(10000L).expireAfterAccess(1L, TimeUnit.HOURS).build();

    public RateLimitFilter(AuditService auditService, ClientIpResolver clientIpResolver) {
        this.auditService = auditService;
        this.clientIpResolver = clientIpResolver;
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!this.enabled) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpServletResponse httpResponse = (HttpServletResponse)response;
        String path = httpRequest.getRequestURI();
        if (this.isExemptPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        String rateLimitKey = this.getRateLimitKey(httpRequest);
        int allowedRpm = this.getAllowedRpm();
        Bucket bucket = this.bucketCache.get(rateLimitKey, k -> this.createBucket(allowedRpm));
        if (bucket.tryConsume(1L)) {
            httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(allowedRpm));
            httpResponse.setHeader("X-RateLimit-Remaining", String.valueOf(bucket.getAvailableTokens()));
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded for key: {} on path: {}", rateLimitKey, path);
            User user = SecurityContext.getCurrentUser();
            if (user != null) {
                this.auditService.logAccessDenied(user, path, "Rate limit exceeded", httpRequest);
            }
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.setHeader("Retry-After", "60");
            httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(allowedRpm));
            httpResponse.setHeader("X-RateLimit-Remaining", "0");
            httpResponse.getWriter().write("{\"error\": \"Rate limit exceeded. Please wait before making more requests.\", \"errorCode\": \"ERR-429\", \"retryAfter\": 60}");
        }
    }

    private Bucket createBucket(int requestsPerMinute) {
        long capacity = requestsPerMinute;
        Bandwidth limit = Bandwidth.builder().capacity(capacity).refillGreedy(capacity, Duration.ofMinutes(1L)).build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String getRateLimitKey(HttpServletRequest request) {
        User user = SecurityContext.getCurrentUser();
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }
        String ip = this.clientIpResolver.resolveClientIp(request);
        return "ip:" + ip;
    }

    private int getAllowedRpm() {
        User user = SecurityContext.getCurrentUser();
        if (user == null) {
            return this.anonymousRpm;
        }
        if (user.hasRole(UserRole.ADMIN)) {
            return this.adminRpm;
        }
        if (user.hasRole(UserRole.ANALYST)) {
            return this.analystRpm;
        }
        return this.viewerRpm;
    }

    private boolean isExemptPath(String path) {
        // R-03: /api/status removed — now requires auth and should be rate-limited
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.startsWith("/fonts/") || "/favicon.ico".equals(path) || "/api/health".equals(path);
    }
}
