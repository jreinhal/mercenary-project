package com.jreinhal.mercenary.controller;

import com.jreinhal.mercenary.model.User;
import com.jreinhal.mercenary.service.OidcAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Handles OIDC Authorization Code + PKCE flow for browser-based login.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Browser hits {@code /api/auth/oidc/authorize} → redirect to IdP with PKCE challenge</li>
 *   <li>User authenticates at IdP → redirect back to {@code /api/auth/oidc/callback}</li>
 *   <li>Backend exchanges authorization code for tokens using PKCE verifier</li>
 *   <li>ID token is validated, user is provisioned/looked up, session created</li>
 *   <li>Browser redirected to app root with session cookie</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/auth/oidc")
@ConditionalOnProperty(name = "app.auth-mode", havingValue = "OIDC")
public class OidcBrowserFlowController {

    private static final Logger log = LoggerFactory.getLogger(OidcBrowserFlowController.class);
    private static final String SESSION_PKCE_VERIFIER = "oidc.pkce.verifier";
    private static final String SESSION_OAUTH_STATE = "oidc.oauth.state";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OidcAuthenticationService oidcAuthenticationService;
    private final RestTemplate restTemplate;

    @Value("${app.oidc.issuer:}")
    private String issuer;

    @Value("${app.oidc.client-id:}")
    private String clientId;

    @Value("${app.oidc.authorization-uri:}")
    private String authorizationUri;

    @Value("${app.oidc.token-uri:}")
    private String tokenUri;

    @Value("${app.oidc.redirect-uri:}")
    private String redirectUri;

    @Value("${app.oidc.scopes:openid profile email}")
    private String scopes;

    public OidcBrowserFlowController(OidcAuthenticationService oidcAuthenticationService) {
        this(oidcAuthenticationService, new RestTemplate());
    }

    OidcBrowserFlowController(OidcAuthenticationService oidcAuthenticationService,
                              RestTemplate restTemplate) {
        this.oidcAuthenticationService = oidcAuthenticationService;
        this.restTemplate = restTemplate;
    }

