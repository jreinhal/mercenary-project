package com.jreinhal.mercenary.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.OidcAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Field;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OidcBrowserFlowControllerTest {

    private OidcAuthenticationService oidcAuthService;
    private OidcBrowserFlowController controller;

    @BeforeEach
    void setUp() throws Exception {
        oidcAuthService = mock(OidcAuthenticationService.class);
        controller = new OidcBrowserFlowController(oidcAuthService);
        setField("clientId", "test-client-id");
        setField("authorizationUri", "https://idp.example.com/authorize");
        setField("tokenUri", "https://idp.example.com/oauth/token");
        setField("redirectUri", "https://app.example.com/api/auth/oidc/callback");
        setField("scopes", "openid profile email");
        setField("issuer", "");
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
        setField("authorizationUri", "");
        setField("issuer", "");
        HttpServletRequest request = mockRequest();

        ResponseEntity<Void> response = controller.authorize(request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void authorizeFallsBackToIssuerForAuthUri() throws Exception {
        setField("authorizationUri", "");
        setField("issuer", "https://idp.example.com");
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
        setField("redirectUri", "");
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

    private void setField(String name, Object value) throws Exception {
        Field field = OidcBrowserFlowController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }
}
