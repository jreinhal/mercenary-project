package com.jreinhal.mercenary.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JWKS (JSON Web Key Set) Provider for JWT validation.
 *
 * Supports multiple key sources:
 * 1. Remote JWKS endpoint (standard OIDC discovery)
 * 2. Local JWKS file (for air-gapped deployments)
 *
 * Features:
 * - Key caching with configurable TTL
 * - Thread-safe key refresh
 * - Graceful fallback when remote unavailable
 */
@Component
public class JwksKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyProvider.class);

    @Value("${app.oidc.jwks-uri:}")
    private String jwksUri;

    @Value("${app.oidc.local-jwks-path:}")
    private String localJwksPath;

    @Value("${app.oidc.jwks-cache-ttl:3600}")
    private long cacheTtlSeconds;

    @Value("${app.oidc.issuer:}")
    private String issuer;

    private JWKSet cachedKeySet;
    private Instant cacheExpiry = Instant.MIN;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Get the current JWKS for validation.
     * Will refresh from source if cache is expired.
     *
     * @return JWKSource for JWT validation, or null if unavailable
     */
    public JWKSource<SecurityContext> getKeySource() {
        lock.readLock().lock();
        try {
            if (cachedKeySet != null && Instant.now().isBefore(cacheExpiry)) {
                return new ImmutableJWKSet<>(cachedKeySet);
            }
        } finally {
            lock.readLock().unlock();
        }

        // Need to refresh - acquire write lock
        lock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            if (cachedKeySet != null && Instant.now().isBefore(cacheExpiry)) {
                return new ImmutableJWKSet<>(cachedKeySet);
            }

            // Try to refresh keys
            JWKSet newKeySet = loadKeys();
            if (newKeySet != null) {
                cachedKeySet = newKeySet;
                cacheExpiry = Instant.now().plusSeconds(cacheTtlSeconds);
                log.info("JWKS refreshed: {} keys loaded, cache expires at {}",
                        newKeySet.getKeys().size(), cacheExpiry);
            }

            return cachedKeySet != null ? new ImmutableJWKSet<>(cachedKeySet) : null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Load keys from configured source (local file or remote).
     */
    private JWKSet loadKeys() {
        // Priority 1: Local file (for air-gapped deployments)
        if (localJwksPath != null && !localJwksPath.isEmpty()) {
            JWKSet localKeys = loadFromFile(localJwksPath);
            if (localKeys != null) {
                log.info("Loaded JWKS from local file: {}", localJwksPath);
                return localKeys;
            }
        }

        // Priority 2: Configured JWKS URI
        if (jwksUri != null && !jwksUri.isEmpty()) {
            JWKSet remoteKeys = loadFromUri(jwksUri);
            if (remoteKeys != null) {
                return remoteKeys;
            }
        }

        // Priority 3: Discover from issuer's well-known endpoint
        if (issuer != null && !issuer.isEmpty()) {
            String discoveredUri = issuer + "/.well-known/jwks.json";
            JWKSet discoveredKeys = loadFromUri(discoveredUri);
            if (discoveredKeys != null) {
                return discoveredKeys;
            }

            // Try OpenID Connect discovery
            discoveredUri = issuer + "/.well-known/openid-configuration";
            // Note: Full OIDC discovery would parse this JSON to find jwks_uri
            // For simplicity, we skip this and rely on explicit configuration
        }

        log.error("Failed to load JWKS from any source");
        return null;
    }

    /**
     * Load JWKS from a local file.
     */
    private JWKSet loadFromFile(String path) {
        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                log.warn("Local JWKS file not found: {}", path);
                return null;
            }

            String content = Files.readString(filePath);
            JWKSet keySet = JWKSet.parse(content);
            log.debug("Loaded {} keys from local file", keySet.getKeys().size());
            return keySet;
        } catch (IOException | ParseException e) {
            log.error("Failed to load JWKS from file {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Load JWKS from a remote URI.
     */
    private JWKSet loadFromUri(String uri) {
        try {
            log.debug("Fetching JWKS from: {}", uri);
            URL url = new URL(uri);
            JWKSet keySet = JWKSet.load(url);
            log.info("Loaded {} keys from remote JWKS endpoint", keySet.getKeys().size());
            return keySet;
        } catch (IOException | ParseException e) {
            log.warn("Failed to load JWKS from {}: {}", uri, e.getMessage());
            return null;
        }
    }

    /**
     * Get a specific key by its key ID (kid).
     */
    public JWK getKey(String keyId) throws JOSEException {
        lock.readLock().lock();
        try {
            if (cachedKeySet == null) {
                return null;
            }
            return cachedKeySet.getKeyByKeyId(keyId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Force a refresh of the key cache.
     * Useful for manual recovery or testing.
     */
    public void forceRefresh() {
        lock.writeLock().lock();
        try {
            cacheExpiry = Instant.MIN;
            cachedKeySet = loadKeys();
            if (cachedKeySet != null) {
                cacheExpiry = Instant.now().plusSeconds(cacheTtlSeconds);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Check if keys are available.
     */
    public boolean hasKeys() {
        return getKeySource() != null;
    }

    /**
     * Pre-load keys for air-gapped deployments.
     * Call this at startup to ensure keys are available before any requests.
     */
    public void preload() {
        log.info("Pre-loading JWKS for air-gapped deployment...");
        forceRefresh();
        if (hasKeys()) {
            log.info("JWKS pre-loaded successfully");
        } else {
            log.warn("JWKS pre-load failed - JWT validation will fail until keys are available");
        }
    }
}
