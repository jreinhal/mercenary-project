package com.jreinhal.mercenary.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {
    @Value("${app.auth.lockout.enabled:true}")
    private boolean enabled;
    @Value("${app.auth.lockout.max-attempts:5}")
    private int maxAttempts;
    @Value("${app.auth.lockout.window-minutes:15}")
    private int windowMinutes;
    @Value("${app.auth.lockout.duration-minutes:15}")
    private int lockoutMinutes;

    private Cache<String, AttemptRecord> attempts;
    private Cache<String, Instant> lockouts;

    @PostConstruct
    public void init() {
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(this.windowMinutes))
                .build();
        this.lockouts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(this.lockoutMinutes))
                .build();
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public boolean isLockedOut(String key) {
        if (!this.enabled) {
            return false;
        }
        return this.lockouts.getIfPresent(key) != null;
    }

    public void recordFailure(String key) {
        if (!this.enabled) {
            return;
        }
        AttemptRecord current = this.attempts.get(key, k -> new AttemptRecord(0, Instant.now()));
        int nextCount = current.count() + 1;
        this.attempts.put(key, new AttemptRecord(nextCount, current.firstAttempt()));
        if (nextCount >= this.maxAttempts) {
            this.lockouts.put(key, Instant.now());
            this.attempts.invalidate(key);
        }
    }

    public void recordSuccess(String key) {
        if (!this.enabled) {
            return;
        }
        this.attempts.invalidate(key);
        this.lockouts.invalidate(key);
    }

    public String buildKey(String username, String clientIp) {
        String safeUser = username == null ? "unknown" : username.trim().toLowerCase();
        String safeIp = clientIp == null ? "unknown" : clientIp.trim();
        return safeUser + "|" + safeIp;
    }

    private record AttemptRecord(int count, Instant firstAttempt) {
    }
}
