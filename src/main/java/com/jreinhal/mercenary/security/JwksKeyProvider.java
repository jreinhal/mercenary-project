package com.jreinhal.mercenary.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwksKeyProvider {
    private static final Logger log = LoggerFactory.getLogger(JwksKeyProvider.class);
    private static final String OIDC_DISCOVERY_PATH = "/.well-known/openid-configuration";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Value(value="${app.oidc.jwks-uri:}")
    private String jwksUri;
    @Value(value="${app.oidc.local-jwks-path:}")
    private String localJwksPath;
    @Value(value="${app.oidc.jwks-cache-ttl:3600}")
    private long cacheTtlSeconds;
    @Value(value="${app.oidc.issuer:}")
    private String issuer;
    private JWKSet cachedKeySet;
    private Instant cacheExpiry = Instant.MIN;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public JWKSource<SecurityContext> getKeySource() {
        this.lock.readLock().lock();
        try {
            if (this.cachedKeySet != null && Instant.now().isBefore(this.cacheExpiry)) {
                ImmutableJWKSet<SecurityContext> immutableJWKSet = new ImmutableJWKSet<>(this.cachedKeySet);
                return immutableJWKSet;
            }
        }
        finally {
            this.lock.readLock().unlock();
        }
        this.lock.writeLock().lock();
        try {
            if (this.cachedKeySet != null && Instant.now().isBefore(this.cacheExpiry)) {
                ImmutableJWKSet<SecurityContext> immutableJWKSet = new ImmutableJWKSet<>(this.cachedKeySet);
                return immutableJWKSet;
            }
            JWKSet newKeySet = this.loadKeys();
            if (newKeySet != null) {
                this.cachedKeySet = newKeySet;
                this.cacheExpiry = Instant.now().plusSeconds(this.cacheTtlSeconds);
                log.info("JWKS refreshed: {} keys loaded, cache expires at {}", newKeySet.getKeys().size(), this.cacheExpiry);
            }
            ImmutableJWKSet<SecurityContext> immutableJWKSet = this.cachedKeySet != null ? new ImmutableJWKSet<>(this.cachedKeySet) : null;
            return immutableJWKSet;
        }
        finally {
            this.lock.writeLock().unlock();
        }
    }

    private JWKSet loadKeys() {
        JWKSet remoteKeys;
        JWKSet localKeys;
        if (this.localJwksPath != null && !this.localJwksPath.isEmpty() && (localKeys = this.loadFromFile(this.localJwksPath)) != null) {
            log.info("Loaded JWKS from local file: {}", this.localJwksPath);
            return localKeys;
        }
        if (this.jwksUri != null && !this.jwksUri.isEmpty() && (remoteKeys = this.loadFromUri(this.jwksUri)) != null) {
            return remoteKeys;
        }
        if (this.issuer != null && !this.issuer.isEmpty()) {
            String normalizedIssuer = normalizeIssuer(this.issuer);
            String discoveredJwksUri = discoverJwksUriFromIssuer(normalizedIssuer);
            if (discoveredJwksUri != null) {
                JWKSet discoveredKeys = this.loadFromUri(discoveredJwksUri);
                if (discoveredKeys != null) {
                    return discoveredKeys;
                }
            }
            // Backward-compatible fallback for providers that expose JWKS at this legacy path.
            String fallbackUri = normalizedIssuer + "/.well-known/jwks.json";
            JWKSet fallbackKeys = this.loadFromUri(fallbackUri);
            if (fallbackKeys != null) {
                return fallbackKeys;
            }
        }
        log.error("Failed to load JWKS from any source");
        return null;
    }

    String discoverJwksUriFromIssuer(String normalizedIssuer) {
        String metadataUri = normalizedIssuer + OIDC_DISCOVERY_PATH;
        Map<String, Object> metadata = fetchOpenIdConfiguration(metadataUri);
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object jwksUriValue = metadata.get("jwks_uri");
        if (jwksUriValue instanceof String jwksUriString && !jwksUriString.isBlank()) {
            return jwksUriString;
        }
        log.warn("OIDC discovery metadata missing jwks_uri at {}", metadataUri);
        return null;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> fetchOpenIdConfiguration(String metadataUri) {
        try {
            URL url = URI.create(metadataUri).toURL();
            try (InputStream stream = url.openStream()) {
                return OBJECT_MAPPER.readValue(stream, Map.class);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.debug("Failed to load OIDC discovery metadata from {}: {}", metadataUri, e.getMessage());
            return null;
        }
    }

    static String normalizeIssuer(String rawIssuer) {
        String normalized = rawIssuer.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private JWKSet loadFromFile(String path) {
        try {
            Path filePath = Path.of(path, new String[0]);
            if (!Files.exists(filePath, new LinkOption[0])) {
                log.warn("Local JWKS file not found: {}", path);
                return null;
            }
            String content = Files.readString(filePath);
            JWKSet keySet = JWKSet.parse((String)content);
            log.debug("Loaded {} keys from local file", keySet.getKeys().size());
            return keySet;
        }
        catch (IOException | ParseException e) {
            log.error("Failed to load JWKS from file {}: {}", path, e.getMessage());
            return null;
        }
    }

    JWKSet loadFromUri(String uri) {
        try {
            log.debug("Fetching JWKS from: {}", uri);
            URL url = URI.create(uri).toURL();
            JWKSet keySet = JWKSet.load((URL)url);
            log.info("Loaded {} keys from remote JWKS endpoint", keySet.getKeys().size());
            return keySet;
        }
        catch (IOException | ParseException | IllegalArgumentException e) {
            log.warn("Failed to load JWKS from {}: {}", uri, e.getMessage());
            return null;
        }
    }

    public JWK getKey(String keyId) throws JOSEException {
        this.lock.readLock().lock();
        try {
            if (this.cachedKeySet == null) {
                JWK jWK = null;
                return jWK;
            }
            JWK jWK = this.cachedKeySet.getKeyByKeyId(keyId);
            return jWK;
        }
        finally {
            this.lock.readLock().unlock();
        }
    }

    public void forceRefresh() {
        this.lock.writeLock().lock();
        try {
            this.cacheExpiry = Instant.MIN;
            this.cachedKeySet = this.loadKeys();
            if (this.cachedKeySet != null) {
                this.cacheExpiry = Instant.now().plusSeconds(this.cacheTtlSeconds);
            }
        }
        finally {
            this.lock.writeLock().unlock();
        }
    }

    public boolean hasKeys() {
        return this.getKeySource() != null;
    }

    public void preload() {
        log.info("Pre-loading JWKS for air-gapped deployment...");
        this.forceRefresh();
        if (this.hasKeys()) {
            log.info("JWKS pre-loaded successfully");
        } else {
            log.warn("JWKS pre-load failed - JWT validation will fail until keys are available");
        }
    }
}
