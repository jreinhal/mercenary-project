package com.jreinhal.mercenary.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class JwksKeyProviderTest {

    @Test
    void normalizeIssuerStripsTrailingSlash() {
        assertEquals("https://idp.example.com", JwksKeyProvider.normalizeIssuer("https://idp.example.com/"));
        assertEquals("https://idp.example.com", JwksKeyProvider.normalizeIssuer("https://idp.example.com///"));
    }

    @Test
    void fetchOpenIdConfigurationReturnsEmptyMapForInvalidUri() {
        JwksKeyProvider provider = new JwksKeyProvider();

        Map<String, Object> metadata = provider.fetchOpenIdConfiguration("://invalid-uri");

        assertTrue(metadata.isEmpty());
    }

    @Test
    void discoverJwksUriFromIssuerReturnsConfiguredJwksUri() {
        JwksKeyProvider provider = spy(new JwksKeyProvider());
        doReturn(Map.of("jwks_uri", "https://idp.example.com/oauth2/keys"))
                .when(provider)
                .fetchOpenIdConfiguration("https://idp.example.com/.well-known/openid-configuration");

        String discovered = provider.discoverJwksUriFromIssuer("https://idp.example.com");

        assertEquals("https://idp.example.com/oauth2/keys", discovered);
    }

    @Test
    void discoverJwksUriFromIssuerReturnsNullWhenMissingJwksUri() {
        JwksKeyProvider provider = spy(new JwksKeyProvider());
        doReturn(Map.of("issuer", "https://idp.example.com/"))
                .when(provider)
                .fetchOpenIdConfiguration("https://idp.example.com/.well-known/openid-configuration");

        String discovered = provider.discoverJwksUriFromIssuer("https://idp.example.com");

        assertNull(discovered);
    }

    @Test
    void forceRefreshUsesDiscoveredJwksUriBeforeLegacyFallback() {
        JwksKeyProvider provider = spy(new JwksKeyProvider());
        ReflectionTestUtils.setField(provider, "issuer", "https://idp.example.com/");
        ReflectionTestUtils.setField(provider, "jwksUri", "");
        ReflectionTestUtils.setField(provider, "localJwksPath", "");

        doReturn(Map.of("jwks_uri", "https://idp.example.com/oauth2/keys"))
                .when(provider)
                .fetchOpenIdConfiguration("https://idp.example.com/.well-known/openid-configuration");
        doReturn(null).when(provider).loadFromUri(anyString());

        provider.forceRefresh();

        InOrder inOrder = inOrder(provider);
        inOrder.verify(provider).loadFromUri("https://idp.example.com/oauth2/keys");
        inOrder.verify(provider).loadFromUri("https://idp.example.com/.well-known/jwks.json");
    }

    @Test
    void forceRefreshFallsBackToLegacyJwksPathWhenDiscoveryMissingJwksUri() {
        JwksKeyProvider provider = spy(new JwksKeyProvider());
        ReflectionTestUtils.setField(provider, "issuer", "https://idp.example.com/");
        ReflectionTestUtils.setField(provider, "jwksUri", "");
        ReflectionTestUtils.setField(provider, "localJwksPath", "");

        doReturn(Map.of("issuer", "https://idp.example.com/"))
                .when(provider)
                .fetchOpenIdConfiguration("https://idp.example.com/.well-known/openid-configuration");
        doReturn(null).when(provider).loadFromUri(anyString());

        provider.forceRefresh();

        verify(provider, times(1)).loadFromUri("https://idp.example.com/.well-known/jwks.json");
    }
}
