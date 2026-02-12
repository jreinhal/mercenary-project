package com.jreinhal.mercenary.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.service.OidcAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class OidcBrowserFlowControllerTest {

    private OidcBrowserFlowController controller;
    private OidcAuthenticationService oidcAuthService;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        oidcAuthService = mock(OidcAuthenticationService.class);
        restTemplate = mock(RestTemplate.class);
        controller = new OidcBrowserFlowController(oidcAuthService, restTemplate);
        ReflectionTestUtils.setField(controller, "clientId", "test-client-id");
        ReflectionTestUtils.setField(controller, "authorizationUri", "https://idp.example.com/authorize");
        ReflectionTestUtils.setField(controller, "tokenUri", "https://idp.example.com/oauth/token");
        ReflectionTestUtils.setField(controller, "redirectUri", "https://app.example.com/api/auth/oidc/callback");
        ReflectionTestUtils.setField(controller, "scopes", "openid profile email");
        ReflectionTestUtils.setField(controller, "issuer", "");
    }

    // ===== Authorize Endpoint Tests =====

    @Test
    void authorizeRedirectsToIdP() {
        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        String url = location.toString();
        assertTrue(url.startsWith("https://idp.example.com/authorize?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=test-client-id"));
        assertTrue(url.contains("redirect_uri="));
        assertTrue(url.contains("scope=openid+profile+email"));
        assertTrue(url.contains("code_challenge="));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state="));

        verify(session).setAttribute(eq("oidc.pkce.verifier"), anyString());
        verify(session).setAttribute(eq("oidc.oauth.state"), anyString());
    }

    @Test
    void authorizeReturns503WhenNoAuthUriConfigured() throws Exception {
        ReflectionTestUtils.setField(controller, "authorizationUri", "");
        ReflectionTestUtils.setField(controller, "issuer", "");
        HttpServletRequest request = mockRequest();

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void authorizeFallsBackToIssuerForAuthUri() throws Exception {
        ReflectionTestUtils.setField(controller, "authorizationUri", "");
        ReflectionTestUtils.setField(controller, "issuer", "https://idp.example.com");
        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().startsWith("https://idp.example.com/authorize?"));
    }

    @Test
    void authorizeUsesDiscoveredAuthorizationEndpointWhenConfigured() throws Exception {
        ReflectionTestUtils.setField(controller, "authorizationUri", "");
        ReflectionTestUtils.setField(controller, "issuer", "https://idp.example.com/");
        when(restTemplate.getForObject("https://idp.example.com/.well-known/openid-configuration", Map.class))
                .thenReturn(Map.of("authorization_endpoint", "https://idp.example.com/oauth2/v2.0/authorize"));

        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().startsWith("https://idp.example.com/oauth2/v2.0/authorize?"));
    }

    @Test
    void authorizeFallsBackToIssuerPathWhenDiscoveryMissingAuthorizationEndpoint() {
        ReflectionTestUtils.setField(controller, "authorizationUri", "");
        ReflectionTestUtils.setField(controller, "issuer", "https://idp.example.com/");
        when(restTemplate.getForObject("https://idp.example.com/.well-known/openid-configuration", Map.class))
                .thenReturn(Map.of("issuer", "https://idp.example.com/"));

        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().startsWith("https://idp.example.com/authorize?"));
    }

    @Test
    void authorizeAutoDetectsRedirectUriWhenNotConfigured() throws Exception {
        ReflectionTestUtils.setField(controller, "redirectUri", "");
        HttpServletRequest request = mockRequest();
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("sentinel.example.com");
        when(request.getServerPort()).thenReturn(443);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        String url = location.toString();
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fsentinel.example.com%2Fapi%2Fauth%2Foidc%2Fcallback"));
    }

    // ===== Callback Endpoint Tests =====

    @Test
    void callbackRedirectsOnIdPError() {
        HttpServletRequest request = mockRequest();

        ResponseEntity<Void> response = controller.callback(
                null, null, "access_denied", "User declined", request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=access_denied"));
    }

    @Test
    void callbackRedirectsWhenCodeMissing() {
        HttpServletRequest request = mockRequest();

        ResponseEntity<Void> response = controller.callback(
                null, "some-state", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=missing_code"));
    }

    @Test
    void callbackRedirectsWhenBlankCode() {
        HttpServletRequest request = mockRequest();

        ResponseEntity<Void> response = controller.callback(
                "  ", "some-state", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=missing_code"));
    }

    @Test
    void callbackRedirectsWhenNoSession() {
        HttpServletRequest request = mockRequest();
        when(request.getSession(false)).thenReturn(null);

        ResponseEntity<Void> response = controller.callback(
                "auth-code", "state123", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=invalid_state"));
    }

    @Test
    void callbackRedirectsOnStateMismatch() {
        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("oidc.oauth.state")).thenReturn("expected-state");

        ResponseEntity<Void> response = controller.callback(
                "auth-code", "wrong-state", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=invalid_state"));
    }

    @Test
    void callbackRedirectsOnNullExpectedState() {
        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("oidc.oauth.state")).thenReturn(null);

        ResponseEntity<Void> response = controller.callback(
                "auth-code", "some-state", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=invalid_state"));
    }

    @Test
    void callbackRedirectsOnMissingCodeVerifier() {
        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("oidc.oauth.state")).thenReturn("state123");
        when(session.getAttribute("oidc.pkce.verifier")).thenReturn(null);

        ResponseEntity<Void> response = controller.callback(
                "auth-code", "state123", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=missing_verifier"));
    }

    @Test
    void callbackCleansUpSessionAttributes() {
        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("oidc.oauth.state")).thenReturn("state123");
        when(session.getAttribute("oidc.pkce.verifier")).thenReturn("verifier123");

        // Will fail at token exchange (RestTemplate not mocked), but we can verify cleanup
        controller.callback("auth-code", "state123", null, null, request);

        verify(session).removeAttribute("oidc.pkce.verifier");
        verify(session).removeAttribute("oidc.oauth.state");
    }

    @Test
    void callbackUsesDiscoveredTokenEndpointWhenConfigured() {
        ReflectionTestUtils.setField(controller, "tokenUri", "");
        ReflectionTestUtils.setField(controller, "issuer", "https://idp.example.com/");
        when(restTemplate.getForObject("https://idp.example.com/.well-known/openid-configuration", Map.class))
                .thenReturn(Map.of("token_endpoint", "https://idp.example.com/oauth2/v2.0/token"));
        when(restTemplate.exchange(
                eq("https://idp.example.com/oauth2/v2.0/token"),
                eq(HttpMethod.POST),
                any(),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("oidc.oauth.state")).thenReturn("state123");
        when(session.getAttribute("oidc.pkce.verifier")).thenReturn("verifier123");

        ResponseEntity<Void> response = controller.callback(
                "auth-code", "state123", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=no_id_token"));
        verify(restTemplate).exchange(
                eq("https://idp.example.com/oauth2/v2.0/token"),
                eq(HttpMethod.POST),
                any(),
                eq(Map.class));
    }

    @Test
    void callbackFallsBackToIssuerTokenPathWhenDiscoveryUnavailable() {
        ReflectionTestUtils.setField(controller, "tokenUri", "");
        ReflectionTestUtils.setField(controller, "issuer", "https://idp.example.com/");
        when(restTemplate.getForObject("https://idp.example.com/.well-known/openid-configuration", Map.class))
                .thenThrow(new RuntimeException("discovery unavailable"));
        when(restTemplate.exchange(
                eq("https://idp.example.com/oauth/token"),
                eq(HttpMethod.POST),
                any(),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        HttpServletRequest request = mockRequest();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute("oidc.oauth.state")).thenReturn("state123");
        when(session.getAttribute("oidc.pkce.verifier")).thenReturn("verifier123");

        ResponseEntity<Void> response = controller.callback(
                "auth-code", "state123", null, null, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().contains("auth_error=no_id_token"));
        verify(restTemplate).exchange(
                eq("https://idp.example.com/oauth/token"),
                eq(HttpMethod.POST),
                any(),
                eq(Map.class));
    }

    // ===== PKCE Utility Tests =====

    @Test
    void generateCodeVerifierProducesUrlSafeBase64() {
        String verifier = OidcBrowserFlowController.generateCodeVerifier();
        assertNotNull(verifier);
        assertTrue(verifier.length() >= 43);
        // URL-safe Base64: no +, /, or =
        assertFalse(verifier.contains("+"));
        assertFalse(verifier.contains("/"));
        assertFalse(verifier.contains("="));
    }

    @Test
    void generateCodeVerifierProducesUniqueValues() {
        String v1 = OidcBrowserFlowController.generateCodeVerifier();
        String v2 = OidcBrowserFlowController.generateCodeVerifier();
        assertNotEquals(v1, v2);
    }

    @Test
    void generateCodeChallengeProducesUrlSafeBase64() {
        String verifier = OidcBrowserFlowController.generateCodeVerifier();
        String challenge = OidcBrowserFlowController.generateCodeChallenge(verifier);
        assertNotNull(challenge);
        assertTrue(challenge.length() >= 43);
        // URL-safe Base64: no +, /, or =
        assertFalse(challenge.contains("+"));
        assertFalse(challenge.contains("/"));
        assertFalse(challenge.contains("="));
    }

    @Test
    void generateCodeChallengeIsDeterministic() {
        String verifier = OidcBrowserFlowController.generateCodeVerifier();
        String c1 = OidcBrowserFlowController.generateCodeChallenge(verifier);
        String c2 = OidcBrowserFlowController.generateCodeChallenge(verifier);
        assertEquals(c1, c2);
    }

    @Test
    void generateCodeChallengeDifferentFromVerifier() {
        String verifier = OidcBrowserFlowController.generateCodeVerifier();
        String challenge = OidcBrowserFlowController.generateCodeChallenge(verifier);
        assertNotEquals(verifier, challenge);
    }

    @Test
    void differentVerifiersProduceDifferentChallenges() {
        String v1 = OidcBrowserFlowController.generateCodeVerifier();
        String v2 = OidcBrowserFlowController.generateCodeVerifier();
        String c1 = OidcBrowserFlowController.generateCodeChallenge(v1);
        String c2 = OidcBrowserFlowController.generateCodeChallenge(v2);
        assertNotEquals(c1, c2);
    }

    // ===== Helper Methods =====

    private HttpServletRequest mockRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8443);
        return request;
    }

}