    /**
     * Initiates the OIDC Authorization Code + PKCE flow.
     * Redirects the browser to the IdP's authorization endpoint.
     */
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(HttpServletRequest request) {
        String authUri = resolveAuthorizationUri();
        if (authUri == null || authUri.isBlank()) {
            log.error("OIDC authorization URI not configured. Set OIDC_AUTHORIZATION_URI or OIDC_ISSUER.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        String resolvedRedirectUri = resolveRedirectUri(request);

        // Generate PKCE code verifier and challenge
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);

        // Generate state parameter for CSRF protection
        String state = generateState();

        // Store in session
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_PKCE_VERIFIER, codeVerifier);
        session.setAttribute(SESSION_OAUTH_STATE, state);

        // Build authorization URL
        String authUrl = authUri
                + "?response_type=code"
                + "&client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(resolvedRedirectUri)
                + "&scope=" + urlEncode(scopes)
                + "&state=" + urlEncode(state)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";

        log.debug("Redirecting to OIDC authorization endpoint");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authUrl))
                .build();
    }

    /**
     * Handles the OIDC callback after user authenticates at the IdP.
     * Exchanges the authorization code for tokens and creates a session.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletRequest request) {

        // Handle IdP error
        if (error != null) {
            log.warn("OIDC callback error: {} - {}", error, errorDescription);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=" + urlEncode(error)))
                    .build();
        }

        if (code == null || code.isBlank()) {
            log.warn("OIDC callback missing authorization code");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=missing_code"))
                    .build();
        }

        // Validate state parameter (CSRF protection)
        HttpSession session = request.getSession(false);
        if (session == null) {
            log.warn("OIDC callback: no session found (state validation failed)");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=invalid_state"))
                    .build();
        }

        String expectedState = (String) session.getAttribute(SESSION_OAUTH_STATE);
        if (expectedState == null || !expectedState.equals(state)) {
            log.warn("OIDC callback: state mismatch (CSRF protection)");
            session.removeAttribute(SESSION_PKCE_VERIFIER);
            session.removeAttribute(SESSION_OAUTH_STATE);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=invalid_state"))
                    .build();
        }

        String codeVerifier = (String) session.getAttribute(SESSION_PKCE_VERIFIER);
        if (codeVerifier == null) {
            log.warn("OIDC callback: PKCE code verifier not found in session");
            session.removeAttribute(SESSION_OAUTH_STATE);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=missing_verifier"))
                    .build();
        }

        // Clean up PKCE/state from session
        session.removeAttribute(SESSION_PKCE_VERIFIER);
        session.removeAttribute(SESSION_OAUTH_STATE);

        // Exchange authorization code for tokens
        String resolvedTokenUri = resolveTokenUri();
        if (resolvedTokenUri == null || resolvedTokenUri.isBlank()) {
            log.error("OIDC token URI not configured. Set OIDC_TOKEN_URI or OIDC_ISSUER.");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=configuration"))
                    .build();
        }

        String resolvedRedirectUri = resolveRedirectUri(request);

        try {
            Map<String, Object> tokenResponse = exchangeCodeForTokens(
                    resolvedTokenUri, code, codeVerifier, resolvedRedirectUri);

            String idToken = (String) tokenResponse.get("id_token");
            if (idToken == null) {
                log.warn("OIDC token response missing id_token");
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/?auth_error=no_id_token"))
                        .build();
            }

            // Create a synthetic request with the id_token as Bearer token
            // so the existing OidcAuthenticationService can validate + provision the user
            User user = authenticateWithIdToken(idToken, request);
            if (user == null) {
                log.warn("OIDC user authentication/provisioning failed");
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/?auth_error=user_denied"))
                        .build();
            }

            // Create session for the authenticated user
            // Invalidate old session and create new one (session fixation protection)
            session.invalidate();
            HttpSession newSession = request.getSession(true);
            newSession.setAttribute("mercenary.auth.userId", user.getId());

            if (log.isInfoEnabled()) {
                log.info("OIDC browser login successful for user: {}", user.getUsername());
            }
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/"))
                    .build();

        } catch (Exception e) {
            log.error("OIDC token exchange failed", e);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/?auth_error=token_exchange"))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForTokens(
            String tokenEndpoint, String code, String codeVerifier, String callbackUri) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", clientId);
        params.add("code", code);
        params.add("redirect_uri", callbackUri);
        params.add("code_verifier", codeVerifier);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                tokenEndpoint, HttpMethod.POST, entity, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Token exchange failed with status " + response.getStatusCode());
        }

        return response.getBody();
    }

    private User authenticateWithIdToken(String idToken, HttpServletRequest originalRequest) {
        // Create a wrapper that injects the id_token as a Bearer token
        // so OidcAuthenticationService.authenticate() can validate it
        BearerTokenRequestWrapper wrapper = new BearerTokenRequestWrapper(originalRequest, idToken);
        return oidcAuthenticationService.authenticate(wrapper);
    }

    // ===== PKCE utilities =====

    static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String generateState() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private String resolveAuthorizationUri() {
        if (authorizationUri != null && !authorizationUri.isBlank()) {
            return authorizationUri;
        }
        if (issuer != null && !issuer.isBlank()) {
            return issuer + "/authorize";
        }
        return null;
    }

    private String resolveTokenUri() {
        if (tokenUri != null && !tokenUri.isBlank()) {
            return tokenUri;
        }
        if (issuer != null && !issuer.isBlank()) {
            return issuer + "/oauth/token";
        }
        return null;
    }

    private String resolveRedirectUri(HttpServletRequest request) {
        if (redirectUri != null && !redirectUri.isBlank()) {
            return redirectUri;
        }
        // Auto-detect from request, honoring X-Forwarded-* headers for reverse proxy
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            String portSuffix = ((port == 80 && "http".equals(scheme))
                    || (port == 443 && "https".equals(scheme))) ? "" : ":" + port;
            host = host + portSuffix;
        }
        return scheme + "://" + host + "/api/auth/oidc/callback";
    }

    /**
     * HttpServletRequest wrapper that injects a Bearer token header.
     * Used to pass the OIDC id_token to the existing OidcAuthenticationService.
     */
    private static class BearerTokenRequestWrapper extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final String bearerToken;

        BearerTokenRequestWrapper(HttpServletRequest request, String token) {
            super(request);
            this.bearerToken = token;
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return "Bearer " + bearerToken;
            }
            return super.getHeader(name);
        }
    }
}
