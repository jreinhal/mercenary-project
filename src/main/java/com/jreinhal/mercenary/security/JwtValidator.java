/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.nimbusds.jose.JOSEException
 *  com.nimbusds.jose.JWSAlgorithm
 *  com.nimbusds.jose.jwk.source.JWKSource
 *  com.nimbusds.jose.proc.BadJOSEException
 *  com.nimbusds.jose.proc.JWSKeySelector
 *  com.nimbusds.jose.proc.JWSVerificationKeySelector
 *  com.nimbusds.jose.proc.SecurityContext
 *  com.nimbusds.jwt.JWTClaimsSet
 *  com.nimbusds.jwt.SignedJWT
 *  com.nimbusds.jwt.proc.DefaultJWTProcessor
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.security;

import com.jreinhal.mercenary.security.JwksKeyProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtValidator {
    private static final Logger log = LoggerFactory.getLogger(JwtValidator.class);
    private final JwksKeyProvider keyProvider;
    @Value(value="${app.oidc.issuer:}")
    private String expectedIssuer;
    @Value(value="${app.oidc.client-id:}")
    private String expectedAudience;
    @Value(value="${app.oidc.allowed-algorithms:RS256,RS384,RS512,ES256}")
    private String allowedAlgorithms;
    @Value(value="${app.oidc.clock-skew-seconds:60}")
    private long clockSkewSeconds;
    @Value(value="${app.oidc.validate-issuer:true}")
    private boolean validateIssuer;
    @Value(value="${app.oidc.validate-audience:true}")
    private boolean validateAudience;

    public JwtValidator(JwksKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public ValidationResult validate(String token) {
        if (token == null || token.isEmpty()) {
            return ValidationResult.failure("Token is null or empty");
        }
        try {
            SignedJWT signedJWT = SignedJWT.parse((String)token);
            JWSAlgorithm algorithm = signedJWT.getHeader().getAlgorithm();
            if (!this.isAllowedAlgorithm(algorithm)) {
                return ValidationResult.failure("Algorithm not allowed: " + String.valueOf(algorithm));
            }
            JWKSource<SecurityContext> keySource = this.keyProvider.getKeySource();
            if (keySource == null) {
                return ValidationResult.failure("JWKS not available - cannot validate signature");
            }
            DefaultJWTProcessor processor = new DefaultJWTProcessor();
            JWSVerificationKeySelector keySelector = new JWSVerificationKeySelector(algorithm, keySource);
            processor.setJWSKeySelector((JWSKeySelector)keySelector);
            JWTClaimsSet claims = processor.process(signedJWT, null);
            ValidationResult claimsValidation = this.validateClaims(claims);
            if (!claimsValidation.isValid()) {
                return claimsValidation;
            }
            log.debug("JWT validated successfully for subject: {}", claims.getSubject());
            return ValidationResult.success(claims);
        }
        catch (ParseException e) {
            log.warn("Failed to parse JWT: {}", e.getMessage());
            return ValidationResult.failure("Invalid JWT format: " + e.getMessage());
        }
        catch (BadJOSEException e) {
            log.warn("JWT signature verification failed: {}", e.getMessage());
            return ValidationResult.failure("Signature verification failed: " + e.getMessage());
        }
        catch (JOSEException e) {
            log.error("JWT processing error: {}", e.getMessage());
            return ValidationResult.failure("JWT processing error: " + e.getMessage());
        }
    }

    private ValidationResult validateClaims(JWTClaimsSet claims) {
        List audience;
        String issuer;
        Instant issuedAt;
        Instant notBefore;
        Instant expiry;
        Instant now = Instant.now();
        Date expirationTime = claims.getExpirationTime();
        if (expirationTime != null && now.isAfter(expiry = expirationTime.toInstant().plusSeconds(this.clockSkewSeconds))) {
            return ValidationResult.failure("Token has expired");
        }
        Date notBeforeTime = claims.getNotBeforeTime();
        if (notBeforeTime != null && now.isBefore(notBefore = notBeforeTime.toInstant().minusSeconds(this.clockSkewSeconds))) {
            return ValidationResult.failure("Token not yet valid");
        }
        Date issueTime = claims.getIssueTime();
        if (issueTime != null && now.isBefore(issuedAt = issueTime.toInstant().minusSeconds(this.clockSkewSeconds))) {
            return ValidationResult.failure("Token issued in the future");
        }
        if (!(!this.validateIssuer || this.expectedIssuer == null || this.expectedIssuer.isEmpty() || (issuer = claims.getIssuer()) != null && issuer.equals(this.expectedIssuer))) {
            return ValidationResult.failure("Invalid issuer: " + issuer);
        }
        if (!(!this.validateAudience || this.expectedAudience == null || this.expectedAudience.isEmpty() || (audience = claims.getAudience()) != null && audience.contains(this.expectedAudience))) {
            return ValidationResult.failure("Invalid audience");
        }
        if (claims.getSubject() == null || claims.getSubject().isEmpty()) {
            return ValidationResult.failure("Token missing subject claim");
        }
        return ValidationResult.success(claims);
    }

    private boolean isAllowedAlgorithm(JWSAlgorithm algorithm) {
        if (this.allowedAlgorithms == null || this.allowedAlgorithms.isEmpty()) {
            return algorithm.equals(JWSAlgorithm.RS256) || algorithm.equals(JWSAlgorithm.RS384) || algorithm.equals(JWSAlgorithm.RS512) || algorithm.equals(JWSAlgorithm.ES256);
        }
        HashSet<String> allowed = new HashSet<String>(Arrays.asList(this.allowedAlgorithms.split(",")));
        return allowed.contains(algorithm.getName());
    }

    public boolean isValidFormat(String token) {
        if (token == null) {
            return false;
        }
        String[] parts = token.split("\\.");
        return parts.length == 3;
    }

    public static class ValidationResult {
        private final boolean valid;
        private final JWTClaimsSet claims;
        private final String error;

        private ValidationResult(boolean valid, JWTClaimsSet claims, String error) {
            this.valid = valid;
            this.claims = claims;
            this.error = error;
        }

        public static ValidationResult success(JWTClaimsSet claims) {
            return new ValidationResult(true, claims, null);
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, null, error);
        }

        public boolean isValid() {
            return this.valid;
        }

        public JWTClaimsSet getClaims() {
            return this.claims;
        }

        public String getError() {
            return this.error;
        }

        public String getSubject() {
            return this.claims != null ? this.claims.getSubject() : null;
        }

        public String getStringClaim(String name) {
            if (this.claims == null) {
                return null;
            }
            try {
                return this.claims.getStringClaim(name);
            }
            catch (ParseException e) {
                return null;
            }
        }
    }
}
