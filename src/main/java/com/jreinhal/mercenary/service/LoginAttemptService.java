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
    // M-04: Global per-username threshold (regardless of IP) to prevent distributed brute-force
    @Value("${app.auth.lockout.global-max-attempts:20}")
    private int globalMaxAttempts;

    private Cache<String, AttemptRecord> attempts;
    private Cache<String, Instant> lockouts;
    // M-04: Separate cache keyed by username only (no IP)
    private Cache<String, AttemptRecord> globalAttempts;
    private Cache<String, Instant> globalLockouts;

    @PostConstruct
    public void init() {
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(this.windowMinutes))
                .build();
        this.lockouts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(this.lockoutMinutes))
                .build();
        // M-04: Global per-username caches
        this.globalAttempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(this.windowMinutes))
                .build();
        this.globalLockouts = Caffeine.newBuilder()
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
        // Check both per-IP key and global per-username lockout
        if (this.lockouts.getIfPresent(key) != null) {
            return true;
        }
        // M-04: Extract username from composite key for global check
        String username = extractUsername(key);
        return this.globalLockouts.getIfPresent(username) != null;
    }

    public void recordFailure(String key) {
        if (!this.enabled) {
            return;
        }
        // Per-IP tracking (existing behavior)
        AttemptRecord current = this.attempts.get(key, k -> new AttemptRecord(0, Instant.now()));
        int nextCount = current.count() + 1;
        this.attempts.put(key, new AttemptRecord(nextCount, current.firstAttempt()));
        if (nextCount >= this.maxAttempts) {
            this.lockouts.put(key, Instant.now());
            this.attempts.invalidate(key);
        }
        // M-04: Global per-username tracking (prevents distributed brute-force from many IPs)
        String username = extractUsername(key);
        AttemptRecord globalCurrent = this.globalAttempts.get(username, k -> new AttemptRecord(0, Instant.now()));
        int globalNext = globalCurrent.count() + 1;
        this.globalAttempts.put(username, new AttemptRecord(globalNext, globalCurrent.firstAttempt()));
        if (globalNext >= this.globalMaxAttempts) {
            this.globalLockouts.put(username, Instant.now());
            this.globalAttempts.invalidate(username);
        }
    }

    public void recordSuccess(String key) {
        if (!this.enabled) {
            return;
        }
        this.attempts.invalidate(key);
        this.lockouts.invalidate(key);
        // M-04: Clear global username counter on success
        String username = extractUsername(key);
        this.globalAttempts.invalidate(username);
        this.globalLockouts.invalidate(username);
    }

    public String buildKey(String username, String clientIp) {
        String safeUser = username == null ? "unknown" : username.trim().toLowerCase(java.util.Locale.ROOT);
        String safeIp = clientIp == null ? "unknown" : clientIp.trim();
        return safeUser + "|" + safeIp;
    }

    private static String extractUsername(String compositeKey) {
        int pipe = compositeKey.indexOf('|');
        return pipe >= 0 ? compositeKey.substring(0, pipe) : compositeKey;
    }

    private record AttemptRecord(int count, Instant firstAttempt) {
    }
}
