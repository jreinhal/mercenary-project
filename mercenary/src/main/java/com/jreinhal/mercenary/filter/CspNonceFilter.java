package com.jreinhal.mercenary.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CSP Nonce Filter for secure inline script handling.
 *
 * SECURITY: Generates a unique nonce per request that must be included
 * in script tags for them to execute. This prevents XSS attacks while
 * still allowing controlled inline scripts.
 *
 * Usage in HTML:
 *   <script nonce="${cspNonce}">...</script>
 *
 * The nonce is available as a request attribute 'cspNonce' for Thymeleaf templates,
 * and is automatically added to the CSP header.
 */
@Component
@Order(0) // Run first
public class CspNonceFilter implements Filter {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_LENGTH = 16;

    public static final String CSP_NONCE_ATTRIBUTE = "cspNonce";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Generate unique nonce for this request
        String nonce = generateNonce();

        // Make nonce available to templates/views
        httpRequest.setAttribute(CSP_NONCE_ATTRIBUTE, nonce);

        // Wrap response to add CSP header with nonce
        CspResponseWrapper wrappedResponse = new CspResponseWrapper(httpResponse, nonce);

        chain.doFilter(request, wrappedResponse);
    }

    /**
     * Generate a cryptographically secure random nonce.
     */
    private String generateNonce() {
        byte[] nonceBytes = new byte[NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonceBytes);
        return Base64.getEncoder().encodeToString(nonceBytes);
    }

    /**
     * Response wrapper that adds the CSP header with nonce.
     */
    private static class CspResponseWrapper extends HttpServletResponseWrapper {

        private final String nonce;
        private boolean cspHeaderSet = false;

        public CspResponseWrapper(HttpServletResponse response, String nonce) {
            super(response);
            this.nonce = nonce;
        }

        @Override
        public void setContentType(String type) {
            super.setContentType(type);
            // Add CSP header for HTML responses
            if (type != null && type.contains("text/html") && !cspHeaderSet) {
                addCspHeader();
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            addCspHeaderIfNeeded();
            return super.getOutputStream();
        }

        @Override
        public java.io.PrintWriter getWriter() throws IOException {
            addCspHeaderIfNeeded();
            return super.getWriter();
        }

        private void addCspHeaderIfNeeded() {
            if (!cspHeaderSet) {
                addCspHeader();
            }
        }

        private void addCspHeader() {
            if (cspHeaderSet) return;
            cspHeaderSet = true;

            // Hash for the theme-flash-prevention inline script in index.html
            // This script must run before CSS loads to prevent theme flash
            // Hash computed: sha256(script.trim())
            String themeScriptHash = "sha256-4jUmbS2PWE4rlTnD7L+eiI8k9L1Vy0cUeG/KZehQ8mU=";

            // Build CSP with nonce for scripts and styles
            // PR-4: Externalized main app JS, only small theme script remains inline (hash-allowed)
            String csp = String.format(
                "default-src 'self'; " +
                "script-src 'self' 'nonce-%s' '%s'; " +
                "style-src 'self' 'nonce-%s' https://fonts.googleapis.com; " +
                "font-src 'self' https://fonts.gstatic.com; " +
                "img-src 'self' data: https:; " +
                "connect-src 'self'; " +
                "frame-ancestors 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'",
                nonce, themeScriptHash, nonce
            );

            setHeader("Content-Security-Policy", csp);
        }
    }
}
