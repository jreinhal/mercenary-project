package com.jreinhal.mercenary.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Instant;
import java.util.*;

/**
 * JWT Validator with full cryptographic signature verification.
 *
 * Validates:
 * - Signature (using JWKS from JwksKeyProvider)
 * - Issuer (iss claim)
 * - Audience (aud claim)
 * - Expiration (exp claim)
 * - Not Before (nbf claim)
 * - Issued At (iat claim)
 *
 * Supports algorithms: RS256, RS384, RS512, ES256, ES384, ES512
 */
@Component
public class JwtValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtValidator.class);

    private final JwksKeyProvider keyProvider;

    @Value("${app.oidc.issuer:}")
    private String expectedIssuer;

    @Value("${app.oidc.client-id:}")
    private String expectedAudience;

    @Value("${app.oidc.allowed-algorithms:RS256,RS384,RS512,ES256}")
    private String allowedAlgorithms;

    @Value("${app.oidc.clock-skew-seconds:60}")
    private long clockSkewSeconds;

    @Value("${app.oidc.validate-issuer:true}")
    private boolean validateIssuer;

    @Value("${app.oidc.validate-audience:true}")
    private boolean validateAudience;

    public JwtValidator(JwksKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * Validation result containing claims or error information.
     */
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
            return valid;
        }

        public JWTClaimsSet getClaims() {
            return claims;
        }

        public String getError() {
            return error;
        }

        public String getSubject() {
            return claims != null ? claims.getSubject() : null;
        }

        public String getStringClaim(String name) {
            if (claims == null) return null;
            try {
                return claims.getStringClaim(name);
            } catch (ParseException e) {
                return null;
            }
        }
    }

    /**
     * Validate a JWT token with full signature verification.
     *
     * @param token The JWT token string
     * @return ValidationResult with claims if valid, or error message if invalid
     */
    public ValidationResult validate(String token) {
        if (token == null || token.isEmpty()) {
            return ValidationResult.failure("Token is null or empty");
        }

        try {
            // Parse the token first (to get header info for key selection)
            SignedJWT signedJWT = SignedJWT.parse(token);

            // Check if algorithm is allowed
            JWSAlgorithm algorithm = signedJWT.getHeader().getAlgorithm();
            if (!isAllowedAlgorithm(algorithm)) {
                return ValidationResult.failure("Algorithm not allowed: " + algorithm);
            }

            // Get JWKS from provider
            JWKSource<SecurityContext> keySource = keyProvider.getKeySource();
            if (keySource == null) {
                return ValidationResult.failure("JWKS not available - cannot validate signature");
            }

            // Configure JWT processor
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

            // Set up key selector for the algorithm
            JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(
                    algorithm, keySource);
            processor.setJWSKeySelector(keySelector);

            // Process and verify the token
            JWTClaimsSet claims = processor.process(signedJWT, null);

            // Validate standard claims
            ValidationResult claimsValidation = validateClaims(claims);
            if (!claimsValidation.isValid()) {
                return claimsValidation;
            }

            log.debug("JWT validated successfully for subject: {}", claims.getSubject());
            return ValidationResult.success(claims);

        } catch (ParseException e) {
            log.warn("Failed to parse JWT: {}", e.getMessage());
            return ValidationResult.failure("Invalid JWT format: " + e.getMessage());
        } catch (BadJOSEException e) {
            log.warn("JWT signature verification failed: {}", e.getMessage());
            return ValidationResult.failure("Signature verification failed: " + e.getMessage());
        } catch (JOSEException e) {
            log.error("JWT processing error: {}", e.getMessage());
            return ValidationResult.failure("JWT processing error: " + e.getMessage());
        }
    }

    /**
     * Validate JWT claims (issuer, audience, expiration, etc.)
     */
    private ValidationResult validateClaims(JWTClaimsSet claims) {
        Instant now = Instant.now();

        // Check expiration
        Date expirationTime = claims.getExpirationTime();
        if (expirationTime != null) {
            Instant expiry = expirationTime.toInstant().plusSeconds(clockSkewSeconds);
            if (now.isAfter(expiry)) {
                return ValidationResult.failure("Token has expired");
            }
        }

        // Check not before
        Date notBeforeTime = claims.getNotBeforeTime();
        if (notBeforeTime != null) {
            Instant notBefore = notBeforeTime.toInstant().minusSeconds(clockSkewSeconds);
            if (now.isBefore(notBefore)) {
                return ValidationResult.failure("Token not yet valid");
            }
        }

        // Check issued at (reject tokens from the future)
        Date issueTime = claims.getIssueTime();
        if (issueTime != null) {
            Instant issuedAt = issueTime.toInstant().minusSeconds(clockSkewSeconds);
            if (now.isBefore(issuedAt)) {
                return ValidationResult.failure("Token issued in the future");
            }
        }

        // Validate issuer
        if (validateIssuer && expectedIssuer != null && !expectedIssuer.isEmpty()) {
            String issuer = claims.getIssuer();
            if (issuer == null || !issuer.equals(expectedIssuer)) {
                return ValidationResult.failure("Invalid issuer: " + issuer);
            }
        }

        // Validate audience
        if (validateAudience && expectedAudience != null && !expectedAudience.isEmpty()) {
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                return ValidationResult.failure("Invalid audience");
            }
        }

        // Ensure subject exists
        if (claims.getSubject() == null || claims.getSubject().isEmpty()) {
            return ValidationResult.failure("Token missing subject claim");
        }

        return ValidationResult.success(claims);
    }

    /**
     * Check if the algorithm is in the allowed list.
     */
    private boolean isAllowedAlgorithm(JWSAlgorithm algorithm) {
        if (allowedAlgorithms == null || allowedAlgorithms.isEmpty()) {
            // Default allowed algorithms
            return algorithm.equals(JWSAlgorithm.RS256) ||
                   algorithm.equals(JWSAlgorithm.RS384) ||
                   algorithm.equals(JWSAlgorithm.RS512) ||
                   algorithm.equals(JWSAlgorithm.ES256);
        }

        Set<String> allowed = new HashSet<>(Arrays.asList(allowedAlgorithms.split(",")));
        return allowed.contains(algorithm.getName());
    }

    /**
     * Quick check if a token appears to be a valid JWT format.
     * Does not validate signature.
     */
    public boolean isValidFormat(String token) {
        if (token == null) return false;
        String[] parts = token.split("\\.");
        return parts.length == 3;
    }
}
