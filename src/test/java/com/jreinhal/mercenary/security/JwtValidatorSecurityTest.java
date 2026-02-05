package com.jreinhal.mercenary.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.service.HipaaPolicy;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtValidatorSecurityTest {

    @Test
    void shouldRejectRevokedTokenJti() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));

        JwksKeyProvider keyProvider = mock(JwksKeyProvider.class);
        when(keyProvider.getKeySource()).thenReturn(keySource);

        HipaaPolicy hipaaPolicy = mock(HipaaPolicy.class);
        when(hipaaPolicy.shouldRequireOidcMfa()).thenReturn(false);

        JwtValidator validator = new JwtValidator(keyProvider, hipaaPolicy);
        ReflectionTestUtils.setField(validator, "blocklistEnabled", true);
        ReflectionTestUtils.setField(validator, "validateIssuer", false);
        ReflectionTestUtils.setField(validator, "validateAudience", false);
        ReflectionTestUtils.setField(validator, "clockSkewSeconds", 0L);

        Instant exp = Instant.now().plusSeconds(3600);
        String jti = "jti-123";

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("user-123")
            .jwtID(jti)
            .issueTime(new Date())
            .expirationTime(Date.from(exp))
            .build();

        String token = signToken(key, claims);

        JwtValidator.ValidationResult ok = validator.validate(token);
        assertTrue(ok.isValid(), ok.getError());

        validator.blockToken(jti, exp);

        JwtValidator.ValidationResult revoked = validator.validate(token);
        assertFalse(revoked.isValid());
        assertEquals("Token has been revoked", revoked.getError());
    }

    @Test
    void shouldRequireJtiWhenConfigured() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK()));

        JwksKeyProvider keyProvider = mock(JwksKeyProvider.class);
        when(keyProvider.getKeySource()).thenReturn(keySource);

        HipaaPolicy hipaaPolicy = mock(HipaaPolicy.class);
        when(hipaaPolicy.shouldRequireOidcMfa()).thenReturn(false);

        JwtValidator validator = new JwtValidator(keyProvider, hipaaPolicy);
        ReflectionTestUtils.setField(validator, "blocklistEnabled", true);
        ReflectionTestUtils.setField(validator, "requireJti", true);
        ReflectionTestUtils.setField(validator, "validateIssuer", false);
        ReflectionTestUtils.setField(validator, "validateAudience", false);
        ReflectionTestUtils.setField(validator, "clockSkewSeconds", 0L);

        Instant exp = Instant.now().plusSeconds(3600);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("user-123")
            .issueTime(new Date())
            .expirationTime(Date.from(exp))
            .build();

        String token = signToken(key, claims);

        JwtValidator.ValidationResult result = validator.validate(token);
        assertFalse(result.isValid());
        assertEquals("Token missing jti claim", result.getError());
    }

    private static String signToken(RSAKey key, JWTClaimsSet claims) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(key.getKeyID())
            .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(key.toPrivateKey()));
        return jwt.serialize();
    }
}

