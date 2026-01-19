/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.security.JwksKeyProvider
 *  com.nimbusds.jose.JOSEException
 *  com.nimbusds.jose.jwk.JWK
 *  com.nimbusds.jose.jwk.JWKSet
 *  com.nimbusds.jose.jwk.source.ImmutableJWKSet
 *  com.nimbusds.jose.jwk.source.JWKSource
 *  com.nimbusds.jose.proc.SecurityContext
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwksKeyProvider {
    private static final Logger log = LoggerFactory.getLogger(JwksKeyProvider.class);
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
                ImmutableJWKSet immutableJWKSet = new ImmutableJWKSet(this.cachedKeySet);
                return immutableJWKSet;
            }
        }
        finally {
            this.lock.readLock().unlock();
        }
        this.lock.writeLock().lock();
        try {
            if (this.cachedKeySet != null && Instant.now().isBefore(this.cacheExpiry)) {
                ImmutableJWKSet immutableJWKSet = new ImmutableJWKSet(this.cachedKeySet);
                return immutableJWKSet;
            }
            JWKSet newKeySet = this.loadKeys();
            if (newKeySet != null) {
                this.cachedKeySet = newKeySet;
                this.cacheExpiry = Instant.now().plusSeconds(this.cacheTtlSeconds);
                log.info("JWKS refreshed: {} keys loaded, cache expires at {}", (Object)newKeySet.getKeys().size(), (Object)this.cacheExpiry);
            }
            ImmutableJWKSet immutableJWKSet = this.cachedKeySet != null ? new ImmutableJWKSet(this.cachedKeySet) : null;
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
            log.info("Loaded JWKS from local file: {}", (Object)this.localJwksPath);
            return localKeys;
        }
        if (this.jwksUri != null && !this.jwksUri.isEmpty() && (remoteKeys = this.loadFromUri(this.jwksUri)) != null) {
            return remoteKeys;
        }
        if (this.issuer != null && !this.issuer.isEmpty()) {
            String discoveredUri = this.issuer + "/.well-known/jwks.json";
            JWKSet discoveredKeys = this.loadFromUri(discoveredUri);
            if (discoveredKeys != null) {
                return discoveredKeys;
            }
            String string = this.issuer + "/.well-known/openid-configuration";
        }
        log.error("Failed to load JWKS from any source");
        return null;
    }

    private JWKSet loadFromFile(String path) {
        try {
            Path filePath = Path.of(path, new String[0]);
            if (!Files.exists(filePath, new LinkOption[0])) {
                log.warn("Local JWKS file not found: {}", (Object)path);
                return null;
            }
            String content = Files.readString(filePath);
            JWKSet keySet = JWKSet.parse((String)content);
            log.debug("Loaded {} keys from local file", (Object)keySet.getKeys().size());
            return keySet;
        }
        catch (IOException | ParseException e) {
            log.error("Failed to load JWKS from file {}: {}", (Object)path, (Object)e.getMessage());
            return null;
        }
    }

    private JWKSet loadFromUri(String uri) {
        try {
            log.debug("Fetching JWKS from: {}", (Object)uri);
            URL url = new URL(uri);
            JWKSet keySet = JWKSet.load((URL)url);
            log.info("Loaded {} keys from remote JWKS endpoint", (Object)keySet.getKeys().size());
            return keySet;
        }
        catch (IOException | ParseException e) {
            log.warn("Failed to load JWKS from {}: {}", (Object)uri, (Object)e.getMessage());
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

