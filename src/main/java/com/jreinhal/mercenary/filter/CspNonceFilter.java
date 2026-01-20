package com.jreinhal.mercenary.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(value=0)
public class CspNonceFilter
implements Filter {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_LENGTH = 16;
    public static final String CSP_NONCE_ATTRIBUTE = "cspNonce";

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpServletResponse httpResponse = (HttpServletResponse)response;
        String nonce = this.generateNonce();
        httpRequest.setAttribute(CSP_NONCE_ATTRIBUTE, nonce);
        CspResponseWrapper wrappedResponse = new CspResponseWrapper(httpResponse, nonce);
        chain.doFilter(request, (ServletResponse)wrappedResponse);
    }

    private String generateNonce() {
        byte[] nonceBytes = new byte[16];
        SECURE_RANDOM.nextBytes(nonceBytes);
        return Base64.getEncoder().encodeToString(nonceBytes);
    }

    private static class CspResponseWrapper
    extends HttpServletResponseWrapper {
        private final String nonce;
        private boolean cspHeaderSet = false;

        public CspResponseWrapper(HttpServletResponse response, String nonce) {
            super(response);
            this.nonce = nonce;
        }

        public void setContentType(String type) {
            super.setContentType(type);
            if (type != null && type.contains("text/html") && !this.cspHeaderSet) {
                this.addCspHeader();
            }
        }

        public ServletOutputStream getOutputStream() throws IOException {
            this.addCspHeaderIfNeeded();
            return super.getOutputStream();
        }

        public PrintWriter getWriter() throws IOException {
            this.addCspHeaderIfNeeded();
            return super.getWriter();
        }

        private void addCspHeaderIfNeeded() {
            if (!this.cspHeaderSet) {
                this.addCspHeader();
            }
        }

        private void addCspHeader() {
            if (this.cspHeaderSet) {
                return;
            }
            this.cspHeaderSet = true;
            String csp = "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
            this.setHeader("Content-Security-Policy", csp);
        }
    }
}
